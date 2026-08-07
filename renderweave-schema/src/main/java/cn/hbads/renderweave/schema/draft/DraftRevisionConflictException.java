package cn.hbads.renderweave.schema.draft;

import cn.hbads.renderweave.schema.identity.SchemaKey;

public final class DraftRevisionConflictException extends RuntimeException {

    private final SchemaKey schemaKey;
    private final long expectedRevision;
    private final long currentRevision;

    public DraftRevisionConflictException(
            SchemaKey schemaKey,
            long expectedRevision,
            long currentRevision
    ) {
        super("Expected revision " + expectedRevision + " but current revision is " + currentRevision);
        this.schemaKey = schemaKey;
        this.expectedRevision = expectedRevision;
        this.currentRevision = currentRevision;
    }

    public SchemaKey schemaKey() {
        return schemaKey;
    }

    public long expectedRevision() {
        return expectedRevision;
    }

    public long currentRevision() {
        return currentRevision;
    }
}
