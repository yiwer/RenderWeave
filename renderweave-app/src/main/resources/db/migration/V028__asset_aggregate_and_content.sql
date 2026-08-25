CREATE TABLE asset_aggregate (
    asset_id VARCHAR(36) NOT NULL CHECK (
        asset_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
    ),
    owner_scope VARCHAR(256) NOT NULL CHECK (btrim(owner_scope) <> ''),
    kind VARCHAR(8) NOT NULL CHECK (kind IN ('IMAGE', 'FONT')),
    lifecycle VARCHAR(16) NOT NULL CHECK (lifecycle IN ('ACTIVE', 'DELETED')),
    asset_revision BIGINT NOT NULL CHECK (asset_revision >= 0),
    current_content_version BIGINT NOT NULL CHECK (current_content_version >= 0),
    display_name VARCHAR(200) NOT NULL,
    tags JSONB NOT NULL DEFAULT '[]',
    source_file_name VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (asset_id)
);

CREATE TABLE asset_content_revision (
    asset_id VARCHAR(36) NOT NULL,
    content_version BIGINT NOT NULL CHECK (content_version >= 0),
    sha256 CHAR(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    media_type VARCHAR(64) NOT NULL CHECK (btrim(media_type) <> ''),
    byte_length BIGINT NOT NULL CHECK (byte_length > 0),
    source_file_name VARCHAR(255),
    descriptor_kind VARCHAR(8) NOT NULL CHECK (descriptor_kind IN ('IMAGE', 'FONT')),
    descriptor_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (asset_id, content_version),
    FOREIGN KEY (asset_id)
        REFERENCES asset_aggregate(asset_id) ON DELETE RESTRICT
);

ALTER TABLE asset_aggregate
    ADD CONSTRAINT asset_aggregate_current_version_fk
    FOREIGN KEY (asset_id, current_content_version)
    REFERENCES asset_content_revision(asset_id, content_version)
    ON DELETE RESTRICT
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE asset_idempotency (
    owner_scope VARCHAR(256) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL CHECK (btrim(idempotency_key) <> ''),
    asset_id VARCHAR(36) NOT NULL,
    fingerprint CHAR(64) NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (owner_scope, idempotency_key)
);

CREATE TABLE asset_capacity (
    deployment_id VARCHAR(64) NOT NULL CHECK (deployment_id = 'default'),
    used_bytes BIGINT NOT NULL DEFAULT 0 CHECK (used_bytes >= 0),
    PRIMARY KEY (deployment_id)
);
