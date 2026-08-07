package cn.hbads.renderweave.schema.definition;

import java.util.Objects;

public record BooleanValue(BooleanConstraints constraints) implements ValueDescriptor {
    public BooleanValue {
        Objects.requireNonNull(constraints, "constraints");
    }

    @Override
    public String type() {
        return "boolean";
    }
}
