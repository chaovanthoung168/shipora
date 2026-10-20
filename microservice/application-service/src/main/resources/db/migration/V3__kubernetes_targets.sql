CREATE TABLE application.kubernetes_targets (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    name VARCHAR(150) NOT NULL,
    api_server VARCHAR(2048) NOT NULL,
    namespace VARCHAR(63) NOT NULL,
    encrypted_credentials TEXT,
    encryption_key_version VARCHAR(50),
    status VARCHAR(20) NOT NULL CHECK (status IN ('UNVERIFIED','ACTIVE','ERROR')),
    check_code VARCHAR(50),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    checked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ,
    UNIQUE (organization_id,id),
    CHECK (deleted_at IS NOT NULL OR (encrypted_credentials IS NOT NULL AND encryption_key_version IS NOT NULL))
);
CREATE UNIQUE INDEX uq_active_kubernetes_target_name ON application.kubernetes_targets (organization_id,name) WHERE deleted_at IS NULL;
CREATE INDEX ix_kubernetes_targets_org ON application.kubernetes_targets (organization_id,created_at DESC,id) WHERE deleted_at IS NULL;

ALTER TABLE application.applications
    ADD COLUMN runtime_type VARCHAR(20) NOT NULL DEFAULT 'DOCKER',
    ADD COLUMN kubernetes_target_id UUID,
    ALTER COLUMN server_id DROP NOT NULL;
ALTER TABLE application.applications ADD CONSTRAINT ck_application_runtime_target CHECK (
    (runtime_type='DOCKER' AND server_id IS NOT NULL AND kubernetes_target_id IS NULL) OR
    (runtime_type='KUBERNETES' AND server_id IS NULL AND kubernetes_target_id IS NOT NULL)
);
ALTER TABLE application.applications ADD CONSTRAINT fk_application_kubernetes_target
    FOREIGN KEY (organization_id,kubernetes_target_id) REFERENCES application.kubernetes_targets (organization_id,id);
CREATE INDEX ix_applications_kubernetes_target ON application.applications (organization_id,kubernetes_target_id) WHERE deleted_at IS NULL;
