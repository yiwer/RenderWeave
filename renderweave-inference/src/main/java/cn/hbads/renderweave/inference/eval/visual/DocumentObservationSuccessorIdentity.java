package cn.hbads.renderweave.inference.eval.visual;

import cn.hbads.renderweave.inference.vision.AcquisitionPolicy;
import cn.hbads.renderweave.inference.vision.DocumentObservationCompatibilityProjection;
import cn.hbads.renderweave.inference.vision.DocumentObservationIR;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Additive, payload-free R0 identity; it does not replace any immutable product-v45 identity. */
public final class DocumentObservationSuccessorIdentity {
    public static final String VERSION = "renderweave-document-observation-successor/1.0";

    private static final StageResponseShapeCatalog SHAPES = new StageResponseShapeCatalog();

    private final AcquisitionPolicy policy;
    private final String identity;

    public DocumentObservationSuccessorIdentity(AcquisitionPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
        if (!DocumentObservationIR.VERSION.equals(policy.observationContractVersion())
                || !DocumentObservationCompatibilityProjection.VERSION.equals(policy.projectionIdentity())) {
            throw new IllegalArgumentException("DOCUMENT_OBSERVATION_SUCCESSOR_IDENTITY_MISMATCH");
        }
        this.identity = VERSION + ":" + sha256(material());
    }

    public String identity() {
        return identity;
    }

    public String acquisitionPolicyIdentity() {
        return policy.identity();
    }

    public String capabilityIdentity() {
        return policy.capabilityIdentity();
    }

    public String observationContractVersion() {
        return policy.observationContractVersion();
    }

    public String projectionIdentity() {
        return policy.projectionIdentity();
    }

    public String shapeCatalogIdentity() {
        return SHAPES.identity();
    }

    @Override
    public String toString() {
        return "DocumentObservationSuccessorIdentity[version=" + VERSION + ", identity=" + identity
                + ", acquisitionPolicyIdentity=" + acquisitionPolicyIdentity()
                + ", capabilityIdentity=" + capabilityIdentity()
                + ", shapeCatalogIdentity=" + shapeCatalogIdentity() + "]";
    }

    private List<String> material() {
        return List.of(
                VERSION,
                policy.observationContractVersion(),
                policy.identity(),
                policy.capabilityIdentity(),
                policy.adapterIdentity(),
                policy.engine(),
                policy.engineVersion(),
                policy.modelManifestSha256(),
                policy.preprocessingIdentity(),
                policy.postprocessingIdentity(),
                policy.coordinateSpaceIdentity(),
                policy.boxSemanticsIdentity(),
                policy.projectionIdentity(),
                policy.readingOrderDerivationIdentity(),
                policy.canonicalizationIdentity(),
                policy.confidenceScaleIdentity(),
                policy.confidenceBucketProjectionIdentity(),
                SHAPES.catalogVersion(),
                SHAPES.identity()
        );
    }

    private static String sha256(List<String> values) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var value : values) {
                var encoded = value.getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(encoded.length).getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) ':');
                digest.update(encoded);
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA_256_UNAVAILABLE", failure);
        }
    }
}
