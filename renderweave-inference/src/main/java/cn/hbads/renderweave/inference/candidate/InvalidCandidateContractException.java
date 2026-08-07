package cn.hbads.renderweave.inference.candidate;

public final class InvalidCandidateContractException extends IllegalArgumentException {
    private final String code;

    public InvalidCandidateContractException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
