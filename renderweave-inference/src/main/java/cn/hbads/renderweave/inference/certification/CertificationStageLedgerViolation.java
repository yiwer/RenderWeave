package cn.hbads.renderweave.inference.certification;

public final class CertificationStageLedgerViolation extends RuntimeException {
    private final String reasonCode;

    public CertificationStageLedgerViolation(String reasonCode) {
        super(reasonCode);
        this.reasonCode = reasonCode;
    }

    public String reasonCode() {
        return reasonCode;
    }
}
