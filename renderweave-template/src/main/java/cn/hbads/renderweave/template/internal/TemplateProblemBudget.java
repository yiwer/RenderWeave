package cn.hbads.renderweave.template.internal;

import cn.hbads.renderweave.template.api.TemplateApplication;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** Shared bounded, canonical Template problem collection contract. */
final class TemplateProblemBudget {
    static final int MAX_ITEMS = 200;
    static final int MAX_ITEM_BYTES = 4096;
    static final int MAX_TOTAL_BYTES = 262_144;
    static final int MARKER_RESERVE_BYTES = 1024;
    static final int MAX_ORDINARY_BYTES = MAX_TOTAL_BYTES - MARKER_RESERVE_BYTES;
    private static final byte[] DOMAIN =
            "renderweave-template-problem-fingerprint/1\0".getBytes(StandardCharsets.UTF_8);

    private TemplateProblemBudget() {
    }

    static TemplateApplication.ValidationReport bounded(
            List<TemplateApplication.ValidationProblem> input
    ) {
        var sorted = new ArrayList<>(List.copyOf(input));
        sorted.sort(problemComparator());
        var itemTruncated = sorted.size() > MAX_ITEMS;
        var ordinaryLimit = itemTruncated ? MAX_ITEMS - 1 : MAX_ITEMS;
        var selected = new ArrayList<TemplateApplication.ValidationProblem>();
        var ordinaryBytes = 0;
        var byteTruncated = false;
        for (var problem : sorted) {
            if (selected.size() >= ordinaryLimit) {
                break;
            }
            var bytes = canonicalBytes(problem);
            if (bytes.length > MAX_ITEM_BYTES
                    || ordinaryBytes + bytes.length > MAX_ORDINARY_BYTES) {
                byteTruncated = true;
                break;
            }
            selected.add(problem);
            ordinaryBytes += bytes.length;
        }
        var truncated = itemTruncated || byteTruncated || selected.size() < sorted.size();
        if (truncated) {
            selected.add(marker(byteTruncated ? "BYTES" : "ITEMS"));
        }
        return new TemplateApplication.ValidationReport(
                selected,
                truncated,
                fingerprint(selected)
        );
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
