CREATE TABLE schema_reference_edge (
    source_schema_key VARCHAR(63) NOT NULL,
    source_revision BIGINT NOT NULL CHECK (source_revision >= 0),
    source_pointer VARCHAR(512) NOT NULL,
    target_kind VARCHAR(16) NOT NULL CHECK (target_kind IN ('DRAFT', 'STATIC')),
    target_schema_key VARCHAR(63) NOT NULL,
    target_version_tag VARCHAR(64) NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (source_schema_key, source_revision, source_pointer),
    FOREIGN KEY (source_schema_key, source_revision)
        REFERENCES schema_draft_revision(schema_key, revision) ON DELETE RESTRICT,
    CHECK (
        (target_kind = 'DRAFT' AND target_version_tag IS NULL)
        OR (target_kind = 'STATIC' AND target_version_tag IS NOT NULL)
    )
);

CREATE INDEX schema_reference_edge_active_source_idx
    ON schema_reference_edge (source_schema_key, target_schema_key)
    WHERE active;

CREATE INDEX schema_reference_edge_active_draft_target_idx
    ON schema_reference_edge (target_schema_key, source_schema_key)
    WHERE active AND target_kind = 'DRAFT';
