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
        Map<String, String> expectedRootShapes
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
        expectedRootShapes.forEach((field, shape) -> {
            if (field.isBlank() || !shape.matches(
                    "(TEXT|DECIMAL|DATE|TIME|BOOLEAN|REFERENCE|UNRESOLVED|CONFLICT|"
                            + "ARRAY:(TEXT|DECIMAL|DATE|TIME|BOOLEAN|REFERENCE|UNRESOLVED|CONFLICT))"
            )) {
                throw new IllegalArgumentException("Gold root shape is invalid");
            }
        });
    }

    private static String requireId(String value, String name) {
        if (value == null || !value.matches("[a-z0-9][a-z0-9-]{0,127}")) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }
}
