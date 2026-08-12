package cn.hbads.renderweave.inference.vision;

/** Payload-free failure at the visual evidence acquisition boundary. */
public final class VisualEvidenceAcquisitionException extends RuntimeException {
    private final String code;

    public VisualEvidenceAcquisitionException(String code) {
        super(requireCode(code));
        this.code = code;
    }

    public String code() {
        return code;
    }

    private static String requireCode(String code) {
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{2,95}")) {
            throw new IllegalArgumentException("VISUAL_EVIDENCE_FAILURE_CODE_INVALID");
        }
        return code;
    }
}
