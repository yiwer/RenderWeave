package cn.hbads.renderweave.inference.certification;

import cn.hbads.renderweave.inference.provider.ProfileRunBudgetPolicy;

import java.util.List;
import java.util.Objects;

public final class FrozenImageOnlyCertificationManifest {
    public static final String VERSION = "renderweave-image-only-certification-manifest/1.0";

    private final String manifestIdentity;
    private final String profileSha256;
    private final String corpusIdentity;
    private final String evaluatorIdentity;
    private final String r1InfrastructureIdentity;
    private final String assignmentSeed;
    private final List<CertificationCanaryCase> canaries;
    private final List<CertificationCaseAssignment> assignments;

    FrozenImageOnlyCertificationManifest(
            String manifestIdentity,
            String profileSha256,
            String corpusIdentity,
            String evaluatorIdentity,
            String r1InfrastructureIdentity,
            String assignmentSeed,
            List<CertificationCanaryCase> canaries,
            List<CertificationCaseAssignment> assignments
    ) {
        this.manifestIdentity = requireIdentity(manifestIdentity, VERSION);
        CertificationCanaryCase.requireSha(profileSha256);
        this.profileSha256 = profileSha256;
        this.corpusIdentity = requireIdentity(corpusIdentity, "renderweave-visual-stage-corpus/2.0");
        this.evaluatorIdentity = requireIdentity(evaluatorIdentity,
                ImageOnlyCertificationManifestFactory.EVALUATOR_VERSION);
        if (!"renderweave-layered-r1-evaluation/1.0".equals(r1InfrastructureIdentity)) {
            throw new IllegalArgumentException("CERTIFICATION_R1_IDENTITY_INVALID");
        }
        this.r1InfrastructureIdentity = r1InfrastructureIdentity;
        if (assignmentSeed == null || !assignmentSeed.matches("[a-z0-9][a-z0-9-]{2,95}")) {
            throw new IllegalArgumentException("CERTIFICATION_ASSIGNMENT_SEED_INVALID");
        }
        this.assignmentSeed = assignmentSeed;
        this.canaries = List.copyOf(Objects.requireNonNull(canaries, "canaries"));
        this.assignments = List.copyOf(Objects.requireNonNull(assignments, "assignments"));
        if (this.canaries.size() != 5 || this.assignments.size() != 60) {
            throw new IllegalArgumentException("CERTIFICATION_MANIFEST_CASE_COUNT_INVALID");
        }
    }

    public String manifestIdentity() { return manifestIdentity; }
    public String profileId() { return ProfileRunBudgetPolicy.IMAGE_ONLY_V46_PROFILE_ID; }
    public String profileSha256() { return profileSha256; }
    public String corpusIdentity() { return corpusIdentity; }
    public String evaluatorIdentity() { return evaluatorIdentity; }
    public String r1InfrastructureIdentity() { return r1InfrastructureIdentity; }
    String assignmentSeed() { return assignmentSeed; }
    /* Full case projections are verifier-only and deliberately stay package-private. */
    List<CertificationCanaryCase> canariesForIndependentReplay() { return canaries; }
    List<CertificationCaseAssignment> assignmentsForIndependentReplay() { return assignments; }

    CertificationStageView stageView(CertificationStage stage) {
        Objects.requireNonNull(stage, "stage");
        List<CertificationStageCase> cases;
        if (stage == CertificationStage.CANARY_5) {
            cases = canaries.stream().map(item -> new CertificationStageCase(
                    item.caseId(), item.artifactSha256(), "image-sha256:" + item.artifactSha256())).toList();
        } else {
            var selected = stage == CertificationStage.DEV_20
                    ? assignments.stream().filter(item -> item.role() == CertificationCaseRole.DEV_VISIBLE)
                    : assignments.stream();
            cases = selected.map(item -> new CertificationStageCase(
                    item.caseId(), item.caseSha256(), item.caseIdentity())).toList();
        }
        return new CertificationStageView(stage, stage.acceptanceThreshold(), cases);
    }

    private static String requireIdentity(String value, String version) {
        if (value == null || !value.matches(java.util.regex.Pattern.quote(version) + ":[0-9a-f]{64}")) {
            throw new IllegalArgumentException("CERTIFICATION_MANIFEST_IDENTITY_INVALID");
        }
        return value;
    }
}
