package cn.hbads.renderweave.inference.admission;

/** Closed, payload-free rejection from the IMAGE_ONLY production admission boundary. */
public final class LiveAdmissionProblem extends RuntimeException {
    private final String code;

    public LiveAdmissionProblem(String code, String message) {
        super(message);
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{2,95}")) {
            throw new IllegalArgumentException("Live admission problem code is invalid");
        }
        this.code = code;
    }

    public String code() {
        return code;
    }
}
