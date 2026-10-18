CREATE TABLE deployment.deployments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id UUID NOT NULL,
    commit_sha VARCHAR(64) NOT NULL,
    branch VARCHAR(255),
    trigger_type VARCHAR(30) NOT NULL,
    triggered_by UUID,
    status VARCHAR(30) NOT NULL DEFAULT 'QUEUED',
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_deployments_trigger_type
        CHECK (trigger_type IN ('MANUAL', 'GIT_PUSH', 'ROLLBACK')),
    CONSTRAINT ck_deployments_status
        CHECK (status IN ('QUEUED', 'BUILDING', 'DEPLOYING', 'SUCCESS', 'FAILED')),
    CONSTRAINT ck_deployments_time_order
        CHECK (finished_at IS NULL OR started_at IS NULL OR finished_at >= started_at),
    CONSTRAINT uq_deployments_id_application UNIQUE (id, application_id)
);

CREATE INDEX ix_deployments_application_created
    ON deployment.deployments (application_id, created_at DESC);

CREATE TABLE deployment.deployment_jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    deployment_id UUID NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 3,
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    locked_by VARCHAR(150),
    locked_until TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_deployment_jobs_deployment FOREIGN KEY (deployment_id)
        REFERENCES deployment.deployments (id),
    CONSTRAINT ck_deployment_jobs_status
        CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED')),
    CONSTRAINT ck_deployment_jobs_attempts
        CHECK (attempts >= 0 AND max_attempts > 0 AND attempts <= max_attempts),
    CONSTRAINT ck_deployment_jobs_lock_pair
        CHECK ((locked_by IS NULL) = (locked_until IS NULL)),
    CONSTRAINT ck_deployment_jobs_running_lock
        CHECK (status <> 'RUNNING' OR (locked_by IS NOT NULL AND locked_until IS NOT NULL))
);

CREATE INDEX ix_deployment_jobs_deployment ON deployment.deployment_jobs (deployment_id);
CREATE INDEX ix_deployment_jobs_pending
    ON deployment.deployment_jobs (available_at, created_at, id) WHERE status = 'PENDING';
CREATE INDEX ix_deployment_jobs_expired
    ON deployment.deployment_jobs (locked_until) WHERE status = 'RUNNING';

CREATE TABLE deployment.deployment_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    deployment_id UUID NOT NULL,
    level VARCHAR(20) NOT NULL DEFAULT 'INFO',
    event_type VARCHAR(100) NOT NULL,
    message TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_deployment_events_deployment FOREIGN KEY (deployment_id)
        REFERENCES deployment.deployments (id),
    CONSTRAINT ck_deployment_events_level CHECK (level IN ('INFO', 'WARN', 'ERROR'))
);

CREATE INDEX ix_deployment_events_deployment_id
    ON deployment.deployment_events (deployment_id, id);

CREATE TABLE deployment.releases (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id UUID NOT NULL,
    deployment_id UUID NOT NULL,
    image_reference TEXT NOT NULL,
    container_name VARCHAR(255),
    status VARCHAR(30) NOT NULL DEFAULT 'CANDIDATE',
    activated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_releases_deployment_application FOREIGN KEY (deployment_id, application_id)
        REFERENCES deployment.deployments (id, application_id),
    CONSTRAINT ck_releases_status CHECK (status IN ('CANDIDATE', 'ACTIVE', 'PREVIOUS', 'FAILED'))
);

CREATE INDEX ix_releases_application_created
    ON deployment.releases (application_id, created_at DESC);
CREATE INDEX ix_releases_deployment ON deployment.releases (deployment_id);
CREATE UNIQUE INDEX uq_releases_active_application
    ON deployment.releases (application_id) WHERE status = 'ACTIVE';

CREATE TABLE deployment.deployment_artifacts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    deployment_id UUID NOT NULL,
    artifact_type VARCHAR(50) NOT NULL,
    artifact_reference TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_deployment_artifacts_deployment FOREIGN KEY (deployment_id)
        REFERENCES deployment.deployments (id),
    CONSTRAINT ck_deployment_artifacts_type CHECK (artifact_type = 'DOCKER_IMAGE')
);

CREATE INDEX ix_deployment_artifacts_deployment
    ON deployment.deployment_artifacts (deployment_id);

CREATE TABLE deployment.deployment_locks (
    application_id UUID PRIMARY KEY,
    owner_id VARCHAR(150) NOT NULL,
    locked_until TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX ix_deployment_locks_expiry ON deployment.deployment_locks (locked_until);
