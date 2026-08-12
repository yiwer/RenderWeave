package cn.hbads.renderweave.inference.vision;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Provider-neutral, ephemeral observations acquired from normalized image artifacts. */
public final class DocumentObservationIR {
    public static final String VERSION = "DocumentObservationIR/1.0";

    private final String contractVersion;
    private final String acquisitionPolicyIdentity;
    private final String capabilityIdentity;
    private final Provenance provenance;
    private final List<ArtifactObservation> artifacts;
    private final int observationCount;
    private final int totalTextBytes;

    private DocumentObservationIR(
            String acquisitionPolicyIdentity,
            String capabilityIdentity,
            Provenance provenance,
            List<ArtifactObservation> artifacts,
            int observationCount,
            int totalTextBytes
    ) {
        this.contractVersion = VERSION;
        this.acquisitionPolicyIdentity = acquisitionPolicyIdentity;
        this.capabilityIdentity = capabilityIdentity;
        this.provenance = provenance;
        this.artifacts = artifacts;
        this.observationCount = observationCount;
        this.totalTextBytes = totalTextBytes;
    }

    public static DocumentObservationIR canonical(
            AcquisitionPolicy policy,
            Provenance provenance,
            List<ArtifactObservation> artifacts
    ) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(provenance, "provenance");
        provenance.requireMatches(policy);
        Objects.requireNonNull(artifacts, "artifacts");
        if (artifacts.isEmpty() || artifacts.size() > policy.maximumArtifacts()) {
            throw new IllegalArgumentException("DOCUMENT_OBSERVATION_ARTIFACT_LIMIT_EXCEEDED");
        }

        var orderedArtifacts = new ArrayList<>(artifacts);
        if (orderedArtifacts.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("DOCUMENT_OBSERVATION_ARTIFACT_INVALID");
        }
        orderedArtifacts.sort(Comparator.comparingInt(ArtifactObservation::sourceOrdinal));
        var artifactIds = new HashSet<String>();
        var artifactOrdinals = new HashSet<Integer>();
        var observationIds = new HashSet<String>();
        var canonicalArtifacts = new ArrayList<ArtifactObservation>(orderedArtifacts.size());
        var observationCount = 0;
        var totalTextBytes = 0;

        for (int artifactIndex = 0; artifactIndex < orderedArtifacts.size(); artifactIndex++) {
            var artifact = orderedArtifacts.get(artifactIndex);
            if (!artifactIds.add(artifact.artifactId()) || !artifactOrdinals.add(artifact.sourceOrdinal())) {
                throw new IllegalArgumentException("DOCUMENT_OBSERVATION_ARTIFACT_IDENTITY_DUPLICATED");
            }
            if (artifact.sourceOrdinal() != artifactIndex) {
                throw new IllegalArgumentException("DOCUMENT_OBSERVATION_ARTIFACT_ORDINAL_INVALID");
            }

            var canonicalLines = new ArrayList<TextLine>(artifact.observations().size());
            for (int lineIndex = 0; lineIndex < artifact.observations().size(); lineIndex++) {
                var line = artifact.observations().get(lineIndex);
                if (line.canonicalOrder() != lineIndex
                        || !line.observationId().equals("ocr-%02d-%03d".formatted(
                        artifact.sourceOrdinal(), lineIndex))) {
                    throw new IllegalArgumentException("DOCUMENT_OBSERVATION_ORDER_INVALID");
                }
                if (!observationIds.add(line.observationId())) {
                    throw new IllegalArgumentException("DOCUMENT_OBSERVATION_ID_DUPLICATED");
                }
                line.sourcePixelBox().requireWithin(artifact.width(), artifact.height());
                line.confidence().requireMatches(policy);
                var lineBytes = line.text().getBytes(StandardCharsets.UTF_8).length;
                if (lineBytes > policy.maximumLineTextBytes()) {
                    throw new IllegalArgumentException("DOCUMENT_OBSERVATION_TEXT_LIMIT_EXCEEDED");
                }
                totalTextBytes = Math.addExact(totalTextBytes, lineBytes);
                if (totalTextBytes > policy.maximumTotalTextBytes()) {
                    throw new IllegalArgumentException("DOCUMENT_OBSERVATION_TEXT_LIMIT_EXCEEDED");
                }
                canonicalLines.add(line);
                observationCount++;
                if (observationCount > policy.maximumObservations()) {
                    throw new IllegalArgumentException("DOCUMENT_OBSERVATION_COUNT_LIMIT_EXCEEDED");
                }
            }
            canonicalArtifacts.add(artifact.withObservations(canonicalLines));
        }

