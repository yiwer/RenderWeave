package cn.hbads.renderweave.schema.definition;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public record DateConstraints(
        Optional<LocalDate> min,
        Optional<LocalDate> exclusiveMin,
        Optional<LocalDate> max,
        Optional<LocalDate> exclusiveMax,
        List<LocalDate> enumValues,
        Optional<LocalDate> constValue
) {
    public DateConstraints {
        min = optional(min);
        exclusiveMin = optional(exclusiveMin);
        max = optional(max);
        exclusiveMax = optional(exclusiveMax);
        enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
        constValue = optional(constValue);
    }

    public static DateConstraints none() {
        return new DateConstraints(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), List.of(), Optional.empty()
        );
    }

    private static <T> Optional<T> optional(Optional<T> value) {
        return value == null ? Optional.empty() : value;
    }
}
