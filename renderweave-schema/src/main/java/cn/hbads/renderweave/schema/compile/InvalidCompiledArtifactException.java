package cn.hbads.renderweave.schema.compile;

public final class InvalidCompiledArtifactException extends RuntimeException {

    public InvalidCompiledArtifactException(String message) {
        super(message);
    }

    public InvalidCompiledArtifactException(String message, Throwable cause) {
        super(message, cause);
    }
}
