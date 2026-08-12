package cn.hbads.renderweave.inference.vision;

import java.util.Objects;

/** Single primary seam for acquiring provider-neutral observations from normalized images. */
@FunctionalInterface
public interface VisualEvidenceAcquisition {
    DocumentObservationIR acquire(ArtifactSet artifactSet, AcquisitionPolicy policy);

    static VisualEvidenceAcquisition unavailable(String code) {
        Objects.requireNonNull(code, "code");
        return new VisualEvidenceAcquisition() {
            @Override
            public DocumentObservationIR acquire(ArtifactSet artifactSet, AcquisitionPolicy policy) {
                Objects.requireNonNull(artifactSet, "artifactSet");
                Objects.requireNonNull(policy, "policy");
                throw new VisualEvidenceAcquisitionException(code);
            }

            @Override
            public String toString() {
                return "VisualEvidenceAcquisition[status=unavailable, code=" + code + "]";
            }
        };
    }
}
