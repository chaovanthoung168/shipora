# Deployment Service

Deployment Service runs on port 8083 and owns the PostgreSQL deployment schema.
It creates durable deployment jobs, coordinates worker leases, validates stage
transitions and records lifecycle events, image digests and release history.
It supports applications configured for either DOCKER or KUBERNETES.

The implementation uses hexagonal architecture: domain rules and application
ports/services, record DTOs, Lombok domain builders/constructor injection, and
Spring MVC/security, HTTP catalog and JDBC adapters. It never reads another
service's database schema. A separate Deployment Worker will call the internal
HTTP APIs; it never queries the deployment tables directly. See the complete
[worker, BuildKit and automatic deployment guide](deployment-pipeline.md).

## Current scope

The worker process, exact-commit checkout, BuildKit image publishing, Docker and
Kubernetes rollout, health checks, rollback and durable GitHub push delivery are
implemented. Enable/configure the worker and automatic delivery as described in
the pipeline guide. Creating a job returns QUEUED until a worker claims it.

Worker status APIs record reports from an authenticated, trusted worker. Calling
them manually in Postman simulates that worker and does not build or deploy
anything. A SUCCEEDED report records a release; it does not independently prove
that a running container or Kubernetes workload exists. Use the simulation
requests only with disposable test data. Structured lifecycle events are
available now; arbitrary build-output logs and streaming are not implemented.

## Start locally

Run PostgreSQL, Project Service (8081), Application Service (8082) and Git Service
(8084). Configure the same API login and organization in those services and here.
Prepare an application with an available synchronized repository, an existing
project and an ACTIVE Docker server or Kubernetes target.

From the repository root:

~~~powershell
. ./scripts/Import-DatabaseEnv.ps1
$env:SPRING_PROFILES_ACTIVE = 'local'
$env:LOCAL_API_USERNAME = 'developer'
$env:LOCAL_API_PASSWORD = '<same API password as the other services>'
$env:LOCAL_WORKER_USERNAME = 'shipora-worker'
$env:LOCAL_WORKER_PASSWORD = '<separate worker password>'
$env:LOCAL_ORGANIZATION_ID = '11111111-1111-1111-1111-111111111111'
./microservice/deployment-service/gradlew.bat -p microservice/deployment-service bootRun
~~~

For IntelliJ, run DeploymentServiceApplication with active profile local and the
same environment variables. LOCAL_API_PASSWORD is required and is independent
of the database password. If LOCAL_WORKER_PASSWORD is omitted, the service starts
with the API login only; worker authentication remains unavailable. The worker
and API usernames must be different.

Alternatively, the local launcher loads the database environment and explicitly
selects the `local` profile so a profile-less launch cannot silently deny every
API request:

~~~powershell
$env:LOCAL_API_PASSWORD = '<same API password as the other services>'
./scripts/Start-DeploymentService.ps1
~~~

The launcher builds the service and binds it to `127.0.0.1:8083`. Use `-SkipBuild`
only after building the current sources. Stop another process using port 8083
before launching it. It does not start a worker or enable automatic deployment.
An empty `403` from both authenticated and unauthenticated deployment requests
usually means the service was launched without `local`/`selfhost`; check its
active profile before changing account permissions. The frontend retains that
`403` and shows an access error instead of reporting a backend outage.

The local profile binds to 127.0.0.1. The selfhost profile supports the configured
installation organization with separate admin, worker and automation identities
and a request header for authenticated mutations. Other profiles deny access.
Use the supplied private Compose network and TLS reverse proxy for selfhosting.

For this Windows Java 25 socket-path issue, if encountered:

~~~powershell
New-Item -ItemType Directory -Force D:/Projects/Shipora/.gradle/git-sockets | Out-Null
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=D:/Projects/Shipora/.gradle/git-sockets'
~~~

