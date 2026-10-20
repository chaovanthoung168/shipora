# Using Shipora, one step at a time

Shipora connects a GitHub repository to a Docker server or Kubernetes namespace.
A project groups applications; each application defines how one repository is
built and where it runs. A deployment builds and rolls out one exact commit.

## 1. Start the local services

Use PostgreSQL and the configured database environment described in
[database setup](database-setup.md). Run one instance of each API:

| Service | Default port | Local setup |
| --- | --- | --- |
| Gateway | 8080 | Routes to the four APIs below |
| Project | 8081 | Active profile `local` |
| Application | 8082 | Active profile `local` |
| Deployment | 8083 | Active profile `local`; `LOCAL_API_PASSWORD` is required |
| Git | 8084 | Active profile `local`; GitHub App configuration is needed to connect repositories |
| Frontend | 3000 | `npm run dev` from `apps/web` |

Use the same API username/password and organization UUID in all four APIs.
The local project/application/git configuration defaults to username `developer`
and password `qwer`; use those only for local development. Deployment has no
default API password. For the matching local login, start it from the root:

```powershell
$env:LOCAL_API_PASSWORD = 'qwer'
./scripts/Start-DeploymentService.ps1
```

The launcher imports the database environment, builds the JAR, selects `local`,
and stays running until you stop it with Ctrl+C. `-SkipBuild` is available after
building current sources. If you use IntelliJ instead, stop the launcher first,
set **Active profiles** to `local` in the DeploymentServiceApplication run
configuration, and supply `LOCAL_API_PASSWORD` plus the database environment.
Shell environment variables set after IntelliJ started are not automatically
inherited by its run configurations. Do not launch both copies on port 8083.

The frontend needs server-only `BETTER_AUTH_SECRET`, `BETTER_AUTH_URL` matching
its exact origin (for example `http://localhost:3000`), and
`GATEWAY_URL=http://localhost:8080` in `apps/web/.env.local`. See the
[frontend setup](../apps/web/README.md). Keep existing secrets when already set.

If another app owns port 3000, use a matching frontend origin and free port:

```powershell
cd apps/web
$env:BETTER_AUTH_URL = 'http://localhost:3001'
npm run dev -- --port 3001
```

Then open `http://localhost:3001` throughout this walkthrough. Configure the
GitHub callback for that same frontend origin if connecting GitHub.

## 2. Sign in

Open `http://localhost:3000`. If there is no session, Shipora redirects to the
login form. Enter the backend API username and password configured in step 1.
For the default local setup above, use `developer` / `qwer`. There is no separate
frontend registration account. After expiry, sign in again.

## 3. Create your project

Select **Projects → New project**. For example:

| Field | Example | Meaning |
| --- | --- | --- |
| Project name | My first project | Display name, at most 150 characters |
| Project slug | my-first-project | Required, unique within the organization; at most 100 characters |
| Description | My web applications | Optional, at most 5000 characters |

The slug is suggested as you enter the name and can be edited. Use lowercase
English letters, numbers and single hyphens, with no leading/trailing hyphen.
Names in other scripts can use an explicitly entered ASCII slug. Click
**Create project**. Success opens the project's own page. Project settings on
that page let you edit the name, slug and description, then **Save changes**.
If the slug is already taken, choose a different one.

## 4. Connect GitHub

An operator must first configure the backend GitHub App, including its private
key, OAuth credentials, webhook secret, and repository Contents read permission.
Follow the [Git service setup](git-service.md). Set the browser callback to
`<frontend-origin>/integrations/github/callback`; GitHub webhook delivery needs
a reachable public HTTPS endpoint rather than localhost alone.

Open **Integrations → Connect GitHub**, complete GitHub consent, and select the
account/installation offered by the flow. Ensure your GitHub App installation
has access to the desired repository. Back on **Integrations**, select the
connected account, synchronize its repositories, and confirm yours is listed.
Consent and repository access must be approved through GitHub.

## 5. Register a deployment target

Open **Infrastructure → Register target**. Choose one runtime:

