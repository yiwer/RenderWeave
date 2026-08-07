package cn.hbads.renderweave.schema.definition;

import cn.hbads.renderweave.schema.identity.FieldKey;

import java.util.Objects;
import java.util.Optional;

public record SchemaField(
        FieldKey fieldKey,
        Optional<String> displayName,
        Optional<String> description,
        boolean required,
        ValueDescriptor value
) {

    public SchemaField {
        Objects.requireNonNull(fieldKey, "fieldKey");
        displayName = displayName == null ? Optional.empty() : displayName;
        description = description == null ? Optional.empty() : description;
        Objects.requireNonNull(value, "value");
    }
}
