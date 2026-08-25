-- T20: current-only Template dependency projection (AssetRef atoms + TemplateUse logical
-- refs), the readiness recheck state, and the replayable STALE consumer cursor.

ALTER TABLE template_aggregate
    DROP CONSTRAINT template_aggregate_readiness_check;

ALTER TABLE template_aggregate
    ADD CONSTRAINT template_aggregate_readiness_check
        CHECK (readiness IN ('READY', 'INVALID', 'STALE'));

-- Current-only AssetRef atom projection; replaced wholesale whenever the Template
-- current changes (create/save), historical revisions never participate.
CREATE TABLE template_asset_reference (
    template_id VARCHAR(128) NOT NULL,
    owner_scope VARCHAR(256) NOT NULL,
    canonical_pointer TEXT NOT NULL,
    asset_id VARCHAR(36) NOT NULL CHECK (
        asset_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
    ),
    asset_kind VARCHAR(16) NOT NULL CHECK (asset_kind IN ('imageRef', 'fontRef')),
    PRIMARY KEY (template_id, canonical_pointer),
    FOREIGN KEY (template_id) REFERENCES template_aggregate(template_id) ON DELETE CASCADE
);

CREATE INDEX template_asset_reference_asset_id_idx
    ON template_asset_reference (asset_id);

-- Current-only TemplateUse logical ref projection; replaced with the asset projection.
CREATE TABLE template_use_reference (
    template_id VARCHAR(128) NOT NULL,
    canonical_pointer TEXT NOT NULL,
    target_template_id VARCHAR(128) NOT NULL CHECK (btrim(target_template_id) <> ''),
    PRIMARY KEY (template_id, canonical_pointer),
    FOREIGN KEY (template_id) REFERENCES template_aggregate(template_id) ON DELETE CASCADE
);

CREATE INDEX template_use_reference_target_idx
    ON template_use_reference (target_template_id);

-- Replayable cursor over asset_audit_event for the STALE consumer.
CREATE TABLE template_asset_stale_cursor (
    singleton BOOLEAN NOT NULL DEFAULT TRUE PRIMARY KEY CHECK (singleton),
    last_event_id BIGINT NOT NULL CHECK (last_event_id >= 0)
);

INSERT INTO template_asset_stale_cursor (singleton, last_event_id) VALUES (TRUE, 0);
