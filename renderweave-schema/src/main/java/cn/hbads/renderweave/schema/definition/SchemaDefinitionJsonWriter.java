package cn.hbads.renderweave.schema.definition;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Writes the normalized DSL shape used as the complete persisted revision snapshot. */
public final class SchemaDefinitionJsonWriter {

    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
            .build();
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    public String write(SchemaDefinition definition) {
        var root = new LinkedHashMap<String, Object>();
        root.put("dslVersion", definition.dslVersion());
        root.put("displayName", definition.displayName());
        definition.description().ifPresent(value -> root.put("description", value));

        var fields = new ArrayList<Map<String, Object>>();
        for (var field : definition.fields()) {
            var fieldObject = new LinkedHashMap<String, Object>();
            fieldObject.put("fieldKey", field.fieldKey().value());
            field.displayName().ifPresent(value -> fieldObject.put("displayName", value));
            field.description().ifPresent(value -> fieldObject.put("description", value));
            fieldObject.put("required", field.required());
            fieldObject.put("value", writeValue(field.value()));
            fields.add(fieldObject);
        }
        root.put("fields", fields);

        try {
            return JSON.writeValueAsString(root);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Normalized SchemaDefinition could not be serialized", exception);
        }
    }

    private static Map<String, Object> writeValue(ValueDescriptor descriptor) {
        var value = new LinkedHashMap<String, Object>();
        value.put("type", descriptor.type());

        if (descriptor instanceof TextValue text) {
            putConstraints(value, writeTextConstraints(text.constraints()));
        } else if (descriptor instanceof DecimalValue decimal) {
            putConstraints(value, writeDecimalConstraints(decimal.constraints()));
        } else if (descriptor instanceof DateValue date) {
            putConstraints(value, writeDateConstraints(date.constraints()));
        } else if (descriptor instanceof TimeValue time) {
            putConstraints(value, writeTimeConstraints(time.constraints()));
        } else if (descriptor instanceof BooleanValue bool) {
            var constraints = new LinkedHashMap<String, Object>();
            bool.constraints().constValue().ifPresent(item -> constraints.put("const", item));
            putConstraints(value, constraints);
        } else if (descriptor instanceof ReferenceValue reference) {
            value.put("ref", writeReference(reference.ref()));
        } else if (descriptor instanceof ArrayValue array) {
            putConstraints(value, writeArrayConstraints(array.constraints()));
            value.put("items", writeValue(array.items()));
        } else {
            throw new IllegalArgumentException("Unsupported value descriptor: " + descriptor.getClass().getName());
        }
        return value;
    }

    private static Map<String, Object> writeTextConstraints(TextConstraints source) {
        var constraints = new LinkedHashMap<String, Object>();
        source.minLength().ifPresent(number -> constraints.put("minLength", number));
        source.maxLength().ifPresent(number -> constraints.put("maxLength", number));
        source.pattern().ifPresent(pattern -> constraints.put("pattern", pattern));
        if (!source.enumValues().isEmpty()) {
            constraints.put("enum", source.enumValues());
        }
        source.constValue().ifPresent(item -> constraints.put("const", item));
        return constraints;
    }

    private static Map<String, Object> writeDecimalConstraints(DecimalConstraints source) {
        var constraints = new LinkedHashMap<String, Object>();
        source.min().ifPresent(number -> constraints.put("min", number));
        source.exclusiveMin().ifPresent(number -> constraints.put("exclusiveMin", number));
        source.max().ifPresent(number -> constraints.put("max", number));
        source.exclusiveMax().ifPresent(number -> constraints.put("exclusiveMax", number));
        source.multipleOf().ifPresent(number -> constraints.put("multipleOf", number));
        if (!source.enumValues().isEmpty()) {
            constraints.put("enum", source.enumValues());
        }
        source.constValue().ifPresent(item -> constraints.put("const", item));
        return constraints;
    }

    private static Map<String, Object> writeDateConstraints(DateConstraints source) {
        var constraints = new LinkedHashMap<String, Object>();
        source.min().ifPresent(item -> constraints.put("min", item.toString()));
        source.exclusiveMin().ifPresent(item -> constraints.put("exclusiveMin", item.toString()));
        source.max().ifPresent(item -> constraints.put("max", item.toString()));
        source.exclusiveMax().ifPresent(item -> constraints.put("exclusiveMax", item.toString()));
        if (!source.enumValues().isEmpty()) {
            constraints.put("enum", source.enumValues().stream().map(Object::toString).toList());
        }
        source.constValue().ifPresent(item -> constraints.put("const", item.toString()));
        return constraints;
    }

    private static Map<String, Object> writeTimeConstraints(TimeConstraints source) {
        var constraints = new LinkedHashMap<String, Object>();
        source.min().ifPresent(item -> constraints.put("min", TIME_FORMAT.format(item)));
        source.exclusiveMin().ifPresent(item -> constraints.put("exclusiveMin", TIME_FORMAT.format(item)));
        source.max().ifPresent(item -> constraints.put("max", TIME_FORMAT.format(item)));
        source.exclusiveMax().ifPresent(item -> constraints.put("exclusiveMax", TIME_FORMAT.format(item)));
        if (!source.enumValues().isEmpty()) {
            constraints.put("enum", source.enumValues().stream().map(TIME_FORMAT::format).toList());
        }
        source.constValue().ifPresent(item -> constraints.put("const", TIME_FORMAT.format(item)));
        return constraints;
    }

    private static Map<String, Object> writeArrayConstraints(ArrayConstraints source) {
        var constraints = new LinkedHashMap<String, Object>();
        source.minItems().ifPresent(number -> constraints.put("minItems", number));
        source.maxItems().ifPresent(number -> constraints.put("maxItems", number));
        source.uniqueItems().ifPresent(item -> constraints.put("uniqueItems", item));
        return constraints;
    }

    private static Map<String, Object> writeReference(SchemaReference source) {
        var ref = new LinkedHashMap<String, Object>();
        ref.put("schemaKey", source.schemaKey().value());
        if (source instanceof StaticSchemaRef staticRef) {
            ref.put("versionTag", staticRef.versionTag().value());
        }
        return ref;
    }

    private static void putConstraints(Map<String, Object> value, Map<String, Object> constraints) {
        if (!constraints.isEmpty()) {
            value.put("constraints", constraints);
        }
    }
}
