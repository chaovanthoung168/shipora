# Deployment pipeline

Shipora now executes deployment jobs using a separate worker. Services retain
their own database schemas and communicate through HTTP ports. The worker has
no database access. Domain rules, use cases and ports remain separate from
Spring, JDBC, Git, BuildKit, SSH and Kubernetes adapters.

## Flow

1. GitHub sends a signed `push` webhook. Git Service validates the signature,
   installation and repository, deduplicates delivery, and writes an outbox
   record in the same transaction.
2. A relay delivers that record to Deployment Service's durable inbox. The
   dispatcher matches applications with `autoDeploy=true`, the same repository
   and exact tracked branch. It creates one idempotent job per application.
3. A worker claims a job, renews its lease every 20 seconds, and requests
   job-scoped source, configuration, runtime secrets and target information.
   Each owning service verifies the current worker lease with Deployment Service.
4. The worker checks out the exact commit using a short-lived, single-repository,
   contents-read GitHub installation token. Authentication is removed before
   building. Application environment variables and secrets are **runtime only**.
5. BuildKit builds the configured Dockerfile/context and pushes to the configured
   registry. The worker reads BuildKit's digest metadata and deploys `repo@sha256:…`.
6. Docker or Kubernetes rolls out the image, checks health, and rolls back on
   failure while the job lease remains valid. The worker records the resulting
   endpoint and release after successful verification.

Stages are `QUEUED → PREPARING → BUILDING → PUSHING → DEPLOYING → VERIFYING → SUCCEEDED`.
Failures have sanitized codes and lifecycle events. BuildKit combines build and
push in one invocation; `PUSHING` starts when export progress is observed, or at
completion when that event is unavailable. BuildKit step names and process output
are sent to Deployment Service as bounded `PROCESS_OUTPUT` events. Step discovery,
actual start, completion and cache reuse are recorded separately, so a listed
future build step is not mistaken for work that has already finished. The worker
reassembles output chunks before removing known job secrets and common credential
patterns. Runtime environment values remain runtime-only and are not build arguments.
The worker queues at most 50 unsent lines and accepts up to 2000 output lines per
attempt; the service caps stored process events at 5000 per deployment and records
a limit warning. Logging is best effort and uses a two-second HTTP timeout.
Remote Docker rejections also produce fixed, sanitized process messages for
registry authentication, connectivity, TLS, image platform/extraction, storage
and health failures. Raw daemon errors and runtime environment values are not
published to the event stream.
Long-running SSH rollout commands are polled with lease/cancellation checks and
the configured deadline; SSHJ's throwing timed `join` is not used as a poll.
Build validation also records an actionable process event when the configured
Dockerfile or build context is missing from the selected commit. Next.js projects
must include a Dockerfile at the configured path; a framework alone does not
provide one. Commit the file and deploy that new revision, or correct the path in
the application settings before retrying.

An older automatic push is marked `FAILED/SUPERSEDED` if the tracked branch has
advanced before checkout or before rollout. A push arriving after the final
check can still briefly follow the preceding deployment; its own job runs next.
Tags and branch deletions do not start deployments. Webhooks must arrive after
the installation is connected and repository metadata is synchronized.

## Start with Docker Compose

Requirements: Docker Compose, JDK 25 for building the service JARs, an image
registry, and a Docker host or Kubernetes target. The sample stack creates its
own named PostgreSQL volume; it does not reuse or alter the database-only stack.
Do not start both stacks with conflicting service URLs or public ports.

### Docker Desktop worker with existing local APIs

When the APIs already run locally, use the separate worker-only stack. It starts
neither a database nor replacement API services:

```powershell
./scripts/Start-LocalWorker.ps1 -ImageRepository ghcr.io/your-account/shipora -BuildkitOnly
```

This prepares ignored `.shipora/local-worker/` files, exports only the saved GHCR
entry from Docker's credential helper, and starts pinned rootless BuildKit over a
shared Unix socket. Sign in with `docker login ghcr.io` first if no login exists.
No registry credential or SSH key is placed in source control. The script preserves
existing private configuration rather than replacing installation secrets.

Add the printed `--spring.config.additional-location=file:.../service-worker.properties`
program argument to each Project, Application, Git and Deployment API and restart
them with their existing profiles and environment. This configures a dedicated
worker identity without changing API accounts. Rebuild/restart Deployment Service
with current sources to apply V5 and expose queue diagnostics and process events.
The worker-only Compose stack reaches host APIs through `host.docker.internal`.

For Docker targets, fill `.shipora/local-worker/worker.yaml` with the same pinned
SSH target as Project Service and put the referenced key under its `ssh/` folder.
Encrypted keys also need the configured passphrase environment variable in the
private `worker.env`. Never disable host-key verification.

Then start the worker explicitly:

```powershell
./scripts/Start-LocalWorker.ps1 -ImageRepository ghcr.io/your-account/shipora
```

