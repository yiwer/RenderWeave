package cn.hbads.renderweave.inference.provider;

import cn.hbads.renderweave.inference.input.ImageNormalizer;

import java.util.Objects;

/** Normalized image bytes; the provider boundary deliberately has no URL-shaped media input. */
public record ProviderImage(
        String artifactId,
        String mediaType,
        byte[] bytes,
        Integer width,
        Integer height
) {
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
        if ((width == null) != (height == null)) {
            throw new IllegalArgumentException("Provider image dimensions must be paired");
        }
        if (width != null && (width < 1 || height < 1
                || Math.max(width, height) > ImageNormalizer.MAX_LONG_EDGE
                || (long) width * height > ImageNormalizer.MAX_NORMALIZED_PIXELS)) {
            throw new IllegalArgumentException("Provider image dimensions exceed the normalized boundary");
        }
        bytes = bytes.clone();
    }

    /** Compatibility constructor keeps historical synthetic requests on the conservative unknown-size bound. */
    public ProviderImage(String artifactId, String mediaType, byte[] bytes) {
        this(artifactId, mediaType, bytes, null, null);
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }
}
