-- Renderer-only Asset exact-current selections (ADR-0043, TV1-T13).
-- Business/content facts live only inside AES-GCM ciphertext. The plaintext columns are
-- the idempotency key, opaque identities and fixed expiry controls needed for lookup/sweep.

CREATE TABLE asset_render_selection (
    render_request_id VARCHAR(256) NOT NULL,
    resource_id VARCHAR(70) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    lease_handle CHAR(64) NOT NULL UNIQUE,
    selection_nonce BYTEA NOT NULL,
    selection_cipher BYTEA NOT NULL,
    issued_at BIGINT NOT NULL,
    lease_expires_at BIGINT NOT NULL,
    record_expires_at BIGINT NOT NULL,
    PRIMARY KEY (render_request_id, resource_id),
    CHECK (resource_id ~ '^rwres_[0-9a-f]{64}$'),
    CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CHECK (lease_handle ~ '^[0-9a-f]{64}$'),
    CHECK (octet_length(selection_nonce) = 12),
    CHECK (octet_length(selection_cipher) > 16),
    CHECK (lease_expires_at > 0),
    CHECK (record_expires_at > issued_at)
);

CREATE INDEX asset_render_selection_expiry_idx
    ON asset_render_selection (record_expires_at);
