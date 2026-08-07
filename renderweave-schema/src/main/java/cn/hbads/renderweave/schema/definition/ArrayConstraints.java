package cn.hbads.renderweave.schema.definition;

import java.util.Optional;
import java.util.OptionalInt;

public record ArrayConstraints(
        OptionalInt minItems,
        OptionalInt maxItems,
        Optional<Boolean> uniqueItems
) {
    public ArrayConstraints {
        minItems = minItems == null ? OptionalInt.empty() : minItems;
        maxItems = maxItems == null ? OptionalInt.empty() : maxItems;
        uniqueItems = uniqueItems == null ? Optional.empty() : uniqueItems;
    }

    public static ArrayConstraints none() {
        return new ArrayConstraints(OptionalInt.empty(), OptionalInt.empty(), Optional.empty());
    }
}
