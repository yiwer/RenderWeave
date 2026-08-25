-- Rendering CapabilityState short-term encrypted records (ADR-0044 §5, TV1-T21).
-- state_cipher holds AES-GCM ciphertext; the GCM nonce is derived server-side
-- (HMAC of the record identity) and never stored.

CREATE TABLE rendering_capability_state (
    capability_state_id VARCHAR(64) PRIMARY KEY,
    render_request_id VARCHAR(256) NOT NULL,
    evaluation_fingerprint VARCHAR(256) NOT NULL,
    state_cipher BYTEA NOT NULL,
    issued_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL
);

CREATE INDEX rendering_capability_state_request_idx
    ON rendering_capability_state (render_request_id);

CREATE INDEX rendering_capability_state_expiry_idx
    ON rendering_capability_state (expires_at);
