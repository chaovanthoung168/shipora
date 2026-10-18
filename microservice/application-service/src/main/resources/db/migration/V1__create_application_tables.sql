CREATE TABLE application.applications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL,
    repository_id UUID NOT NULL,
    server_id UUID NOT NULL,
    name VARCHAR(150) NOT NULL,
    slug VARCHAR(100) NOT NULL,
    branch VARCHAR(255) NOT NULL DEFAULT 'main',
    dockerfile_path VARCHAR(255) NOT NULL DEFAULT 'Dockerfile',
    build_context VARCHAR(255) NOT NULL DEFAULT '.',
    container_port INTEGER NOT NULL,
    health_path VARCHAR(255) NOT NULL DEFAULT '/',
    auto_deploy BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_applications_project_slug UNIQUE (project_id, slug),
    CONSTRAINT ck_applications_container_port CHECK (container_port BETWEEN 1 AND 65535)
);

CREATE INDEX ix_applications_repository ON application.applications (repository_id);
CREATE INDEX ix_applications_server ON application.applications (server_id);

CREATE TABLE application.application_env_vars (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id UUID NOT NULL,
    key VARCHAR(255) NOT NULL,
    value TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_application_env_vars_application FOREIGN KEY (application_id)
        REFERENCES application.applications (id) ON DELETE CASCADE,
    CONSTRAINT uq_application_env_vars_application_key UNIQUE (application_id, key)
);

CREATE TABLE application.application_secrets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id UUID NOT NULL,
    key VARCHAR(255) NOT NULL,
    encrypted_value TEXT NOT NULL,
    encryption_key_version VARCHAR(50) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_application_secrets_application FOREIGN KEY (application_id)
        REFERENCES application.applications (id) ON DELETE CASCADE,
    CONSTRAINT uq_application_secrets_application_key UNIQUE (application_id, key)
);

CREATE TABLE application.application_domains (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id UUID NOT NULL,
    hostname VARCHAR(255) NOT NULL,
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    https_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_application_domains_application FOREIGN KEY (application_id)
        REFERENCES application.applications (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uq_application_domains_hostname
    ON application.application_domains (lower(hostname));
CREATE UNIQUE INDEX uq_application_domains_primary
    ON application.application_domains (application_id) WHERE is_primary;
CREATE INDEX ix_application_domains_application
    ON application.application_domains (application_id);
