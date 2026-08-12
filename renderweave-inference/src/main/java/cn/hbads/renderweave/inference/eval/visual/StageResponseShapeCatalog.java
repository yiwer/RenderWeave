package cn.hbads.renderweave.inference.eval.visual;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialects;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Offline syntax-shape authority for the three product-v45 semantic-stage responses. */
public final class StageResponseShapeCatalog {
    public static final String VERSION = "renderweave-stage-response-shape-catalog/1.0";
    public static final int MAXIMUM_RESPONSE_BYTES = 256 * 1024;

    private static final ObjectMapper STRICT_JSON = JsonMapper.builder(
                    JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();
    private static final SchemaRegistry SCHEMAS = SchemaRegistry.withDialect(Dialects.getDraft202012());

    private final Map<Stage, Entry> entries;

    public StageResponseShapeCatalog() {
        var loaded = new EnumMap<Stage, Entry>(Stage.class);
        for (var stage : Stage.values()) {
            var document = canonicalDocument(load(stage.resource()));
            var schema = SCHEMAS.getSchema(document, InputFormat.JSON);
            loaded.put(stage, new Entry(document, sha256(document), schema));
        }
        entries = Map.copyOf(loaded);
    }

    public String catalogVersion() {
        return VERSION;
    }

    public String schemaDocument(Stage stage) {
        return require(stage).document();
    }

    public String schemaSha256(Stage stage) {
        return require(stage).sha256();
    }

    public String identity() {
        var material = new StringBuilder(VERSION);
        for (var stage : Stage.values()) {
            material.append('\n').append(stage.name()).append('=').append(schemaSha256(stage));
        }
        return sha256(material.toString());
    }

    public ValidationResult validate(Stage stage, String responseJson) {
        Objects.requireNonNull(stage, "stage");
        if (responseJson == null || responseJson.isBlank()) {
            return ValidationResult.rejected("STAGE_RESPONSE_EMPTY");
        }
        if (responseJson.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_RESPONSE_BYTES) {
            return ValidationResult.rejected("STAGE_RESPONSE_BYTE_LIMIT_EXCEEDED");
        }
        try {
            STRICT_JSON.readTree(responseJson);
        } catch (Exception invalidJson) {
            return ValidationResult.rejected("STAGE_RESPONSE_JSON_INVALID");
        }
        try {
            var errors = require(stage).schema().validate(responseJson, InputFormat.JSON);
            if (errors.isEmpty()) return ValidationResult.acceptedResult();
            var codes = errors.stream()
                    .map(error -> "JSON_SCHEMA_" + error.getKeyword().toUpperCase(Locale.ROOT)
                            .replaceAll("[^A-Z0-9]+", "_"))
                    .distinct()
                    .sorted()
                    .toList();
            return new ValidationResult(false, codes);
        } catch (Exception invalidJson) {
            return ValidationResult.rejected("STAGE_RESPONSE_JSON_INVALID");
        }
    }

    @Override
    public String toString() {
        return "StageResponseShapeCatalog[catalogVersion=" + VERSION + ", identity=" + identity()
                + ", stages=" + entries.size() + "]";
    }

    private Entry require(Stage stage) {
        return Objects.requireNonNull(entries.get(Objects.requireNonNull(stage, "stage")), "stage");
    }

    private static String load(String resource) {
        try (InputStream input = StageResponseShapeCatalog.class.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("STAGE_RESPONSE_SCHEMA_MISSING");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IllegalStateException known) {
            throw known;
        } catch (Exception failure) {
            throw new IllegalStateException("STAGE_RESPONSE_SCHEMA_UNREADABLE", failure);
        }
    }

    private static String canonicalDocument(String value) {
        try {
            var parsed = STRICT_JSON.readTree(value);
            return STRICT_JSON.writeValueAsString(canonicalNode(parsed));
        } catch (Exception failure) {
            throw new IllegalStateException("STAGE_RESPONSE_SCHEMA_INVALID", failure);
        }
    }

    private static JsonNode canonicalNode(JsonNode source) {
        if (source.isObject()) {
            var result = STRICT_JSON.createObjectNode();
            var properties = new ArrayList<>(source.properties());
            properties.sort(Map.Entry.comparingByKey());
            for (var property : properties) {
                result.set(property.getKey(), canonicalNode(property.getValue()));
            }
            return result;
        }
        if (source.isArray()) {
            var result = STRICT_JSON.createArrayNode();
            for (var item : source) result.add(canonicalNode(item));
            return result;
        }
        return source.deepCopy();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA_256_UNAVAILABLE", failure);
        }
    }

    public enum Stage {
        OBSERVE("/response-shapes/1.0/observe.schema.json"),
        HIERARCHY("/response-shapes/1.0/hierarchy.schema.json"),
        ELEMENT_BINDING("/response-shapes/1.0/element-binding.schema.json");

        private final String resource;

        Stage(String resource) {
            this.resource = resource;
        }

        private String resource() {
            return resource;
        }
    }

    public record ValidationResult(boolean accepted, List<String> diagnosticCodes) {
        public ValidationResult {
            diagnosticCodes = List.copyOf(Objects.requireNonNull(diagnosticCodes, "diagnosticCodes"));
            if (accepted != diagnosticCodes.isEmpty()) {
                throw new IllegalArgumentException("STAGE_RESPONSE_VALIDATION_RESULT_INVALID");
            }
        }

        private static ValidationResult acceptedResult() {
            return new ValidationResult(true, List.of());
        }

        private static ValidationResult rejected(String code) {
            return new ValidationResult(false, List.of(code));
        }
    }

    private record Entry(String document, String sha256, Schema schema) {
    }
}