Catalog URLs default to APPLICATION_SERVICE_URL=http://localhost:8082,
PROJECT_SERVICE_URL=http://localhost:8081 and GIT_SERVICE_URL=http://localhost:8084.
They are trusted operator-configured HTTP(S) origins, not request parameters.
Use HTTPS across untrusted networks. Catalog requests forward the authenticated
caller's Authorization header, never follow redirects, cap responses at 256 KiB
and have bounded network waits. Upstream response bodies are not exposed in errors.

Flyway applies the existing V1 and the new V2 automatically. V1 is unchanged.
V2 adds tenant ownership, request keys, configuration snapshots, lease tokens and
application queue slots. Legacy unowned records remain stored but hidden and
unclaimable. Legacy SUCCESS is renamed SUCCEEDED. Ownership is never guessed.

## Test the user APIs in Postman

Import docs/postman/Shipora-Deployment-Service.postman_collection.json. Set:

| Variable | Value |
| --- | --- |
| baseUrl | http://localhost:8083 |
| username / password | Your API login |
| applicationId | Shipora application UUID |
| commitSha | A full 40- or 64-character non-zero Git commit hash |
| workerUsername / workerPassword | Separate worker login, for optional simulation |

Run request 1, then requests 2-5 individually. The collection saves deploymentId
and deploymentVersion. Its create request generates an idempotencyKey if empty
and preserves it for retransmission. Clear/change that variable when intentionally
creating another deployment. Do not run all optional folders automatically.

Request 1:

~~~http
POST http://localhost:8083/api/deployments
Authorization: Basic <API credentials>
Content-Type: application/json
Idempotency-Key: <a UUID or another unique request key>
~~~

~~~json
{
  "applicationId": "<Shipora application UUID>",
  "commitSha": "<full Git commit hash>"
}
~~~

Expected: 202 Accepted, Location header, ETag and a body with status QUEUED.
Creation verifies application/project ownership, repository availability and
target readiness, then stores the immutable application metadata/version and
commit SHA. It validates the SHA format; the worker verifies that the
commit exists in the selected repository before building it. Branch comes from
the application; clients cannot set a different trigger type or organization.

The snapshot contains paths, branch, target references, container port, health
path and application version. It contains no environment values, decrypted
secrets, GitHub credentials or caller Authorization header. A retry takes a fresh
metadata snapshot of the current application while retaining the original SHA.
The worker resolves environment/secrets against the recorded version, rejects
changed configuration, and revalidates repository and target access at execution
time through job-scoped service endpoints.

Repeat the same request/key to get the original deployment with no second job
or repeated catalog calls. Reusing a key for another application, SHA or retry
origin returns 409. Keys are scoped to the authenticated organization. The
deployment, queue job and initial event are committed in one transaction.

## Public API

| Method and endpoint | Result |
| --- | --- |
| POST /api/deployments | Required Idempotency-Key and applicationId/commitSha; 202 |
| GET /api/deployments/{id} | Deployment, snapshot, version, timestamps and outcome |
| GET /api/deployments?applicationId=...&page=0&size=20 | Tenant-scoped history; applicationId optional |
| GET /api/deployments/{id}/events?afterId=0&limit=100 | Structured lifecycle events, ascending cursor |
| GET /api/applications/{id}/releases?page=0&size=20 | Recorded active/previous releases |
| POST /api/deployments/{id}/cancellations | Body with current version; QUEUED only |
| POST /api/deployments/{id}/retries | FAILED only; new Idempotency-Key; creates a new deployment/job |

The application/releases route is on Deployment Service (8083), not Application
Service. Events are generated by the service from known codes; arbitrary worker
messages are not accepted. Use the last event ID as afterId to read the next page.
Read operations require DEPLOYMENT_ACCESS; creation/retry/cancellation require
DEPLOYMENT_MANAGE. Organization is derived only from authenticated authorities.
Public APIs do not expose lease tokens or worker credentials.

Cancellation example:

~~~json
{ "version": 0 }
~~~

Cancellation and claim operations serialize on the same application slot; exactly
one can win. Running deployments cannot be cancelled through this endpoint yet,
because runtime termination belongs to the worker. Status changes advance version;
heartbeats do not. Failed deployments can be retried with a new request key.
Retry creates a new record linked by retryOf and does not erase the original.

