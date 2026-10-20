# Git Service

Git Service owns instance GitHub App setup, verified account connections,
repository metadata and signed webhooks. It runs on port 8084 and uses the
PostgreSQL git schema. It serves JSON; Next.js owns the dashboard UI.

## Repository search

`GET /api/git/installations/{id}/repositories?q=storefront&page=0&size=10`
searches synchronized repository names within the authenticated organization's
installation. `q` is an optional, case-insensitive literal substring (trimmed,
at most 200 characters); `%` and `_` are literal characters. Filtering happens
before pagination, so matches beyond the original first page are included.
Omitting or leaving `q` blank preserves the existing paged list. The response
is the same repository array, including availability and default branch.
Search uses stored metadata and does not contact GitHub. The application form
uses the connected account automatically and shows results only after a search.

## Repository commits

`GET /api/git/repositories/{id}/commits?branch=main&page=0&size=20` lists live
GitHub commit history for a synchronized repository. Authentication requires
`GIT_ACCESS`; repository and installation ownership are checked against the
authenticated organization before any GitHub request. Unavailable repositories
or inactive installations require reconnecting/synchronizing first.

The branch is required, pagination is zero-based (`page` 0–1000, `size` 1–100),
and the response is an uncached JSON array of `{sha, message, authorName,
committedAt}`. `message` is the first line, bounded to 500 characters;
`committedAt` may be null. Branch names use the source adapter's supported
letters/numbers/underscore/dot/hyphen/slash format. No credential is returned.
The adapter uses GitHub's [list commits endpoint](https://docs.github.com/en/rest/commits/commits#list-commits)
with `sha=<branch>` and a short-lived installation token restricted to that
repository with Contents read permission. The Next.js deployment form uses
these results and sends the chosen full SHA to Deployment Service, preserving
exact-commit checkout. This read operation never queues a deployment.

## Customer experience

1. Start Shipora with Docker Compose and sign in.
2. The instance administrator clicks **Set up GitHub** once.
3. Next.js submits the preconfigured App Manifest to GitHub.
4. The administrator confirms the App name on GitHub.
5. Git Service exchanges the callback code and stores encrypted credentials.
6. Follow installationUrl and grant repository access.
7. Click **Connect GitHub**, approve, choose an account and synchronize repositories.

Customers do not copy App IDs, secrets or PEM files. GitHub registration,
installation and authorization still require their approval. Setup is once per
instance; connections remain scoped to the authenticated Shipora organization.

## Docker Compose

The supplied Compose file runs **Git Service and its database**. Other Shipora
services and the future Next.js dashboard are separate components.

From the repository root:

~~~powershell
./microservice/git-service/gradlew.bat -p microservice/git-service bootJar
Copy-Item infrastructure/git-service/.env.example infrastructure/git-service/.env
# Edit .env: choose distinct admin/database passwords and set the instance URL.
docker compose --env-file infrastructure/git-service/.env -f infrastructure/git-service/compose.yaml up -d --build
~~~

The Dockerfile packages the existing bootJar and runs Java 25 as a non-root user.
Compose uses a read-only root filesystem and separate persistent volumes for the
database and encryption key. Publishing is an operator action: set
SHIPORA_GIT_IMAGE to your registry image/version and use up -d --no-build when
distributing an already-built image.

| Setting | Purpose |
| --- | --- |
| SHIPORA_ADMIN_USERNAME | Instance API administrator; default admin |
| SHIPORA_ADMIN_PASSWORD | Required instance administrator password |
| SHIPORA_DB_PASSWORD | Required separate database password |
| SHIPORA_PUBLIC_URL | Browser-visible origin; HTTPS for remote deployments |
| SHIPORA_GITHUB_WEBHOOKS_ENABLED | Enable after public HTTPS webhook ingress works |
| SHIPORA_BIND_ADDRESS / SHIPORA_PORT | Defaults 127.0.0.1:8084 |

No GITHUB_* credentials are needed for guided setup. Put an HTTPS reverse proxy
in front for remote access. Both browser callbacks and GitHub webhook delivery
must reach the configured external routes. Preserve the instance URL after
registration; changing an environment variable does not automatically edit an
existing GitHub App registration or its stored callback.

The selfhost profile supplies a single administrator using stateless Basic Auth.
It is an API identity adapter, not a multi-user dashboard login system. Next.js
must authenticate its users and proxy authorized operations under their trusted
identity. Keep shared administrator credentials out of browser JavaScript.
The local profile is for development; other profiles deny requests until a real
identity adapter is supplied.

Selfhost API mutations require the header X-Shipora-Request: true, in addition
to authentication. Browser forms cannot supply this header with cached Basic
Auth credentials. The Postman collection adds it automatically. Signed GitHub
webhooks are exempt. Do not enable permissive credentialed CORS.

## Persistent credentials

The App ID, slug, client ID/secret, private key, webhook secret and OAuth callback
are stored together using AES-256-GCM, random nonces and a versioned envelope.
API responses return only public status/installation metadata.

