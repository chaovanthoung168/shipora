CREATE TABLE git.deployment_push_outbox (
 id UUID PRIMARY KEY,
 organization_id UUID NOT NULL,
 repository_id UUID NOT NULL,
 commit_sha VARCHAR(64) NOT NULL,
 branch VARCHAR(255) NOT NULL,
 attempts INTEGER NOT NULL DEFAULT 0,
 next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 lease_token UUID,
 leased_until TIMESTAMPTZ,
 delivered_at TIMESTAMPTZ,
 last_code VARCHAR(50),
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX ix_push_outbox_due ON git.deployment_push_outbox(organization_id,next_attempt_at,created_at,id) WHERE delivered_at IS NULL;
