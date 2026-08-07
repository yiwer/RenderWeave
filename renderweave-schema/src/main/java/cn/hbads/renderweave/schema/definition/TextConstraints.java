package cn.hbads.renderweave.schema.definition;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.List;

/** Type-specific constraints for a text value. */
public record TextConstraints(
        OptionalInt minLength,
        OptionalInt maxLength,
        Optional<String> pattern,
        List<String> enumValues,
        Optional<String> constValue
) {

    public TextConstraints {
        minLength = minLength == null ? OptionalInt.empty() : minLength;
        maxLength = maxLength == null ? OptionalInt.empty() : maxLength;
        pattern = pattern == null ? Optional.empty() : pattern;
        enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
        constValue = constValue == null ? Optional.empty() : constValue;
    }

    public static TextConstraints none() {
        return new TextConstraints(
                OptionalInt.empty(), OptionalInt.empty(), Optional.empty(), List.of(), Optional.empty()
        );
    }
}
