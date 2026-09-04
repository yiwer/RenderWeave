CREATE TABLE inference_artifact_envelope (
    artifact_id CHAR(64) PRIMARY KEY CHECK (artifact_id ~ '^[a-f0-9]{64}$'),
    envelope_version VARCHAR(128) NOT NULL
        CHECK (envelope_version = 'renderweave-artifact-envelope/1.0'),
    payload_algorithm VARCHAR(32) NOT NULL CHECK (payload_algorithm = 'AES-256-GCM'),
    wrapping_algorithm VARCHAR(32) NOT NULL CHECK (wrapping_algorithm = 'AES-256-GCM'),
    ciphertext_locator CHAR(64) NOT NULL UNIQUE
        CHECK (ciphertext_locator = artifact_id),
    ciphertext_sha256 CHAR(64) NOT NULL CHECK (ciphertext_sha256 ~ '^[a-f0-9]{64}$'),
    payload_nonce BYTEA NOT NULL CHECK (octet_length(payload_nonce) = 12),
    payload_tag BYTEA NOT NULL CHECK (octet_length(payload_tag) = 16),
    kek_id VARCHAR(128) NOT NULL CHECK (kek_id ~ '^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$'),
    wrapped_dek BYTEA NOT NULL CHECK (octet_length(wrapped_dek) = 32),
    wrapping_nonce BYTEA NOT NULL CHECK (octet_length(wrapping_nonce) = 12),
    wrapping_tag BYTEA NOT NULL CHECK (octet_length(wrapping_tag) = 16),
    created_at TIMESTAMPTZ NOT NULL,
    rewrapped_at TIMESTAMPTZ NOT NULL CHECK (rewrapped_at >= created_at)
);

CREATE INDEX inference_artifact_envelope_kek_idx
    ON inference_artifact_envelope (kek_id);

COMMENT ON TABLE inference_artifact_envelope IS
    'Envelope metadata and wrapped per-artifact DEKs only; KEK bytes and plaintext are forbidden.';

CREATE OR REPLACE FUNCTION enforce_inference_artifact_envelope_update()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.artifact_id IS DISTINCT FROM OLD.artifact_id
       OR NEW.envelope_version IS DISTINCT FROM OLD.envelope_version
       OR NEW.payload_algorithm IS DISTINCT FROM OLD.payload_algorithm
       OR NEW.wrapping_algorithm IS DISTINCT FROM OLD.wrapping_algorithm
       OR NEW.ciphertext_locator IS DISTINCT FROM OLD.ciphertext_locator
       OR NEW.ciphertext_sha256 IS DISTINCT FROM OLD.ciphertext_sha256
       OR NEW.payload_nonce IS DISTINCT FROM OLD.payload_nonce
       OR NEW.payload_tag IS DISTINCT FROM OLD.payload_tag
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'artifact payload envelope fields are immutable';
    END IF;
    IF NEW.rewrapped_at <= OLD.rewrapped_at THEN
        RAISE EXCEPTION 'artifact envelope re-wrap time must advance';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER inference_artifact_envelope_rewrap_only
    BEFORE UPDATE ON inference_artifact_envelope
    FOR EACH ROW EXECUTE FUNCTION enforce_inference_artifact_envelope_update();
