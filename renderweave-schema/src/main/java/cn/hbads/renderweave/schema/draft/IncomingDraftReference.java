package cn.hbads.renderweave.schema.draft;

import cn.hbads.renderweave.schema.identity.SchemaKey;

import java.util.Objects;

public record IncomingDraftReference(
        SchemaKey sourceSchemaKey,
        long sourceRevision,
        String sourcePointer
) {

    public IncomingDraftReference {
        Objects.requireNonNull(sourceSchemaKey, "sourceSchemaKey");
        Objects.requireNonNull(sourcePointer, "sourcePointer");
    }
}
