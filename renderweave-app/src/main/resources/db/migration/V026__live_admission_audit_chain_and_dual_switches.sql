-- IMAGE_ONLY P2-05: payload-free Live Admission Audit chain and dual-switch authority.

CREATE TABLE image_only_admission_policy (
    policy_version INTEGER PRIMARY KEY CHECK (policy_version >= 1),
    enabled BOOLEAN NOT NULL,
    changed_by VARCHAR(192) NOT NULL
        CHECK (changed_by ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,191}$'),
    change_reason VARCHAR(64) NOT NULL CHECK (change_reason IN (
        'DEFAULT_CLOSED', 'OPS_ENABLED', 'OPS_DISABLED',
        'MISCLASSIFICATION_SHUTDOWN', 'AUTOMATIC_COST_STOP')),
    changed_at TIMESTAMPTZ NOT NULL
);

INSERT INTO image_only_admission_policy (policy_version, enabled, changed_by, change_reason, changed_at)
VALUES (1, FALSE, 'renderweave-system-bootstrap', 'DEFAULT_CLOSED', TIMESTAMPTZ '2026-08-18 00:00:00+00');

CREATE TABLE live_admission_audit_event (
    run_id UUID NOT NULL,
    sequence INTEGER NOT NULL CHECK (sequence >= 1),
    event_code VARCHAR(64) NOT NULL CHECK (event_code IN (
        'LIVE_RUN_ADMITTED',
        'ADMISSION_REJECTED_POLICY',
        'ADMISSION_REJECTED_EGRESS',
        'RUN_DRAINED_POLICY',
        'RUN_DRAINED_EGRESS',
        'CALL_AUTHORIZED',
        'CALL_DISPATCH_SUCCEEDED',
        'CALL_DISPATCH_FAILED',
        'CALL_ATTEMPT_AMBIGUOUS')),
    actor_id VARCHAR(192) NULL
        CHECK (actor_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,191}$'),
    confirmation_id UUID NULL,
    reservation_id UUID NULL,
    call_authorization_id UUID NULL,
    attempt_ordinal INTEGER NULL CHECK (attempt_ordinal BETWEEN 0 AND 11),
    input_fingerprint CHAR(64) NULL CHECK (input_fingerprint ~ '^[0-9a-f]{64}$'),
    profile_id VARCHAR(192) NULL
        CHECK (profile_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,191}$'),
    profile_sha256 CHAR(64) NULL CHECK (profile_sha256 ~ '^[0-9a-f]{64}$'),
    decision_code VARCHAR(96) NULL CHECK (decision_code ~ '^[A-Z][A-Z0-9_]{2,95}$'),
    usage_input_tokens BIGINT NULL CHECK (usage_input_tokens >= 0),
    usage_output_tokens BIGINT NULL CHECK (usage_output_tokens >= 0),
    cost_micros_cny BIGINT NULL CHECK (cost_micros_cny >= 0),
    occurred_at TIMESTAMPTZ NOT NULL,
    previous_event_digest CHAR(64) NOT NULL CHECK (previous_event_digest ~ '^[0-9a-f]{64}$'),
    event_digest CHAR(64) NOT NULL CHECK (event_digest ~ '^[0-9a-f]{64}$'),
    PRIMARY KEY (run_id, sequence)
);

COMMENT ON COLUMN live_admission_audit_event.run_id IS
    'Immutable audit reference; intentionally not foreign-keyed so audit facts outlive run rows.';

CREATE INDEX live_admission_audit_event_occurred_idx
    ON live_admission_audit_event (occurred_at);

