package cn.hbads.renderweave.inference.vision;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Immutable identity and bounds for one visual-evidence acquisition call. */
public record AcquisitionPolicy(
        String policyVersion,
        String observationContractVersion,
        String capabilityIdentity,
        String adapterIdentity,
        String engine,
        String engineVersion,
        String modelManifestSha256,
        String preprocessingIdentity,
        String postprocessingIdentity,
        String coordinateSpaceIdentity,
        String boxSemanticsIdentity,
        String projectionIdentity,
        String readingOrderDerivationIdentity,
        String canonicalizationIdentity,
        String confidenceScaleIdentity,
        String confidenceBucketProjectionIdentity,
        TextExposure textExposure,
        int maximumArtifacts,
        int maximumObservations,
        int maximumLineTextBytes,
        int maximumTotalTextBytes,
        int maximumResponseBytes,
        int timeoutMillis
) {
    public static final String VERSION = "AcquisitionPolicy/1.0";

    public AcquisitionPolicy {
        if (!VERSION.equals(policyVersion)) {
            throw new IllegalArgumentException("ACQUISITION_POLICY_VERSION_UNSUPPORTED");
        }
        if (!DocumentObservationIR.VERSION.equals(observationContractVersion)) {
            throw new IllegalArgumentException("DOCUMENT_OBSERVATION_VERSION_UNSUPPORTED");
        }
        requireIdentity(capabilityIdentity, "ACQUISITION_CAPABILITY_IDENTITY_INVALID");
        requireIdentity(adapterIdentity, "ACQUISITION_ADAPTER_IDENTITY_INVALID");
        requireIdentity(engine, "ACQUISITION_ENGINE_IDENTITY_INVALID");
        requireIdentity(engineVersion, "ACQUISITION_ENGINE_VERSION_INVALID");
        if (modelManifestSha256 == null || !modelManifestSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("ACQUISITION_MODEL_MANIFEST_INVALID");
        }
        requireIdentity(preprocessingIdentity, "ACQUISITION_PREPROCESSING_IDENTITY_INVALID");
        requireIdentity(postprocessingIdentity, "ACQUISITION_POSTPROCESSING_IDENTITY_INVALID");
        requireIdentity(coordinateSpaceIdentity, "ACQUISITION_COORDINATE_IDENTITY_INVALID");
        requireIdentity(boxSemanticsIdentity, "ACQUISITION_BOX_SEMANTICS_IDENTITY_INVALID");
        requireIdentity(projectionIdentity, "ACQUISITION_PROJECTION_IDENTITY_INVALID");
        requireIdentity(readingOrderDerivationIdentity, "ACQUISITION_ORDER_IDENTITY_INVALID");
        requireIdentity(canonicalizationIdentity, "ACQUISITION_CANONICALIZATION_IDENTITY_INVALID");
        requireIdentity(confidenceScaleIdentity, "ACQUISITION_CONFIDENCE_SCALE_INVALID");
        requireIdentity(confidenceBucketProjectionIdentity, "ACQUISITION_CONFIDENCE_PROJECTION_INVALID");
        if (textExposure != TextExposure.EPHEMERAL_STAGE_CONTEXT_ONLY) {
            throw new IllegalArgumentException("ACQUISITION_TEXT_EXPOSURE_INVALID");
        }
        if (maximumArtifacts < 1 || maximumArtifacts > ArtifactSet.MAXIMUM_ARTIFACTS
                || maximumObservations < 1 || maximumObservations > 512
                || maximumLineTextBytes < 1 || maximumLineTextBytes > 256
                || maximumTotalTextBytes < maximumLineTextBytes || maximumTotalTextBytes > 32 * 1024
                || maximumResponseBytes < 1 || maximumResponseBytes > 512 * 1024
                || timeoutMillis < 1 || timeoutMillis > 60_000) {
            throw new IllegalArgumentException("ACQUISITION_POLICY_BOUNDS_INVALID");
        }
    }

    public String identity() {
        var fields = List.of(
                policyVersion, observationContractVersion, capabilityIdentity, adapterIdentity,
                engine, engineVersion, modelManifestSha256, preprocessingIdentity,
                postprocessingIdentity, coordinateSpaceIdentity, boxSemanticsIdentity,
                projectionIdentity, readingOrderDerivationIdentity, canonicalizationIdentity,
                confidenceScaleIdentity, confidenceBucketProjectionIdentity, textExposure.name(),
                Integer.toString(maximumArtifacts), Integer.toString(maximumObservations),
                Integer.toString(maximumLineTextBytes), Integer.toString(maximumTotalTextBytes),
                Integer.toString(maximumResponseBytes), Integer.toString(timeoutMillis)
        );
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update("renderweave-acquisition-policy\u0000".getBytes(StandardCharsets.UTF_8));
            for (var field : fields) {
                var encoded = field.getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(encoded.length).getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) ':');
                digest.update(encoded);
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA_256_UNAVAILABLE", exception);
        }
    }

    @Override
    public String toString() {
        return "AcquisitionPolicy[version=" + policyVersion + ", identity=" + identity()
                + ", capabilityIdentity=" + capabilityIdentity + ", adapterIdentity=" + adapterIdentity
                + ", observationLimit=" + maximumObservations + ", textExposure=" + textExposure + "]";
    }

    private static void requireIdentity(String value, String code) {
        Objects.requireNonNull(code, "code");
        if (value == null || value.isBlank() || value.length() > 160
                || value.chars().anyMatch(character -> Character.isISOControl(character)
                || Character.isWhitespace(character))) {
            throw new IllegalArgumentException(code);
        }
    }

    public enum TextExposure {
        EPHEMERAL_STAGE_CONTEXT_ONLY
    }
}
