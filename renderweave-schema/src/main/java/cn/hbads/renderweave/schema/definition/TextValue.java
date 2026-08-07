package cn.hbads.renderweave.schema.definition;

import java.util.Objects;

public record TextValue(TextConstraints constraints) implements ValueDescriptor {

    public TextValue {
        Objects.requireNonNull(constraints, "constraints");
    }

    @Override
    public String type() {
        return "text";
    }
}
