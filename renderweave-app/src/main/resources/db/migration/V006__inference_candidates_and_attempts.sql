CREATE TABLE inference_candidate (
    run_id UUID PRIMARY KEY REFERENCES inference_run(run_id) ON DELETE CASCADE,
    revision BIGINT NOT NULL CHECK (revision >= 0),
    contract_version VARCHAR(128) NOT NULL,
    original_json JSONB NOT NULL,
    current_json JSONB NOT NULL,
    validation_problems JSONB NOT NULL CHECK (jsonb_typeof(validation_problems) = 'array'),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CHECK (octet_length(original_json::text) <= 2097152),
    CHECK (octet_length(current_json::text) <= 2097152),
    CHECK (octet_length(validation_problems::text) <= 2097152)
);

CREATE TABLE inference_attempt (
    run_id UUID NOT NULL REFERENCES inference_run(run_id) ON DELETE CASCADE,
    attempt_ordinal INTEGER NOT NULL CHECK (attempt_ordinal BETWEEN 0 AND 2),
    stage VARCHAR(32) NOT NULL CHECK (stage IN ('STRUCTURE', 'REPAIR')),
    status VARCHAR(16) NOT NULL CHECK (status IN ('SUCCEEDED', 'REJECTED')),
    outcome_code VARCHAR(128) NOT NULL CHECK (outcome_code ~ '^[A-Z][A-Z0-9_]{0,127}$'),
    completed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (run_id, attempt_ordinal)
);
