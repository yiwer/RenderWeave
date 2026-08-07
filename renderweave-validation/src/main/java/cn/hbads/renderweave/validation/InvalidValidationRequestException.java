package cn.hbads.renderweave.validation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Stable request-level failure raised before target resolution or per-document validation. */
public final class InvalidValidationRequestException extends RuntimeException {

    public enum Kind {
        MALFORMED,
        INVALID_ENVELOPE,
        LIMIT_EXCEEDED
    }

    private final Kind kind;
    private final String code;
    private final String pointer;
    private final Map<String, Object> messageArgs;

    public InvalidValidationRequestException(
            Kind kind,
            String code,
            String pointer,
            Map<String, Object> messageArgs,
            String message
    ) {
        this(kind, code, pointer, messageArgs, message, null);
    }

    public InvalidValidationRequestException(
            Kind kind,
            String code,
            String pointer,
            Map<String, Object> messageArgs,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.code = Objects.requireNonNull(code, "code");
        this.pointer = Objects.requireNonNull(pointer, "pointer");
        this.messageArgs = Collections.unmodifiableMap(new LinkedHashMap<>(messageArgs));
    }

    public Kind kind() {
        return kind;
    }

    public String code() {
        return code;
    }

    public String pointer() {
        return pointer;
    }

    public Map<String, Object> messageArgs() {
        return messageArgs;
    }
}
