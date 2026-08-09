package cn.hbads.renderweave.inference.replay;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Bounded problem-code counts only; no provider values, paths, messages or payloads are accepted. */
public final class InferenceAttemptProblemTaxonomy {
    public static final int MAX_DISTINCT_CODES = 64;
    public static final int MAX_COUNT_PER_CODE = 10_000;
    public static final String TRUNCATED_CODE = "ATTEMPT_PROBLEM_TAXONOMY_TRUNCATED";
    private static final int MAX_ORDINARY_CODES = MAX_DISTINCT_CODES - 1;

    private InferenceAttemptProblemTaxonomy() { }

    public static Map<String, Integer> count(Iterable<String> codes) {
        Objects.requireNonNull(codes, "codes");
        var builder = new Builder();
        for (var code : codes) builder.add(code, 1);
        return builder.build();
    }

    public static Map<String, Integer> merge(Iterable<Map<String, Integer>> counts) {
        Objects.requireNonNull(counts, "counts");
        var builder = new Builder();
        for (var value : counts) {
            for (var entry : normalize(value).entrySet()) {
                builder.add(entry.getKey(), entry.getValue());
            }
        }
        return builder.build();
    }

    public static Map<String, Integer> normalize(Map<String, Integer> counts) {
        Objects.requireNonNull(counts, "counts");
        if (counts.size() > MAX_DISTINCT_CODES) {
            throw new IllegalArgumentException("Attempt problem taxonomy has too many codes");
        }
        var normalized = new TreeMap<String, Integer>();
        for (var entry : counts.entrySet()) {
            requireCode(entry.getKey());
            var count = Objects.requireNonNull(entry.getValue(), "problem count");
            if (count < 1 || count > MAX_COUNT_PER_CODE) {
                throw new IllegalArgumentException("Attempt problem taxonomy count is out of bounds");
            }
            normalized.put(entry.getKey(), count);
        }
        return Collections.unmodifiableMap(normalized);
    }

    private static void requireCode(String code) {
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw new IllegalArgumentException("Attempt problem taxonomy code is invalid");
        }
    }

    private static final class Builder {
        private final TreeMap<String, Integer> counts = new TreeMap<>();
        private boolean truncated;

        private void add(String code, int increment) {
            requireCode(code);
            if (increment < 1) {
                throw new IllegalArgumentException("Attempt problem taxonomy increment is invalid");
            }
            if (TRUNCATED_CODE.equals(code)) {
                truncated = true;
                return;
            }
            var current = counts.get(code);
            if (current != null) {
                var next = (long) current + increment;
                if (next > MAX_COUNT_PER_CODE) truncated = true;
                counts.put(code, (int) Math.min(MAX_COUNT_PER_CODE, next));
                return;
            }
            if (counts.size() >= MAX_ORDINARY_CODES) {
                truncated = true;
                return;
            }
            if (increment > MAX_COUNT_PER_CODE) truncated = true;
            counts.put(code, Math.min(MAX_COUNT_PER_CODE, increment));
        }

        private Map<String, Integer> build() {
            if (truncated) counts.put(TRUNCATED_CODE, 1);
            return Collections.unmodifiableMap(new TreeMap<>(counts));
        }
    }
}
