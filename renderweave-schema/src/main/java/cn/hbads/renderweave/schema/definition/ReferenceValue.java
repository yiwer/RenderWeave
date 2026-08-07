package cn.hbads.renderweave.schema.definition;

import java.util.Objects;

public record ReferenceValue(SchemaReference ref) implements ValueDescriptor {
    public ReferenceValue {
        Objects.requireNonNull(ref, "ref");
    }

    @Override
    public String type() {
        return "reference";
    }
}
