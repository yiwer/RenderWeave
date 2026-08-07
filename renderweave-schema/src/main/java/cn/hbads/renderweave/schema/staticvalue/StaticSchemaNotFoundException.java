package cn.hbads.renderweave.schema.staticvalue;

import cn.hbads.renderweave.schema.definition.StaticSchemaRef;

public final class StaticSchemaNotFoundException extends RuntimeException {

    private final StaticSchemaRef reference;

    public StaticSchemaNotFoundException(StaticSchemaRef reference) {
        super("StaticSchema not found: " + reference.schemaKey() + "@" + reference.versionTag());
        this.reference = reference;
    }

    public StaticSchemaRef reference() {
        return reference;
    }
}