The script first verifies all four APIs accept the dedicated worker identity,
without claiming a job. It builds the worker JAR/image and enables processing only
after these checks pass. `-SkipBuild` reuses an already verified current image.
An enabled worker will claim queued deployments; automatic Git-push deployment
remains independently controlled. A disabled worker logs a clear startup warning;
an enabled worker fails startup if `git` or `buildctl` cannot execute.

From the repository root in PowerShell:

```powershell
./scripts/Prepare-Pipeline.ps1
# Edit infrastructure/pipeline/.env first; replace every placeholder password.
# Set SHIPORA_PUBLIC_URL to your externally reachable HTTPS Shipora origin.
./scripts/Build-PipelineImages.ps1
docker compose --env-file infrastructure/pipeline/.env -f infrastructure/pipeline/compose.yaml config --quiet
docker compose --env-file infrastructure/pipeline/.env -f infrastructure/pipeline/compose.yaml up -d
```

The sample API listens on `http://localhost:8080`. Put a TLS reverse proxy in
front of it for external access. Only the API proxy publishes a port; it denies
`/internal/`. Every authenticated mutation in `selfhost` requires
`X-Shipora-Request: true`, including requests from the future Next.js frontend.
Basic Auth uses the configured admin login. The worker and automation relay
have separate identities with restricted roles. Keep their passwords distinct.
All configured service origins and namespace/SSH destinations are operator input.

`SHIPORA_WORKER_ENABLED` and `SHIPORA_AUTO_DEPLOY_ENABLED` start as `false` so
configuration can be completed first. Then set both to `true` and recreate the
stack with the same `up -d` command. Automatic push requires the automation
password to match in Project, Application, Git and Deployment services, and all
services to use the same organization UUID. This Compose identity adapter supports
one installation organization; multiple organizations require an identity adapter.

Persist and back up the PostgreSQL, Application and Git data volumes together.
Application/Git data directories contain installation-specific encryption keys.
Do not use `docker compose down -v` on an installation whose data you need.

### Registry

Set `SHIPORA_IMAGE_REPOSITORY=ghcr.io/your-account/shipora` (or another explicit
registry/namespace). Images are stored below that prefix by organization and
application UUID, with a deployment UUID tag and immutable digest for execution.

Generate a **dedicated** registry config directly in the mounted directory:

```powershell
docker --config infrastructure/pipeline/registry login ghcr.io
```

Use registry credentials that can push and pull those repositories. The config
must have a direct `auths["ghcr.io"].auth` entry. Host desktop credential helpers
are not available inside the worker and cannot supply runtime pull secrets.
Do not copy your entire personal Docker config. BuildKit receives registry
authentication through its client session. Docker receives the matching registry
auth over SSH stdin; Kubernetes receives a namespaced pull Secret.

