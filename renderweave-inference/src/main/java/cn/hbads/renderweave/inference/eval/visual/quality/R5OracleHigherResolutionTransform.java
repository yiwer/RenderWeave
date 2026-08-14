package cn.hbads.renderweave.inference.eval.visual.quality;

import cn.hbads.renderweave.inference.eval.visual.LayeredVisualCorpus;
import cn.hbads.renderweave.inference.eval.visual.VisualStageCorpus;
import cn.hbads.renderweave.inference.eval.visual.VisualStageRasterizer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Fixed local-only oracle that rerenders repository scenes at up to 2x within 2400px. */
public final class R5OracleHigherResolutionTransform {
    public static final String VERSION = "renderweave-r5-oracle-higher-resolution/1.0";
    private static final int MAXIMUM_SCALE = 2;
    private static final int MAXIMUM_DIMENSION = 2_400;
    private static final String IDENTITY = VERSION + ":" + sha256(List.of(
            VERSION,
            "repository-scene-vector-rerender/1.0",
            "maximum-scale=2x",
            "maximum-dimension=2400",
            "aspect-ratio=floor-limited-dimension/1.0",
            "product-inspection-request=absent"));

    public String identity() {
        return IDENTITY;
    }

    public TransformedArtifact render(LayeredVisualCorpus.Case evaluationCase) {
        Objects.requireNonNull(evaluationCase, "evaluationCase");
        var source = evaluationCase.renderCase();
        var dimensions = dimensions(source.width(), source.height());
        var transformedCase = new VisualStageCorpus.EvaluationCase(
                source.caseId(), source.scene(), source.variantOrdinal(), source.partition(), source.style(),
                dimensions.width(), dimensions.height(), source.contrastBps(), source.distractorCount(),
                source.noiseSeed());
        var rendered = new VisualStageRasterizer().render(transformedCase);
        return new TransformedArtifact(
                IDENTITY,
                source.width(),
                source.height(),
                rendered.width(),
                rendered.height(),
                rendered.sha256(),
                rendered.mediaType(),
                rendered.bytes());
    }

    private static Dimensions dimensions(int width, int height) {
        var doubledWidth = Math.multiplyExact(width, MAXIMUM_SCALE);
        var doubledHeight = Math.multiplyExact(height, MAXIMUM_SCALE);
        if (doubledWidth <= MAXIMUM_DIMENSION && doubledHeight <= MAXIMUM_DIMENSION) {
            return new Dimensions(doubledWidth, doubledHeight);
        }
        if (Math.multiplyExact((long) width, MAXIMUM_DIMENSION)
                >= Math.multiplyExact((long) height, MAXIMUM_DIMENSION)) {
            var scaledHeight = Math.toIntExact(Math.floorDiv(
                    Math.multiplyExact((long) height, MAXIMUM_DIMENSION), width));
            return new Dimensions(MAXIMUM_DIMENSION, scaledHeight);
        }
        var scaledWidth = Math.toIntExact(Math.floorDiv(
                Math.multiplyExact((long) width, MAXIMUM_DIMENSION), height));
        return new Dimensions(scaledWidth, MAXIMUM_DIMENSION);
    }

    private static String sha256(List<String> values) {
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
            throw new IllegalStateException("SHA_256_UNAVAILABLE", impossible);
        }
    }

    private static final class Dimensions {
        private final int width;
        private final int height;

        private Dimensions(int width, int height) {
            if (width < 1 || height < 1 || width > MAXIMUM_DIMENSION || height > MAXIMUM_DIMENSION) {
                throw new IllegalArgumentException("R5_ORACLE_DIMENSIONS_INVALID");
            }
            this.width = width;
            this.height = height;
        }

        int width() { return width; }

        int height() { return height; }
    }

    public record TransformedArtifact(
            String identity,
            int sourceWidth,
            int sourceHeight,
            int width,
            int height,
            String artifactId,
            String mediaType,
            byte[] bytes
    ) {
        public TransformedArtifact {
            if (!IDENTITY.equals(identity) || sourceWidth < 1 || sourceHeight < 1
                    || width <= sourceWidth || height <= sourceHeight
                    || width > MAXIMUM_DIMENSION || height > MAXIMUM_DIMENSION
                    || artifactId == null || !artifactId.matches("[0-9a-f]{64}")
                    || !"image/png".equals(mediaType)) {
                throw new IllegalArgumentException("R5_ORACLE_ARTIFACT_INVALID");
            }
            bytes = Objects.requireNonNull(bytes, "bytes").clone();
            if (bytes.length == 0) throw new IllegalArgumentException("R5_ORACLE_ARTIFACT_INVALID");
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        @Override
        public String toString() {
            return "TransformedArtifact[identity=" + identity + ", source=" + sourceWidth + "x"
                    + sourceHeight + ", oracle=" + width + "x" + height + ", artifactId="
                    + artifactId + ", bytes=<redacted:" + bytes.length + ">]";
        }
    }
}
