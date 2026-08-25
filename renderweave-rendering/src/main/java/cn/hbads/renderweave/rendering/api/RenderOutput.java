package cn.hbads.renderweave.rendering.api;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.regex.Pattern;

/**
 * RenderEngine 为一条 Command 原子产生的一张完整 PNG/JPEG 及 closed 安全 metadata
 * （ADR-0044 §4）。正式输出和权威预览都使用该请求瞬态结果。
 *
 * <p>不是 RenderDocument、LaidOutScene、partial transport、batch、一组图片、浏览器本地画布、
 * 自动持久化 Artifact 或 Workspace 历史。
 */
public record RenderOutput(
        byte[] sealedImageBytes,
        String contractVersion,
        String rendererProfile,
        String dslVersion,
        String layoutProfile,
        String outputProfile,
        String format,
        String mediaType,
        int widthPx,
        int heightPx,
        int dpi,
        OptionalInt quality,
        long byteLength,
        String contentSha256
) {

    private static final Pattern PROFILE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,255}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public RenderOutput {
        Objects.requireNonNull(sealedImageBytes, "sealedImageBytes");
        Objects.requireNonNull(contractVersion, "contractVersion");
        Objects.requireNonNull(rendererProfile, "rendererProfile");
        Objects.requireNonNull(dslVersion, "dslVersion");
        Objects.requireNonNull(layoutProfile, "layoutProfile");
        Objects.requireNonNull(outputProfile, "outputProfile");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(mediaType, "mediaType");
        Objects.requireNonNull(quality, "quality");
        Objects.requireNonNull(contentSha256, "contentSha256");
        if (sealedImageBytes.length == 0) {
            throw new IllegalArgumentException("sealedImageBytes must not be empty");
        }
        if (sealedImageBytes.length != byteLength) {
            throw new IllegalArgumentException("byteLength must equal sealedImageBytes length");
        }
        if (widthPx < 1 || heightPx < 1) {
            throw new IllegalArgumentException("widthPx and heightPx must be positive");
        }
        if (dpi < 1 || dpi > 600) {
            throw new IllegalArgumentException("dpi must be within 1..600");
        }
        if (!"renderweave-render-result/1.0".equals(contractVersion)) {
            throw new IllegalArgumentException(
                    "contractVersion must be renderweave-render-result/1.0");
        }
        if (!"renderweave-render/1.0".equals(dslVersion)) {
            throw new IllegalArgumentException("dslVersion must be renderweave-render/1.0");
        }
        requireProfile(rendererProfile, "rendererProfile");
        requireProfile(layoutProfile, "layoutProfile");
        requireProfile(outputProfile, "outputProfile");
        if ("renderweave-output-png/1.0".equals(outputProfile)) {
            if (!"PNG".equals(format) || !"image/png".equals(mediaType) || quality.isPresent()) {
                throw new IllegalArgumentException("PNG result metadata shape is invalid");
            }
        } else if ("renderweave-output-jpeg/1.0".equals(outputProfile)) {
            if (!"JPEG".equals(format) || !"image/jpeg".equals(mediaType)
                    || quality.isEmpty() || quality.getAsInt() < 1 || quality.getAsInt() > 100) {
                throw new IllegalArgumentException("JPEG result metadata shape is invalid");
            }
        } else {
            throw new IllegalArgumentException("outputProfile is not supported");
        }
        if (!SHA256.matcher(contentSha256).matches()
                || !MessageDigest.isEqual(
                        contentSha256.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                        sha256(sealedImageBytes).getBytes(
                                java.nio.charset.StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException(
                    "contentSha256 must equal the raw sealed image SHA-256");
        }
        sealedImageBytes = sealedImageBytes.clone();
    }

    public byte[] sealedImageBytes() {
        return sealedImageBytes.clone();
    }

    public Evaluator.OutputSelection outputSelection() {
        if ("PNG".equals(format)) {
            return new Evaluator.OutputSelection.Png(dpi);
        }
        return new Evaluator.OutputSelection.Jpeg(dpi, quality.orElseThrow());
    }

    private static void requireProfile(String value, String name) {
        if (!PROFILE.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
