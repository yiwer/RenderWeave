package cn.hbads.renderweave.inference.admission;

/** A payload-free, closed failure returned by the gateway trust boundary. */
public final class GatewayAssertionProblem extends RuntimeException {
    private final String code;

    public GatewayAssertionProblem(String code, String message) {
        super(message);
        this.code = requireCode(code);
    }

    public GatewayAssertionProblem(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = requireCode(code);
    }

    public String code() {
        return code;
    }

    private static String requireCode(String value) {
        if (value == null || !value.matches("[A-Z][A-Z0-9_]{2,95}")) {
            throw new IllegalArgumentException("Gateway assertion problem code is invalid");
        }
        return value;
    }
}
