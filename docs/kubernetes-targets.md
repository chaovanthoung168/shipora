# Kubernetes application targets

Application Service now supports two runtime configurations:

| runtimeType | Required destination | Omitted destination |
| --- | --- | --- |
| DOCKER (default) | serverId for an ACTIVE Project Service server | kubernetesTargetId |
| KUBERNETES | kubernetesTargetId for an ACTIVE target in the same organization | serverId |

This change registers and verifies a namespace target and attaches applications
to it. It does not deploy workloads yet. Deployment Service/Worker still need the
push event pipeline, BuildKit image builder, registry publishing and runtime
adapters. Setting autoDeploy alone does not start a deployment. These manifests
authorize Shipora to access a customer cluster; they do not install Shipora itself
inside Kubernetes. Shipora can continue running on the customer's Docker Compose host.

[Deployment Service](deployment-service.md) now provides the durable job and worker
lease APIs for these targets. The worker's build/rollout implementation remains separate.

## 1. Prepare a namespace in the customer cluster

Check your kubectl context before applying. The following command changes that
cluster by creating a dedicated namespace, service account and RBAC bindings:

~~~powershell
kubectl config current-context
kubectl apply -f infrastructure/kubernetes/target-access.yaml
~~~

The example uses shipora-apps. To use a different namespace, change every matching
namespace, resourceNames entry and binding/role name in the manifest. Register
each cluster/namespace pair as a separate Shipora target. Use a dedicated namespace:
these permissions include deploying pods indirectly and accessing its Secrets.

The service account can manage Deployments, Services, ConfigMaps and Secrets in
that namespace, read pods/logs, get that one namespace and review its own access.
It cannot create namespaces or grant roles. There is no cluster-admin binding.

## 2. Obtain connection values

Run with a cluster administrator context:

~~~powershell
kubectl config view --raw --flatten --minify -o jsonpath='{.clusters[0].cluster.server}'
kubectl config view --raw --flatten --minify -o jsonpath='{.clusters[0].cluster.certificate-authority-data}'
kubectl -n shipora-apps create token shipora-deployer --duration=1h
~~~

Copy the three outputs into the Postman variables kubeApiServer, kubeCaData and
kubeToken respectively. kubeCaData is base64-encoded PEM certificate data, not a
file path. It may be empty for an API server trusted by the JVM's system roots.
The token is sensitive; keep it local or in Postman Vault and avoid shared exports.

The HTTPS address must be reachable from Application Service. A localhost
endpoint refers to Application Service's own host/container; replace it with a
reachable cluster address whose name/IP is in the API server's TLS certificate.
Self-signed/local clusters require the correct CA data. HTTPS certificate and
hostname verification are always enabled. Redirects and kubeconfig exec plugins
are not supported. The API accepts service account bearer tokens, not entire
kubeconfig files or client private keys.

