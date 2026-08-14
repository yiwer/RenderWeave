package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.candidate.CandidateBoundingBox;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Product-equivalent in-memory crop/resize used by the bounded inspection module. */
final class R5ProductRasterTransform {
    static final String VERSION = "renderweave-r5-product-raster-transform/1.0";
    private static final int CANONICAL_MAX = 10_000;
    private static final int MAXIMUM_LONG_EDGE = 2_400;
    private static final int MAXIMUM_MARGIN_BPS = 500;

    RasterView render(
            VisualSourceImage source,
            VisualView baseView,
            CandidateBoundingBox viewRelativeBox,
            int marginBps,
            int requestedLongEdge
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(baseView, "baseView");
        viewRelativeBox = VisualAnalysisValidation.canonicalBox(viewRelativeBox, "inspection region");
        if (!source.artifactId().equals(baseView.descriptor().sourceArtifactId())
                || source.width() != baseView.sourceWidth()
                || source.height() != baseView.sourceHeight()) {
            throw new IllegalArgumentException("R5_PRODUCT_TRANSFORM_SOURCE_LINEAGE_INVALID");
        }
        if (marginBps < 0 || marginBps > MAXIMUM_MARGIN_BPS
                || requestedLongEdge < 1 || requestedLongEdge > MAXIMUM_LONG_EDGE) {
            throw new IllegalArgumentException("R5_PRODUCT_TRANSFORM_POLICY_INVALID");
        }

        var decoded = decode(source);
        var requested = project(baseView.crop(), viewRelativeBox);
        var expanded = expand(requested, marginBps, source.width(), source.height());
        var output = resize(decoded, expanded, requestedLongEdge);
        var bytes = png(output);
        var artifactId = sha256(bytes);
        var sourceBox = canonical(expanded, source.width(), source.height());
        var identity = "renderweave-r5-product-raster-view/1.0:" + framedSha256(List.of(
                VERSION,
                "source=" + source.artifactId(),
                "base-view=" + baseView.descriptor().viewId(),
                "base-kind=" + baseView.descriptor().kind(),
                "request=" + coordinates(viewRelativeBox),
                "margin-bps=" + marginBps,
                "long-edge=" + requestedLongEdge,
                "source-crop=" + coordinates(sourceBox),
                "dimensions=" + output.getWidth() + "x" + output.getHeight(),
                "artifact=" + artifactId,
                "codec=java-imageio-png/1.0",
                "interpolation=java2d-bicubic/1.0"));
        return new RasterView(
                identity, artifactId, "image/png", source.artifactId(), sourceBox,
                output.getWidth(), output.getHeight(), expanded, bytes);
    }

    private static MultiScaleVisualViewPlanner.PixelCrop project(
            MultiScaleVisualViewPlanner.PixelCrop base,
            CandidateBoundingBox box
    ) {
        var width = base.right() - base.left();
        var height = base.bottom() - base.top();
        return new MultiScaleVisualViewPlanner.PixelCrop(
                base.left() + Math.toIntExact(Math.floorDiv((long) box.left() * width, CANONICAL_MAX)),
                base.top() + Math.toIntExact(Math.floorDiv((long) box.top() * height, CANONICAL_MAX)),
                base.left() + Math.toIntExact(Math.ceilDiv((long) box.right() * width, CANONICAL_MAX)),
                base.top() + Math.toIntExact(Math.ceilDiv((long) box.bottom() * height, CANONICAL_MAX)));
    }

    private static MultiScaleVisualViewPlanner.PixelCrop expand(
            MultiScaleVisualViewPlanner.PixelCrop crop,
            int marginBps,
            int sourceWidth,
            int sourceHeight
    ) {
        var horizontal = Math.toIntExact(Math.ceilDiv(
                Math.multiplyExact((long) crop.right() - crop.left(), marginBps), CANONICAL_MAX));
        var vertical = Math.toIntExact(Math.ceilDiv(
                Math.multiplyExact((long) crop.bottom() - crop.top(), marginBps), CANONICAL_MAX));
        return new MultiScaleVisualViewPlanner.PixelCrop(
                Math.max(0, crop.left() - horizontal),
                Math.max(0, crop.top() - vertical),
                Math.min(sourceWidth, Math.addExact(crop.right(), horizontal)),
                Math.min(sourceHeight, Math.addExact(crop.bottom(), vertical)));
    }

