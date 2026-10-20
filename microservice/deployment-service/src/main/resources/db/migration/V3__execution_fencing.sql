CREATE SEQUENCE deployment.execution_fences;
ALTER TABLE deployment.deployment_jobs ADD COLUMN execution_fence BIGINT NOT NULL DEFAULT 0;
ALTER TABLE deployment.deployments ADD COLUMN runtime_endpoint VARCHAR(2048);
ALTER TABLE deployment.releases ADD COLUMN runtime_endpoint VARCHAR(2048);
