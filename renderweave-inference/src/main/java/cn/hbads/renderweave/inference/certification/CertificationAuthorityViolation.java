package cn.hbads.renderweave.inference.certification;

import java.util.Objects;

public final class CertificationAuthorityViolation extends IllegalArgumentException {
    private final String reasonCode;
    private final String referenceId;

    CertificationAuthorityViolation(String reasonCode, String referenceId) {
        super(Objects.requireNonNull(reasonCode, "reasonCode"));
        this.reasonCode = reasonCode;
        this.referenceId = Objects.requireNonNull(referenceId, "referenceId");
    }

    public String reasonCode() {
        return reasonCode;
    }

    public String referenceId() {
        return referenceId;
    }
}
