package cn.hbads.renderweave.inference.dashscope;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

final class DashScopeApiKey {
    private static final int MAX_SECRET_BYTES = 512;
    private static final Pattern TOKEN_PLAN_KEY = Pattern.compile(
            "sk-[A-Za-z0-9_.-]{12,509}"
    );
    private final String value;

    private DashScopeApiKey(String value) {
        if (value == null
                || value.getBytes(StandardCharsets.UTF_8).length > MAX_SECRET_BYTES
                || !TOKEN_PLAN_KEY.matcher(value).matches()) {
            throw new IllegalStateException("DASHSCOPE_TOKEN_API_KEY has an invalid format");
        }
        this.value = value;
    }

    static DashScopeApiKey fromValue(String value) {
        return new DashScopeApiKey(value == null ? null : value.strip());
    }

    static Optional<DashScopeApiKey> resolve(String directValue, String fileName) {
        var hasDirect = directValue != null && !directValue.isBlank();
        var hasFile = fileName != null && !fileName.isBlank();
        if (hasDirect && hasFile) {
            throw new IllegalStateException(
                    "Configure either DASHSCOPE_TOKEN_API_KEY or DASHSCOPE_TOKEN_API_KEY_FILE, not both"
            );
        }
        if (hasDirect) return Optional.of(fromValue(directValue));
        if (!hasFile) return Optional.empty();
        var path = Path.of(fileName);
        try {
            if (!Files.isRegularFile(path) || Files.size(path) > MAX_SECRET_BYTES) {
                throw new IllegalStateException(
                        "DASHSCOPE_TOKEN_API_KEY_FILE is not a bounded regular file"
                );
            }
            return Optional.of(fromValue(Files.readString(path, StandardCharsets.UTF_8)));
        } catch (IOException exception) {
            throw new IllegalStateException("DASHSCOPE_TOKEN_API_KEY_FILE cannot be read", exception);
        }
    }

    String authorizationHeader() {
        return "Bearer " + value;
    }

    @Override
    public String toString() {
        return "<redacted:DASHSCOPE_TOKEN_API_KEY>";
    }
}
