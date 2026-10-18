# PostgreSQL connections for local development

Shipora uses one PostgreSQL database with a schema for each service that owns data.
The API services own their Flyway migrations. The deployment worker shares the
deployment schema, with Flyway disabled so deployment-service owns its migrations.

| Service | Schema | Default HTTP port |
| --- | --- | --- |
| gateway | No database | 8080 |
| project-service | project | 8081 |
| application-service | application | 8082 |
| deployment-service | deployment | 8083 |
| git-service | git | 8084 |
| deployment-worker | deployment | 8085, if a web server is enabled |

## Start PostgreSQL

Keep the database name, username, and password in `infrastructure/database/.env`:

```dotenv
POSTGRES_DB=shipora
POSTGRES_USER=shipora_user
POSTGRES_PASSWORD=replace_with_your_local_password
```

Use the existing values if the PostgreSQL volume is already initialized. Changing
the environment file does not change credentials inside an existing database.

From the repository root:

```powershell
docker compose --project-directory infrastructure/database up -d --wait
```

The initialization script creates the service schemas on a new database. For an
existing database, inspect schemas with psql and apply `init/01-create-schemas.sql`
if necessary. The script uses `CREATE SCHEMA IF NOT EXISTS` and preserves data.
With the example credentials above:

```powershell
docker exec -it shipora-postgres psql -U shipora_user -d shipora
```

Inside psql:

```text
\dn
\i /docker-entrypoint-initdb.d/01-create-schemas.sql
\q
```

## Start each service

Open a separate PowerShell terminal for each service. From the repository root,
load the database environment into that terminal, then run the service:

```powershell
.\scripts\Import-DatabaseEnv.ps1
Set-Location .\microservice\project-service
.\gradlew.bat bootRun
```

Repeat with `application-service`, `deployment-service`, `git-service`, and
`deployment-worker`. Start deployment-service before the worker. Start the gateway
from `apps/gateway`; the gateway does not need database environment variables.

The helper reads only the database variables, does not print credential values,
and affects the current terminal and its child processes. Use single-line literal
values, optionally enclosed in matching quotes. It does not expand shell variables
or decode dotenv escape sequences. Remove any inherited `SERVER_PORT` override
when running services together so each service uses its own default port.

## Run from IntelliJ

Set these environment variables in each database-backed service's run configuration:

```dotenv
POSTGRES_DB=your_existing_database
POSTGRES_USER=your_existing_user
POSTGRES_PASSWORD=your_existing_password
```

Use the same values as the database environment file. Loading variables in an IDE
terminal does not add them to IntelliJ's application run configurations. Select the
appropriate service module and Java 25. Each service already selects its own schema
and default HTTP port; `DB_SCHEMA` is not required.

## External PostgreSQL and migrations

The services also accept `DB_NAME`, `DB_USER`, and `DB_PASSWORD`, which take
precedence over their `POSTGRES_*` counterparts. `DB_HOST` defaults to `localhost`,
`DB_PORT` to `5432`, and `SERVER_PORT` can override a service's HTTP port.
For a fully customized JDBC URL, including TLS settings, set `SPRING_DATASOURCE_URL`.
Services in Docker need a database hostname reachable from their container network.

Each service's JDBC search path, Hibernate default schema, and Flyway schema are
aligned. Add table migrations under that service's
`src/main/resources/db/migration/V1__description.sql`, followed by `V2__...sql` for
later changes. Flyway manages table changes; Hibernate validates entity mappings.
The initial table migrations are now included. Apply all six schemas with the
migration-only runner before starting the services; see [Database migrations](database-migrations.md)
for the command, table ownership, schema decisions, and future migration versions.
Business entities and APIs still need to be implemented.

Shared local credentials allow access to all schemas. For a customer deployment,
provision database roles with the permissions each service and migration process
requires; schemas alone do not restrict access.

References: [Spring Boot external configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html)
and [Flyway database initialization](https://docs.spring.io/spring-boot/how-to/data-initialization.html).
