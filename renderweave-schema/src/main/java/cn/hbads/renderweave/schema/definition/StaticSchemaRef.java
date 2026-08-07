package cn.hbads.renderweave.schema.definition;

import cn.hbads.renderweave.schema.identity.SchemaKey;
import cn.hbads.renderweave.schema.identity.VersionTag;

import java.util.Objects;

public record StaticSchemaRef(SchemaKey schemaKey, VersionTag versionTag) implements SchemaReference {
    public StaticSchemaRef {
        Objects.requireNonNull(schemaKey, "schemaKey");
        Objects.requireNonNull(versionTag, "versionTag");
    }
}
