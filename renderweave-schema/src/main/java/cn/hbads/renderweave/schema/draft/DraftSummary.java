package cn.hbads.renderweave.schema.draft;

import cn.hbads.renderweave.schema.identity.SchemaKey;

import java.time.Instant;
import java.util.Objects;

public record DraftSummary(
        SchemaKey schemaKey,
        long revision,
        CreationSource creationSource,
        String displayName,
        int fieldCount,
        Instant createdAt,
        Instant updatedAt,
        Instant savedAt
) {

    public DraftSummary {
        Objects.requireNonNull(schemaKey, "schemaKey");
        Objects.requireNonNull(creationSource, "creationSource");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(savedAt, "savedAt");
        if (revision < 0 || fieldCount < 0) {
            throw new IllegalArgumentException("Draft summary counts must not be negative");
        }
    }
}
