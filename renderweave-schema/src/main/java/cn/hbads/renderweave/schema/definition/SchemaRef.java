package cn.hbads.renderweave.schema.definition;

import cn.hbads.renderweave.schema.identity.SchemaKey;

import java.util.Objects;

public record SchemaRef(SchemaKey schemaKey) implements SchemaReference {
    public SchemaRef {
        Objects.requireNonNull(schemaKey, "schemaKey");
    }
}
