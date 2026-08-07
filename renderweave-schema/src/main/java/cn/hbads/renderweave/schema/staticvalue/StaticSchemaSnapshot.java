package cn.hbads.renderweave.schema.staticvalue;

import cn.hbads.renderweave.schema.definition.SchemaDefinition;
import cn.hbads.renderweave.schema.definition.StaticSchemaRef;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record StaticSchemaSnapshot(
        StaticSchemaRef reference,
        StaticSchemaOrigin origin,
        Optional<Long> sourceDraftRevision,
        SchemaDefinition definition,
        String compiledJsonSchema,
        String compilerVersion,
        Optional<String> releaseNote,
        int referenceDepth,
        Instant publishedAt
) {

    public StaticSchemaSnapshot {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(origin, "origin");
        sourceDraftRevision = sourceDraftRevision == null ? Optional.empty() : sourceDraftRevision;
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(compiledJsonSchema, "compiledJsonSchema");
        Objects.requireNonNull(compilerVersion, "compilerVersion");
        releaseNote = releaseNote == null ? Optional.empty() : releaseNote;
        Objects.requireNonNull(publishedAt, "publishedAt");
    }
}
