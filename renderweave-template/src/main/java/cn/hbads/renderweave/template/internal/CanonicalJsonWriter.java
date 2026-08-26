package cn.hbads.renderweave.template.internal;

import cn.hbads.renderweave.template.api.DesignInputExpressionCapacityAuthority;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;

final class CanonicalJsonWriter {

    private static final Comparator<String> UTF8_ORDER = (left, right) -> {
        var leftBytes = left.getBytes(StandardCharsets.UTF_8);
        var rightBytes = right.getBytes(StandardCharsets.UTF_8);
        var length = Math.min(leftBytes.length, rightBytes.length);
        for (int index = 0; index < length; index++) {
            var comparison = Integer.compare(
                    Byte.toUnsignedInt(leftBytes[index]),
                    Byte.toUnsignedInt(rightBytes[index])
            );
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(leftBytes.length, rightBytes.length);
    };
    private static final byte[] ZERO_CHUNK = "0".repeat(4_096).getBytes(StandardCharsets.US_ASCII);

    private final DesignInputExpressionCapacityAuthority capacity;

    CanonicalJsonWriter() {
        this(CanonicalDesignInputExpressionCapacityAuthority.INSTANCE);
    }

    CanonicalJsonWriter(DesignInputExpressionCapacityAuthority capacity) {
        this.capacity = Objects.requireNonNull(capacity, "capacity");
    }

    byte[] write(JsonValue value) throws CanonicalLimitException {
        var counter = new CountingSink(capacity);
        write(value, counter);

        var output = new ByteArrayOutputStream(counter.acceptedCount());
        write(value, new OutputSink(output));
        return output.toByteArray();
    }

    private void write(JsonValue value, Sink output) throws CanonicalLimitException {
        switch (value) {
            case JsonValue.ObjectValue object -> writeObject(object, output);
            case JsonValue.ArrayValue array -> writeArray(array, output);
            case JsonValue.StringValue string -> writeString(string.value(), output);
            case JsonValue.NumberValue number -> writeNumber(number.token(), output);
            case JsonValue.BooleanValue bool -> output.ascii(bool.value() ? "true" : "false");
            case JsonValue.NullValue ignored -> output.ascii("null");
        }
    }

    private void writeObject(JsonValue.ObjectValue object, Sink output)
            throws CanonicalLimitException {
        output.single('{');
        var names = new ArrayList<>(object.members().keySet());
        names.sort(UTF8_ORDER);
        for (int index = 0; index < names.size(); index++) {
            if (index > 0) {
                output.single(',');
            }
            var name = names.get(index);
            writeString(name, output);
            output.single(':');
            write(object.members().get(name), output);
        }
        output.single('}');
    }

    private void writeArray(JsonValue.ArrayValue array, Sink output)
            throws CanonicalLimitException {
        output.single('[');
        for (int index = 0; index < array.items().size(); index++) {
            if (index > 0) {
                output.single(',');
            }
            write(array.items().get(index), output);
        }
        output.single(']');
    }

    private void writeString(String value, Sink output) throws CanonicalLimitException {
        output.single('"');
        for (int offset = 0; offset < value.length(); ) {
            var codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            switch (codePoint) {
                case '"' -> output.ascii("\\\"");
                case '\\' -> output.ascii("\\\\");
                case '\b' -> output.ascii("\\b");
                case '\f' -> output.ascii("\\f");
                case '\n' -> output.ascii("\\n");
                case '\r' -> output.ascii("\\r");
                case '\t' -> output.ascii("\\t");
                default -> {
                    if (codePoint < 0x20) {
                        output.ascii("\\u00");
                        output.single(hexDigit(codePoint >>> 4));
                        output.single(hexDigit(codePoint));
                    } else {
                        output.utf8(new String(Character.toChars(codePoint)));
                    }
                }
            }
        }
        output.single('"');
    }

    private void writeNumber(String token, Sink output) throws CanonicalLimitException {
        var value = new BigDecimal(token);
        if (value.signum() == 0) {
            output.single('0');
            return;
        }
        var normalized = value.stripTrailingZeros();
        if (normalized.signum() < 0) {
            output.single('-');
        }
        var digits = normalized.unscaledValue().abs().toString();
        var scale = normalized.scale();
        if (scale <= 0) {
            output.ascii(digits);
            output.zeros(-(long) scale);
            return;
        }
        var point = digits.length() - scale;
        if (point <= 0) {
            output.ascii("0.");
            output.zeros(-(long) point);
            output.ascii(digits);
            return;
        }
        output.ascii(digits.substring(0, point));
        output.single('.');
        output.ascii(digits.substring(point));
    }

    private int hexDigit(int value) {
        return "0123456789abcdef".charAt(value & 0x0f);
    }

    private interface Sink {
        void bytes(byte[] value, int offset, int length) throws CanonicalLimitException;

        default void single(int value) throws CanonicalLimitException {
            var bytes = new byte[]{(byte) value};
            bytes(bytes, 0, 1);
        }

        default void ascii(String value) throws CanonicalLimitException {
            var bytes = value.getBytes(StandardCharsets.US_ASCII);
            bytes(bytes, 0, bytes.length);
        }

        default void utf8(String value) throws CanonicalLimitException {
            var bytes = value.getBytes(StandardCharsets.UTF_8);
            bytes(bytes, 0, bytes.length);
        }

        default void zeros(long count) throws CanonicalLimitException {
            long remaining = count;
            while (remaining > 0) {
                var length = (int) Math.min(remaining, ZERO_CHUNK.length);
                bytes(ZERO_CHUNK, 0, length);
                remaining -= length;
            }
        }
    }

    private static final class CountingSink implements Sink {
        private final DesignInputExpressionCapacityAuthority capacity;
        private long count;

        private CountingSink(DesignInputExpressionCapacityAuthority capacity) {
            this.capacity = capacity;
        }

        @Override
        public void bytes(byte[] value, int offset, int length) {
            count = Math.addExact(count, length);
        }

        @Override
        public void zeros(long zeroCount) {
            count = Math.addExact(count, zeroCount);
        }

        private int acceptedCount() throws CanonicalLimitException {
            var decision = capacity.evaluate(new DesignInputExpressionCapacityAuthority.Observation(
                    "designDslParser.canonicalBytes",
                    Long.toString(count)
            ));
            if (!(decision instanceof DesignInputExpressionCapacityAuthority.Accepted)) {
                throw new CanonicalLimitException();
            }
            return Math.toIntExact(count);
        }
    }

    private record OutputSink(ByteArrayOutputStream output) implements Sink {
        @Override
        public void bytes(byte[] value, int offset, int length) {
            output.write(value, offset, length);
        }
    }

    static final class CanonicalLimitException extends Exception {
    }
}
