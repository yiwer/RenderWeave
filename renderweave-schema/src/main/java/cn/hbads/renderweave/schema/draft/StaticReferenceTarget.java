package cn.hbads.renderweave.schema.draft;

import cn.hbads.renderweave.schema.definition.StaticSchemaRef;

import java.util.Objects;

/** One exact StaticSchema-reference occurrence projected from a normalized definition. */
public record StaticReferenceTarget(String pointer, StaticSchemaRef reference) {

    public StaticReferenceTarget {
        Objects.requireNonNull(pointer, "pointer");
        Objects.requireNonNull(reference, "reference");
    }
}
