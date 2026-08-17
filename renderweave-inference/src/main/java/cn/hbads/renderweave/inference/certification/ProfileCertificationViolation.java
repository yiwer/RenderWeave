package cn.hbads.renderweave.inference.certification;

public final class ProfileCertificationViolation extends IllegalStateException {
    private final String reasonCode;

    ProfileCertificationViolation(String reasonCode) {
        super(reasonCode);
        this.reasonCode = reasonCode;
    }

    public String reasonCode() {
        return reasonCode;
    }
}
