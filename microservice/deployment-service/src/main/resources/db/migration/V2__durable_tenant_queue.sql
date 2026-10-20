-- Legacy rows stay unowned and invisible. Ownership is never guessed.
ALTER TABLE deployment.deployments
    ADD COLUMN organization_id UUID,
    ADD COLUMN request_key VARCHAR(128),
    ADD COLUMN requested_by VARCHAR(150),
    ADD COLUMN configuration JSONB,
    ADD COLUMN retry_of UUID,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    ADD COLUMN image_reference VARCHAR(1024),
    ADD COLUMN failure_code VARCHAR(50);
ALTER TABLE deployment.deployments DROP CONSTRAINT ck_deployments_status;
UPDATE deployment.deployments SET status='SUCCEEDED' WHERE status='SUCCESS';
ALTER TABLE deployment.deployments ADD CONSTRAINT ck_deployments_status CHECK
    (status IN ('QUEUED','PREPARING','BUILDING','PUSHING','DEPLOYING','VERIFYING','SUCCEEDED','FAILED','CANCELLED'));
ALTER TABLE deployment.deployments DROP CONSTRAINT ck_deployments_trigger_type;
ALTER TABLE deployment.deployments ADD CONSTRAINT ck_deployments_trigger_type CHECK
    (trigger_type IN ('MANUAL','GIT_PUSH','ROLLBACK','RETRY'));
ALTER TABLE deployment.deployments ADD CONSTRAINT ck_managed_deployment_owner CHECK
    (organization_id IS NOT NULL AND request_key IS NOT NULL AND requested_by IS NOT NULL AND configuration IS NOT NULL) NOT VALID;
ALTER TABLE deployment.deployments ADD CONSTRAINT uq_deployment_owner_id UNIQUE (organization_id,id);
ALTER TABLE deployment.deployments ADD CONSTRAINT fk_deployment_retry_owner
    FOREIGN KEY (organization_id,retry_of) REFERENCES deployment.deployments (organization_id,id);
CREATE UNIQUE INDEX uq_deployment_request ON deployment.deployments (organization_id,request_key) WHERE organization_id IS NOT NULL;
CREATE INDEX ix_deployment_org_created ON deployment.deployments (organization_id,created_at DESC,id) WHERE organization_id IS NOT NULL;

ALTER TABLE deployment.deployment_jobs
    ADD COLUMN managed BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN lease_token UUID,
    ADD COLUMN last_worker VARCHAR(150);
ALTER TABLE deployment.deployment_jobs DROP CONSTRAINT ck_deployment_jobs_status;
ALTER TABLE deployment.deployment_jobs ADD CONSTRAINT ck_deployment_jobs_status
    CHECK (status IN ('PENDING','RUNNING','DONE','FAILED','CANCELLED'));
ALTER TABLE deployment.deployment_jobs ADD CONSTRAINT ck_managed_job_token
    CHECK (NOT managed OR status<>'RUNNING' OR lease_token IS NOT NULL);
CREATE UNIQUE INDEX uq_managed_job_deployment ON deployment.deployment_jobs (deployment_id) WHERE managed;

-- Every mutation of a managed job locks its application slot first. Claims use
-- SKIP LOCKED so unrelated applications can be processed concurrently.
CREATE TABLE deployment.application_slots (
    organization_id UUID NOT NULL,
    application_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (organization_id,application_id)
);
