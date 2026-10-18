ALTER TABLE git.git_installations
    ADD COLUMN active BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;

ALTER TABLE git.repositories ADD COLUMN available BOOLEAN NOT NULL DEFAULT true;
CREATE INDEX ix_repositories_installation ON git.repositories (installation_id);

CREATE TABLE git.connection_states (
    state_hash VARCHAR(64) PRIMARY KEY,
    organization_id UUID NOT NULL,
    installation_external_id VARCHAR(100) NOT NULL,
    code_verifier VARCHAR(128) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_connection_states_expiry ON git.connection_states (expires_at);
