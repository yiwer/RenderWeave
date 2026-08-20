package cn.hbads.renderweave.rendering.internal;

import java.math.BigDecimal;
import java.util.TreeMap;

/**
 * canonical JSON 编码原语（冻结 c14n 规则：object member 按 member name UTF-8 字节词典序、
 * strict JSON escaping、decimal 使用 plain 记法且无尾零与 {@code -0}）。
 */
final class CanonicalJson {

    private static final java.util.Comparator<String> UTF8_ORDER =
            java.util.Comparator.comparing(
                    name -> name.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.util.Arrays::compareUnsigned);

    private CanonicalJson() {
    }

    /** members 的 value 必须已是合法 JSON 编码；键按 UTF-8 字节序输出。 */
    static String object(TreeMap<String, String> members) {
        var sorted = new TreeMap<>(UTF8_ORDER);
        sorted.putAll(members);
        var builder = new StringBuilder("{");
        boolean first = true;
        for (var entry : sorted.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            builder.append(string(entry.getKey())).append(':').append(entry.getValue());
        }
        return builder.append('}').toString();
    }

    static String array(java.util.List<String> items) {
        var builder = new StringBuilder("[");
        for (int index = 0; index < items.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append(items.get(index));
        }
        return builder.append(']').toString();
    }

    static String string(String value) {
        var builder = new StringBuilder("\"");
        int index = 0;
        while (index < value.length()) {
            int codePoint = value.codePointAt(index);
            switch (codePoint) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (codePoint < 0x20) {
                        builder.append(String.format("\\u%04x", codePoint));
                    } else {
                        builder.appendCodePoint(codePoint);
                    }
                }
            }
            index += Character.charCount(codePoint);
        }
        return builder.append('"').toString();
    }

    /** canonical decimal：plain 记法、无指数、无尾零、{@code -0} 归一为 {@code 0}。 */
    static String decimal(BigDecimal value) {
        if (value.signum() == 0) {
            return "0";
        }
        var normalized = value.stripTrailingZeros();
        var plain = normalized.toPlainString();
        return plain;
    }

    static String bool(boolean value) {
        return value ? "true" : "false";
    }
}
