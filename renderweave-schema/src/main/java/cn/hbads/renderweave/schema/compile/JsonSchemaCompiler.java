package cn.hbads.renderweave.schema.compile;

import cn.hbads.renderweave.schema.definition.ArrayValue;
import cn.hbads.renderweave.schema.definition.BooleanValue;
import cn.hbads.renderweave.schema.definition.DateValue;
import cn.hbads.renderweave.schema.definition.DecimalValue;
import cn.hbads.renderweave.schema.definition.ReferenceValue;
import cn.hbads.renderweave.schema.definition.SchemaDefinition;
import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.schema.definition.TextValue;
import cn.hbads.renderweave.schema.definition.TimeValue;
import cn.hbads.renderweave.schema.definition.ValueDescriptor;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Deterministic JSON Schema 2020-12 interoperability compiler. */
public final class JsonSchemaCompiler {

    public static final String COMPILER_VERSION = "renderweave-json-schema/1.0";
    public static final int MAX_ARTIFACT_BYTES = 2 * 1024 * 1024;
    public static final String META_SCHEMA = "https://json-schema.org/draft/2020-12/schema";
    public static final String DATE_PATTERN = "^(?:(?:000[1-9])|(?:00[1-9][0-9])|(?:0[1-9][0-9]{2})|(?:[1-9][0-9]{3}))-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12][0-9]|3[01])$";
    public static final String TIME_PATTERN = "^(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$";

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
            .build();

    public CompiledJsonSchema compile(
            StaticSchemaRef identity,
            SchemaDefinition definition,
            StaticArtifactResolver artifactResolver
    ) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(artifactResolver, "artifactResolver");

