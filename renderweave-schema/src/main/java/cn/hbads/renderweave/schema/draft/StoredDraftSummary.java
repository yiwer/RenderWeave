package cn.hbads.renderweave.schema.draft;

import cn.hbads.renderweave.schema.identity.SchemaKey;

import java.time.Instant;
import java.util.Objects;

/** Persistence projection for Draft list cards; intentionally excludes definition JSON. */
public record StoredDraftSummary(
        SchemaKey schemaKey,
        long revision,
        CreationSource creationSource,
        String displayName,
        int fieldCount,
        Instant createdAt,
        Instant updatedAt,
        Instant savedAt
) {
    public StoredDraftSummary {
        Objects.requireNonNull(schemaKey, "schemaKey");
        Objects.requireNonNull(creationSource, "creationSource");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(savedAt, "savedAt");
        if (revision < 0 || fieldCount < 0 || fieldCount > 256) {
            throw new IllegalArgumentException("Draft summary counts are outside the supported range");
        }
    }
}
