# Application Service

Application Service stores the configuration that connects a project, GitHub
repository and Docker server or Kubernetes namespace target. It runs on port 8082 and owns only the
PostgreSQL application schema.

It does not clone code, build images, enqueue deployments, configure DNS or run
containers. Those operations belong to Deployment Service and Deployment Worker.
The autoDeploy setting opts an application into signed GitHub push deployment
when the worker and automatic delivery are enabled.

Deployment orchestration APIs are available in [Deployment Service](deployment-service.md).
They queue work and record worker reports. Runtime execution is implemented in
Deployment Worker; see [deployment-pipeline.md](deployment-pipeline.md) for setup
and end-to-end Postman testing.

## Run locally

Start PostgreSQL, Project Service (8081), and Git Service (8084). Use the same
local API username, password and organization in all three services. Application
Service forwards the authenticated caller's Authorization header to the two
trusted catalog services; it does not substitute a global administrator identity
or accept an organization header supplied by the request.

From the repository root:

~~~powershell
. ./scripts/Import-DatabaseEnv.ps1
$env:SPRING_PROFILES_ACTIVE = 'local'
$env:LOCAL_API_USERNAME = 'developer'
$env:LOCAL_API_PASSWORD = '<same API password as Project and Git Service>'
$env:LOCAL_ORGANIZATION_ID = '11111111-1111-1111-1111-111111111111'
$env:PROJECT_SERVICE_URL = 'http://localhost:8081'
$env:GIT_SERVICE_URL = 'http://localhost:8084'
./microservice/application-service/gradlew.bat -p microservice/application-service bootRun
~~~

Alternatively run ApplicationServiceApplication in IntelliJ with active profile
local and these environment variables. The local profile binds to 127.0.0.1;
other profiles deny access until a production identity adapter is supplied.
The local API password is independent of the database password.

For the Windows Java 25 loopback/socket error in this workspace:

~~~powershell
New-Item -ItemType Directory -Force D:/Projects/Shipora/.gradle/git-sockets | Out-Null
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=D:/Projects/Shipora/.gradle/git-sockets'
~~~

The equivalent IntelliJ VM option is
-Djdk.net.unixdomain.tmpdir=D:/Projects/Shipora/.gradle/git-sockets.

PROJECT_SERVICE_URL and GIT_SERVICE_URL are trusted operator-configured origins,
not request parameters. Use HTTPS across untrusted networks. HTTP is intended
for loopback or a controlled internal network. Requests are authenticated,
bounded, timed out, and never follow redirects. Upstream response bodies and
credentials are not returned in error messages.

## Prepare the references

For Kubernetes, follow [Kubernetes targets](kubernetes-targets.md) instead of
registering a Docker server. The Kubernetes guide includes RBAC and a separate
Postman collection. Existing requests default to runtimeType DOCKER.

- Create a project through Project Service.
- Register a server and run its connection check until its status is ACTIVE.
- Complete GitHub setup/connection and synchronize repositories through Git Service.
- Copy the project UUID, available repository UUID and server UUID from the same
  authenticated organization. These are Shipora UUIDs, not numeric GitHub IDs.

Create and metadata-update operations verify references through HTTP ports.
Repository availability includes the installation's active status. Project and
server ownership are also checked against the authenticated organization.
These are configuration-time checks, not guarantees of future availability:
Deployment Worker must revalidate access/server readiness when executing a job.
No database transaction is held during catalog requests.

## Postman

Import docs/postman/Shipora-Application-Service.postman_collection.json. Set:

| Variable | Value |
| --- | --- |
| baseUrl | http://localhost:8082 |
| username / password | Local API credentials recognized by all three services |
| projectId | Project Service UUID |
| repositoryId | Available Git Service repository UUID |
| serverId | ACTIVE server UUID |
| secretValue | A local test secret; keep real values out of exported collections |

Run requests 1–8 in order. The collection automatically saves applicationId and
the latest version after each successful mutation. Cleanup requests are separate.

### Create

~~~http
POST /api/applications
Content-Type: application/json

{
  "projectId": "<project UUID>",
  "repositoryId": "<repository UUID>",
  "serverId": "<server UUID>",
  "name": "Shipora Web",
  "slug": "shipora-web",
  "dockerfilePath": "Dockerfile",
  "buildContext": ".",
  "containerPort": 3000,
  "healthPath": "/",
  "autoDeploy": false
}
~~~

Expect 201 with Location, ETag, application configuration and version:0.
Omit branch to use the repository's default branch. Paths default to Dockerfile
and '.', healthPath to '/', autoDeploy to false. Container port is required.

Slugs are lowercase letters/numbers separated by single hyphens and unique within
an active project application set. Branches use a conservative ASCII Git-ref
policy. Build paths use relative POSIX repository paths without traversal,
backslashes or absolute paths. Dockerfile path and build context are both
relative to the repository root. The worker must additionally enforce filesystem
and symlink boundaries when resolving those paths.

### Metadata updates

~~~http
PATCH /api/applications/<application UUID>
Content-Type: application/json

{ "version": 0, "name": "Renamed application", "branch": "develop" }
~~~

PATCH preserves omitted/null fields. Project ownership is immutable; changing
projects requires another application. Repository/server may be changed after
scoped validation. A repository change preserves the existing branch unless
another branch is supplied explicitly. Every metadata update revalidates all
references, so catalog services must be available and the server ACTIVE.

All configuration changes increment one application version. Supply the latest
version for PATCH, environment replacement, secret write/delete and application
delete. A stale version returns 409: reload the current resource, review changes,
and retry. ETags on metadata reads/writes represent that version; mutation
preconditions use the explicit version field/query parameter.

