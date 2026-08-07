package cn.hbads.renderweave.schema.definition;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

public record TimeConstraints(
        Optional<LocalTime> min,
        Optional<LocalTime> exclusiveMin,
        Optional<LocalTime> max,
        Optional<LocalTime> exclusiveMax,
        List<LocalTime> enumValues,
        Optional<LocalTime> constValue
) {
    public TimeConstraints {
        min = optional(min);
        exclusiveMin = optional(exclusiveMin);
        max = optional(max);
        exclusiveMax = optional(exclusiveMax);
        enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
        constValue = optional(constValue);
    }

    public static TimeConstraints none() {
        return new TimeConstraints(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), List.of(), Optional.empty()
        );
    }

    private static <T> Optional<T> optional(Optional<T> value) {
        return value == null ? Optional.empty() : value;
    }
}
