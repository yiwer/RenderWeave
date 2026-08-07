package cn.hbads.renderweave.schema.definition;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record SchemaDefinition(
        String dslVersion,
        String displayName,
        Optional<String> description,
        List<SchemaField> fields
) {

    public static final String DSL_VERSION = "renderweave-schema/1.0";

    public SchemaDefinition {
        Objects.requireNonNull(dslVersion, "dslVersion");
        Objects.requireNonNull(displayName, "displayName");
        description = description == null ? Optional.empty() : description;
        fields = List.copyOf(fields);
    }
}
