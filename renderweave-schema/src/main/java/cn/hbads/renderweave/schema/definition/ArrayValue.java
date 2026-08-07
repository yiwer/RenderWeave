package cn.hbads.renderweave.schema.definition;

import java.util.Objects;

public record ArrayValue(ArrayConstraints constraints, ValueDescriptor items) implements ValueDescriptor {
    public ArrayValue {
        Objects.requireNonNull(constraints, "constraints");
        Objects.requireNonNull(items, "items");
        if (items instanceof ArrayValue) {
            throw new IllegalArgumentException("Nested arrays are not supported");
        }
    }

    @Override
    public String type() {
        return "array";
    }
}