A random 32-byte encryption key is generated before registration starts, at
github-credentials.key inside SHIPORA_DATA_DIR. Docker uses /var/lib/shipora
(the git-keys volume); development defaults to ./.shipora/git-service, ignored
by Git. POSIX key-file permissions are 600.

**Back up the database and key volume together.** The encrypted database alone
cannot recover credentials. Missing/wrong keys produce a sanitized 503;
decryption does not silently generate a replacement.

Stored configuration takes precedence over optional legacy GITHUB_* variables
and is usable without restarting. Setup returns 409 after configuration, and
requires GIT_ADMIN beyond the normal GIT_MANAGE role. There is no credential
export/reset API. Rotation and recovery require an operator procedure; deleting
volumes is not a supported reset method.

Flyway preserves V1–V4 and applies V5 automatically. V5 adds the singleton
encrypted App record and short-lived, administrator-bound setup states.

## Postman: first-time registration

Import docs/postman/Shipora-Git-Service.postman_collection.json. Set collection
variables baseUrl, username and password. Local IntelliJ commonly uses developer;
Docker defaults to admin. Remove conflicting environment overrides.

Open **Instance GitHub setup - run once**:

1. Send **A. Check GitHub setup status**. Skip registration if configured is true.
2. Send **B. Start guided GitHub App registration**:

~~~http
POST /api/git/setup/github
Content-Type: application/json

{}
~~~

Optional fields: name (unique App name, at most 34 characters) and
githubOrganization (an organization where you can manage Apps). Omit name for
a generated name. The response contains actionUrl, method, formFields.manifest
and expiresAt.

3. GitHub requires a **browser POST form** containing the manifest. Simply opening
actionUrl does not submit it. Save the entire response to github-setup.json using
Postman's Save Response, then run:

~~~powershell
./scripts/Open-GitHubManifest.ps1 -ResponseFile ./github-setup.json
~~~

Click **Continue to GitHub setup**, then confirm registration on GitHub. This
temporary local form is a testing bridge; Git Service serves no HTML. Delete the
temporary form/JSON afterward. Next.js will submit this form automatically.

4. The backend callback may prompt for the **same Shipora administrator**
credentials. It returns configured, appId and installationUrl. Another
administrator/organization cannot complete the pending setup.
5. Send **C. Verify GitHub setup**. Expect configured:true, setupRequired:false
and source:DATABASE.
6. Open installationUrl, select the GitHub account and grant repository access.
Then run the connection requests below.

Setup expires after ten minutes and is consumed once before contacting GitHub.
Starting again invalidates the same administrator's previous pending setup.
If registration fails after creating an App, restart setup and clean up the
unused App in GitHub settings as needed.

## Postman: connect and synchronize

1. Send **1. Start GitHub connection**:

~~~http
POST /api/git/connections
Content-Type: application/json

{}
~~~

Expect 201 with authorizationUrl, connectionToken and expiresAt. The collection
saves them automatically.

2. Open authorizationUrl, approve on GitHub, and authenticate the backend callback
with the same Shipora API identity if prompted. Expect authorized:true and
availableAccounts. Return to Postman.
3. Send **2. List eligible GitHub accounts**. The saved X-Git-Connection-Token
header is included. The first opaque account-choice UUID is saved as choiceId;
choose the correct one if several accounts are returned.
4. Send **3. Connect selected account** with choiceId. The returned Shipora
installation UUID is saved as installationId.
5. Send **4. Synchronize repositories**, then **5. List repositories**.
6. Send **6. Get repository**. **7. List connected installations** is also available.

Browser cookies need not be copied to Postman. Connection tokens are stored as
hashes and require the same authenticated organization. Choices expire after
ten minutes; selecting one consumes all options from that flow.

Personal ownership or active GitHub organization-admin membership, App identity,
and installation activity are checked. Cross-organization installation claims
return 409. OAuth and installation tokens are not persisted or returned; each
sync generates a fresh installation token.

The optional legacy flow accepts a numeric installationId at connection start.
Its public GET callback uses one-time state and PKCE, links directly, and has no
account-choice/token step. Numeric GitHub IDs differ from Shipora installation
UUIDs, App IDs and Client IDs.

## Next.js integration

Use Next.js server routes under the logged-in user's trusted identity:

- Read status; show **Set up GitHub** only to the instance administrator.
- POST setup, store state in that administrator's session, then submit a browser
  form using actionUrl, method POST and hidden manifest=formFields.manifest.
  Do not forward Shipora Basic Auth credentials to GitHub.
- Before registration, set SHIPORA_GITHUB_SETUP_CALLBACK_URL to the Next.js
  server callback. Validate returned state against the original admin session,
  then POST code/state to /api/git/setup/github/callback under that admin.
- Follow the returned installationUrl to grant repository access.
- POST connections, retain state/connectionToken server-side, and redirect to
  authorizationUrl.
