package cn.hbads.renderweave.inference.admission;

import java.util.Locale;
import java.util.Set;

/** Exact request facts that a GatewayAssertion must bind. */
public record GatewayAssertionRequest(
        String method,
        String path,
        String idempotencyKey
) {
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    public GatewayAssertionRequest {
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("Gateway request method is required");
        }
        method = method.toUpperCase(Locale.ROOT);
        if (!method.matches("[A-Z]{3,8}")) {
            throw new IllegalArgumentException("Gateway request method is invalid");
        }
        if (path == null || path.isBlank() || path.length() > 1024
                || path.charAt(0) != '/' || path.indexOf('?') >= 0 || path.indexOf('#') >= 0
                || path.indexOf('\\') >= 0 || path.indexOf('\r') >= 0 || path.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Gateway request path is invalid");
        }
    }

    public boolean mutation() {
        return !SAFE_METHODS.contains(method);
    }
}
