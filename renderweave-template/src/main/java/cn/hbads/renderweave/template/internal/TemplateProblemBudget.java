package cn.hbads.renderweave.template.internal;

import cn.hbads.renderweave.template.api.DesignInputExpressionCapacityAuthority;
import cn.hbads.renderweave.template.api.TemplateApplication;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Shared bounded, canonical Template problem collection contract. */
final class TemplateProblemBudget {
    private static final int MARKER_RESERVE_BYTES = 1024;
    private static final byte[] DOMAIN =
            "renderweave-template-problem-fingerprint/1\0".getBytes(StandardCharsets.UTF_8);

    private final DesignInputExpressionCapacityAuthority capacity;
    private final List<TemplateApplication.ValidationProblem> ordinary = new ArrayList<>();
    private long ordinaryBytes;
    private boolean truncated;
    private String markerReason;

    TemplateProblemBudget() {
        this(CanonicalDesignInputExpressionCapacityAuthority.INSTANCE);
    }

    TemplateProblemBudget(DesignInputExpressionCapacityAuthority capacity) {
        this.capacity = Objects.requireNonNull(capacity, "capacity");
        if (!accepted("problems.limitMarkerReservedBytes", MARKER_RESERVE_BYTES)) {
            stop("BYTES");
        }
    }

    static TemplateApplication.ValidationReport bounded(
            List<TemplateApplication.ValidationProblem> input
    ) {
        return bounded(input, CanonicalDesignInputExpressionCapacityAuthority.INSTANCE);
    }

    static TemplateApplication.ValidationReport bounded(
            List<TemplateApplication.ValidationProblem> input,
            DesignInputExpressionCapacityAuthority capacity
    ) {
        var sorted = new ArrayList<>(List.copyOf(input));
        sorted.sort(problemComparator());
        var budget = new TemplateProblemBudget(capacity);
        for (var problem : sorted) {
            if (!budget.add(problem)) {
                break;
            }
        }
        return budget.report();
    }

    boolean add(TemplateApplication.ValidationProblem problem) {
        Objects.requireNonNull(problem, "problem");
        if (truncated) {
            return false;
        }
        var bytes = canonicalBytes(problem);
        if (!accepted("problems.canonicalBytesPerItem", bytes.length)
                || !accepted(
                "problems.canonicalBytesTotal",
                ordinaryBytes + bytes.length + MARKER_RESERVE_BYTES
        )) {
            return stop("BYTES");
        }
        if (!accepted("problems.itemsIncludingLimitMarker", ordinary.size() + 1L)) {
            if (!ordinary.isEmpty()) {
                var removed = ordinary.removeLast();
                ordinaryBytes -= canonicalSize(removed);
            }
            accepted("problems.ordinaryItemsWhenTruncated", ordinary.size());
            return stop("ITEMS");
        }
        ordinary.add(problem);
        ordinaryBytes += bytes.length;
        return true;
    }

    boolean stopped() {
        return truncated;
    }

    TemplateApplication.ValidationReport report() {
        var selected = new ArrayList<>(ordinary);
        selected.sort(problemComparator());
        if (truncated) {
            selected.add(marker(markerReason));
        }
        return new TemplateApplication.ValidationReport(
                selected,
                truncated,
                fingerprint(selected)
        );
    }

    private boolean stop(String reason) {
        truncated = true;
        markerReason = reason;
        while (!ordinary.isEmpty()
                && !accepted("problems.itemsIncludingLimitMarker", ordinary.size() + 1L)) {
            var removed = ordinary.removeLast();
            ordinaryBytes -= canonicalSize(removed);
        }
        return false;
    }

    private boolean accepted(String limitId, long observedValue) {
        try {
            return capacity.evaluate(new DesignInputExpressionCapacityAuthority.Observation(
                    limitId,
                    Long.toString(observedValue)
            )) instanceof DesignInputExpressionCapacityAuthority.Accepted;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private static TemplateApplication.ValidationProblem marker(String reason) {
        return new TemplateApplication.ValidationProblem(
                "PROBLEM_LIMIT_REACHED",
                TemplateApplication.ProblemCategory.LIMIT,
                TemplateApplication.ProblemSeverity.ERROR,
                "",
                List.of(reason)
        );
    }

    private static Comparator<TemplateApplication.ValidationProblem> problemComparator() {
        return (left, right) -> {
            var pointer = compareUtf8(left.canonicalPointer(), right.canonicalPointer());
            return pointer != 0 ? pointer : compareUtf8(left.code(), right.code());
        };
    }

    private static int compareUtf8(String left, String right) {
        return Arrays.compareUnsigned(
                left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String fingerprint(List<TemplateApplication.ValidationProblem> problems) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(DOMAIN);
            for (var problem : problems) {
                var bytes = canonicalBytes(problem);
                digest.update((byte) (bytes.length >>> 24));
                digest.update((byte) (bytes.length >>> 16));
                digest.update((byte) (bytes.length >>> 8));
                digest.update((byte) bytes.length);
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static byte[] canonicalBytes(TemplateApplication.ValidationProblem problem) {
        var json = new StringBuilder(256);
        json.append("{\"code\":");
        string(json, problem.code());
        json.append(",\"category\":");
        string(json, problem.category().name());
        json.append(",\"severity\":");
        string(json, problem.severity().name());
        json.append(",\"canonicalPointer\":");
        string(json, problem.canonicalPointer());
        json.append(",\"messageArgs\":[");
        for (int index = 0; index < problem.messageArgs().size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            string(json, problem.messageArgs().get(index));
        }
        json.append("]}");
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    static int canonicalSize(TemplateApplication.ValidationProblem problem) {
        return canonicalBytes(problem).length;
    }

    private static void string(StringBuilder target, String value) {
        target.append('"');
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '"' -> target.append("\\\"");
                case '\\' -> target.append("\\\\");
                case '\b' -> target.append("\\b");
                case '\f' -> target.append("\\f");
                case '\n' -> target.append("\\n");
                case '\r' -> target.append("\\r");
                case '\t' -> target.append("\\t");
                default -> {
                    if (current < 0x20) {
                        target.append(String.format("\\u%04x", (int) current));
                    } else {
                        target.append(current);
                    }
                }
            }
        }
        target.append('"');
    }
}
