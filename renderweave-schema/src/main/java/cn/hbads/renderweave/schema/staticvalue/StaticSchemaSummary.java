package cn.hbads.renderweave.schema.staticvalue;

import cn.hbads.renderweave.schema.definition.StaticSchemaRef;

import java.time.Instant;
import java.util.Objects;

public record StaticSchemaSummary(
        StaticSchemaRef reference,
        StaticSchemaOrigin origin,
        String displayName,
        int fieldCount,
        int referenceDepth,
        Instant publishedAt
) {

    public StaticSchemaSummary {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(publishedAt, "publishedAt");
    }
}
