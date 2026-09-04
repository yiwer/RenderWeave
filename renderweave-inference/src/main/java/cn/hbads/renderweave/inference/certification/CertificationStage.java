package cn.hbads.renderweave.inference.certification;

public enum CertificationStage {
    CANARY_5(5, 5, true),
    DEV_20(20, 18, true),
    FINAL_60(60, 54, true),
    PROFILE_SUCCESSOR_DIAGNOSTIC_1(1, 1, false);

    private static final CertificationStage[] SCORED_STAGES = {
            CANARY_5, DEV_20, FINAL_60
    };

    private final int caseCount;
    private final int acceptanceThreshold;
    private final boolean scored;

    CertificationStage(int caseCount, int acceptanceThreshold, boolean scored) {
        this.caseCount = caseCount;
        this.acceptanceThreshold = acceptanceThreshold;
        this.scored = scored;
    }

    public int caseCount() {
        return caseCount;
    }

    public int acceptanceThreshold() {
        return acceptanceThreshold;
    }

    public boolean scored() {
        return scored;
    }

    public static CertificationStage[] scoredStages() {
        return SCORED_STAGES.clone();
    }
}
