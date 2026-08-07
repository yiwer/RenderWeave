package cn.hbads.renderweave.schema.staticvalue;

import cn.hbads.renderweave.schema.definition.StaticSchemaRef;

public final class StaticSchemaAlreadyExistsException extends RuntimeException {

    private final StaticSchemaRef reference;

    public StaticSchemaAlreadyExistsException(StaticSchemaRef reference, Throwable cause) {
        super("StaticSchema already exists: " + reference.schemaKey() + "@" + reference.versionTag(), cause);
        this.reference = reference;
    }

    public StaticSchemaRef reference() {
        return reference;
    }
}
