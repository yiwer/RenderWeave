ALTER TABLE inference_run RENAME COLUMN replay_fixture_id TO source_reference;

ALTER TABLE inference_attempt
    DROP CONSTRAINT inference_attempt_status_check,
    ADD CONSTRAINT inference_attempt_status_check
        CHECK (status IN ('SUCCEEDED', 'REJECTED', 'FAILED')),
    ADD COLUMN provider_request_id VARCHAR(200) NULL,
    ADD COLUMN provider_model VARCHAR(128) NULL,
    ADD COLUMN input_tokens BIGINT NOT NULL DEFAULT 0 CHECK (input_tokens >= 0),
    ADD COLUMN output_tokens BIGINT NOT NULL DEFAULT 0 CHECK (output_tokens >= 0),
    ADD COLUMN estimated_cost_micros_cny BIGINT NOT NULL DEFAULT 0
        CHECK (estimated_cost_micros_cny >= 0),
    ADD COLUMN duration_millis BIGINT NOT NULL DEFAULT 0 CHECK (duration_millis >= 0),
    ADD CONSTRAINT inference_attempt_provider_metadata_check CHECK (
        (provider_request_id IS NULL AND provider_model IS NULL)
        OR (provider_request_id IS NOT NULL AND provider_model IS NOT NULL)
    );

CREATE TABLE inference_provider_budget (
    budget_key VARCHAR(64) PRIMARY KEY,
    maximum_attempts INTEGER NOT NULL CHECK (maximum_attempts > 0),
    maximum_cost_micros_cny BIGINT NOT NULL CHECK (maximum_cost_micros_cny > 0),
    created_at TIMESTAMPTZ NOT NULL
);

INSERT INTO inference_provider_budget (
    budget_key, maximum_attempts, maximum_cost_micros_cny, created_at
) VALUES (
    'p5-synthetic-canary', 6, 1000000, TIMESTAMPTZ '2026-08-08 00:00:00+00'
);

CREATE TABLE inference_provider_reservation (
    reservation_id UUID PRIMARY KEY,
    budget_key VARCHAR(64) NOT NULL REFERENCES inference_provider_budget(budget_key) ON DELETE RESTRICT,
    run_id UUID NOT NULL REFERENCES inference_run(run_id) ON DELETE CASCADE,
    attempt_ordinal INTEGER NOT NULL CHECK (attempt_ordinal BETWEEN 0 AND 2),
    reserved_cost_micros_cny BIGINT NOT NULL CHECK (reserved_cost_micros_cny > 0),
    actual_cost_micros_cny BIGINT NULL CHECK (actual_cost_micros_cny IS NULL OR actual_cost_micros_cny >= 0),
    state VARCHAR(16) NOT NULL CHECK (state IN ('RESERVED', 'SETTLED')),
    created_at TIMESTAMPTZ NOT NULL,
    settled_at TIMESTAMPTZ NULL,
    UNIQUE (run_id, attempt_ordinal),
    CHECK (
        (state = 'RESERVED' AND actual_cost_micros_cny IS NULL AND settled_at IS NULL)
        OR (state = 'SETTLED' AND actual_cost_micros_cny IS NOT NULL AND settled_at IS NOT NULL)
    )
);

CREATE INDEX inference_provider_reservation_budget_idx
    ON inference_provider_reservation (budget_key, state, created_at);
