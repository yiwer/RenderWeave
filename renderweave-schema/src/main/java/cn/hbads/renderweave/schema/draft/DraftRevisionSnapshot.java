package cn.hbads.renderweave.schema.draft;

import cn.hbads.renderweave.schema.definition.SchemaDefinition;
import cn.hbads.renderweave.schema.identity.SchemaKey;

import java.time.Instant;
import java.util.Objects;

public record DraftRevisionSnapshot(
        SchemaKey schemaKey,
        long revision,
        SchemaDefinition definition,
        Instant savedAt
) {

    public DraftRevisionSnapshot {
        Objects.requireNonNull(schemaKey, "schemaKey");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(savedAt, "savedAt");
    }
}
