package cn.hbads.renderweave.schema.draft;

import cn.hbads.renderweave.schema.identity.SchemaKey;

import java.time.Instant;
import java.util.Objects;

/** Persistence-port payload. definitionJson is a complete normalized DSL snapshot. */
public record StoredDraft(
        SchemaKey schemaKey,
        long revision,
        String definitionJson,
        CreationSource creationSource,
        Instant createdAt,
        Instant updatedAt,
        Instant savedAt
) {

    public StoredDraft {
        Objects.requireNonNull(schemaKey, "schemaKey");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        Objects.requireNonNull(definitionJson, "definitionJson");
        Objects.requireNonNull(creationSource, "creationSource");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(savedAt, "savedAt");
    }
}
