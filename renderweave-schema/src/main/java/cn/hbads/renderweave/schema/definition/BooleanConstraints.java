package cn.hbads.renderweave.schema.definition;

import java.util.Optional;

public record BooleanConstraints(Optional<Boolean> constValue) {
    public BooleanConstraints {
        constValue = constValue == null ? Optional.empty() : constValue;
    }

    public static BooleanConstraints none() {
        return new BooleanConstraints(Optional.empty());
    }
}