TokenRequest tokens expire; the API server decides their actual lifetime. This
implementation stores the submitted token and does not refresh it automatically.
Rotate it using PATCH and run a new connection check before it expires. Kubernetes
recommends time-limited tokens: see the official
[service account token documentation](https://kubernetes.io/docs/reference/access-authn-authz/service-accounts-admin/#tokenrequest-api).

## 3. Register and verify in Postman

Restart Application Service with the local profile and LOCAL_API_PASSWORD. Flyway
will apply V3 automatically. No existing Docker configuration needs changing.
Import docs/postman/Shipora-Kubernetes-Targets.postman_collection.json.

Set these collection variables (or environment overrides):

| Variable | Value |
| --- | --- |
| baseUrl | http://localhost:8082 |
| username / password | Application Service API login |
| kubeApiServer | HTTPS Kubernetes API origin, e.g. https://cluster.example:6443 |
| kubeNamespace | shipora-apps |
| kubeToken | The generated service account token |
| kubeCaData | Base64 PEM CA bundle, or empty for system trust |
| projectId / repositoryId | Existing Shipora UUIDs from your organization |

Project/Git/Application services must recognize the same caller credentials and
organization. The repository must be synchronized and available.

Run requests 1-3 individually:

1. POST /api/kubernetes-targets creates a target with status UNVERIFIED, version 0.
2. POST /api/kubernetes-targets/{id}/connection-checks with its current version
   returns status ACTIVE and checkCode OK on success, and advances the version.
3. POST /api/applications creates an application using that target.

Example application body:

~~~json
{
  "projectId": "<Shipora project UUID>",
  "repositoryId": "<Shipora repository UUID>",
  "name": "Web on Kubernetes",
  "slug": "web-kubernetes",
  "runtimeType": "KUBERNETES",
  "kubernetesTargetId": "<target UUID from request 1>",
  "branch": "main",
  "dockerfilePath": "Dockerfile",
  "buildContext": ".",
  "containerPort": 3000,
  "healthPath": "/",
  "autoDeploy": false
}
~~~

The collection tracks targetVersion separately from applicationVersion. Requests
with stale versions return 409. Run optional rotation and cleanup requests only
when desired; do not run the entire collection blindly.

## API behavior

| Method and endpoint | Purpose |
| --- | --- |
| POST /api/kubernetes-targets | Register encrypted credentials; returns 201 |
| GET /api/kubernetes-targets?page=0&size=20 | List only the current organization's target metadata |
| GET /api/kubernetes-targets/{id} | Metadata, status, checkCode and version; no credentials |
| PATCH /api/kubernetes-targets/{id} | Required version; optional name, token, certificateAuthorityData |
| POST /api/kubernetes-targets/{id}/connection-checks | Required version; read-only namespace/permission verification |
| DELETE /api/kubernetes-targets/{id}?version=... | Soft-delete and erase credentials, unless used by an active application |

apiServer and namespace are immutable after creation; create a new target to
change them. Omitted token/CA fields preserve credentials. An empty CA string
selects system trust. An empty token is invalid. Changing token or CA resets the
target to UNVERIFIED. A rename preserves the last check. Both require version.
Existing applications remain attached during rotation; metadata updates to them
require the target to be verified again. Moving an application back to Docker is
allowed while its previous Kubernetes target is unverified.

To switch an existing Docker application:

~~~json
{
  "version": 0,
  "runtimeType": "KUBERNETES",
  "kubernetesTargetId": "<verified target UUID>"
}
~~~

PATCH /api/applications/{id} clears the previous serverId automatically. To switch
back, send runtimeType DOCKER and a new serverId; kubernetesTargetId is cleared.
If runtimeType is omitted, the existing type is preserved. Requests containing
both destination IDs are rejected. Legacy creates without runtimeType use DOCKER.

## Checks, credentials and failures

Connection checks GET the named namespace and create SelfSubjectAccessReview
requests for every permission in target-access.yaml's namespace Role. These
reviews only evaluate authorization; they do not create Deployments, pods or
other workloads. TLS, certificate hostname, namespace name/Active phase and JSON
responses are verified. Redirects are refused. Responses are capped at 128 KiB;
one check has a 20-second network budget, with four simultaneous checks per
Application Service instance and one per target per instance. Cross-instance
results are protected by database versions. Old results cannot approve rotated
credentials.

An executed check returns HTTP 200 even when it records ERROR; inspect status and
checkCode. Possible codes: OK, AUTHENTICATION_FAILED, ACCESS_DENIED,
NAMESPACE_NOT_FOUND, INVALID_CERTIFICATE, TLS_FAILED, UNREACHABLE, INVALID_RESPONSE.
For permission errors, compare the supplied RBAC manifest against the account's
actual bindings. For TLS failures, check CA data, certificate expiry and hostname.
Missing/cross-organization resources return 404; invalid input 400; stale versions,
inactive targets and in-use deletions 409; missing encryption keys or storage
failures 503. Upstream bodies and tokens are not returned as errors.

Tokens and CA data are encrypted with the same AES-GCM key store as application
secrets, bound to the organization and target ID. They are write-only through
these APIs. Preserve SHIPORA_APPLICATION_DATA_DIR across container replacement
(mount a persistent volume) and back it up with PostgreSQL. Losing the encryption
key makes existing target credentials unreadable. In a multi-replica service,
all replicas must share this same persisted key store. Target API access uses a
Kubernetes bearer token, never the incoming Shipora Basic Auth header.

Target creation, credential changes, deletion and checks require
APPLICATION_MANAGE. Metadata reads require APPLICATION_ACCESS. API addresses are
trusted operator input and may point to private cluster networks; restrict target
management to trusted operators and limit outbound network access accordingly.
The local profile remains for loopback development; selfhost supports a configured
installation organization. See [deployment-pipeline.md](deployment-pipeline.md)
for private networking, TLS and worker credentials.

ACTIVE records a successful check at checkedAt. It does not prove admission
policies, quota, image pulls, registry credentials, rollout health or future token
validity. The worker revalidates versioned configuration and active credentials at
job execution, deploys into the exact namespace, and reports rollout failures.
Reapply the provided namespace Role and recheck existing targets to grant the
Ingress permissions now required by the runtime adapter.

## Verification

The Application Service test suite covers existing Docker behavior, runtime
switching, tenant-scoped targets, encrypted credential recovery, token rotation,
stale-check rejection, concurrent creation/deletion, permission checks, trusted
CA and hostname validation, redirect refusal, malformed/oversized responses and
timeouts. PostgreSQL and Spring HTTP integration tests use disposable databases;
the Kubernetes HTTPS tests use a local fixture API and generated test certificate.
No customer cluster or workloads are modified by these tests.

Verified locally: 82 tests passed with zero failures or skips, including 17
PostgreSQL store tests, two full Spring startup/HTTP tests and five Kubernetes
HTTPS tests. The boot HTTP workflow uses a fixture permission probe; separate
HTTPS tests exercise the real probe with a generated CA/server certificate.
