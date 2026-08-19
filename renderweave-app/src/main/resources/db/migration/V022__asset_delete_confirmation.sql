-- T12b: single-use, short-lived confirmation tokens for soft-deleting referenced
-- Assets. The token binds the actor, owner scope, asset, the exact asset revision and
-- the full reference fingerprint captured at precheck; the delete transaction validates
-- every binding while holding the exclusive Asset row reservation and re-derives the
-- fingerprint before any write. Expired/used rows are inert facts, not credentials.
CREATE TABLE asset_delete_confirmation (
    confirmation_token VARCHAR(64) PRIMARY KEY CHECK (btrim(confirmation_token) <> ''),
    owner_scope VARCHAR(256) NOT NULL CHECK (btrim(owner_scope) <> ''),
    asset_id VARCHAR(36) NOT NULL CHECK (
        asset_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
    ),
    actor_id VARCHAR(256) NOT NULL CHECK (btrim(actor_id) <> ''),
    asset_revision BIGINT NOT NULL CHECK (asset_revision >= 0),
    reference_fingerprint CHAR(64) NOT NULL CHECK (
        reference_fingerprint ~ '^[0-9a-f]{64}$'
    ),
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (asset_id) REFERENCES asset_aggregate(asset_id) ON DELETE RESTRICT
);

CREATE INDEX asset_delete_confirmation_asset_id_idx
    ON asset_delete_confirmation (asset_id);
