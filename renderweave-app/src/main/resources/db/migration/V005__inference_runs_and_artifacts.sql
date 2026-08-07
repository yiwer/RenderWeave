CREATE TABLE inference_artifact (
    artifact_id CHAR(64) PRIMARY KEY CHECK (artifact_id ~ '^[a-f0-9]{64}$'),
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('IMAGE', 'JSON_PROFILE')),
    locator VARCHAR(255) NOT NULL UNIQUE,
    media_type VARCHAR(128) NOT NULL,
    byte_length BIGINT NOT NULL CHECK (byte_length >= 0),
    width INTEGER NULL CHECK (width IS NULL OR width > 0),
    height INTEGER NULL CHECK (height IS NULL OR height > 0),
    deletion_pending BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    CHECK ((width IS NULL) = (height IS NULL)),
    CHECK (
        (kind = 'IMAGE' AND width IS NOT NULL AND height IS NOT NULL)
        OR (kind = 'JSON_PROFILE' AND width IS NULL AND height IS NULL)
    )
);

CREATE TABLE inference_run (
    run_id UUID PRIMARY KEY,
    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
    request_fingerprint CHAR(64) NOT NULL CHECK (request_fingerprint ~ '^[a-f0-9]{64}$'),
    input_fingerprint CHAR(64) NOT NULL CHECK (input_fingerprint ~ '^[a-f0-9]{64}$'),
    mode VARCHAR(16) NOT NULL CHECK (mode IN ('IMAGE_ONLY', 'JSON_ONLY', 'COMBINED')),
    state VARCHAR(32) NOT NULL CHECK (
        state IN ('QUEUED', 'RUNNING', 'REVIEW_REQUIRED', 'APPLYING', 'COMPLETED', 'FAILED', 'CANCELLED')
    ),
    stage VARCHAR(32) NOT NULL CHECK (
        stage IN (
            'NORMALIZE', 'OBSERVE', 'STRUCTURE', 'DETERMINISTIC_VALIDATE',
            'CRITIQUE', 'REPAIR', 'USER_APPROVAL', 'ATOMIC_CREATE'
        )
    ),
    sequence BIGINT NOT NULL CHECK (sequence >= 1),
    profile_id VARCHAR(128) NOT NULL,
    profile_snapshot JSONB NOT NULL,
    replay_fixture_id VARCHAR(128) NOT NULL,
    retry_of_run_id UUID NULL REFERENCES inference_run(run_id) ON DELETE SET NULL,
    cancellation_requested BOOLEAN NOT NULL DEFAULT FALSE,
    lease_owner VARCHAR(128) NULL,
    lease_token UUID NULL,
    lease_expires_at TIMESTAMPTZ NULL,
    failure_code VARCHAR(128) NULL,
    checkpoint_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ NULL,
    CHECK (
        (lease_owner IS NULL AND lease_token IS NULL AND lease_expires_at IS NULL)
        OR (lease_owner IS NOT NULL AND lease_token IS NOT NULL AND lease_expires_at IS NOT NULL)
    ),
    CHECK ((state = 'RUNNING') = (lease_token IS NOT NULL)),
    CHECK ((state IN ('COMPLETED', 'FAILED', 'CANCELLED')) = (finished_at IS NOT NULL)),
    CHECK (octet_length(profile_snapshot::text) <= 1048576),
    CHECK (octet_length(checkpoint_json::text) <= 2097152)
);

CREATE INDEX inference_run_claim_idx
    ON inference_run (state, lease_expires_at, created_at, run_id)
    WHERE state IN ('QUEUED', 'RUNNING');

CREATE TABLE inference_run_input (
    run_id UUID NOT NULL REFERENCES inference_run(run_id) ON DELETE CASCADE,
    input_kind VARCHAR(16) NOT NULL CHECK (input_kind IN ('IMAGE', 'JSON_PROFILE')),
    input_ordinal INTEGER NOT NULL CHECK (input_ordinal >= 0),
    artifact_id CHAR(64) NOT NULL REFERENCES inference_artifact(artifact_id) ON DELETE RESTRICT,
    PRIMARY KEY (run_id, input_kind, input_ordinal)
);

CREATE INDEX inference_run_input_artifact_idx ON inference_run_input (artifact_id);

CREATE TABLE inference_run_event (
    run_id UUID NOT NULL REFERENCES inference_run(run_id) ON DELETE CASCADE,
    sequence BIGINT NOT NULL CHECK (sequence >= 1),
    event_type VARCHAR(64) NOT NULL,
    state VARCHAR(32) NOT NULL,
    stage VARCHAR(32) NOT NULL,
    data_json JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (run_id, sequence)
);