Compose runs pinned BuildKit v0.34.0 rootless, with a native snapshotter and a
shared Unix socket; TCP is not published. It uses the official rootless Docker
security options and retains the process sandbox. See [BuildKit rootless setup](https://github.com/moby/buildkit/blob/master/docs/rootless.md).
For a remote BuildKit daemon, configure worker `buildkit-ca`, `buildkit-cert` and
`buildkit-key`. Plain TCP requires explicit `allow-insecure-buildkit=true` and is
only for a trusted isolated network.

### Docker runtime

The target is a Linux server with Docker Engine API v1.44 or newer, Python 3, and
an SSH user permitted to use `/var/run/docker.sock`. It must reach the registry.
The worker itself has **no host Docker socket mount**.

Put the target's private SSH key in `infrastructure/pipeline/ssh/runtime-key`.
Configure matching entries in `project.yaml` and `worker.yaml` using the verified
literal IP, SSH port, username, credential reference and pinned host fingerprint.
The server record must use the same values and have an ACTIVE connection check.
On Linux bind mounts, use group 10001 and permissions 0640 on the key, and 0750
on its directory; API containers use UID/GID10001, worker UID1000/GID10001.
Windows Docker Desktop mounts use its file sharing permissions. For encrypted
keys, configure `passphrase-environment-variable` and supply that environment
variable to the relevant containers using a local Compose override.

Docker checks a temporary candidate on loopback, then replaces the stable
container on its existing published port. The first deployment receives an
ephemeral host port. The previous container is retained until verification.
This replacement strategy has a brief interruption; it is not a load-balanced
zero-downtime strategy. Permit the published port through your runtime network,
or place a separate reverse proxy in front of it. The returned endpoint is
`http://server-ip:published-port`. Containers have CPU/memory/PID limits,
dropped capabilities and no-new-privileges. Images must support those settings.

A server-side per-application file lock and monotonically increasing execution
fence reject old workers. Fence files live under the SSH user's
`~/.local/state/shipora-runtime`; retain them along with the deployment database.
Do not independently reset the deployment fence sequence while retaining runtimes.

### Kubernetes runtime

Apply [target-access.yaml](../infrastructure/kubernetes/target-access.yaml) to
the intended namespace, then register and check the target using the Kubernetes
Postman collection. Reapply the Role and recheck existing targets: the worker now
also needs namespaced Ingress access. The worker must reach the cluster API with
valid TLS; private CAs are supported, hostname checking remains enabled.

The adapter creates owned, namespaced Deployment, ClusterIP Service, environment
Secret and optional registry Secret. It uses immutable digests, HTTP readiness,
rolling update (surge1/unavailable0), and waits for the expected generation and
one ready updated replica. Resource-version checks and fence annotations reject
stale writes; resources with foreign ownership labels are never adopted.
Runtime containers receive no service-account token.

Without `SHIPORA_APPS_DOMAIN`, the recorded URL is cluster-internal. With a base
domain, the worker creates an Ingress for `app-<application-uuid-without-dashes>.<domain>`.
Configure wildcard DNS and an ingress controller; `SHIPORA_INGRESS_CLASS` is optional.
TLS certificates and external DNS are operator-managed; the generated Ingress
currently records HTTP. Previous versioned Secrets are retained for rollback;
registry retention and obsolete Secret cleanup are operator tasks in this version.
Applications currently deploy one replica with fixed resource limits.

## Test through Postman

Import [Shipora-Deployment-Pipeline.postman_collection.json](postman/Shipora-Deployment-Pipeline.postman_collection.json).
Set `baseUrl`, `username`, `password`, `applicationId` and an exact full `commitSha`.
The collection supplies the selfhost request header and Basic Auth.

1. Connect your GitHub account using the existing Git Service collection. Enable
   the GitHub App's repository `Contents: Read` permission and subscribe to `push`.
   Approve updated installation permissions if GitHub requests it. Synchronize repos.
2. Create a checked target and application using the existing collections. Include
   `autoDeploy: true`, the synchronized `repositoryId`, tracked branch, Dockerfile
   path, build context, container port and an HTTP health path that returns 2xx.
3. Start with **Create manual deployment** to verify credentials/build/runtime.
   Poll deployment status and events until success or failure; inspect releases
   and the returned `runtimeEndpoint`. Never simulate worker success for a real job.
4. Commit a change and `git push` to the application's tracked branch. GitHub
   needs to reach your public HTTPS webhook endpoint. Poll **List app deployments**;
   a `GIT_PUSH` job should appear without clicking Deploy. Localhost alone cannot
   receive GitHub webhook deliveries; use your public domain or a development tunnel.

GitHub browser consent is still required when connecting an account. Postman can
exercise your API endpoints and inspect jobs; it cannot bypass GitHub consent.
Manual deployment creation remains available for explicit deployments and retries.

For local IntelliJ runs, give all APIs the same `LOCAL_WORKER_USERNAME/PASSWORD`.
Set the automation password and flag in Git/Deployment, plus the same automation
account password in App/Project. Configure worker URLs, registry, workspace and
BuildKit address. Run the worker on Linux (the supplied image is the easiest
option); Windows paths are not interchangeable with paths inside the container.

## Failures and recovery

Use `docker compose ... logs --tail 100 deployment-worker git-service deployment-service`.
Public deployment events expose fixed failure codes without raw secrets/output.
`CONFIGURATION_UNAVAILABLE` means the configuration version, credentials or target
cannot be used; recheck the target and create a new deployment after updating
configuration. SOURCE/BUILD/PUSH/DEPLOY/HEALTH failures identify the stage.

Webhook outbox/inbox delivery retries durably with bounded backoff after outages;
target cursors and idempotency prevent duplicate jobs on replay. A dead worker's
PREPARING lease expires and the queue retries up to its three-claim limit. Lease
loss during later stages marks the deployment failed and requires explicit retry.
An expired worker
stops reporting and mutating; a replacement gets a higher runtime fence.
Persistent failures require fixing configuration and retrying the failed deployment.
During an outage, inspect the current workload before treating database status
as proof of runtime state: no distributed transaction spans a runtime and SQL.

## Verification boundaries

Tests cover SQL queue/outbox/inbox transactions, identity/tenant checks, exact
commit checkout arguments, restricted GitHub tokens, build digest handling,
lease cancellation, Docker requests and Kubernetes HTTPS/resource-version/rollback
behavior. The Docker Python script also has isolated Docker API fixture tests.
A disposable rootless BuildKit build executes a Dockerfile RUN and pushes to a
disposable registry. The non-root worker image can access its rootless socket.
An isolated three-service HTTP smoke test verifies signed webhook delivery,
duplicate suppression, durable dispatch, worker claim, execution fences and
lease-gated configuration with stale-token and admin-access denial.
Verified locally: 324 Java tests and four Python runtime tests passed without
failures or skips; all five local service images built successfully.
Live GitHub credentials, a real SSH Docker host and a customer
Kubernetes cluster are configuration-dependent integration checks, not simulated
production successes. Build arguments, submodules/LFS, build secrets, public
log streaming, custom runtime resource limits and database workloads are not
implemented by this pipeline.
