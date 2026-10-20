-- Presence is tenant scoped and contains no credentials or lease tokens.
CREATE TABLE deployment.worker_presence (
    organization_id UUID NOT NULL,
    worker_name VARCHAR(150) NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (organization_id, worker_name)
);
