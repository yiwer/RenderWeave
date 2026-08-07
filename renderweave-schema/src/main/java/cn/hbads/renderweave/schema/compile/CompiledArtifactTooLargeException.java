package cn.hbads.renderweave.schema.compile;

public final class CompiledArtifactTooLargeException extends RuntimeException {

    private final int actualBytes;
    private final int maximumBytes;

    public CompiledArtifactTooLargeException(int actualBytes, int maximumBytes) {
        super("Compiled JSON Schema is " + actualBytes + " UTF-8 bytes; maximum is " + maximumBytes);
        this.actualBytes = actualBytes;
        this.maximumBytes = maximumBytes;
    }

    public int actualBytes() {
        return actualBytes;
    }

    public int maximumBytes() {
        return maximumBytes;
    }
}
