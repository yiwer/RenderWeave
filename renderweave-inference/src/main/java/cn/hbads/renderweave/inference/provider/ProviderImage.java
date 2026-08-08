package cn.hbads.renderweave.inference.provider;

import java.util.Objects;

/** Normalized image bytes; the provider boundary deliberately has no URL-shaped media input. */
public record ProviderImage(String artifactId, String mediaType, byte[] bytes) {
    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;

    public ProviderImage {
        if (artifactId == null || !artifactId.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("artifactId must be a SHA-256 hex digest");
        }
        if (!("image/png".equals(mediaType) || "image/jpeg".equals(mediaType))) {
            throw new IllegalArgumentException("Only normalized PNG/JPEG provider images are allowed");
        }
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("Provider image bytes must be 1..10 MiB");
        }
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }
}
