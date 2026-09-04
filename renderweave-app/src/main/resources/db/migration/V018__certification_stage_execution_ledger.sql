CREATE TABLE certification_stage_ledger (
    authorization_id VARCHAR(96) PRIMARY KEY,
    cycle_id UUID NOT NULL,
    stage VARCHAR(16) NOT NULL CHECK (stage IN ('CANARY_5', 'DEV_20', 'FINAL_60')),
    profile_id VARCHAR(160) NOT NULL,
    profile_sha256 CHAR(64) NOT NULL CHECK (profile_sha256 ~ '^[0-9a-f]{64}$'),
    manifest_identity VARCHAR(256) NOT NULL,
    evaluator_identity VARCHAR(256) NOT NULL,
    maximum_runs INTEGER NOT NULL CHECK (maximum_runs BETWEEN 1 AND 60),
    maximum_provider_calls INTEGER NOT NULL CHECK (maximum_provider_calls BETWEEN 1 AND 720),
    maximum_model_tokens BIGINT NOT NULL CHECK (maximum_model_tokens BETWEEN 1 AND 1000000),
    maximum_cost_micros_cny BIGINT NOT NULL
        CHECK (maximum_cost_micros_cny BETWEEN 1 AND 360000000),
    maximum_provider_calls_per_run INTEGER NOT NULL
        CHECK (maximum_provider_calls_per_run BETWEEN 1 AND 12),
    maximum_cost_per_run_micros_cny BIGINT NOT NULL
        CHECK (maximum_cost_per_run_micros_cny BETWEEN 1 AND 6000000),
    effective_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    approved_by VARCHAR(256) NOT NULL,
    approved_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('OPEN', 'CLOSED')),
    opened_at TIMESTAMPTZ NOT NULL,
    closed_at TIMESTAMPTZ NULL,
    closure_reason VARCHAR(128) NULL,
    CHECK (expires_at > effective_at),
    CHECK (maximum_provider_calls <= maximum_runs * maximum_provider_calls_per_run),
    CHECK (maximum_cost_micros_cny <= maximum_runs * maximum_cost_per_run_micros_cny),
    CHECK (
        (status = 'OPEN' AND closed_at IS NULL AND closure_reason IS NULL)
        OR
        (status = 'CLOSED' AND closed_at IS NOT NULL AND closure_reason IS NOT NULL)
    )
);

CREATE UNIQUE INDEX certification_stage_single_open_stage
    ON certification_stage_ledger (cycle_id, stage)
    WHERE status = 'OPEN';

CREATE TABLE certification_stage_case (
    authorization_id VARCHAR(96) NOT NULL
        REFERENCES certification_stage_ledger(authorization_id) ON DELETE RESTRICT,
    case_id VARCHAR(96) NOT NULL,
    artifact_sha256 CHAR(64) NOT NULL CHECK (artifact_sha256 ~ '^[0-9a-f]{64}$'),
    PRIMARY KEY (authorization_id, case_id),
    UNIQUE (authorization_id, artifact_sha256)
);

CREATE TABLE certification_stage_run (
    authorization_id VARCHAR(96) NOT NULL
        REFERENCES certification_stage_ledger(authorization_id) ON DELETE RESTRICT,
    run_id UUID NOT NULL,
    case_id VARCHAR(96) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (run_id),
    UNIQUE (authorization_id, run_id),
    UNIQUE (authorization_id, case_id),
    FOREIGN KEY (authorization_id, case_id)
        REFERENCES certification_stage_case(authorization_id, case_id) ON DELETE RESTRICT
);

CREATE TABLE certification_stage_call_reservation (
    reservation_id UUID PRIMARY KEY,
    authorization_id VARCHAR(96) NOT NULL,
    run_id UUID NOT NULL,
    attempt_ordinal INTEGER NOT NULL CHECK (attempt_ordinal BETWEEN 0 AND 11),
    reserved_model_tokens BIGINT NOT NULL CHECK (reserved_model_tokens > 0),
    actual_model_tokens BIGINT NULL CHECK (actual_model_tokens IS NULL OR actual_model_tokens >= 0),
    reserved_cost_micros_cny BIGINT NOT NULL CHECK (reserved_cost_micros_cny > 0),
    actual_cost_micros_cny BIGINT NULL
        CHECK (actual_cost_micros_cny IS NULL OR actual_cost_micros_cny >= 0),
    state VARCHAR(16) NOT NULL CHECK (state IN ('RESERVED', 'SETTLED')),
    reserved_at TIMESTAMPTZ NOT NULL,
    settled_at TIMESTAMPTZ NULL,
    UNIQUE (run_id, attempt_ordinal),
    FOREIGN KEY (authorization_id, run_id)
        REFERENCES certification_stage_run(authorization_id, run_id) ON DELETE RESTRICT,
    CHECK (
        (state = 'RESERVED' AND actual_model_tokens IS NULL
            AND actual_cost_micros_cny IS NULL AND settled_at IS NULL)
        OR
        (state = 'SETTLED' AND actual_model_tokens IS NOT NULL
            AND actual_cost_micros_cny IS NOT NULL AND settled_at IS NOT NULL)
    )
);

CREATE INDEX certification_stage_call_authorization_state
    ON certification_stage_call_reservation (authorization_id, state, reserved_at);
