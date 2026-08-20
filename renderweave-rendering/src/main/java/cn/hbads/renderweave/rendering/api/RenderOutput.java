package cn.hbads.renderweave.rendering.api;

import java.util.Objects;

/**
 * RenderEngine 为一条 Command 原子产生的一张完整 PNG/JPEG 及 closed 安全 metadata
 * （ADR-0044 §4）。正式输出和权威预览都使用该请求瞬态结果。
 *
 * <p>不是 RenderDocument、LaidOutScene、partial transport、batch、一组图片、浏览器本地画布、
 * 自动持久化 Artifact 或 Workspace 历史。
 */
public record RenderOutput(
        byte[] sealedImageBytes,
        Evaluator.OutputSelection outputProfile,
        int widthPx,
        int heightPx,
        long byteLength
) {

    public RenderOutput {
        Objects.requireNonNull(sealedImageBytes, "sealedImageBytes");
        Objects.requireNonNull(outputProfile, "outputProfile");
        if (sealedImageBytes.length == 0) {
            throw new IllegalArgumentException("sealedImageBytes must not be empty");
        }
        if (sealedImageBytes.length != byteLength) {
            throw new IllegalArgumentException("byteLength must equal sealedImageBytes length");
        }
        if (widthPx < 1 || heightPx < 1) {
            throw new IllegalArgumentException("widthPx and heightPx must be positive");
        }
        sealedImageBytes = sealedImageBytes.clone();
    }

    public byte[] sealedImageBytes() {
        return sealedImageBytes.clone();
    }
}
