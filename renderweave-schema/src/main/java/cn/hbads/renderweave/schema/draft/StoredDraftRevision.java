package cn.hbads.renderweave.schema.draft;

import cn.hbads.renderweave.schema.identity.SchemaKey;

import java.time.Instant;
import java.util.Objects;

/** Immutable history payload at the persistence boundary. */
public record StoredDraftRevision(
        SchemaKey schemaKey,
        long revision,
        String definitionJson,
        Instant savedAt
) {

    public StoredDraftRevision {
        Objects.requireNonNull(schemaKey, "schemaKey");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        Objects.requireNonNull(definitionJson, "definitionJson");
        Objects.requireNonNull(savedAt, "savedAt");
    }
}
