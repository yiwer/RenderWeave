package cn.hbads.renderweave.schema.definition;

import java.util.Objects;

public record DecimalValue(DecimalConstraints constraints) implements ValueDescriptor {
    public DecimalValue {
        Objects.requireNonNull(constraints, "constraints");
    }

    @Override
    public String type() {
        return "decimal";
    }
}
