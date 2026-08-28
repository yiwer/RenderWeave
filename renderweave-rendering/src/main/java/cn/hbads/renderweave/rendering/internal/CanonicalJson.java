package cn.hbads.renderweave.rendering.internal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    @FunctionalInterface
    interface Utf8Sink {
        void writeUtf8(String canonicalText);
    }

    @FunctionalInterface
    interface CanonicalValue {
        void writeTo(Utf8Sink sink);
    }

    /** members 的 value 必须已是合法 JSON 编码；键按 UTF-8 字节序输出。 */
    static String object(TreeMap<String, String> members) {
        var values = new TreeMap<String, CanonicalValue>();
        for (var entry : members.entrySet()) {
            values.put(entry.getKey(), encodedValue(entry.getValue()));
        }
        return encode(objectValue(values));
    }

    static String array(java.util.List<String> items) {
        var values = new ArrayList<CanonicalValue>(items.size());
        for (var item : items) {
            values.add(encodedValue(item));
        }
        return encode(arrayValue(values));
    }

    static CanonicalValue objectValue(Map<String, CanonicalValue> members) {
        Objects.requireNonNull(members, "members");
        var sorted = new TreeMap<String, CanonicalValue>(UTF8_ORDER);
        sorted.putAll(members);
        var entries = sorted.entrySet().stream()
                .map(entry -> Map.entry(entry.getKey(), entry.getValue()))
                .toList();
        return sink -> {
            sink.writeUtf8("{");
            for (int index = 0; index < entries.size(); index++) {
                if (index > 0) {
                    sink.writeUtf8(",");
                }
                var entry = entries.get(index);
                writeString(sink, entry.getKey());
                sink.writeUtf8(":");
                entry.getValue().writeTo(sink);
            }
            sink.writeUtf8("}");
        };
    }

    static CanonicalValue arrayValue(List<CanonicalValue> items) {
        var values = List.copyOf(items);
        return sink -> {
            sink.writeUtf8("[");
            for (int index = 0; index < values.size(); index++) {
                if (index > 0) {
                    sink.writeUtf8(",");
                }
                values.get(index).writeTo(sink);
            }
            sink.writeUtf8("]");
        };
    }

    static CanonicalValue stringValue(String value) {
        Objects.requireNonNull(value, "value");
        return sink -> writeString(sink, value);
    }

    static CanonicalValue decimalValue(BigDecimal value) {
        Objects.requireNonNull(value, "value");
        return sink -> sink.writeUtf8(decimal(value));
    }

    static CanonicalValue boolValue(boolean value) {
        return sink -> sink.writeUtf8(bool(value));
    }

    static String encode(CanonicalValue value) {
        var builder = new StringBuilder();
        value.writeTo(builder::append);
        return builder.toString();
    }

    private static CanonicalValue encodedValue(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        return sink -> sink.writeUtf8(encoded);
    }

    private static void writeString(Utf8Sink sink, String value) {
        sink.writeUtf8("\"");
        var chunk = new StringBuilder(4_096);
        int index = 0;
        while (index < value.length()) {
            int codePoint = value.codePointAt(index);
            appendEscaped(chunk, codePoint);
            if (chunk.length() >= 4_096) {
                sink.writeUtf8(chunk.toString());
                chunk.setLength(0);
            }
            index += Character.charCount(codePoint);
        }
        if (!chunk.isEmpty()) {
            sink.writeUtf8(chunk.toString());
        }
        sink.writeUtf8("\"");
    }

    static String string(String value) {
        return '"' + escapedContent(value) + '"';
    }

    /** strict JSON 转义（不含引号）；canonical writer 与 seal writer 共用单点实现。 */
    static String escapedContent(String value) {
        var builder = new StringBuilder();
        int index = 0;
        while (index < value.length()) {
            int codePoint = value.codePointAt(index);
            appendEscaped(builder, codePoint);
            index += Character.charCount(codePoint);
        }
        return builder.toString();
    }

    private static void appendEscaped(StringBuilder builder, int codePoint) {
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
