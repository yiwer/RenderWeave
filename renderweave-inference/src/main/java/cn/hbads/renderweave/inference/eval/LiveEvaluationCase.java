package cn.hbads.renderweave.inference.eval;

import cn.hbads.renderweave.inference.input.InferenceMode;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public record LiveEvaluationCase(
        String caseId,
        String fixtureId,
        InferenceMode mode,
        LiveEvaluationPartition partition,
        int expectedSchemaCount,
        Map<String, String> expectedRootShapes,
        Map<String, Map<String, String>> expectedNestedShapes
) {
    public LiveEvaluationCase {
        caseId = requireId(caseId, "caseId");
        fixtureId = requireId(fixtureId, "fixtureId");
        mode = Objects.requireNonNull(mode, "mode");
        partition = Objects.requireNonNull(partition, "partition");
        if (expectedSchemaCount < 1) throw new IllegalArgumentException("expectedSchemaCount must be positive");
        expectedRootShapes = Collections.unmodifiableMap(new TreeMap<>(
                Objects.requireNonNull(expectedRootShapes, "expectedRootShapes")
        ));
        var nested = new TreeMap<String, Map<String, String>>();
        if (expectedNestedShapes != null) {
            expectedNestedShapes.forEach((path, fields) -> nested.put(
                    requireSchemaPath(path),
                    Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(fields, "nested fields")))
            ));
        }
        expectedNestedShapes = Collections.unmodifiableMap(nested);
        if (expectedSchemaCount != 1 + expectedNestedShapes.size()) {
            throw new IllegalArgumentException("expectedSchemaCount must match the complete gold graph");
        }
        expectedRootShapes.forEach(LiveEvaluationCase::validateFieldShape);
        expectedNestedShapes.values().forEach(fields -> fields.forEach(LiveEvaluationCase::validateFieldShape));
        var schemas = new TreeMap<String, Map<String, String>>();
        schemas.put("/", expectedRootShapes);
        schemas.putAll(expectedNestedShapes);
        schemas.forEach((schemaPath, fields) -> fields.forEach((field, shape) -> {
            if ("REFERENCE".equals(shape) || "ARRAY:REFERENCE".equals(shape)) {
                var targetPath = childPath(schemaPath, field);
                if (!schemas.containsKey(targetPath)) {
                    throw new IllegalArgumentException("Gold reference target is missing: " + targetPath);
                }
            }
        }));
    }

    public Map<String, Map<String, String>> expectedSchemas() {
        var value = new TreeMap<String, Map<String, String>>();
        value.put("/", expectedRootShapes);
        value.putAll(expectedNestedShapes);
        return Collections.unmodifiableMap(value);
    }

    static String childPath(String schemaPath, String fieldKey) {
        return "/".equals(schemaPath)
                ? "/" + escape(fieldKey)
                : schemaPath + "/" + escape(fieldKey);
    }

    static String fieldIdentity(String schemaPath, String fieldKey) {
        return schemaPath + "#" + escape(fieldKey);
    }

    private static String escape(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private static String requireSchemaPath(String value) {
        if (value == null || !value.matches("/(?:[^/~]|~[01])+(?:/(?:[^/~]|~[01])+)*")) {
            throw new IllegalArgumentException("Gold schema path is invalid");
        }
        return value;
    }

    private static void validateFieldShape(String field, String shape) {
            if (field == null || field.isBlank() || shape == null || !shape.matches(
                    "(TEXT|DECIMAL|DATE|TIME|BOOLEAN|REFERENCE|UNRESOLVED|CONFLICT|"
                            + "ARRAY:(TEXT|DECIMAL|DATE|TIME|BOOLEAN|REFERENCE|UNRESOLVED|CONFLICT))"
            )) {
                throw new IllegalArgumentException("Gold field shape is invalid");
            }
    }

    private static String requireId(String value, String name) {
        if (value == null || !value.matches("[a-z0-9][a-z0-9-]{0,127}")) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }
}
