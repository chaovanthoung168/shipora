CREATE TABLE git.github_app_configuration (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    encrypted_credentials TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE git.github_setup_states (
    state_hash VARCHAR(64) PRIMARY KEY,
    organization_id UUID NOT NULL,
    actor VARCHAR(255) NOT NULL,
    callback_url TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_github_setup_states_expiry ON git.github_setup_states (expires_at);
