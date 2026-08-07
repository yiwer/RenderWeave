package cn.hbads.renderweave.schema.definition;

import java.util.Objects;

public record TimeValue(TimeConstraints constraints) implements ValueDescriptor {
    public TimeValue {
        Objects.requireNonNull(constraints, "constraints");
    }

    @Override
    public String type() {
        return "time";
    }
}
