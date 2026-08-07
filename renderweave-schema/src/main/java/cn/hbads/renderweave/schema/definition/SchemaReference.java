package cn.hbads.renderweave.schema.definition;

import cn.hbads.renderweave.schema.identity.SchemaKey;

public sealed interface SchemaReference permits SchemaRef, StaticSchemaRef {
    SchemaKey schemaKey();
}
