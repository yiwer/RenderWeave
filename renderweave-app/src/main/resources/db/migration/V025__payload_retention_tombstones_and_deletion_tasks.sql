ALTER TABLE inference_artifact
    ADD COLUMN payload_deleted_at TIMESTAMPTZ NULL;

CREATE TABLE inference_payload_retention (
    run_id UUID NOT NULL REFERENCES external_transfer_confirmation(run_id) ON DELETE RESTRICT,
    artifact_id CHAR(64) NOT NULL REFERENCES inference_artifact(artifact_id) ON DELETE RESTRICT,
    origin_run_id UUID NOT NULL REFERENCES inference_run(run_id) ON DELETE RESTRICT,
    first_uploaded_at TIMESTAMPTZ NOT NULL,
    payload_expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (run_id, artifact_id),
    CHECK (payload_expires_at > first_uploaded_at),
    CHECK (payload_expires_at <= first_uploaded_at + INTERVAL '7 days'),
    CHECK (created_at >= first_uploaded_at)
);

CREATE INDEX inference_payload_retention_artifact_idx
    ON inference_payload_retention (artifact_id, payload_expires_at);

CREATE INDEX inference_payload_retention_expiry_idx
    ON inference_payload_retention (payload_expires_at, run_id);

CREATE TABLE payload_deletion_tombstone (
    run_id UUID PRIMARY KEY REFERENCES external_transfer_confirmation(run_id) ON DELETE RESTRICT,
    reason VARCHAR(64) NOT NULL CHECK (
        reason IN ('COMPLETED', 'TERMINAL_RETENTION_ELAPSED', 'PAYLOAD_EXPIRED', 'USER_REQUESTED')
    ),
    tombstoned_at TIMESTAMPTZ NOT NULL,
    delete_deadline_at TIMESTAMPTZ NOT NULL,
    CHECK (delete_deadline_at = tombstoned_at + INTERVAL '24 hours')
);

CREATE INDEX payload_deletion_tombstone_deadline_idx
    ON payload_deletion_tombstone (delete_deadline_at, run_id);

-- Normalization necessarily precedes the transaction that creates a run and confirmation. This
-- short lease closes that gap without extending the seven-day payload retention grant.
CREATE TABLE inference_artifact_ingest_lease (
    artifact_id CHAR(64) PRIMARY KEY CHECK (artifact_id ~ '^[a-f0-9]{64}$'),
    observed_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CHECK (expires_at > observed_at),
    CHECK (expires_at <= observed_at + INTERVAL '15 minutes')
);

CREATE INDEX inference_artifact_ingest_lease_expiry_idx
    ON inference_artifact_ingest_lease (expires_at, artifact_id);

CREATE TABLE payload_artifact_deletion_task (
    artifact_id CHAR(64) PRIMARY KEY CHECK (artifact_id ~ '^[a-f0-9]{64}$'),
    state VARCHAR(16) NOT NULL CHECK (
        state IN ('PENDING', 'IN_PROGRESS', 'DELETED', 'SUPERSEDED')
    ),
    scheduled_at TIMESTAMPTZ NOT NULL,
    delete_deadline_at TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lease_token UUID NULL,
    lease_expires_at TIMESTAMPTZ NULL,
    last_failure_code VARCHAR(128) NULL CHECK (
        last_failure_code IS NULL OR last_failure_code ~ '^[A-Z][A-Z0-9_]{0,127}$'
    ),
    completed_at TIMESTAMPTZ NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CHECK (delete_deadline_at = scheduled_at + INTERVAL '24 hours'),
    CHECK (
        (state = 'IN_PROGRESS' AND lease_token IS NOT NULL
            AND lease_expires_at IS NOT NULL AND completed_at IS NULL)
        OR (state = 'PENDING' AND lease_token IS NULL
            AND lease_expires_at IS NULL AND completed_at IS NULL)
        OR (state IN ('DELETED', 'SUPERSEDED') AND lease_token IS NULL
            AND lease_expires_at IS NULL AND completed_at IS NOT NULL)
    )
);

CREATE INDEX payload_artifact_deletion_task_claim_idx
    ON payload_artifact_deletion_task (next_attempt_at, scheduled_at, artifact_id)
    WHERE state IN ('PENDING', 'IN_PROGRESS');

CREATE INDEX payload_artifact_deletion_task_deadline_idx
    ON payload_artifact_deletion_task (delete_deadline_at, artifact_id)
    WHERE state IN ('PENDING', 'IN_PROGRESS');

CREATE OR REPLACE FUNCTION reject_payload_lifecycle_fact_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'payload lifecycle facts are append-only';
END;
$$;

CREATE TRIGGER inference_payload_retention_append_only
    BEFORE UPDATE OR DELETE ON inference_payload_retention
    FOR EACH ROW EXECUTE FUNCTION reject_payload_lifecycle_fact_mutation();

CREATE TRIGGER payload_deletion_tombstone_append_only
    BEFORE UPDATE OR DELETE ON payload_deletion_tombstone
    FOR EACH ROW EXECUTE FUNCTION reject_payload_lifecycle_fact_mutation();

COMMENT ON TABLE inference_payload_retention IS
    'Immutable per-run payload grant; retry/reference reuse must preserve origin and expiry.';

COMMENT ON TABLE payload_deletion_tombstone IS
    'Immutable logical deletion boundary; payload reads, retries, Provider calls and apply stop here.';

COMMENT ON TABLE payload_artifact_deletion_task IS
    'Mutable, payload-free operational queue for ciphertext and wrapped-DEK erasure.';
