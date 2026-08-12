package cn.hbads.renderweave.inference.vision;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Canonical in-memory artifacts after the v1 image-normalization boundary. */
public final class ArtifactSet {
    public static final int MAXIMUM_ARTIFACTS = 10;

    private final List<Artifact> artifacts;

    private ArtifactSet(List<Artifact> artifacts) {
        this.artifacts = artifacts;
    }

    public static ArtifactSet canonical(List<Artifact> artifacts) {
        Objects.requireNonNull(artifacts, "artifacts");
        if (artifacts.isEmpty() || artifacts.size() > MAXIMUM_ARTIFACTS) {
            throw new IllegalArgumentException("ARTIFACT_SET_BOUNDS_INVALID");
        }
        var ordered = new ArrayList<>(artifacts);
        if (ordered.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("ARTIFACT_SET_ENTRY_INVALID");
        }
        ordered.sort(Comparator.comparingInt(Artifact::sourceOrdinal));

        var ids = new HashSet<String>();
        var ordinals = new HashSet<Integer>();
        for (int index = 0; index < ordered.size(); index++) {
            var artifact = ordered.get(index);
            if (!ids.add(artifact.artifactId()) || !ordinals.add(artifact.sourceOrdinal())) {
                throw new IllegalArgumentException("ARTIFACT_SET_IDENTITY_DUPLICATED");
            }
            if (artifact.sourceOrdinal() != index) {
                throw new IllegalArgumentException("ARTIFACT_SET_ORDINAL_INVALID");
            }
        }
        return new ArtifactSet(List.copyOf(ordered));
    }

    public List<Artifact> artifacts() {
        return artifacts;
    }

    @Override
    public String toString() {
        return "ArtifactSet[artifactCount=" + artifacts.size() + "]";
    }

    public record Artifact(
            String artifactId,
            int sourceOrdinal,
            String mediaType,
            byte[] bytes,
            int width,
            int height,
            boolean orientationApplied
    ) {
        private static final int MAXIMUM_BYTES = 10 * 1024 * 1024;
        private static final int MAXIMUM_DIMENSION = 4_096;
        private static final long MAXIMUM_PIXELS = 16_000_000L;

        public Artifact {
            if (artifactId == null || !artifactId.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("ARTIFACT_ID_INVALID");
            }
            if (sourceOrdinal < 0 || sourceOrdinal >= MAXIMUM_ARTIFACTS) {
                throw new IllegalArgumentException("ARTIFACT_ORDINAL_INVALID");
            }
            if (!"image/png".equals(mediaType) && !"image/jpeg".equals(mediaType)) {
                throw new IllegalArgumentException("ARTIFACT_MEDIA_TYPE_UNSUPPORTED");
            }
            bytes = Objects.requireNonNull(bytes, "bytes").clone();
            if (bytes.length == 0 || bytes.length > MAXIMUM_BYTES
                    || width < 1 || height < 1
                    || width > MAXIMUM_DIMENSION || height > MAXIMUM_DIMENSION
                    || Math.multiplyExact((long) width, height) > MAXIMUM_PIXELS) {
                throw new IllegalArgumentException("ARTIFACT_BOUNDS_INVALID");
            }
            if (!orientationApplied) {
                throw new IllegalArgumentException("ARTIFACT_ORIENTATION_NOT_APPLIED");
            }
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        @Override
        public String toString() {
            return "Artifact[artifactId=" + artifactId + ", sourceOrdinal=" + sourceOrdinal
                    + ", mediaType=" + mediaType + ", bytes=<redacted:" + bytes.length + ">, width="
                    + width + ", height=" + height + ", orientationApplied=" + orientationApplied + "]";
        }
    }
}