        return new DocumentObservationIR(
                policy.identity(), policy.capabilityIdentity(), provenance,
                List.copyOf(canonicalArtifacts), observationCount, totalTextBytes
        );
    }

    public String contractVersion() {
        return contractVersion;
    }

    public String acquisitionPolicyIdentity() {
        return acquisitionPolicyIdentity;
    }

    public String capabilityIdentity() {
        return capabilityIdentity;
    }

    public Provenance provenance() {
        return provenance;
    }

    public List<ArtifactObservation> artifacts() {
        return artifacts;
    }

    public int observationCount() {
        return observationCount;
    }

    public int totalTextBytes() {
        return totalTextBytes;
    }

    @Override
    public String toString() {
        return "DocumentObservationIR[contractVersion=" + contractVersion
                + ", acquisitionPolicyIdentity=" + acquisitionPolicyIdentity
                + ", capabilityIdentity=" + capabilityIdentity + ", artifactCount=" + artifacts.size()
                + ", observationCount=" + observationCount + ", totalTextBytes=" + totalTextBytes + "]";
    }

    public record ArtifactObservation(
            String artifactId,
            int sourceOrdinal,
            String mediaType,
            int width,
            int height,
            boolean orientationApplied,
            List<TextLine> observations
    ) {
        public ArtifactObservation {
            if (artifactId == null || !artifactId.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("DOCUMENT_OBSERVATION_ARTIFACT_ID_INVALID");
            }
            if (sourceOrdinal < 0 || sourceOrdinal >= ArtifactSet.MAXIMUM_ARTIFACTS) {
                throw new IllegalArgumentException("DOCUMENT_OBSERVATION_ARTIFACT_ORDINAL_INVALID");
            }
            if (!"image/png".equals(mediaType) && !"image/jpeg".equals(mediaType)) {
                throw new IllegalArgumentException("DOCUMENT_OBSERVATION_MEDIA_TYPE_UNSUPPORTED");
            }
            if (width < 1 || height < 1 || width > 4_096 || height > 4_096
                    || Math.multiplyExact((long) width, height) > 16_000_000L) {
                throw new IllegalArgumentException("DOCUMENT_OBSERVATION_ARTIFACT_BOUNDS_INVALID");
            }
            if (!orientationApplied) {
                throw new IllegalArgumentException("DOCUMENT_OBSERVATION_ORIENTATION_NOT_APPLIED");
            }
            Objects.requireNonNull(observations, "observations");
            var ordered = new ArrayList<>(observations);
            if (ordered.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("DOCUMENT_OBSERVATION_ENTRY_INVALID");
            }
            ordered.sort(Comparator.comparingInt(TextLine::canonicalOrder));
            observations = List.copyOf(ordered);
        }

        private ArtifactObservation withObservations(List<TextLine> canonicalObservations) {
            return new ArtifactObservation(
                    artifactId, sourceOrdinal, mediaType, width, height, orientationApplied, canonicalObservations
            );
        }

        @Override
        public String toString() {
            return "ArtifactObservation[artifactId=" + artifactId + ", sourceOrdinal=" + sourceOrdinal
                    + ", mediaType=" + mediaType + ", width=" + width + ", height=" + height
                    + ", orientationApplied=" + orientationApplied + ", observationCount="
                    + observations.size() + "]";
        }
    }

    public record TextLine(
            String observationId,
            int canonicalOrder,
            SourcePixelBox sourcePixelBox,
            Confidence confidence,
            String text,
            Sensitivity sensitivity
    ) {
        public TextLine {
            if (observationId == null || !observationId.matches("ocr-[0-9]{2}-[0-9]{3}")) {
                throw new IllegalArgumentException("DOCUMENT_OBSERVATION_ID_INVALID");
            }
            if (canonicalOrder < 0 || canonicalOrder >= 512) {
                throw new IllegalArgumentException("DOCUMENT_OBSERVATION_ORDER_INVALID");
            }
            Objects.requireNonNull(sourcePixelBox, "sourcePixelBox");
            Objects.requireNonNull(confidence, "confidence");
            text = canonicalText(text);
            if (sensitivity != Sensitivity.EPHEMERAL_UNTRUSTED) {
                throw new IllegalArgumentException("DOCUMENT_OBSERVATION_SENSITIVITY_INVALID");
            }
        }

        @Override
        public String toString() {
            return "TextLine[textBytes=" + text.getBytes(StandardCharsets.UTF_8).length
                    + ", sensitivity=" + sensitivity + ", payload=<redacted>]";
        }
    }

    public record SourcePixelBox(int left, int top, int right, int bottom) {
        public SourcePixelBox {
            if (left < 0 || top < 0 || right <= left || bottom <= top) {
                throw new IllegalArgumentException("DOCUMENT_OBSERVATION_BOX_INVALID");
            }
        }

        public SourcePixelBox requireWithin(int width, int height) {
            if (width < 1 || height < 1 || right > width || bottom > height) {
                throw new IllegalArgumentException("DOCUMENT_OBSERVATION_BOX_OUT_OF_BOUNDS");
            }
            return this;
        }

        @Override
        public String toString() {
            return "SourcePixelBox[payload=<redacted>]";
        }
    }

    public record Confidence(
            int nativeValueBps,
            String nativeScaleIdentity,
            ConfidenceBucket derivedBucket,
            String bucketProjectionIdentity
    ) {
        public Confidence {
            if (nativeValueBps < 0 || nativeValueBps > 10_000) {
                throw new IllegalArgumentException("DOCUMENT_OBSERVATION_CONFIDENCE_INVALID");
            }
            AcquisitionPolicyIdentity.require(nativeScaleIdentity,
                    "DOCUMENT_OBSERVATION_CONFIDENCE_SCALE_INVALID");
            Objects.requireNonNull(derivedBucket, "derivedBucket");
            AcquisitionPolicyIdentity.require(bucketProjectionIdentity,
                    "DOCUMENT_OBSERVATION_CONFIDENCE_PROJECTION_INVALID");
            var expected = nativeValueBps < 6_000 ? ConfidenceBucket.LOW
                    : nativeValueBps < 8_500 ? ConfidenceBucket.MEDIUM : ConfidenceBucket.HIGH;
            if (derivedBucket != expected) {
                throw new IllegalArgumentException("DOCUMENT_OBSERVATION_CONFIDENCE_BUCKET_INVALID");
            }
        }

        private void requireMatches(AcquisitionPolicy policy) {
            if (!nativeScaleIdentity.equals(policy.confidenceScaleIdentity())
                    || !bucketProjectionIdentity.equals(policy.confidenceBucketProjectionIdentity())) {
                throw new IllegalArgumentException("DOCUMENT_OBSERVATION_CONFIDENCE_IDENTITY_MISMATCH");
            }
        }

        @Override
        public String toString() {
            return "Confidence[payload=<redacted>]";
        }
    }

    public record Provenance(
            String capabilityIdentity,
            String adapterIdentity,
            String engine,
            String engineVersion,
            String modelManifestSha256,
            String preprocessingIdentity,
            String postprocessingIdentity,
            String readingOrderDerivationIdentity,
            String projectionIdentity,
            String confidenceScaleIdentity,
            String confidenceBucketProjectionIdentity,
            String canonicalizationIdentity
    ) {
        public Provenance {
            AcquisitionPolicyIdentity.require(capabilityIdentity,
                    "DOCUMENT_OBSERVATION_CAPABILITY_IDENTITY_INVALID");
            AcquisitionPolicyIdentity.require(adapterIdentity,
                    "DOCUMENT_OBSERVATION_ADAPTER_IDENTITY_INVALID");
            AcquisitionPolicyIdentity.require(engine, "DOCUMENT_OBSERVATION_ENGINE_INVALID");
            AcquisitionPolicyIdentity.require(engineVersion, "DOCUMENT_OBSERVATION_ENGINE_VERSION_INVALID");
            if (modelManifestSha256 == null || !modelManifestSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("DOCUMENT_OBSERVATION_MODEL_MANIFEST_INVALID");
            }
            AcquisitionPolicyIdentity.require(preprocessingIdentity,
                    "DOCUMENT_OBSERVATION_PREPROCESSING_IDENTITY_INVALID");
            AcquisitionPolicyIdentity.require(postprocessingIdentity,
                    "DOCUMENT_OBSERVATION_POSTPROCESSING_IDENTITY_INVALID");
            AcquisitionPolicyIdentity.require(readingOrderDerivationIdentity,
                    "DOCUMENT_OBSERVATION_ORDER_IDENTITY_INVALID");
            AcquisitionPolicyIdentity.require(projectionIdentity,
                    "DOCUMENT_OBSERVATION_PROJECTION_IDENTITY_INVALID");
            AcquisitionPolicyIdentity.require(confidenceScaleIdentity,
                    "DOCUMENT_OBSERVATION_CONFIDENCE_SCALE_INVALID");
            AcquisitionPolicyIdentity.require(confidenceBucketProjectionIdentity,
                    "DOCUMENT_OBSERVATION_CONFIDENCE_PROJECTION_INVALID");
            AcquisitionPolicyIdentity.require(canonicalizationIdentity,
                    "DOCUMENT_OBSERVATION_CANONICALIZATION_IDENTITY_INVALID");
        }

        private void requireMatches(AcquisitionPolicy policy) {
            if (!capabilityIdentity.equals(policy.capabilityIdentity())
                    || !adapterIdentity.equals(policy.adapterIdentity())
                    || !engine.equals(policy.engine())
                    || !engineVersion.equals(policy.engineVersion())
                    || !modelManifestSha256.equals(policy.modelManifestSha256())
                    || !preprocessingIdentity.equals(policy.preprocessingIdentity())
                    || !postprocessingIdentity.equals(policy.postprocessingIdentity())
                    || !readingOrderDerivationIdentity.equals(policy.readingOrderDerivationIdentity())
                    || !projectionIdentity.equals(policy.projectionIdentity())
                    || !confidenceScaleIdentity.equals(policy.confidenceScaleIdentity())
                    || !confidenceBucketProjectionIdentity.equals(policy.confidenceBucketProjectionIdentity())
                    || !canonicalizationIdentity.equals(policy.canonicalizationIdentity())) {
                throw new IllegalArgumentException("DOCUMENT_OBSERVATION_PROVENANCE_MISMATCH");
            }
        }
    }

    public enum ConfidenceBucket {
        LOW, MEDIUM, HIGH
    }

    public enum Sensitivity {
        EPHEMERAL_UNTRUSTED
    }

    private static String canonicalText(String value) {
        if (value == null) {
            throw new IllegalArgumentException("DOCUMENT_OBSERVATION_TEXT_INVALID");
        }
        var normalized = Normalizer.normalize(value, Normalizer.Form.NFC)
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.isEmpty() || normalized.chars().anyMatch(character ->
                Character.isISOControl(character))) {
            throw new IllegalArgumentException("DOCUMENT_OBSERVATION_TEXT_INVALID");
        }
        return normalized;
    }

    private static final class AcquisitionPolicyIdentity {
        private AcquisitionPolicyIdentity() {
        }

        private static void require(String value, String code) {
            if (value == null || value.isBlank() || value.length() > 160
                    || value.chars().anyMatch(character -> Character.isISOControl(character)
                    || Character.isWhitespace(character))) {
                throw new IllegalArgumentException(code);
            }
        }
    }
}
