package cn.hbads.renderweave.inference.candidate;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

public record CandidateProblem(
        String code,
        CandidateProblemSeverity severity,
        UUID itemId,
        String pointer,
        Map<String, String> args
) {
    public CandidateProblem {
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw new IllegalArgumentException("code must be a stable uppercase identifier");
        }
        Objects.requireNonNull(severity, "severity");
        if (pointer == null) throw new IllegalArgumentException("pointer is required");
        args = Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(args, "args")));
    }
}
