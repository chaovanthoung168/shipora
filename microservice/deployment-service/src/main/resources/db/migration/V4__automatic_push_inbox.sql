CREATE TABLE deployment.push_inbox (
 organization_id UUID NOT NULL,
 event_id UUID NOT NULL,
 repository_id UUID NOT NULL,
 commit_sha VARCHAR(64) NOT NULL,
 branch VARCHAR(255) NOT NULL,
 targets JSONB,
 cursor INTEGER NOT NULL DEFAULT 0,
 attempts INTEGER NOT NULL DEFAULT 0,
 next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 lease_token UUID,
 leased_until TIMESTAMPTZ,
 completed_at TIMESTAMPTZ,
 last_code VARCHAR(50),
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(organization_id,event_id)
);
CREATE INDEX ix_push_inbox_due ON deployment.push_inbox(organization_id,next_attempt_at,created_at,event_id) WHERE completed_at IS NULL;