        var root = new LinkedHashMap<String, Object>();
        root.put("$schema", META_SCHEMA);
        root.putAll(compileObjectBody(identity, definition, artifactResolver));
        final String artifact;
        try {
            artifact = JSON.writeValueAsString(root);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Compiled JSON Schema could not be serialized", exception);
        }
        var utf8Bytes = artifact.getBytes(StandardCharsets.UTF_8).length;
        if (utf8Bytes > MAX_ARTIFACT_BYTES) {
            throw new CompiledArtifactTooLargeException(utf8Bytes, MAX_ARTIFACT_BYTES);
        }
        return new CompiledJsonSchema(artifact, COMPILER_VERSION, utf8Bytes);
    }

    private static Map<String, Object> compileObjectBody(
            StaticSchemaRef identity,
            SchemaDefinition definition,
            StaticArtifactResolver resolver
    ) {
        var body = new LinkedHashMap<String, Object>();
        body.put("type", "object");

        var properties = new LinkedHashMap<String, Object>();
        var required = new ArrayList<String>();
        for (var field : definition.fields()) {
            properties.put(field.fieldKey().value(), compileValue(field.value(), resolver));
            if (field.required()) {
                required.add(field.fieldKey().value());
            }
        }
        body.put("properties", properties);
        body.put("required", required);
        body.put("additionalProperties", true);
        body.put("x-renderweave-static-schema-ref", referenceObject(identity));
        body.put("x-renderweave-compiler-version", COMPILER_VERSION);
        return body;
    }

    private static Object compileValue(ValueDescriptor descriptor, StaticArtifactResolver resolver) {
        if (descriptor instanceof TextValue text) {
            var result = typed("string", "text");
            text.constraints().minLength().ifPresent(value -> result.put("minLength", value));
            text.constraints().maxLength().ifPresent(value -> result.put("maxLength", value));
            text.constraints().pattern().ifPresent(value -> result.put("pattern", value));
            if (!text.constraints().enumValues().isEmpty()) {
                result.put("enum", text.constraints().enumValues());
            }
            text.constraints().constValue().ifPresent(value -> result.put("const", value));
            moveTypeMarkerLast(result, "text");
            return result;
        }
        if (descriptor instanceof DecimalValue decimal) {
            var result = typed("number", "decimal");
            decimal.constraints().min().ifPresent(value -> result.put("minimum", value));
            decimal.constraints().exclusiveMin().ifPresent(value -> result.put("exclusiveMinimum", value));
            decimal.constraints().max().ifPresent(value -> result.put("maximum", value));
            decimal.constraints().exclusiveMax().ifPresent(value -> result.put("exclusiveMaximum", value));
            decimal.constraints().multipleOf().ifPresent(value -> result.put("multipleOf", value));
            if (!decimal.constraints().enumValues().isEmpty()) {
                result.put("enum", decimal.constraints().enumValues());
            }
            decimal.constraints().constValue().ifPresent(value -> result.put("const", value));
            moveTypeMarkerLast(result, "decimal");
            return result;
        }
        if (descriptor instanceof DateValue date) {
            var result = typed("string", "date");
            result.put("pattern", DATE_PATTERN);
            result.put("format", "date");
            if (!date.constraints().enumValues().isEmpty()) {
                result.put("enum", date.constraints().enumValues().stream().map(Object::toString).toList());
            }
            date.constraints().constValue().ifPresent(value -> result.put("const", value.toString()));
            moveTypeMarkerLast(result, "date");
            var extension = new LinkedHashMap<String, Object>();
            date.constraints().min().ifPresent(value -> extension.put("min", value.toString()));
            date.constraints().exclusiveMin().ifPresent(value -> extension.put("exclusiveMin", value.toString()));
            date.constraints().max().ifPresent(value -> extension.put("max", value.toString()));
            date.constraints().exclusiveMax().ifPresent(value -> extension.put("exclusiveMax", value.toString()));
            if (!extension.isEmpty()) {
                result.put("x-renderweave-constraints", extension);
            }
            return result;
        }
        if (descriptor instanceof TimeValue time) {
            var result = typed("string", "time");
            result.put("pattern", TIME_PATTERN);
            if (!time.constraints().enumValues().isEmpty()) {
                result.put("enum", time.constraints().enumValues().stream().map(TIME_FORMAT::format).toList());
            }
            time.constraints().constValue().ifPresent(value -> result.put("const", TIME_FORMAT.format(value)));
            moveTypeMarkerLast(result, "time");
            var extension = new LinkedHashMap<String, Object>();
            time.constraints().min().ifPresent(value -> extension.put("min", TIME_FORMAT.format(value)));
            time.constraints().exclusiveMin().ifPresent(value -> extension.put("exclusiveMin", TIME_FORMAT.format(value)));
            time.constraints().max().ifPresent(value -> extension.put("max", TIME_FORMAT.format(value)));
            time.constraints().exclusiveMax().ifPresent(value -> extension.put("exclusiveMax", TIME_FORMAT.format(value)));
            if (!extension.isEmpty()) {
                result.put("x-renderweave-constraints", extension);
            }
            return result;
        }
        if (descriptor instanceof BooleanValue bool) {
            var result = typed("boolean", "boolean");
            bool.constraints().constValue().ifPresent(value -> result.put("const", value));
            moveTypeMarkerLast(result, "boolean");
            return result;
        }
        if (descriptor instanceof ReferenceValue reference) {
            if (!(reference.ref() instanceof StaticSchemaRef staticReference)) {
                throw new InvalidCompiledArtifactException(
                        "StaticSchema compilation cannot resolve a live Draft reference: "
                                + reference.ref().schemaKey().value()
                );
            }
            return embeddedArtifact(staticReference, resolver);
        }
        if (descriptor instanceof ArrayValue array) {
            var result = typed("array", "array");
            result.put("items", compileValue(array.items(), resolver));
            array.constraints().minItems().ifPresent(value -> result.put("minItems", value));
            array.constraints().maxItems().ifPresent(value -> result.put("maxItems", value));
            array.constraints().uniqueItems().ifPresent(value -> result.put("uniqueItems", value));
            moveTypeMarkerLast(result, "array");
            return result;
        }
        throw new IllegalArgumentException("Unsupported value descriptor: " + descriptor.getClass().getName());
    }

    private static ObjectNode embeddedArtifact(
            StaticSchemaRef requested,
            StaticArtifactResolver resolver
    ) {
        var resolved = Objects.requireNonNull(resolver.resolve(requested), "resolved artifact");
        if (!requested.equals(resolved.reference())) {
            throw new InvalidCompiledArtifactException("Resolver returned a different StaticSchema identity");
        }
        final JsonNode parsed;
        try {
            parsed = JSON.readTree(resolved.compiledJsonSchema());
        } catch (JacksonException exception) {
            throw new InvalidCompiledArtifactException("Stored child artifact is not strict JSON", exception);
        }
        if (!(parsed instanceof ObjectNode object)) {
            throw new InvalidCompiledArtifactException("Stored child artifact root must be an object");
        }
        if (!META_SCHEMA.equals(object.path("$schema").asText())) {
            throw new InvalidCompiledArtifactException("Stored child artifact has an unexpected meta-schema");
        }
        var marker = object.path("x-renderweave-static-schema-ref");
        if (!requested.schemaKey().value().equals(marker.path("schemaKey").asText())
                || !requested.versionTag().value().equals(marker.path("versionTag").asText())) {
            throw new InvalidCompiledArtifactException("Stored child artifact identity marker does not match target");
        }
        var embedded = object.deepCopy();
        embedded.remove("$schema");
        embedded.put("x-renderweave-type", "reference");
        return embedded;
    }

    private static LinkedHashMap<String, Object> typed(String jsonType, String renderWeaveType) {
        var result = new LinkedHashMap<String, Object>();
        result.put("type", jsonType);
        result.put("x-renderweave-type", renderWeaveType);
        return result;
    }

    private static void moveTypeMarkerLast(Map<String, Object> result, String renderWeaveType) {
        result.remove("x-renderweave-type");
        result.put("x-renderweave-type", renderWeaveType);
    }

    private static Map<String, Object> referenceObject(StaticSchemaRef reference) {
        var result = new LinkedHashMap<String, Object>();
        result.put("schemaKey", reference.schemaKey().value());
        result.put("versionTag", reference.versionTag().value());
        return result;
    }
}
