package cn.hbads.renderweave.schema.staticvalue;

import cn.hbads.renderweave.schema.definition.StaticSchemaRef;

import java.time.Instant;
import java.util.Objects;

/** Persistence projection for list cards; intentionally excludes DSL and compiled artifact bytes. */
public record StoredStaticSchemaSummary(
        StaticSchemaRef reference,
        StaticSchemaOrigin origin,
        String displayName,
        int fieldCount,
        int referenceDepth,
        Instant publishedAt
) {
    public StoredStaticSchemaSummary {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(displayName, "displayName");
        if (fieldCount < 0 || fieldCount > 256) {
            throw new IllegalArgumentException("fieldCount must be between 0 and 256");
        }
        if (referenceDepth < 1 || referenceDepth > 16) {
            throw new IllegalArgumentException("referenceDepth must be between 1 and 16");
        }
        Objects.requireNonNull(publishedAt, "publishedAt");
    }
}
