CREATE TABLE identity.organizations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(150) NOT NULL,
    slug VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_organizations_slug UNIQUE (slug)
);

CREATE TABLE identity.users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL,
    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    full_name VARCHAR(150),
    role VARCHAR(30) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_users_organization FOREIGN KEY (organization_id)
        REFERENCES identity.organizations (id),
    CONSTRAINT ck_users_role CHECK (role IN ('OWNER', 'ADMIN', 'DEVELOPER', 'VIEWER'))
);

CREATE UNIQUE INDEX uq_users_organization_email
    ON identity.users (organization_id, lower(email));

CREATE TABLE identity.user_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    token_hash VARCHAR(255) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_user_sessions_user FOREIGN KEY (user_id)
        REFERENCES identity.users (id) ON DELETE CASCADE,
    CONSTRAINT uq_user_sessions_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_user_sessions_expiry CHECK (expires_at > created_at)
);

CREATE INDEX ix_user_sessions_user ON identity.user_sessions (user_id);
CREATE INDEX ix_user_sessions_expiry ON identity.user_sessions (expires_at);
