package cn.hbads.renderweave.inference.candidate;

public final class CandidateApplyConflictException extends RuntimeException {
    private final String code;

    public CandidateApplyConflictException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
