package cn.hbads.renderweave.schema.staticvalue;

import cn.hbads.renderweave.schema.definition.StaticSchemaRef;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record StoredStaticSchema(
        StaticSchemaRef reference,
        StaticSchemaOrigin origin,
        Optional<Long> sourceDraftRevision,
        String definitionJson,
        String compiledJsonSchema,
        String compilerVersion,
        Optional<String> releaseNote,
        int referenceDepth,
        Instant publishedAt
) {

    public StoredStaticSchema {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(origin, "origin");
        sourceDraftRevision = sourceDraftRevision == null ? Optional.empty() : sourceDraftRevision;
        Objects.requireNonNull(definitionJson, "definitionJson");
        Objects.requireNonNull(compiledJsonSchema, "compiledJsonSchema");
        Objects.requireNonNull(compilerVersion, "compilerVersion");
        releaseNote = releaseNote == null ? Optional.empty() : releaseNote;
        if (referenceDepth < 1 || referenceDepth > 16) {
            throw new IllegalArgumentException("referenceDepth must be between 1 and 16");
        }
        Objects.requireNonNull(publishedAt, "publishedAt");
    }
}
