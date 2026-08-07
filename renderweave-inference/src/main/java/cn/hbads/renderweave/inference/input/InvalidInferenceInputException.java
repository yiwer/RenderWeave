package cn.hbads.renderweave.inference.input;

import java.util.Map;

public final class InvalidInferenceInputException extends IllegalArgumentException {
    private final String code;
    private final String pointer;
    private final Map<String, Object> args;

    public InvalidInferenceInputException(String code, String pointer, String message) {
        this(code, pointer, Map.of(), message, null);
    }

    public InvalidInferenceInputException(
            String code,
            String pointer,
            Map<String, Object> args,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.code = code;
        this.pointer = pointer;
        this.args = Map.copyOf(args);
    }

    public String code() {
        return code;
    }

    public String pointer() {
        return pointer;
    }

    public Map<String, Object> args() {
        return args;
    }
}