* **Docker server:** enter the target name, server IP/hostname, SSH username and
  port, an existing server-side credential secret reference, and the verified
  `SHA256:…` SSH host-key fingerprint. The reference points to an operator-
  configured private key; it is not a password or private key pasted into the
  form. Configure matching Project Service and worker SSH access. The server
  needs Docker Engine and Python 3. See [SSH checks](server-connection-checks.md)
  and the [pipeline setup](deployment-pipeline.md#docker-runtime).
* **Kubernetes cluster:** enter the target name, HTTPS API URL, namespace,
  namespace-scoped service-account token and, for a private CA, its base64 CA
  data. Prepare the namespace permissions first using the
  [Kubernetes target guide](kubernetes-targets.md). TLS verification stays enabled.

Click **Register target**, then **Check connection** on its Infrastructure card.
Continue only after the target reports **ACTIVE**. Registration alone does not
establish connectivity or install Docker/Kubernetes.

## 6. Add an application

Open your project and click **New application**. Choose the connected GitHub
account and repository, then fill in:

| Field | Example | Use |
| --- | --- | --- |
| Application name / slug | Storefront / storefront | Display name and unique identifier |
| Branch | main | Branch containing the commit to deploy |
| Container port | 3000 | The port your application listens on inside the container |
| Runtime / target | Docker server / your ACTIVE target | Where the application will run |
| Dockerfile path | Dockerfile | Path relative to the repository |
| Build context | . | Directory sent to BuildKit |
| Health path | /health | HTTP path returning 2xx once the application is ready |

The repository must contain a working Dockerfile. The process in the container
must listen on `0.0.0.0` at the configured port. Choose `/` for the health path
only if it is a reliable readiness endpoint. Click **Create application**.
Creating it saves configuration; automatic deployment starts disabled.

Runtime environment variables and application secrets currently use the backend
API/Postman collections, not the frontend. They are injected at runtime and are
not build arguments. See [application service](application-service.md).

## 7. Prepare the worker before deploying

Running the APIs and frontend alone does not build or deploy applications.
An operator must configure and start the separate Deployment Worker, BuildKit,
registry push/pull credentials, and runtime access. Worker credentials are
separate from the API login and must match across participating services. Use
the [deployment pipeline guide](deployment-pipeline.md). Do not start the sample
Compose stack alongside an existing installation with conflicting ports.

Worker enablement and infrastructure changes are explicit operator actions.
The frontend does not enable them automatically. A job remains **QUEUED** if
no configured worker can claim it.

## 8. Deploy one exact commit

Open the application page. Under **Deploy from GitHub**, choose a commit by its
message, author and short SHA. The list uses the application's configured
repository and branch; no manual SHA entry is required. Use **Refresh commits**
for the latest history, or **Older/Newer** to browse. Refreshing or changing pages
clears the selection so you can review the exact revision before deploying.
Click **Deploy selected commit**. This
queues a real deployment once the required source/target checks pass. The
deployment opens on a dedicated page; watch events and stages:

`QUEUED → PREPARING → BUILDING → PUSHING → DEPLOYING → VERIFYING → SUCCEEDED`

For a failure, read the reported failure code/events, correct the source or
configuration, and retry. **Retry deployment** is available for failed jobs;
**Cancel deployment** is available only while a job is queued. A cancelled job
offers **Create new deployment**, which opens the application's deployment form
without submitting a job. Choose the desired GitHub commit and explicitly deploy it.
After
**SUCCEEDED**, open/check the reported runtime endpoint and inspect the release.
A queued record is not proof that your application is running.

## 9. Follow deployment history

Open **Deployments** to view jobs. Use the named application filter to request
that application's jobs. Search, status filters and counts apply to the loaded
page; use Previous/Next to browse more. Click a deployment to open its details.

Automatic Git-push deployment requires separately enabling/configuring the
backend automation and the application's `autoDeploy` setting through the API.
The frontend does not currently expose that switch. First verify a manual
deployment; then follow the automation steps in the pipeline guide.

## Troubleshooting

| Symptom | What to check |
| --- | --- |
| Create/update project returns 400 | Refresh for the slug field; fill name and valid slug. Read the field error. |
| Project returns 409 | Slug already exists in this organization; choose another. |
| Deployments return 403 | Deployment API must use `local` or configured `selfhost`; verify its roles and matching organization/account. |
| Login returns 401 | Check the configured backend API username/password. |
| Auth returns 503 / missing configuration | Set the three frontend server environment settings and restart Next.js. |
| Port 8083 already in use | Stop the existing Deployment Service in its terminal/IntelliJ before launching another. |
| No repository available | Complete GitHub installation consent, grant repository access, then synchronize. |
| Target fails a check | Verify pinned SSH key/credential mapping or Kubernetes TLS, permissions and reachability. |
| Deployment stays QUEUED | Configure/start the worker and check its service URLs and worker credentials. |

Use the deployment service guide for precise
[local startup settings](deployment-service.md#start-locally). Keep the existing
database and installation keys; resetting volumes is not a troubleshooting step.
