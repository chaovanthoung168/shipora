# Shipora agent guide

## Scope and working agreements

- Apply this guide throughout the repository. Read any more specific `AGENTS.md` before editing its directory; `apps/web/AGENTS.md` contains additional Next.js requirements.
- Inspect `git status --short` before changing files. Preserve existing local changes and keep edits focused on the requested work.
- `apps/web` is tracked by the parent repository as a Git gitlink. Check its own Git status and instructions when working on the frontend; do not replace it with ordinary parent-repository files.
- Treat source, build files, and tests as the authority when older documentation disagrees. Update affected documentation alongside behavior changes.
- Do not edit generated output, dependency directories, IDE metadata, or local installation files as part of ordinary source changes.

## Product and repository map

Shipora is a self-hosted deployment platform connecting GitHub repositories to Docker and Kubernetes targets. Backend services use Java 25, Spring Boot, and independent Gradle builds. The frontend uses Next.js, React, TypeScript, and Redux Toolkit Query.

| Location | Responsibility |
| --- | --- |
| `apps/gateway` | Reactive Spring Cloud Gateway; public API routing and authentication |
| `apps/web` | Next.js App Router frontend, workspace UI, and typed API client |
| `microservice/project-service` | Projects, server inventory, and SSH connection checks |
| `microservice/application-service` | Application configuration, environment/secrets, and Kubernetes targets |
| `microservice/git-service` | GitHub App setup, connections, repositories, signed webhooks, and push outbox |
| `microservice/deployment-service` | Durable deployment queue, lifecycle, leases, events, and releases |
| `microservice/deployment-worker` | Exact-commit checkout, BuildKit publishing, rollout, health checks, and rollback |
| `common/deployment-protocol` | Shared internal HTTP protocol and security helpers compiled into participating services |
| `infrastructure` | Database bootstrap/migrator, Compose stacks, proxies, and Kubernetes access manifests |
| `scripts` | PowerShell setup/build helpers and isolated Python runtime tests |
| `docs` | Service contracts, operational instructions, and Postman collections |

Default API ports are gateway `8080`, project `8081`, application `8082`, deployment `8083`, and git `8084`. Read each service's configuration for overrides. The pipeline Compose stack exposes its API proxy on `8080`; check for conflicts before starting a local gateway.

## Backend conventions

- Preserve the existing hexagonal layout: `domain`, `application` (use cases, DTOs, ports, services), `adapter/in`, `adapter/out`, and `configuration`.
- Keep business rules independent of Spring, JDBC, HTTP clients, SSH, Docker, and Kubernetes. Put external integrations behind application ports and wire adapters in configuration.
- Follow adjacent Java conventions, including record DTOs, existing Lombok usage, and constructor injection. Avoid broad formatting changes.
- Services own their data. Resolve cross-service references through HTTP ports and validate organization ownership; do not query another service's schema or add cross-schema foreign keys.
- The deployment worker has no database access. It uses authenticated internal HTTP endpoints and job-scoped credentials.
- Shared protocol changes affect multiple independent builds. Inspect consumers and verify every affected service rather than assuming one build covers the repository.
- Keep gateway work reactive; avoid introducing blocking service operations into request filters.

## Frontend conventions

- Read `apps/web/AGENTS.md` and the relevant bundled guides under `apps/web/node_modules/next/dist/docs/` before changing Next.js code. Use the installed version's APIs.
- Use the existing App Router structure, strict TypeScript, and `@/*` imports. Extend typed endpoints in `lib/store/api/shiporaApi.ts` and contracts in `lib/store/api/types.ts` when adding API interactions.
- Inspect the actual route and data flow before changing behavior. The workspace includes sample-data preview flows as well as API client code; the frontend README may describe an earlier preview state. Keep simulated actions clearly distinguished from real operations.
- Preserve responsive layouts, accessible labels, keyboard navigation, dialog focus behavior, and reduced-motion support. Reuse existing CSS, icons, branding, and local fonts.
- Never introduce production credentials into client code, browser storage, or `NEXT_PUBLIC_*` variables. These variables are public browser configuration.

