package cn.hbads.renderweave.schema.draft;

import cn.hbads.renderweave.schema.identity.SchemaKey;

import java.util.Objects;

/** One live Draft-reference occurrence projected from a normalized definition. */
public record DraftReferenceTarget(String pointer, SchemaKey schemaKey) {

    public DraftReferenceTarget {
        Objects.requireNonNull(pointer, "pointer");
        Objects.requireNonNull(schemaKey, "schemaKey");
    }
}
