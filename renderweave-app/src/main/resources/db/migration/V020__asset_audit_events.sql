-- Bounded, replayable audit events for effective Asset mutations. The content-changing
-- operations (CONTENT_REPLACE/CONTENT_RESTORE, later DELETE/RESTORE) are the reliable facts
-- that drive Template STALE recheck once the Template dependency projection ticket consumes
-- them; same-content no-ops never emit an event. No raw bytes, full tags or request bodies.
CREATE TABLE asset_audit_event (
    event_id BIGSERIAL PRIMARY KEY,
    asset_id VARCHAR(36) NOT NULL,
    before_asset_revision BIGINT CHECK (before_asset_revision IS NULL OR before_asset_revision >= 0),
    after_asset_revision BIGINT NOT NULL CHECK (after_asset_revision >= 0),
    actor_id VARCHAR(256) NOT NULL CHECK (btrim(actor_id) <> ''),
    operation_type VARCHAR(32) NOT NULL CHECK (operation_type IN (
        'CREATE', 'METADATA_UPDATE', 'CONTENT_REPLACE', 'CONTENT_RESTORE', 'DELETE', 'RESTORE'
    )),
    content_version BIGINT NOT NULL CHECK (content_version >= 0),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (asset_id) REFERENCES asset_aggregate(asset_id) ON DELETE RESTRICT
);

CREATE INDEX asset_audit_event_asset_id_idx
    ON asset_audit_event (asset_id, event_id);
