CREATE TABLE project.projects (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL,
    name VARCHAR(150) NOT NULL,
    slug VARCHAR(100) NOT NULL,
    description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_projects_organization_slug UNIQUE (organization_id, slug)
);

CREATE TABLE project.servers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL,
    name VARCHAR(150) NOT NULL,
    host VARCHAR(255) NOT NULL,
    ssh_port INTEGER NOT NULL DEFAULT 22,
    ssh_username VARCHAR(100) NOT NULL,
    credential_secret_ref VARCHAR(255) NOT NULL,
    ssh_host_key_fingerprint VARCHAR(255) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'OFFLINE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_servers_ssh_port CHECK (ssh_port BETWEEN 1 AND 65535),
    CONSTRAINT ck_servers_status CHECK (status IN ('ACTIVE', 'OFFLINE', 'ERROR'))
);

CREATE INDEX ix_servers_organization ON project.servers (organization_id);
