package cn.hbads.renderweave.validation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record ValidationProblem(
        String code,
        String instancePath,
        String schemaPath,
        Map<String, Object> messageArgs
) {
    public ValidationProblem {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(instancePath, "instancePath");
        Objects.requireNonNull(schemaPath, "schemaPath");
        messageArgs = Collections.unmodifiableMap(new LinkedHashMap<>(messageArgs));
    }
}
