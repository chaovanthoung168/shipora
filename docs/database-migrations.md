# Shipora database migrations

The initial migrations implement the 19 business tables in the six schemas.
Every schema has its own `flyway_schema_history` table and independent version
sequence. A V1 in project does not conflict with a V1 in deployment.

## Migration ownership

| Schema | Tables | SQL source |
| --- | --- | --- |
| identity | organizations, users, user_sessions | infrastructure/database/migrator/src/main/resources/db/migration/identity/ |
| git | git_installations, repositories, webhook_deliveries | microservice/git-service/src/main/resources/db/migration/ |
| project | projects, servers | microservice/project-service/src/main/resources/db/migration/ |
| application | applications, application_env_vars, application_secrets, application_domains | microservice/application-service/src/main/resources/db/migration/ |
| deployment | deployments, deployment_jobs, deployment_events, releases, deployment_artifacts, deployment_locks | microservice/deployment-service/src/main/resources/db/migration/ |
| audit | audit_events | infrastructure/database/migrator/src/main/resources/db/migration/audit/ |

Identity and Audit have no hosting application yet, so their SQL lives with the
migration-only runner. This does not add another HTTP service. When those modules
get runtime owners, move their SQL unchanged and retain their schema histories.

The runner packages copies of the four services' SQL during `processResources`.
Edit only the SQL source listed above. Both bootstrap and normal service startup
use identical scripts and the same history tables.

## Apply all migrations

Run from `D:\Projects\Shipora` in PowerShell:

```powershell
docker compose --project-directory infrastructure/database up -d --wait
.\scripts\Import-DatabaseEnv.ps1
.\microservice\project-service\gradlew.bat -p infrastructure/database/migrator run
```

The existing wrapper is reused with `-p` to select the migration runner's build.
The runner exits after migrating; it does not start the services or seed accounts.
The selected database user must be able to create the target schemas, tables, and
indexes. It reads the same `DB_*` / `POSTGRES_*` variables as the API services.
`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and
`SPRING_DATASOURCE_PASSWORD` can override the connection, including TLS options.

To migrate just Identity and Audit:

```powershell
.\microservice\project-service\gradlew.bat -p infrastructure/database/migrator run --args="identity audit"
```

The four API services also migrate their own schemas on startup. After bootstrap,
Flyway validates their V1 checksums and finds no work to repeat. The worker keeps
Flyway disabled and uses the deployment schema after deployment-service migrations.
The gateway has no database migrations.

## Inspect the result

Open psql using the actual user and database name from the database environment:

```powershell
docker exec -it shipora-postgres psql -U shipora_user -d shipora
```

```sql
SELECT schemaname, tablename
FROM pg_tables
WHERE schemaname IN ('identity', 'git', 'project', 'application', 'deployment', 'audit')
ORDER BY schemaname, tablename;
```

Expect 19 business tables plus six Flyway history tables, for 25 tables total.
For example, inspect the project migration record with:

```sql
SELECT version, description, success FROM project.flyway_schema_history;
```

## Decisions made in the initial migrations

- UUID primary keys default to PostgreSQL `gen_random_uuid()`. Java can supply IDs.
- Required fields use `NOT NULL`. All timestamps described as required have a
  default of `CURRENT_TIMESTAMP`; optional lifecycle timestamps remain nullable.
- `updated_at` defaults only on insertion. Update it from the owning service on
  every change, including worker writes to deployment locks. No automatic-update
  trigger is installed.
- `FK` relationships inside a schema are actual foreign keys. Cross-service `REF`
  columns are UUIDs without cross-schema foreign keys. Services must validate
  existence, organization ownership, and authorization before using those IDs.
- Emails are case-insensitively unique within an organization. Login must include
  organization context. Slugs use the specified scoped uniqueness and should be
  normalized by the owning service before insertion.
- Provider installation IDs are unique per provider across Shipora. Repository IDs
  are unique within their installation. Webhook delivery IDs are unique.
- Environment-variable keys and secret keys are individually unique per
  application. Cross-table name conflicts still need an explicit application policy.
- Domain names are case-insensitively unique. At most one primary domain per app
  and one active release per app are allowed by partial unique indexes.
- Server status defaults to OFFLINE. Registration requires a credential reference
  and a verified SSH host-key fingerprint. Repository privacy must be provided.
- Release application IDs must match their referenced deployment's application ID;
  a composite foreign key enforces this within the deployment schema.
- Queue attempts must be between zero and max_attempts, with max_attempts positive.
  A RUNNING job requires a worker and lease expiry. Claim jobs atomically and
  check lease ownership when completing or retrying work. The migrations do not
  implement the worker queue logic.
- Deleting a user deletes their sessions. Deleting an application deletes its
  environment variables, secrets, and domains. Deleting a repository preserves
  webhook history with a null repository ID. Other local foreign keys restrict
  deletion until dependent records are handled explicitly.

Hashing passwords and tokens, encrypting secrets, verifying SSH host keys,
redacting logs, and sanitizing audit metadata are application responsibilities.
The column definitions do not perform those operations. Tenant-scoped UUIDs and
schema separation also do not enforce organization authorization on their own.

## Add future changes

Add `V2__description.sql`, `V3__description.sql`, and so on in the owning schema's
SQL source directory. Leave applied migration files unchanged. Flyway stores a
checksum and will reject a changed historical migration.

These initial migrations intentionally use `CREATE TABLE`, without `IF NOT EXISTS`,
so unexpected pre-existing business tables fail visibly. An existing database with
hand-created tables needs a reviewed reconciliation or baseline plan; do not enable
automatic baselining to hide a schema mismatch. Existing empty service schemas or
Flyway histories created by the earlier connection checks are supported.

References: [Spring Boot Flyway integration](https://docs.spring.io/spring-boot/how-to/data-initialization.html),
[Flyway Java API](https://documentation.red-gate.com/flyway/reference/usage/api-java),
[PostgreSQL constraints](https://www.postgresql.org/docs/17/ddl-constraints.html),
and [PostgreSQL UUID generation](https://www.postgresql.org/docs/17/functions-uuid.html).