## Worker HTTP contract

Use the worker login, not the API login. Authentication supplies the worker name
and organization; clients cannot supply a different owner ID in the body.

| Method and endpoint | Body / response |
| --- | --- |
| POST /internal/deployment-jobs/claims | No body; 200 with jobId, leaseToken, leaseExpiresAt, attempt and deployment snapshot; 204 when no claim is available |
| POST /internal/deployment-jobs/{jobId}/heartbeats | leaseToken; returns renewed expiry and current status/version |
| PUT /internal/deployment-jobs/{jobId}/status | leaseToken, expectedStatus, status; imageReference/failureCode when applicable |

Worker role DEPLOYMENT_WORKER cannot create deployments or use the public APIs.
API users cannot claim or update worker jobs. Claim credentials and responses
must remain private; lease request/claim toString representations are redacted.

Claims use PostgreSQL row locks and SKIP LOCKED. One managed job per application
can have a valid lease; different applications can run concurrently. Jobs for an
application are claimed in creation order. There is no additional broker to run
for this initial queue implementation. Claimed jobs begin at PREPARING.

Leases last 60 seconds according to the database clock. Send heartbeats every
20 seconds while working, including during long builds/rollouts. Each reassignment
gets a new opaque token. Every heartbeat/status mutation checks organization,
worker principal, token and expiry under a transaction. Old tokens return 409.
Stop work on lease loss; never continue reporting success with an expired token.

Normal lifecycle:

~~~text
QUEUED -> PREPARING -> BUILDING -> PUSHING -> DEPLOYING -> VERIFYING -> SUCCEEDED
~~~

Any claimed active stage may report FAILED with a permitted failureCode.
Queued jobs may become CANCELLED. Skipping stages returns 409. The worker must
perform each stage's actual work before reporting the next stage; these endpoints
do not execute that work.

For PREPARING to BUILDING:

~~~json
{
  "leaseToken": "<token from claim>",
  "expectedStatus": "PREPARING",
  "status": "BUILDING"
}
~~~

Entering DEPLOYING requires the published image digest, not a mutable tag:

~~~json
{
  "leaseToken": "<token from claim>",
  "expectedStatus": "PUSHING",
  "status": "DEPLOYING",
  "imageReference": "registry.example/shipora/web@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
}
~~~

The digest above is illustrative. It is not an image built by Shipora. The worker
must obtain the actual pushed digest from BuildKit/its registry before using it.
The image reference is stored as an artifact. After reporting successful runtime
health verification, SUCCEEDED creates an ACTIVE release and changes the previous
ACTIVE release to PREVIOUS in the same transaction. Failure preserves the last
active release record; it does not itself roll back or clean up a runtime.

PUT retries with the same attempt token, expected stage and result are idempotent,
including a completed SUCCEEDED/FAILED report. They do not create duplicate events,
artifacts or releases. Reassignment invalidates the old token. A terminal attempt's
retained token permits only replay of its already recorded result, not a new
transition or heartbeat.

Failure example:

~~~json
{
  "leaseToken": "<token from claim>",
  "expectedStatus": "BUILDING",
  "status": "FAILED",
  "failureCode": "BUILD_FAILED"
}
~~~

Worker failure codes: SOURCE_UNAVAILABLE, CONFIGURATION_UNAVAILABLE, BUILD_FAILED,
PUSH_FAILED, DEPLOY_FAILED, HEALTH_CHECK_FAILED, WORKER_FAILED. Raw exceptions,
provider responses, credentials and arbitrary log messages are not accepted as
failure reasons.

## Recovery and execution boundaries

`GET /api/deployments/{id}/queue-status` uses the same tenant-scoped API identity
as deployment reads. It returns `reason`, `lastWorkerSeenAt`, `runningDeployments`
and `queuedAt`. Reasons are `NO_WORKER` (no recent poll or valid execution lease),
`APPLICATION_BUSY` (another valid execution for this application),
`WAITING_FOR_CLAIM` (recent worker contact or occupied capacity), and `NOT_QUEUED`.
Presence is updated by authenticated claims and heartbeats; a 30-second window
allows for the worker's 20-second lease renewal interval. These are diagnostic
snapshots, not reserved capacity or an estimated start time. V5 adds the
tenant-scoped `deployment.worker_presence` table.

