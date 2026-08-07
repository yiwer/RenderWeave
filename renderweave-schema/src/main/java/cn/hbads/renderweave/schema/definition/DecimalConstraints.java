package cn.hbads.renderweave.schema.definition;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public record DecimalConstraints(
        Optional<BigDecimal> min,
        Optional<BigDecimal> exclusiveMin,
        Optional<BigDecimal> max,
        Optional<BigDecimal> exclusiveMax,
        Optional<BigDecimal> multipleOf,
        List<BigDecimal> enumValues,
        Optional<BigDecimal> constValue
) {
    public DecimalConstraints {
        min = optional(min);
        exclusiveMin = optional(exclusiveMin);
        max = optional(max);
        exclusiveMax = optional(exclusiveMax);
        multipleOf = optional(multipleOf);
        enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
        constValue = optional(constValue);
    }

    public static DecimalConstraints none() {
        return new DecimalConstraints(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), List.of(), Optional.empty()
        );
    }

    private static <T> Optional<T> optional(Optional<T> value) {
        return value == null ? Optional.empty() : value;
    }
}
