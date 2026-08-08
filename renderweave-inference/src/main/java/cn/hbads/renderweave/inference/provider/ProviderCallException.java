package cn.hbads.renderweave.inference.provider;

import java.time.Duration;
import java.util.Optional;

/** Safe provider failure metadata; upstream response bodies are deliberately excluded. */
public final class ProviderCallException extends RuntimeException {
    private final String code;
    private final boolean retryable;
    private final Integer statusCode;
    private final Optional<Duration> retryAfter;

    public ProviderCallException(
            String code,
            boolean retryable,
            Integer statusCode,
            Optional<Duration> retryAfter,
            Throwable cause
    ) {
        super(code, cause);
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw new IllegalArgumentException("code must be a stable uppercase identifier");
        }
        if (statusCode != null && (statusCode < 100 || statusCode > 599)) {
            throw new IllegalArgumentException("statusCode must be a valid HTTP status");
        }
        this.code = code;
        this.retryable = retryable;
        this.statusCode = statusCode;
        this.retryAfter = retryAfter == null ? Optional.empty() : retryAfter;
        this.retryAfter.ifPresent(value -> {
            if (value.isNegative() || value.compareTo(Duration.ofMinutes(5)) > 0) {
                throw new IllegalArgumentException("retryAfter must be 0..5 minutes");
            }
        });
    }

    public String code() { return code; }
    public boolean retryable() { return retryable; }
    public Optional<Integer> statusCode() { return Optional.ofNullable(statusCode); }
    public Optional<Duration> retryAfter() { return retryAfter; }
}
