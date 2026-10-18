CREATE TABLE audit.audit_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL,
    actor_user_id UUID,
    action VARCHAR(100) NOT NULL,
    resource_type VARCHAR(100),
    resource_id UUID,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    ip_address INET,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX ix_audit_events_organization_created
    ON audit.audit_events (organization_id, created_at DESC);
CREATE INDEX ix_audit_events_resource
    ON audit.audit_events (resource_type, resource_id) WHERE resource_id IS NOT NULL;
