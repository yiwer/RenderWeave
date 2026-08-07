package cn.hbads.renderweave.schema.draft;

import cn.hbads.renderweave.schema.identity.SchemaKey;

public final class DraftRevisionNotFoundException extends RuntimeException {

    private final SchemaKey schemaKey;
    private final long revision;

    public DraftRevisionNotFoundException(SchemaKey schemaKey, long revision) {
        super("Draft revision not found: " + schemaKey + "@" + revision);
        this.schemaKey = schemaKey;
        this.revision = revision;
    }

    public SchemaKey schemaKey() {
        return schemaKey;
    }

    public long revision() {
        return revision;
    }
}
