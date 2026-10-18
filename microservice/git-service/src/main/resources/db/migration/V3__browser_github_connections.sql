ALTER TABLE git.connection_states ALTER COLUMN installation_external_id DROP NOT NULL;
ALTER TABLE git.connection_states ADD COLUMN browser_hash VARCHAR(64);

CREATE TABLE git.browser_connection_choices (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    browser_hash VARCHAR(64) NOT NULL,
    installation_external_id VARCHAR(100) NOT NULL,
    account_name VARCHAR(150) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_browser_choices_session ON git.browser_connection_choices (organization_id, browser_hash);
CREATE INDEX ix_browser_choices_expiry ON git.browser_connection_choices (expires_at);
