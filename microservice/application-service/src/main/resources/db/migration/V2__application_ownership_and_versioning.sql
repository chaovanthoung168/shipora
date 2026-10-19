ALTER TABLE application.applications
    ADD COLUMN organization_id UUID,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    ADD COLUMN deleted_at TIMESTAMPTZ;
-- Existing unowned rows remain hidden. New/updated rows must have an explicit owner.
ALTER TABLE application.applications ADD CONSTRAINT ck_applications_owner CHECK (organization_id IS NOT NULL) NOT VALID;
ALTER TABLE application.applications ALTER COLUMN auto_deploy SET DEFAULT FALSE;
ALTER TABLE application.applications DROP CONSTRAINT uq_applications_project_slug;
CREATE UNIQUE INDEX uq_active_applications_project_slug ON application.applications (organization_id, project_id, slug) WHERE deleted_at IS NULL;
CREATE INDEX ix_applications_organization ON application.applications (organization_id, created_at DESC, id) WHERE deleted_at IS NULL;
CREATE TABLE application.secret_key_registry (
    key_version VARCHAR(50) PRIMARY KEY,
    key_fingerprint VARCHAR(64) NOT NULL
);
