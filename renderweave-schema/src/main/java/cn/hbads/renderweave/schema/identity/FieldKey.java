package cn.hbads.renderweave.schema.identity;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Field identity component. The value is deliberately not trimmed or Unicode-normalized. */
public final class FieldKey {

    private static final int MAX_UTF8_BYTES = 128;

    private final String value;

    private FieldKey(String value) {
        this.value = validate(value);
    }

    public static FieldKey of(String value) {
        return new FieldKey(value);
    }

    public String value() {
        return value;
    }

    public String jsonPointerSegment() {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private static String validate(String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("fieldKey must not be empty");
        }
        if (value.codePoints().anyMatch(codePoint -> Character.getType(codePoint) == Character.CONTROL)) {
            throw new IllegalArgumentException("fieldKey must not contain control characters");
        }

        final ByteBuffer encoded;
        try {
            encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(value));
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("fieldKey must contain valid Unicode scalar values", exception);
        }
        if (encoded.remaining() > MAX_UTF8_BYTES) {
            throw new IllegalArgumentException("fieldKey must be at most 128 UTF-8 bytes");
        }
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof FieldKey that && value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
