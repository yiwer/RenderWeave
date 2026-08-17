package cn.hbads.renderweave.inference.certification;

import cn.hbads.renderweave.inference.provider.ProfileRunBudgetPolicy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class ImageOnlyCertificationPreflight {
    private static final Duration MAXIMUM_WINDOW = Duration.ofHours(48);
    private static final long MAXIMUM_MODEL_TOKENS = 1_000_000L;

    public CertificationPreflightProof requireProviderZeroProof(
            ImageOnlyCertificationAuthorization authorization,
            FrozenCertificationCycle cycle,
            FrozenImageOnlyCertificationManifest manifest,
            ProfileCertificationProgress progress,
            Instant now
    ) {
        if (authorization == null) fail("CERTIFICATION_AUTHORIZATION_REQUIRED");
        Objects.requireNonNull(cycle, "cycle");
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(progress, "progress");
        Objects.requireNonNull(now, "now");
        if (authorization.status() != AuthorizationStatus.OPEN) {
            fail("CERTIFICATION_AUTHORIZATION_NOT_OPEN");
        }
        if (progress.status() != ProfileCertificationStatus.IN_PROGRESS) {
            fail("CERTIFICATION_AUTHORIZATION_CYCLE_TERMINAL");
        }
        if (now.isBefore(authorization.effectiveAt())) {
            fail("CERTIFICATION_AUTHORIZATION_NOT_YET_EFFECTIVE");
        }
        if (!now.isBefore(authorization.expiresAt())) {
            fail("CERTIFICATION_AUTHORIZATION_EXPIRED");
        }
        if (!authorization.expiresAt().isAfter(authorization.effectiveAt())
                || Duration.between(authorization.effectiveAt(), authorization.expiresAt())
                .compareTo(MAXIMUM_WINDOW) > 0) {
            fail("CERTIFICATION_AUTHORIZATION_DURATION_INVALID");
        }
        if (authorization.approvedAt().isAfter(authorization.effectiveAt())) {
            fail("CERTIFICATION_AUTHORIZATION_APPROVAL_INVALID");
        }
        if (!authorization.cycleId().equals(cycle.cycleId())) {
            fail("CERTIFICATION_AUTHORIZATION_CYCLE_MISMATCH");
        }
        if (!progress.cycleId().equals(cycle.cycleId())
                || !progress.profileSha256().equals(cycle.profileSha256())
                || !progress.manifestIdentity().equals(cycle.manifestIdentity())
                || !progress.evaluatorIdentity().equals(cycle.evaluatorIdentity())
                || !progress.authorityInventorySha256().equals(cycle.authorityInventorySha256())) {
            fail("CERTIFICATION_AUTHORIZATION_PROGRESS_MISMATCH");
        }
        if (authorization.stage() != progress.nextStage()) {
            fail("CERTIFICATION_AUTHORIZATION_STAGE_NOT_UNLOCKED");
        }
        if (!cycle.profileId().equals(manifest.profileId())
                || !cycle.profileSha256().equals(manifest.profileSha256())
                || !cycle.manifestIdentity().equals(manifest.manifestIdentity())
                || !cycle.evaluatorIdentity().equals(manifest.evaluatorIdentity())) {
            fail("CERTIFICATION_CYCLE_MANIFEST_MISMATCH");
        }
        if (!ProfileRunBudgetPolicy.IMAGE_ONLY_V46_PROFILE_ID.equals(authorization.profileId())
                || !cycle.profileId().equals(authorization.profileId())
                || !cycle.profileSha256().equals(authorization.profileSha256())) {
            fail("CERTIFICATION_AUTHORIZATION_PROFILE_MISMATCH");
        }
        if (!manifest.manifestIdentity().equals(authorization.manifestIdentity())) {
            fail("CERTIFICATION_AUTHORIZATION_MANIFEST_MISMATCH");
        }
        if (!manifest.evaluatorIdentity().equals(authorization.evaluatorIdentity())) {
            fail("CERTIFICATION_AUTHORIZATION_EVALUATOR_MISMATCH");
        }
        if (!"DASHSCOPE".equals(authorization.provider())
                || !"qwen3.8-max".equals(authorization.model())
                || !"https://dashscope.aliyuncs.com/compatible-mode/v1".equals(
                authorization.providerBaseUrl())) {
            fail("CERTIFICATION_AUTHORIZATION_PROVIDER_MISMATCH");
        }
        if (!"USER_PROVIDED".equals(authorization.inputProvenance())
                || !"ORDINARY_DESIGN".equals(authorization.dataClassification())) {
            fail("CERTIFICATION_AUTHORIZATION_DATA_CLASS_NOT_ALLOWED");
        }
        var expectedCases = manifest.stageView(authorization.stage()).cases().stream()
                .map(item -> new AuthorizedCertificationCase(item.caseId(), item.caseSha256())).toList();
        if (!sameCases(expectedCases, authorization.cases())) {
            fail("CERTIFICATION_AUTHORIZATION_CASE_SET_MISMATCH");
        }
        if (authorization.maximumRuns() != authorization.stage().caseCount()) {
            fail("CERTIFICATION_AUTHORIZATION_RUN_CAP_INVALID");
        }
        if (authorization.maximumProviderCalls() < 1
                || authorization.maximumProviderCalls() > authorization.maximumRuns() * 12) {
            fail("CERTIFICATION_AUTHORIZATION_CALL_CAP_INVALID");
        }
        if (authorization.maximumModelTokens() < 1
                || authorization.maximumModelTokens() > MAXIMUM_MODEL_TOKENS) {
            fail("CERTIFICATION_AUTHORIZATION_TOKEN_CAP_INVALID");
        }
        if (authorization.maximumCostMicrosCny() < 1
                || authorization.maximumCostMicrosCny() > authorization.maximumRuns() * 6_000_000L) {
            fail("CERTIFICATION_AUTHORIZATION_COST_CAP_INVALID");
        }
        if (!("IMAGE_ONLY_PROFILE_CERTIFICATION_" + authorization.stage().name())
                .equals(authorization.approvalScope())) {
            fail("CERTIFICATION_AUTHORIZATION_SCOPE_MISMATCH");
        }
        return new CertificationPreflightProof(
                authorization.authorizationId(), authorization.cycleId(), authorization.stage(),
                authorization.profileSha256(), authorization.manifestIdentity(), 0, 0, 0, 0, false
        );
    }

    private static boolean sameCases(
            List<AuthorizedCertificationCase> expected,
            List<AuthorizedCertificationCase> actual
    ) {
        return expected.size() == actual.size()
                && new java.util.HashSet<>(expected).equals(new java.util.HashSet<>(actual));
    }

    private static void fail(String reasonCode) {
        throw new CertificationAuthorizationViolation(reasonCode);
    }
}