### Environment variables

~~~http
PUT /api/applications/<application UUID>/environment
Content-Type: application/json

{ "version": 1, "variables": { "NODE_ENV": "production" } }
~~~

This replaces the entire non-secret map. An empty map clears it.
GET on the same route returns version and variables. Configuration reads require
APPLICATION_MANAGE. Ordinary application metadata responses contain no variables.

Keys follow shell environment identifier syntax. There may be at most 100
variables, with at most 16384 characters per value and a 256 KiB total UTF-8
configuration limit. NUL bytes are rejected. Environment variables are visible
to authorized configuration readers; sensitive values belong in secrets.

### Secrets

~~~http
PUT /api/applications/<application UUID>/secrets/API_TOKEN
Content-Type: application/json

{ "version": 2, "value": "<secret value>" }
~~~

The response returns only version and sorted key names. GET /secrets lists names,
never plaintext or ciphertext. PUT replaces an existing secret (rotation).
DELETE /secrets/API_TOKEN?version=3 removes one. Deleting a missing key returns
404 without advancing the application version.

An application may contain at most 100 secrets. Values may contain at most 16384
characters and no NUL bytes; empty values are allowed. The same key cannot exist
in both the environment and secret tables. Conflicting writes roll back atomically,
including the version increment.

Secrets use AES-256-GCM with a fresh random nonce. Authentication data binds the
ciphertext to organization UUID, application UUID, key name and format version,
so it cannot be copied to another context and decrypted successfully.

The master key is generated on first secret write in SHIPORA_APPLICATION_DATA_DIR
(default ./.shipora/application-service, ignored by Git). On POSIX systems it is
mode 600. A database key fingerprint prevents generating a replacement after
losing the key volume. Missing/wrong keys fail closed with a sanitized 503.

Persist and back up that directory together with PostgreSQL. Database backups
alone cannot recover secret values. This API deliberately has no secret-value
export endpoint. Deployment runtime secret delivery is a later authenticated
internal integration. Keep bodies containing secret values out of access logs.

### Delete

~~~http
DELETE /api/applications/<application UUID>?version=<latest version>
~~~

Returns 204, soft-deletes the application and erases environment, secret and
domain configuration in one transaction. The slug becomes reusable. Historical
application identity remains for later deployment records. This does not stop a
remote container, delete a project/repository/server, or delete deployment history.

## API and errors

| Method | Route | Success |
| --- | --- | --- |
| POST | /api/applications | 201 application |
| GET | /api/applications?projectId=UUID&page=0&size=20 | 200 scoped array |
| GET | /api/applications/{id} | 200 application |
| PATCH | /api/applications/{id} | 200 updated application |
| DELETE | /api/applications/{id}?version=N | 204 |
| GET / PUT | /api/applications/{id}/environment | 200 version/variables |
| GET | /api/applications/{id}/secrets | 200 version/key names |
| PUT | /api/applications/{id}/secrets/{key} | 200 version/key names |
| DELETE | /api/applications/{id}/secrets/{key}?version=N | 200 version/key names |

Metadata reads require APPLICATION_ACCESS. Mutations and configuration reads
require APPLICATION_MANAGE. Every request also needs exactly one trusted ORG_UUID
authority. Callers cannot select an organization in a body, query or tenant header.
List is zero-based with page >= 0 and size 1–100.

ProblemDetail errors: 400 invalid input, 401 invalid authentication, 403 insufficient
permission/upstream authorization, 404 missing or cross-organization resource,
409 stale version/slug/key/reference-state conflict, 503 unavailable catalog,
database or encryption key. Database/provider details and secrets are sanitized.

## Schema and architecture

Application services use plain Java, Lombok constructor injection, record DTOs
and application ports. Spring MVC, security, JDBC, HTTP catalogs and encryption
are adapters. Application Service never queries another service's schema.

Flyway preserves V1 and adds V2 for ownership, optimistic versions, soft deletion,
active-slug uniqueness and the secret key registry. Existing V1 rows without an
organization remain stored but hidden; the migration does not guess ownership.
New writes require an organization. An operator must explicitly verify and assign
ownership before adopting legacy rows.

V3 adds encrypted Kubernetes targets and the application's runtime type and target
reference. Existing applications remain DOCKER. PostgreSQL enforces exactly one
destination and a tenant-scoped target foreign key; target row locks serialize
application assignment with target deletion and credential updates.

Application domain and input validation cover safe paths, branches, ports,
configuration limits and pagination. PostgreSQL guards configuration/version
mutations transactionally. Automatic deployment, domain provisioning, build
logs, container lifecycle and multi-user production authentication remain the
next services/integrations, not simulated behavior in these endpoints.

## Tests

~~~powershell
./microservice/application-service/gradlew.bat -p microservice/application-service test
~~~

Integration tests run only when APPLICATION_TEST_JDBC_URL points to a disposable
database named shipora_application_test_*. They recreate only its application
schema. Optional APPLICATION_TEST_DB_USER / APPLICATION_TEST_DB_PASSWORD default
to postgres/empty. Never point these tests at customer/development data.
Use test --rerun-tasks when enabling integration-test environment variables.

APPLICATION_BOOT_TEST_JDBC_URL enables the full Spring startup/HTTP regression
test and must select a separate disposable shipora_application_test_* database.
It uses Flyway, while the JDBC tests above recreate their schema directly; do not
point both test variables at the same database.

The suite includes PostgreSQL store tests, full Spring startup/authenticated HTTP
tests, Kubernetes HTTPS fixture tests and Docker compatibility regressions. See
the Kubernetes target guide for coverage. No real server, GitHub repository or
customer cluster is modified by these checks.
