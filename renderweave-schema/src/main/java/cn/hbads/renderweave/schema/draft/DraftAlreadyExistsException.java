package cn.hbads.renderweave.schema.draft;

import cn.hbads.renderweave.schema.identity.SchemaKey;

public final class DraftAlreadyExistsException extends RuntimeException {

    private final SchemaKey schemaKey;

    public DraftAlreadyExistsException(SchemaKey schemaKey, Throwable cause) {
        super("Draft already exists: " + schemaKey, cause);
        this.schemaKey = schemaKey;
    }

    public SchemaKey schemaKey() {
        return schemaKey;
    }
}