CREATE TABLE provider_call_authorization (
    call_authorization_id UUID PRIMARY KEY,
    run_id UUID NOT NULL,
    attempt_ordinal INTEGER NOT NULL CHECK (attempt_ordinal BETWEEN 0 AND 11),
    confirmation_id UUID NULL,
    policy_version INTEGER NOT NULL
        REFERENCES image_only_admission_policy(policy_version) ON DELETE RESTRICT,
    egress_permit_identity VARCHAR(192) NOT NULL
        CHECK (egress_permit_identity ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,191}$'),
    profile_id VARCHAR(192) NOT NULL
        CHECK (profile_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,191}$'),
    profile_sha256 CHAR(64) NOT NULL CHECK (profile_sha256 ~ '^[0-9a-f]{64}$'),
    endpoint VARCHAR(512) NOT NULL CHECK (endpoint LIKE 'https://%'),
    manifest_sha256 CHAR(64) NULL CHECK (manifest_sha256 ~ '^[0-9a-f]{64}$'),
    input_fingerprint CHAR(64) NOT NULL CHECK (input_fingerprint ~ '^[0-9a-f]{64}$'),
    reservation_id UUID NOT NULL UNIQUE,
    audit_sequence INTEGER NOT NULL CHECK (audit_sequence >= 1),
    authorized_at TIMESTAMPTZ NOT NULL,
    provider_calls_not_after TIMESTAMPTZ NOT NULL,
    UNIQUE (run_id, attempt_ordinal),
    UNIQUE (run_id, audit_sequence),
    CHECK (provider_calls_not_after > authorized_at)
);

COMMENT ON TABLE provider_call_authorization IS
    'Atomic per-call authorization facts; one row commits together with the cost reservation and audit event before any Provider byte leaves.';

CREATE OR REPLACE FUNCTION enforce_live_admission_audit_chain()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    expected_sequence INTEGER;
    expected_previous CHAR(64);
BEGIN
    SELECT coalesce(max(sequence), 0) + 1,
           coalesce(
               (SELECT event_digest FROM live_admission_audit_event
                WHERE run_id = NEW.run_id ORDER BY sequence DESC LIMIT 1),
               'f617f35d307de727cca8a07a58bf7b09bac9144722b8e370aec119f80ded24fd')
    INTO expected_sequence, expected_previous
    FROM live_admission_audit_event
    WHERE run_id = NEW.run_id;
    IF NEW.sequence <> expected_sequence THEN
        RAISE EXCEPTION 'live admission audit sequence must be monotonic per run';
    END IF;
    IF NEW.previous_event_digest <> expected_previous THEN
        RAISE EXCEPTION 'live admission audit digest chain is broken';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER live_admission_audit_event_chain
    BEFORE INSERT ON live_admission_audit_event
    FOR EACH ROW EXECUTE FUNCTION enforce_live_admission_audit_chain();

CREATE TRIGGER image_only_admission_policy_append_only
    BEFORE UPDATE OR DELETE ON image_only_admission_policy
    FOR EACH ROW EXECUTE FUNCTION reject_live_admission_fact_mutation();

CREATE TRIGGER live_admission_audit_event_append_only
    BEFORE UPDATE OR DELETE ON live_admission_audit_event
    FOR EACH ROW EXECUTE FUNCTION reject_live_admission_fact_mutation();

CREATE TRIGGER provider_call_authorization_append_only
    BEFORE UPDATE OR DELETE ON provider_call_authorization
    FOR EACH ROW EXECUTE FUNCTION reject_live_admission_fact_mutation();

-- Flyway owner keeps UPDATE/DELETE-free audit facts; the runtime role only selects and inserts.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'renderweave_live_runtime') THEN
        CREATE ROLE renderweave_live_runtime NOLOGIN;
    END IF;
END
$$;

GRANT SELECT, INSERT ON image_only_admission_policy TO renderweave_live_runtime;
GRANT SELECT, INSERT ON live_admission_audit_event TO renderweave_live_runtime;
GRANT SELECT, INSERT ON provider_call_authorization TO renderweave_live_runtime;
REVOKE UPDATE, DELETE ON image_only_admission_policy FROM renderweave_live_runtime;
REVOKE UPDATE, DELETE ON live_admission_audit_event FROM renderweave_live_runtime;
REVOKE UPDATE, DELETE ON provider_call_authorization FROM renderweave_live_runtime;
