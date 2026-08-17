CREATE TABLE profile_certification_event (
    event_id UUID PRIMARY KEY,
    cycle_id UUID NOT NULL,
    sequence_no INTEGER NOT NULL CHECK (sequence_no >= 0),
    profile_id VARCHAR(160) NOT NULL,
    profile_sha256 CHAR(64) NOT NULL CHECK (profile_sha256 ~ '^[0-9a-f]{64}$'),
    manifest_identity VARCHAR(256) NOT NULL,
    evaluator_identity VARCHAR(256) NOT NULL,
    event_type VARCHAR(32) NOT NULL CHECK (event_type IN (
        'CYCLE_STARTED', 'STAGE_PASSED', 'CYCLE_FAILED',
        'CERTIFICATION_GRANTED', 'CERTIFICATION_REVOKED'
    )),
    stage VARCHAR(16) NULL CHECK (stage IS NULL OR stage IN ('CANARY_5', 'DEV_20', 'FINAL_60')),
    accepted_cases INTEGER NULL CHECK (accepted_cases IS NULL OR accepted_cases >= 0),
    total_cases INTEGER NULL CHECK (total_cases IS NULL OR total_cases IN (5, 20, 60)),
    evidence_identity VARCHAR(256) NOT NULL,
    authority_reference VARCHAR(256) NULL,
    reason_code VARCHAR(128) NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT profile_certification_event_cycle_sequence UNIQUE (cycle_id, sequence_no),
    CONSTRAINT profile_certification_event_stage_shape CHECK (
        (event_type IN ('STAGE_PASSED', 'CYCLE_FAILED')
            AND stage IS NOT NULL AND accepted_cases IS NOT NULL AND total_cases IS NOT NULL)
        OR
        (event_type NOT IN ('STAGE_PASSED', 'CYCLE_FAILED')
            AND stage IS NULL AND accepted_cases IS NULL AND total_cases IS NULL)
    ),
    CONSTRAINT profile_certification_event_authority_shape CHECK (
        (event_type = 'CERTIFICATION_GRANTED' AND authority_reference IS NOT NULL)
        OR (event_type <> 'CERTIFICATION_GRANTED' AND authority_reference IS NULL)
    ),
    CONSTRAINT profile_certification_event_reason_shape CHECK (
        (event_type IN ('CYCLE_FAILED', 'CERTIFICATION_REVOKED') AND reason_code IS NOT NULL)
        OR (event_type NOT IN ('CYCLE_FAILED', 'CERTIFICATION_REVOKED') AND reason_code IS NULL)
    )
);

CREATE INDEX profile_certification_event_cycle_order
    ON profile_certification_event (cycle_id, sequence_no);

CREATE FUNCTION reject_profile_certification_event_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'PROFILE_CERTIFICATION_EVENTS_ARE_APPEND_ONLY';
END;
$$;

CREATE TRIGGER profile_certification_event_no_update_delete
    BEFORE UPDATE OR DELETE ON profile_certification_event
    FOR EACH ROW EXECUTE FUNCTION reject_profile_certification_event_mutation();
