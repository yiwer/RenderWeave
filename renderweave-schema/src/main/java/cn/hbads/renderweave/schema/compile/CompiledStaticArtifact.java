package cn.hbads.renderweave.schema.compile;

import cn.hbads.renderweave.schema.definition.StaticSchemaRef;

import java.util.Objects;

public record CompiledStaticArtifact(
        StaticSchemaRef reference,
        String compiledJsonSchema
) {

    public CompiledStaticArtifact {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(compiledJsonSchema, "compiledJsonSchema");
    }
}
