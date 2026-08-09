package cn.hbads.renderweave.inference.input;

import java.util.Objects;

/**
 * Internal structural paths reserve a raw {@code *} segment for array items. Object member
 * segments therefore extend RFC 6901 with {@code ~2} for a literal asterisk; evidence pointers
 * remain ordinary RFC 6901 pointers to the original sample.
 */
public final class JsonStructuralPointer {
    private JsonStructuralPointer() { }

    public static String objectSegment(String fieldKey) {
        return Objects.requireNonNull(fieldKey, "fieldKey")
                .replace("~", "~0")
                .replace("/", "~1")
                .replace("*", "~2");
    }

    public static String decodeObjectSegment(String segment) {
        return Objects.requireNonNull(segment, "segment")
                .replace("~2", "*")
                .replace("~1", "/")
                .replace("~0", "~");
    }
}