    private static BufferedImage resize(
            BufferedImage source,
            MultiScaleVisualViewPlanner.PixelCrop crop,
            int requestedLongEdge
    ) {
        var cropWidth = crop.right() - crop.left();
        var cropHeight = crop.bottom() - crop.top();
        var outputWidth = cropWidth >= cropHeight
                ? requestedLongEdge
                : Math.max(1, Math.toIntExact(Math.floorDiv(
                        Math.multiplyExact((long) cropWidth, requestedLongEdge), cropHeight)));
        var outputHeight = cropHeight >= cropWidth
                ? requestedLongEdge
                : Math.max(1, Math.toIntExact(Math.floorDiv(
                        Math.multiplyExact((long) cropHeight, requestedLongEdge), cropWidth)));
        var output = new BufferedImage(
                outputWidth, outputHeight,
                source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        var graphics = output.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, outputWidth, outputHeight,
                    crop.left(), crop.top(), crop.right(), crop.bottom(), null);
        } finally {
            graphics.dispose();
        }
        return output;
    }

    private static BufferedImage decode(VisualSourceImage source) {
        try {
            var decoded = ImageIO.read(new ByteArrayInputStream(source.bytes()));
            if (decoded == null || decoded.getWidth() != source.width() || decoded.getHeight() != source.height()) {
                throw new IllegalArgumentException("R5_PRODUCT_TRANSFORM_SOURCE_INVALID");
            }
            return decoded;
        } catch (IOException failure) {
            throw new IllegalArgumentException("R5_PRODUCT_TRANSFORM_SOURCE_INVALID", failure);
        }
    }

    private static byte[] png(BufferedImage value) {
        try {
            var output = new ByteArrayOutputStream();
            try (var stream = new MemoryCacheImageOutputStream(output)) {
                var writers = ImageIO.getImageWritersByFormatName("png");
                if (!writers.hasNext()) throw new IOException("PNG writer is unavailable");
                var writer = writers.next();
                try {
                    writer.setOutput(stream);
                    writer.write(value);
                    stream.flush();
                } finally {
                    writer.dispose();
                }
            }
            return output.toByteArray();
        } catch (IOException failure) {
            throw new IllegalStateException("R5_PRODUCT_TRANSFORM_ENCODING_FAILED", failure);
        }
    }

    private static CandidateBoundingBox canonical(
            MultiScaleVisualViewPlanner.PixelCrop crop,
            int width,
            int height
    ) {
        return new CandidateBoundingBox(
                Math.toIntExact(Math.floorDiv((long) crop.left() * CANONICAL_MAX, width)),
                Math.toIntExact(Math.floorDiv((long) crop.top() * CANONICAL_MAX, height)),
                Math.toIntExact(Math.ceilDiv((long) crop.right() * CANONICAL_MAX, width)),
                Math.toIntExact(Math.ceilDiv((long) crop.bottom() * CANONICAL_MAX, height)));
    }

    private static String coordinates(CandidateBoundingBox box) {
        return box.left() + "," + box.top() + "," + box.right() + "," + box.bottom();
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    private static String framedSha256(List<String> values) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var value : values) {
                var bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) ':');
                digest.update(bytes);
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    record RasterView(
            String identity,
            String artifactId,
            String mediaType,
            String sourceArtifactId,
            CandidateBoundingBox sourceBoundingBox,
            int width,
            int height,
            MultiScaleVisualViewPlanner.PixelCrop sourceCrop,
            byte[] bytes
    ) {
        RasterView {
            if (identity == null || !identity.matches("renderweave-r5-product-raster-view/1\\.0:[0-9a-f]{64}")
                    || artifactId == null || !artifactId.matches("[0-9a-f]{64}")
                    || sourceArtifactId == null || !sourceArtifactId.matches("[0-9a-f]{64}")
                    || !"image/png".equals(mediaType) || width < 1 || height < 1
                    || width > MAXIMUM_LONG_EDGE || height > MAXIMUM_LONG_EDGE) {
                throw new IllegalArgumentException("R5_PRODUCT_TRANSFORM_RESULT_INVALID");
            }
            Objects.requireNonNull(sourceBoundingBox, "sourceBoundingBox");
            Objects.requireNonNull(sourceCrop, "sourceCrop");
            bytes = Objects.requireNonNull(bytes, "bytes").clone();
            if (bytes.length == 0) throw new IllegalArgumentException("R5_PRODUCT_TRANSFORM_RESULT_INVALID");
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        @Override
        public String toString() {
            return "RasterView[identity=" + identity + ", dimensions=" + width + "x" + height
                    + ", bytes=<redacted:" + bytes.length + ">]";
        }
    }
}
