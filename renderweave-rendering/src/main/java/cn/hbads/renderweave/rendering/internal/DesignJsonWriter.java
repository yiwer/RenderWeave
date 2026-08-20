package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.template.api.DesignSemanticAuthority.ArrayNode;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.Bool;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.DesignNodeValue;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.NumberToken;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.ObjectNode;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.Text;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 语义值树 → strict JSON bytes 重构：Binding overlay 后把修改过的文档重构为 strict JSON
 * 重新 admission，完成 exact leaf/aggregate 重验（同一 Catalog，不复制 Node switch）。
 * decimal 保留原始 token；字符串做 strict JSON 转义。
 */
final class DesignJsonWriter {

    private DesignJsonWriter() {
    }

    static byte[] write(DesignNodeValue value) {
        var out = new ByteArrayOutputStream();
        writeValue(value, out);
        return out.toByteArray();
    }

    private static void writeValue(DesignNodeValue value, ByteArrayOutputStream out) {
        if (value instanceof ObjectNode object) {
            out.write('{');
            boolean first = true;
            for (var entry : object.members().entrySet()) {
                if (!first) {
                    out.write(',');
                }
                first = false;
                writeString(entry.getKey(), out);
                out.write(':');
                writeValue(entry.getValue(), out);
            }
            out.write('}');
            return;
        }
        if (value instanceof ArrayNode array) {
            out.write('[');
            for (int index = 0; index < array.items().size(); index++) {
                if (index > 0) {
                    out.write(',');
                }
                writeValue(array.items().get(index), out);
            }
            out.write(']');
            return;
        }
        if (value instanceof Text text) {
            writeString(text.value(), out);
            return;
        }
        if (value instanceof NumberToken number) {
            var bytes = number.rawToken().getBytes(StandardCharsets.US_ASCII);
            out.write(bytes, 0, bytes.length);
            return;
        }
        if (value instanceof Bool bool) {
            var bytes = bool.value() ? "true" : "false";
            out.write(bytes.getBytes(StandardCharsets.US_ASCII), 0, bytes.length());
            return;
        }
        throw new IllegalStateException("unknown DesignNodeValue variant");
    }

    private static void writeString(String value, ByteArrayOutputStream out) {
        out.write('"');
        int index = 0;
        while (index < value.length()) {
            int codePoint = value.codePointAt(index);
            switch (codePoint) {
                case '"' -> writeEscape('"', out);
                case '\\' -> writeEscape('\\', out);
                case '\b' -> writeEscape('b', out);
                case '\f' -> writeEscape('f', out);
                case '\n' -> writeEscape('n', out);
                case '\r' -> writeEscape('r', out);
                case '\t' -> writeEscape('t', out);
                default -> {
                    if (codePoint < 0x20) {
                        out.write('\\');
                        out.write('u');
                        var hex = String.format("%04x", codePoint);
                        out.write(hex.getBytes(StandardCharsets.US_ASCII), 0, 4);
                    } else {
                        var encoded = new String(Character.toChars(codePoint))
                                .getBytes(StandardCharsets.UTF_8);
                        out.write(encoded, 0, encoded.length);
                    }
                }
            }
            index += Character.charCount(codePoint);
        }
        out.write('"');
    }

    private static void writeEscape(char marker, ByteArrayOutputStream out) {
        out.write('\\');
        out.write(marker);
    }
}
