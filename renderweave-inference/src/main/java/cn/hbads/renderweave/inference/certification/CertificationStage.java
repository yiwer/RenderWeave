package cn.hbads.renderweave.inference.certification;

public enum CertificationStage {
    CANARY_5(5, 5),
    DEV_20(20, 18),
    FINAL_60(60, 54);

    private final int caseCount;
    private final int acceptanceThreshold;

    CertificationStage(int caseCount, int acceptanceThreshold) {
        this.caseCount = caseCount;
        this.acceptanceThreshold = acceptanceThreshold;
    }

    public int caseCount() {
        return caseCount;
    }

    public int acceptanceThreshold() {
        return acceptanceThreshold;
    }
}
