CREATE TABLE static_schema (
    schema_key VARCHAR(63) NOT NULL,
    version_tag VARCHAR(64) NOT NULL,
    origin VARCHAR(16) NOT NULL CHECK (origin IN ('DRAFT', 'SYSTEM')),
    source_draft_revision BIGINT NULL CHECK (source_draft_revision IS NULL OR source_draft_revision >= 0),
    definition_json JSONB NOT NULL,
    compiled_json_schema JSON NOT NULL,
    compiler_version VARCHAR(128) NOT NULL,
    release_note TEXT NULL,
    reference_depth INTEGER NOT NULL CHECK (reference_depth BETWEEN 1 AND 16),
    published_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (schema_key, version_tag),
    FOREIGN KEY (schema_key, source_draft_revision)
        REFERENCES schema_draft_revision(schema_key, revision) ON DELETE RESTRICT,
    CHECK (
        (origin = 'DRAFT' AND source_draft_revision IS NOT NULL)
        OR (origin = 'SYSTEM' AND source_draft_revision IS NULL)
    ),
    CHECK (octet_length(compiled_json_schema::text) <= 2097152)
);

CREATE TABLE static_schema_reference_edge (
    source_schema_key VARCHAR(63) NOT NULL,
    source_version_tag VARCHAR(64) NOT NULL,
    source_pointer VARCHAR(512) NOT NULL,
    target_schema_key VARCHAR(63) NOT NULL,
    target_version_tag VARCHAR(64) NOT NULL,
    PRIMARY KEY (source_schema_key, source_version_tag, source_pointer),
    FOREIGN KEY (source_schema_key, source_version_tag)
        REFERENCES static_schema(schema_key, version_tag) ON DELETE RESTRICT,
    FOREIGN KEY (target_schema_key, target_version_tag)
        REFERENCES static_schema(schema_key, version_tag) ON DELETE RESTRICT
);

INSERT INTO static_schema (
    schema_key, version_tag, origin, definition_json, compiled_json_schema,
    compiler_version, release_note, reference_depth
) VALUES
(
    'system-empty', 'v1', 'SYSTEM',
    CAST($json${"dslVersion":"renderweave-schema/1.0","displayName":"空 Schema","description":"系统预置：无字段。","fields":[]}$json$ AS jsonb),
    CAST($json${"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object","properties":{},"required":[],"additionalProperties":true,"x-renderweave-static-schema-ref":{"schemaKey":"system-empty","versionTag":"v1"},"x-renderweave-compiler-version":"renderweave-json-schema/1.0"}$json$ AS json),
    'renderweave-json-schema/1.0', '系统预置', 1
),
(
    'system-basic-text', 'v1', 'SYSTEM',
    CAST($json${"dslVersion":"renderweave-schema/1.0","displayName":"基础文本项","fields":[{"fieldKey":"index","required":true,"value":{"type":"decimal","constraints":{"min":0,"multipleOf":1}}},{"fieldKey":"value","required":true,"value":{"type":"text"}}]}$json$ AS jsonb),
    CAST($json${"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object","properties":{"index":{"type":"number","minimum":0,"multipleOf":1,"x-renderweave-type":"decimal"},"value":{"type":"string","x-renderweave-type":"text"}},"required":["index","value"],"additionalProperties":true,"x-renderweave-static-schema-ref":{"schemaKey":"system-basic-text","versionTag":"v1"},"x-renderweave-compiler-version":"renderweave-json-schema/1.0"}$json$ AS json),
    'renderweave-json-schema/1.0', '系统预置', 1
),
(
    'system-basic-decimal', 'v1', 'SYSTEM',
    CAST($json${"dslVersion":"renderweave-schema/1.0","displayName":"基础数值项","fields":[{"fieldKey":"index","required":true,"value":{"type":"decimal","constraints":{"min":0,"multipleOf":1}}},{"fieldKey":"value","required":true,"value":{"type":"decimal"}}]}$json$ AS jsonb),
    CAST($json${"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object","properties":{"index":{"type":"number","minimum":0,"multipleOf":1,"x-renderweave-type":"decimal"},"value":{"type":"number","x-renderweave-type":"decimal"}},"required":["index","value"],"additionalProperties":true,"x-renderweave-static-schema-ref":{"schemaKey":"system-basic-decimal","versionTag":"v1"},"x-renderweave-compiler-version":"renderweave-json-schema/1.0"}$json$ AS json),
    'renderweave-json-schema/1.0', '系统预置', 1
),
(
    'system-basic-date', 'v1', 'SYSTEM',
    CAST($json${"dslVersion":"renderweave-schema/1.0","displayName":"基础日期项","fields":[{"fieldKey":"index","required":true,"value":{"type":"decimal","constraints":{"min":0,"multipleOf":1}}},{"fieldKey":"value","required":true,"value":{"type":"date"}}]}$json$ AS jsonb),
    CAST($json${"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object","properties":{"index":{"type":"number","minimum":0,"multipleOf":1,"x-renderweave-type":"decimal"},"value":{"type":"string","pattern":"^(?:(?:000[1-9])|(?:00[1-9][0-9])|(?:0[1-9][0-9]{2})|(?:[1-9][0-9]{3}))-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12][0-9]|3[01])$","format":"date","x-renderweave-type":"date"}},"required":["index","value"],"additionalProperties":true,"x-renderweave-static-schema-ref":{"schemaKey":"system-basic-date","versionTag":"v1"},"x-renderweave-compiler-version":"renderweave-json-schema/1.0"}$json$ AS json),
    'renderweave-json-schema/1.0', '系统预置', 1
),
(
    'system-basic-time', 'v1', 'SYSTEM',
    CAST($json${"dslVersion":"renderweave-schema/1.0","displayName":"基础时间项","fields":[{"fieldKey":"index","required":true,"value":{"type":"decimal","constraints":{"min":0,"multipleOf":1}}},{"fieldKey":"value","required":true,"value":{"type":"time"}}]}$json$ AS jsonb),
    CAST($json${"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object","properties":{"index":{"type":"number","minimum":0,"multipleOf":1,"x-renderweave-type":"decimal"},"value":{"type":"string","pattern":"^(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$","x-renderweave-type":"time"}},"required":["index","value"],"additionalProperties":true,"x-renderweave-static-schema-ref":{"schemaKey":"system-basic-time","versionTag":"v1"},"x-renderweave-compiler-version":"renderweave-json-schema/1.0"}$json$ AS json),
    'renderweave-json-schema/1.0', '系统预置', 1
),
(
    'system-basic-boolean', 'v1', 'SYSTEM',
    CAST($json${"dslVersion":"renderweave-schema/1.0","displayName":"基础布尔项","fields":[{"fieldKey":"index","required":true,"value":{"type":"decimal","constraints":{"min":0,"multipleOf":1}}},{"fieldKey":"value","required":true,"value":{"type":"boolean"}}]}$json$ AS jsonb),
    CAST($json${"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object","properties":{"index":{"type":"number","minimum":0,"multipleOf":1,"x-renderweave-type":"decimal"},"value":{"type":"boolean","x-renderweave-type":"boolean"}},"required":["index","value"],"additionalProperties":true,"x-renderweave-static-schema-ref":{"schemaKey":"system-basic-boolean","versionTag":"v1"},"x-renderweave-compiler-version":"renderweave-json-schema/1.0"}$json$ AS json),
    'renderweave-json-schema/1.0', '系统预置', 1
);
