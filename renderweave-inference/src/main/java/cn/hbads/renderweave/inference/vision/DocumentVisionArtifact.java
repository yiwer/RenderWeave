package cn.hbads.renderweave.inference.vision;

import java.util.Objects;

/** One normalized, in-memory source image supplied to the local preprocessor. */
public record DocumentVisionArtifact(
        String artifactId,
        int sourceOrdinal,
        String mediaType,
        byte[] bytes,
        int width,
        int height
) {
    public DocumentVisionArtifact {
        if (artifactId == null || !artifactId.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Document vision artifact id is invalid");
        }
        if (sourceOrdinal < 0 || sourceOrdinal >= 10) {
            throw new IllegalArgumentException("Document vision source ordinal is invalid");
        }
        if (!"image/png".equals(mediaType) && !"image/jpeg".equals(mediaType)) {
            throw new IllegalArgumentException("Document vision media type is unsupported");
        }
        bytes = Objects.requireNonNull(bytes, "bytes").clone();
        if (bytes.length == 0 || bytes.length > 10 * 1024 * 1024 || width < 1 || height < 1
                || width > 4_096 || height > 4_096 || Math.multiplyExact((long) width, height) > 16_000_000L) {
            throw new IllegalArgumentException("Document vision artifact bounds are invalid");
        }
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    @Override
    public String toString() {
        return "DocumentVisionArtifact[artifactId=" + artifactId + ", sourceOrdinal=" + sourceOrdinal
                + ", mediaType=" + mediaType + ", bytes=<redacted:" + bytes.length + ">, width=" + width
                + ", height=" + height + "]";
    }
}
