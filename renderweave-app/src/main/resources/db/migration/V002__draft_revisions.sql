CREATE TABLE schema_draft (
    schema_key VARCHAR(63) PRIMARY KEY,
    current_revision BIGINT NOT NULL CHECK (current_revision >= 0),
    creation_source VARCHAR(16) NOT NULL CHECK (creation_source IN ('USER', 'AI')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    deleted_at TIMESTAMPTZ NULL
);

CREATE TABLE schema_draft_revision (
    schema_key VARCHAR(63) NOT NULL REFERENCES schema_draft(schema_key) ON DELETE RESTRICT,
    revision BIGINT NOT NULL CHECK (revision >= 0),
    definition_json JSONB NOT NULL,
    saved_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (schema_key, revision)
);
