package cn.hbads.renderweave.schema.draft;

import cn.hbads.renderweave.schema.identity.SchemaKey;

public final class DraftNotFoundException extends RuntimeException {

    private final SchemaKey schemaKey;

    public DraftNotFoundException(SchemaKey schemaKey) {
        super("Draft not found: " + schemaKey);
        this.schemaKey = schemaKey;
    }

    public SchemaKey schemaKey() {
        return schemaKey;
    }
}