Workers send bounded process messages to
`POST /internal/deployment-jobs/{jobId}/events`:

```json
{
  "leaseToken": "{{leaseToken}}",
  "events": [{"level": "INFO", "message": "Running production build"}]
}
```

The endpoint requires the worker role and a current lease belonging to the same
worker and organization. It accepts 1–50 entries, levels INFO/WARN/ERROR, and
single-line messages up to 2048 characters, and returns 204. Expired, reassigned
and terminal leases are rejected. It does not change deployment versions or
stages. The existing public events endpoint exposes these as `PROCESS_OUTPUT`.
The worker redacts known job secrets and common credential patterns before
submission; do not deliberately print secrets in Dockerfiles or build commands.
Storage is limited to 5000 process events per deployment, with one limit warning.
Lifecycle/stage events remain available after this cap.

Claim polling performs bounded recovery of expired managed jobs. Expired
PREPARING attempts are requeued automatically, up to three total claims. Keep
preparation repeatable and free of build/deployment side effects. Losing the
lease after PREPARING marks that deployment FAILED with LEASE_EXPIRED; exhausting
claims records ATTEMPTS_EXHAUSTED. Later stages are not automatically rerun.
Recovery runs when workers poll for claims, not on a background timer; a GET
does not recover expired leases. Jobs and history survive service restarts.

Lease tokens fence database updates. They cannot revoke an already running shell
process, BuildKit operation or Kubernetes rollout. The worker stops on lease loss
and uses runtime fencing/idempotency; inspect uncertain runtime state before
retrying after an outage. This service does not
claim exactly-once execution of external side effects.

Deployment Worker now implements exact checkout, scoped credentials, versioned
runtime configuration, BuildKit/registry publishing and Docker/Kubernetes health
verification with rollback and runtime fences. Git Service uses a durable outbox
and Deployment Service a durable inbox to match repository/branch and autoDeploy.
Enable the pipeline to deploy on git push; creation APIs also support manual
testing and retries. See [deployment-pipeline.md](deployment-pipeline.md).

## Errors and tests

400 means invalid input; 401 missing/wrong authentication; 403 wrong role or
catalog access; 404 missing/cross-organization resource; 409 stale version, invalid
stage, inactive reference or expired/reassigned lease; 503 unavailable database
or catalog. Error bodies do not expose database/provider details. Retrying a
creation request after an uncertain response must preserve its Idempotency-Key.

~~~powershell
./microservice/deployment-service/gradlew.bat -p microservice/deployment-service test bootJar
~~~

DEPLOYMENT_TEST_JDBC_URL enables PostgreSQL tests and must point to an explicitly
disposable database named shipora_deployment_test_*. Those tests recreate its
deployment schema. DEPLOYMENT_BOOT_TEST_JDBC_URL enables the full Spring/HTTP
workflow and must select a separate disposable database; it uses Flyway and
resets its test deployment data. Optional DEPLOYMENT_TEST_DB_USER and
DEPLOYMENT_TEST_DB_PASSWORD default to postgres/empty. Never use customer or
development databases. Use test --rerun-tasks when enabling these variables.

Tests cover domain stages, idempotency, current snapshots, tenant isolation,
concurrent claims/cancellation, lease renewal/reassignment/exhaustion, stale
tokens, release activation/history, legacy migration, catalog credential
forwarding, malformed/upstream failures, role separation, production default-deny
and a full authenticated HTTP workflow with real PostgreSQL and fixture catalog
services. Worker reports are simulated; tests do not build images or modify
Docker servers, GitHub repositories or Kubernetes clusters.

Use the current Gradle reports as verification evidence. Database and boot
coverage is skipped unless the explicitly disposable test databases are configured.
