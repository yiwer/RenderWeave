package cn.hbads.renderweave.schema.draft;

import cn.hbads.renderweave.schema.identity.SchemaKey;

import java.util.List;

public final class DraftDeleteBlockedException extends RuntimeException {

    private final SchemaKey schemaKey;
    private final List<IncomingDraftReference> incomingReferences;
    private final long total;

    public DraftDeleteBlockedException(
            SchemaKey schemaKey,
            List<IncomingDraftReference> incomingReferences,
            long total
    ) {
        super("Draft " + schemaKey + " has " + total + " active incoming reference(s)");
        this.schemaKey = schemaKey;
        this.incomingReferences = List.copyOf(incomingReferences);
        this.total = total;
    }

    public SchemaKey schemaKey() {
        return schemaKey;
    }

    public List<IncomingDraftReference> incomingReferences() {
        return incomingReferences;
    }

    public long total() {
        return total;
    }
}