- Before registration, set SHIPORA_GITHUB_AUTH_CALLBACK_URL to the Next.js OAuth
  callback. Validate returned state and POST code/state to /api/git/github/callback.
- Call account-choice, synchronization and inventory APIs. Include
  X-Shipora-Request: true on selfhost mutations. Add CSRF protection to frontend
  routes and keep credentials/connection tokens server-side.

POST callbacks are alternatives to backend GET callbacks; never exchange the
same code twice. During Postman testing, leave both callback overrides unset.

If migrating from manual environment configuration, a valid GITHUB_* setup is
reported as source:ENVIRONMENT and registration is skipped. To adopt a newly
registered instance App instead, first remove those legacy settings and restart;
existing installation links may need to be reconnected under the new App.

## API

Routes below are relative to /api/git.

| Method | Route | Result / required authority |
| --- | --- | --- |
| GET | /setup/github | Setup status; GIT_ACCESS |
| POST | /setup/github | 201 manifest form; GIT_ADMIN |
| GET / POST | /setup/github/callback | Registration metadata; original GIT_ADMIN |
| POST | /connections | 201 authorization URL/token; GIT_MANAGE |
| GET / POST | /github/callback | Authorization result; GIT_MANAGE for discovery |
| GET | /connections/accounts | Choices; GIT_ACCESS plus connection token |
| POST | /connections/accounts | Installation; GIT_MANAGE plus connection token |
| GET | /installations | Installation array; GIT_ACCESS |
| POST | /installations/{id}/sync | Sync count; GIT_MANAGE |
| GET | /installations/{id}/repositories | Repository array; GIT_ACCESS |
| GET | /repositories/{id} | Repository; GIT_ACCESS |
| POST | /webhooks/github | Receipt; verified GitHub signature |

Inventory pagination is zero-based page and size 1–100. Organization identity
comes from authentication; callers cannot select another tenant. Out-of-scope
resources return 404.

ProblemDetail errors: 400 invalid input/state; 401 invalid authentication;
403 missing authority, rejected access/signature or missing selfhost header;
404 missing scoped resource; 409 ownership/setup/concurrency conflict;
413 oversized webhook; 502 GitHub failure; 503 unavailable configuration/storage.
Provider error bodies and credentials are not exposed.

## Repository and webhook guarantees

- Collect all pages before committing a snapshot; failed sync preserves the last
  snapshot. The limit is 10,000 repositories.
- Bound provider response size and connection/request/body completion times.
  Do not follow redirects. Limit concurrent provider operations to four.
- Preserve repository UUIDs through rename/sync; missing repositories become
  unavailable. Recheck provider access before deployment clone operations.
- Validate original webhook bytes using HMAC-SHA256 and constant-time comparison.
  Limit bodies to 2 MiB and deduplicate delivery IDs.
- Suspension/deletion/permission changes disable installations; reconnect and sync.
  Repository changes invalidate snapshots. Revision checks reject stale syncs.
- Push receipts record metadata/commit SHA; they do not schedule deployments.
- Git Service does not clone, build images, run containers or execute SSH commands.

Keep callback codes/webhook bodies out of access logs and configure receipt
retention in production.

## Architecture and tests

Plain Java application services use Lombok constructor injection, record DTOs and
ports. JDBC, encryption, GitHub HTTP, manifest serialization and Spring MVC are
adapters. No database transaction spans a GitHub network call.

~~~powershell
./microservice/git-service/gradlew.bat -p microservice/git-service test
~~~

PostgreSQL tests run only when GIT_TEST_JDBC_URL selects a disposable database
named shipora_git_test_*. They recreate its git schema. Optional GIT_TEST_DB_USER
and GIT_TEST_DB_PASSWORD default to postgres/empty. Use test --rerun-tasks when
enabling integration tests so cached results do not skip them.

For the Windows Java 25 loopback/socket error in this environment:

~~~powershell
New-Item -ItemType Directory -Force D:/Projects/Shipora/.gradle/git-sockets | Out-Null
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=D:/Projects/Shipora/.gradle/git-sockets'
~~~

Use the equivalent IntelliJ VM option. Docker's Linux runtime does not need it.

Verified: 116 tests passed, including 18 PostgreSQL integration tests. An isolated
HTTP check covered manifest setup, encrypted persistence, immediate credential
use, service restart recovery and dynamic webhook verification. Registration in
that check used a fake GitHub provider. The built Docker image was also started
with Compose and checked for authentication, migrations, setup, non-root UID and
mode-600 key persistence. Real GitHub registration/consent remains interactive.

References: [GitHub App Manifest flow](https://docs.github.com/en/apps/sharing-github-apps/registering-a-github-app-from-a-manifest),
[App authorization](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-a-user-access-token-for-a-github-app),
[webhook signatures](https://docs.github.com/en/webhooks/using-webhooks/validating-webhook-deliveries).
