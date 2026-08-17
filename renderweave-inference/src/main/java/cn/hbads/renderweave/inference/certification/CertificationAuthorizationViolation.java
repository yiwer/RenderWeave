package cn.hbads.renderweave.inference.certification;

public final class CertificationAuthorizationViolation extends IllegalStateException {
    private final String reasonCode;

    CertificationAuthorizationViolation(String reasonCode) {
        super(reasonCode);
        this.reasonCode = reasonCode;
    }

    public String reasonCode() {
        return reasonCode;
    }
}
