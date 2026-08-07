package cn.hbads.renderweave.validation;

import cn.hbads.renderweave.schema.definition.SchemaDefinition;

import java.util.Objects;

public record ResolvedSchema(
        ResolvedSchemaIdentity identity,
        SchemaDefinition definition
) {
    public ResolvedSchema {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(definition, "definition");
    }
}