## Security and deployment invariants

- Derive organization context from authenticated identity and enforce it at persistence and service boundaries. Retain cross-organization denial tests when changing access behavior.
- Keep admin, worker, and automation identities separate. Preserve job lease validation on internal source/configuration endpoints and the selfhost `X-Shipora-Request: true` mutation guard.
- Verify GitHub webhook signatures before processing. Preserve delivery deduplication, transactional outbox/inbox behavior, and idempotent job creation.
- Preserve deployment stage validation, lease renewal/cancellation, execution fencing, exact commit checkout, immutable image digests, health verification, and rollback behavior. Do not report a real deployment successful without runtime verification.
- Application environment values and secrets are runtime-only; do not forward them as build arguments or leave checkout credentials in the build context.
- Preserve pinned SSH host-key verification, Kubernetes TLS verification, namespace/ownership checks, bounded network waits, and sanitized public errors.
- Keep encryption keys, private keys, passwords, registry authentication, and local installation configuration out of commits and logs. `.shipora/` and ignored pipeline configuration are installation state; do not inspect their contents unless the task requires it.

## Build, run, and verify

Run commands from the repository root in PowerShell unless stated otherwise. There is no root Gradle build: use the selected service's wrapper and `-p` directory. Java builds require JDK 25.

```powershell
# Example backend checks; substitute the service being changed.
.\microservice\project-service\gradlew.bat -p microservice/project-service test
.\microservice\project-service\gradlew.bat -p microservice/project-service bootJar

# Gateway checks use its own wrapper.
.\apps\gateway\gradlew.bat -p apps/gateway test
```

Run frontend commands from `apps/web`:

```powershell
npm ci                 # When dependencies need installation; retain package-lock.json.
npm run lint
npx tsc --noEmit
npm run build
npm run dev
```

- Run focused tests for affected behavior, then the relevant service checks. Add meaningful regression coverage for changes to business rules, security, persistence, or deployment execution.
- Some database/boot tests require explicitly configured disposable PostgreSQL databases and otherwise skip. Inspect their environment guards and report skipped coverage; never point destructive fixtures at an installation database.
- `scripts/test_pipeline_runtime.py` uses Linux Unix sockets and `/runtime/docker_runtime.py`; run it in its intended isolated Linux fixture environment, not directly as a Windows Python test against a real Docker daemon.
- For frontend interaction changes, check affected routes and flows at desktop and mobile sizes, including keyboard behavior. Reuse a running development server for this workspace.
- `scripts/Build-PipelineImages.ps1` builds five local backend/worker images; it does not publish or deploy. Consult `docs/deployment-pipeline.md` for prerequisites before using setup scripts or starting a stack.
- Do not enable automatic deployment, run infrastructure mutations, or remove persistent volumes as a side effect of development checks. Carry out those actions only when within the user's requested scope.
- Report checks actually run, their results, and any prerequisites or verification gaps. Do not reuse historical test counts in documentation as evidence for the current change.

## Persistence and documentation

- Add new versioned Flyway SQL under the owning service's `src/main/resources/db/migration`. Do not modify already-applied migrations; each schema has an independent version sequence.
- Identity and Audit bootstrap SQL lives under `infrastructure/database/migrator/src/main/resources/db/migration`. The migrator packages service migration sources; edit their originals rather than generated copies.
- Consult `docs/database-setup.md` and `docs/database-migrations.md` for database setup. Cross-check older worker/database descriptions against current source and `docs/deployment-pipeline.md`.
- Update relevant service guides and `docs/postman/` collections when changing endpoints, request/response contracts, authentication, or setup requirements. Use placeholders instead of real credentials.
- Use `docs/deployment-pipeline.md` for pipeline operation, `docs/deployment-service.md` for job APIs, `docs/kubernetes-targets.md` for target access, and `docs/server-connection-checks.md` for SSH verification.
