package cn.hbads.renderweave.schema.definition;

import java.util.Objects;

public record DateValue(DateConstraints constraints) implements ValueDescriptor {
    public DateValue {
        Objects.requireNonNull(constraints, "constraints");
    }

    @Override
    public String type() {
        return "date";
    }
}
