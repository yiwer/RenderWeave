CREATE TABLE template_aggregate (
    template_id VARCHAR(128) NOT NULL CHECK (btrim(template_id) <> ''),
    owner_scope VARCHAR(256) NOT NULL CHECK (btrim(owner_scope) <> ''),
    schema_key VARCHAR(63) NOT NULL,
    schema_version_tag VARCHAR(64) NOT NULL,
    current_revision BIGINT NOT NULL CHECK (current_revision >= 0),
    lifecycle VARCHAR(16) NOT NULL CHECK (lifecycle IN ('ACTIVE', 'DELETED')),
    readiness VARCHAR(16) NOT NULL CHECK (readiness IN ('READY')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (template_id),
    FOREIGN KEY (schema_key, schema_version_tag)
        REFERENCES static_schema(schema_key, version_tag) ON DELETE RESTRICT
);

CREATE TABLE template_revision (
    template_id VARCHAR(128) NOT NULL,
    revision BIGINT NOT NULL CHECK (revision >= 0),
    design_dsl JSONB NOT NULL,
    canonical_design_dsl BYTEA NOT NULL
        CHECK (octet_length(canonical_design_dsl) BETWEEN 1 AND 16777216),
    content_hash VARCHAR(71) NOT NULL
        CHECK (content_hash ~ '^sha256:[0-9a-f]{64}$'),
    saved_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (template_id, revision),
    FOREIGN KEY (template_id)
        REFERENCES template_aggregate(template_id) ON DELETE RESTRICT
);

ALTER TABLE template_aggregate
    ADD CONSTRAINT template_aggregate_current_revision_fk
    FOREIGN KEY (template_id, current_revision)
    REFERENCES template_revision(template_id, revision)
    ON DELETE RESTRICT
    DEFERRABLE INITIALLY DEFERRED;
