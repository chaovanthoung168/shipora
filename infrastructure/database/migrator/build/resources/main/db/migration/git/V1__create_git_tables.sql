CREATE TABLE git.git_installations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL,
    provider VARCHAR(30) NOT NULL DEFAULT 'GITHUB',
    installation_external_id VARCHAR(100) NOT NULL,
    account_name VARCHAR(150),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_git_installations_provider CHECK (provider = 'GITHUB'),
    CONSTRAINT uq_git_installations_provider_external
        UNIQUE (provider, installation_external_id)
);

CREATE INDEX ix_git_installations_organization
    ON git.git_installations (organization_id);

CREATE TABLE git.repositories (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    installation_id UUID NOT NULL,
    external_repository_id VARCHAR(100) NOT NULL,
    full_name VARCHAR(255) NOT NULL,
    clone_url TEXT,
    default_branch VARCHAR(255) NOT NULL DEFAULT 'main',
    is_private BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_repositories_installation FOREIGN KEY (installation_id)
        REFERENCES git.git_installations (id),
    CONSTRAINT uq_repositories_installation_external
        UNIQUE (installation_id, external_repository_id)
);

CREATE TABLE git.webhook_deliveries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    repository_id UUID,
    delivery_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    commit_sha VARCHAR(64),
    status VARCHAR(30) NOT NULL DEFAULT 'RECEIVED',
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMPTZ,
    CONSTRAINT fk_webhook_deliveries_repository FOREIGN KEY (repository_id)
        REFERENCES git.repositories (id) ON DELETE SET NULL,
    CONSTRAINT uq_webhook_deliveries_delivery UNIQUE (delivery_id),
    CONSTRAINT ck_webhook_deliveries_status
        CHECK (status IN ('RECEIVED', 'PROCESSED', 'FAILED'))
);

CREATE INDEX ix_webhook_deliveries_repository
    ON git.webhook_deliveries (repository_id);
