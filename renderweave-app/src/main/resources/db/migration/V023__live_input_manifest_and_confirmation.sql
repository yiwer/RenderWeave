CREATE TABLE external_transfer_notice (
    notice_version VARCHAR(128) NOT NULL,
    locale VARCHAR(32) NOT NULL,
    content_sha256 CHAR(64) NOT NULL CHECK (content_sha256 ~ '^[a-f0-9]{64}$'),
    provider_legal_entity VARCHAR(256) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    model VARCHAR(128) NOT NULL,
    endpoint VARCHAR(512) NOT NULL CHECK (endpoint LIKE 'https://%'),
    region VARCHAR(64) NOT NULL,
    processing_purpose VARCHAR(1024) NOT NULL,
    provider_retention_statement VARCHAR(1024) NOT NULL,
    provider_secondary_use_statement VARCHAR(1024) NOT NULL,
    provider_human_access_statement VARCHAR(1024) NOT NULL,
    profile_id VARCHAR(128) NOT NULL,
    profile_sha256 CHAR(64) NOT NULL CHECK (profile_sha256 ~ '^[a-f0-9]{64}$'),
    maximum_provider_calls INTEGER NOT NULL CHECK (maximum_provider_calls BETWEEN 1 AND 100),
    maximum_cost_micros_cny BIGINT NOT NULL CHECK (maximum_cost_micros_cny > 0),
    local_payload_retention_seconds BIGINT NOT NULL
        CHECK (local_payload_retention_seconds BETWEEN 1 AND 604800),
    policy_version VARCHAR(128) NOT NULL,
    policy_sha256 CHAR(64) NOT NULL CHECK (policy_sha256 ~ '^[a-f0-9]{64}$'),
    provider_contract_id VARCHAR(192) NOT NULL,
    provider_contract_sha256 CHAR(64) NOT NULL
        CHECK (provider_contract_sha256 ~ '^[a-f0-9]{64}$'),
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (notice_version, locale),
    UNIQUE (notice_version, locale, content_sha256)
);

CREATE TABLE live_input_manifest (
    run_id UUID PRIMARY KEY REFERENCES inference_run(run_id) ON DELETE RESTRICT,
    manifest_version VARCHAR(128) NOT NULL
        CHECK (manifest_version = 'renderweave-live-input-manifest/1.0'),
    manifest_sha256 CHAR(64) NOT NULL CHECK (manifest_sha256 ~ '^[a-f0-9]{64}$'),
    aggregate_normalized_bytes BIGINT NOT NULL CHECK (aggregate_normalized_bytes > 0),
    artifact_count INTEGER NOT NULL CHECK (artifact_count BETWEEN 1 AND 10),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (run_id, manifest_version, manifest_sha256)
);

CREATE TABLE live_input_manifest_item (
    run_id UUID NOT NULL REFERENCES live_input_manifest(run_id) ON DELETE RESTRICT,
    input_ordinal INTEGER NOT NULL CHECK (input_ordinal BETWEEN 0 AND 9),
    artifact_id CHAR(64) NOT NULL REFERENCES inference_artifact(artifact_id) ON DELETE RESTRICT,
    media_type VARCHAR(128) NOT NULL CHECK (media_type = 'image/png'),
    byte_length BIGINT NOT NULL CHECK (byte_length > 0),
    width INTEGER NOT NULL CHECK (width > 0),
    height INTEGER NOT NULL CHECK (height > 0),
    PRIMARY KEY (run_id, input_ordinal)
);

CREATE INDEX live_input_manifest_item_artifact_idx
    ON live_input_manifest_item (artifact_id);

CREATE TABLE external_transfer_confirmation (
    confirmation_id UUID PRIMARY KEY,
    run_id UUID NOT NULL UNIQUE,
    request_fingerprint CHAR(64) NOT NULL CHECK (request_fingerprint ~ '^[a-f0-9]{64}$'),
    actor_id VARCHAR(192) NOT NULL,
    request_id VARCHAR(192) NOT NULL,
    gateway_jti VARCHAR(192) NOT NULL,
    gateway_key_id VARCHAR(128) NOT NULL,
    input_provenance VARCHAR(32) NOT NULL CHECK (input_provenance = 'USER_PROVIDED'),
    sensitivity_class VARCHAR(32) NOT NULL CHECK (sensitivity_class = 'ORDINARY_DESIGN'),
    policy_version VARCHAR(128) NOT NULL,
    policy_sha256 CHAR(64) NOT NULL CHECK (policy_sha256 ~ '^[a-f0-9]{64}$'),
    provider_contract_id VARCHAR(192) NOT NULL,
    provider_contract_sha256 CHAR(64) NOT NULL
        CHECK (provider_contract_sha256 ~ '^[a-f0-9]{64}$'),
    notice_version VARCHAR(128) NOT NULL,
    notice_locale VARCHAR(32) NOT NULL,
    notice_content_sha256 CHAR(64) NOT NULL
        CHECK (notice_content_sha256 ~ '^[a-f0-9]{64}$'),
    provider VARCHAR(64) NOT NULL,
    model VARCHAR(128) NOT NULL,
    endpoint VARCHAR(512) NOT NULL CHECK (endpoint LIKE 'https://%'),
    region VARCHAR(64) NOT NULL,
    profile_id VARCHAR(128) NOT NULL,
    profile_sha256 CHAR(64) NOT NULL CHECK (profile_sha256 ~ '^[a-f0-9]{64}$'),
    manifest_version VARCHAR(128) NOT NULL,
    manifest_sha256 CHAR(64) NOT NULL CHECK (manifest_sha256 ~ '^[a-f0-9]{64}$'),
    maximum_provider_calls INTEGER NOT NULL CHECK (maximum_provider_calls BETWEEN 1 AND 100),
    maximum_cost_micros_cny BIGINT NOT NULL CHECK (maximum_cost_micros_cny > 0),
    confirmed_at TIMESTAMPTZ NOT NULL,
    dispatch_not_after TIMESTAMPTZ NOT NULL,
    provider_calls_not_after TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (run_id, manifest_version, manifest_sha256)
        REFERENCES live_input_manifest(run_id, manifest_version, manifest_sha256) ON DELETE RESTRICT,
    FOREIGN KEY (notice_version, notice_locale, notice_content_sha256)
        REFERENCES external_transfer_notice(notice_version, locale, content_sha256) ON DELETE RESTRICT,
    CHECK (dispatch_not_after = confirmed_at + INTERVAL '15 minutes'),
    CHECK (provider_calls_not_after = confirmed_at + INTERVAL '2 hours')
);

CREATE OR REPLACE FUNCTION reject_live_admission_fact_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'live admission facts are append-only';
END;
$$;

CREATE TRIGGER external_transfer_notice_append_only
    BEFORE UPDATE OR DELETE ON external_transfer_notice
    FOR EACH ROW EXECUTE FUNCTION reject_live_admission_fact_mutation();

CREATE TRIGGER live_input_manifest_append_only
    BEFORE UPDATE OR DELETE ON live_input_manifest
    FOR EACH ROW EXECUTE FUNCTION reject_live_admission_fact_mutation();

CREATE TRIGGER live_input_manifest_item_append_only
    BEFORE UPDATE OR DELETE ON live_input_manifest_item
    FOR EACH ROW EXECUTE FUNCTION reject_live_admission_fact_mutation();

CREATE TRIGGER external_transfer_confirmation_append_only
    BEFORE UPDATE OR DELETE ON external_transfer_confirmation
    FOR EACH ROW EXECUTE FUNCTION reject_live_admission_fact_mutation();
