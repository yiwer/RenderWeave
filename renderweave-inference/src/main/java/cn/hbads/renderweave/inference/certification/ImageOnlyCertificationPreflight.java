package cn.hbads.renderweave.inference.certification;

import cn.hbads.renderweave.inference.provider.ProfileRunBudgetPolicy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class ImageOnlyCertificationPreflight {
    private static final Duration MAXIMUM_WINDOW = Duration.ofHours(48);
    private static final Duration MAXIMUM_DIAGNOSTIC_WINDOW = Duration.ofHours(2);
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
        if (!authorization.stage().scored()) {
            fail("CERTIFICATION_AUTHORIZATION_STAGE_NOT_SCORING");
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
        if (authorization.approvedAt().isBefore(cycle.createdAt())) {
            fail("CERTIFICATION_AUTHORIZATION_APPROVAL_PREDATES_CYCLE");
        }
        if (!authorization.expiresAt().isAfter(authorization.approvedAt())
                || Duration.between(authorization.approvedAt(), authorization.expiresAt())
                .compareTo(MAXIMUM_WINDOW) > 0) {
            fail("CERTIFICATION_AUTHORIZATION_DURATION_INVALID");
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
        if (!ProfileRunBudgetPolicy.IMAGE_ONLY_V47_PROFILE_ID.equals(authorization.profileId())
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
                || authorization.maximumProviderCallsPerRun() < 1
                || authorization.maximumProviderCallsPerRun() > 12
                || authorization.maximumProviderCalls()
                > authorization.maximumRuns() * authorization.maximumProviderCallsPerRun()) {
            fail("CERTIFICATION_AUTHORIZATION_CALL_CAP_INVALID");
        }
        if (authorization.maximumModelTokens() < 1
                || authorization.maximumModelTokens() > MAXIMUM_MODEL_TOKENS) {
            fail("CERTIFICATION_AUTHORIZATION_TOKEN_CAP_INVALID");
        }
        if (authorization.maximumCostMicrosCny() < 1
                || authorization.maximumCostPerRunMicrosCny() < 1
                || authorization.maximumCostPerRunMicrosCny() > 6_000_000L
                || authorization.maximumCostMicrosCny()
                > authorization.maximumRuns() * authorization.maximumCostPerRunMicrosCny()) {
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

    public CertificationPreflightProof requireProfileSuccessorDiagnosticProviderZeroProof(
            ImageOnlyCertificationAuthorization authorization,
            ProfileSuccessorDiagnosticManifest manifest,
            Instant now
    ) {
        if (authorization == null) fail("CERTIFICATION_AUTHORIZATION_REQUIRED");
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(now, "now");
        if (authorization.status() != AuthorizationStatus.OPEN) {
            fail("CERTIFICATION_AUTHORIZATION_NOT_OPEN");
        }
        if (authorization.stage() != CertificationStage.PROFILE_SUCCESSOR_DIAGNOSTIC_1) {
            fail("PROFILE_SUCCESSOR_DIAGNOSTIC_STAGE_MISMATCH");
        }
        if (now.isBefore(authorization.effectiveAt())) {
            fail("CERTIFICATION_AUTHORIZATION_NOT_YET_EFFECTIVE");
        }
        if (!now.isBefore(authorization.expiresAt())) {
            fail("CERTIFICATION_AUTHORIZATION_EXPIRED");
        }
        if (!authorization.expiresAt().isAfter(authorization.effectiveAt())
                || Duration.between(authorization.effectiveAt(), authorization.expiresAt())
                .compareTo(MAXIMUM_DIAGNOSTIC_WINDOW) > 0
                || authorization.approvedAt().isAfter(authorization.effectiveAt())
                || authorization.approvedAt().isBefore(manifest.createdAt())
                || !authorization.expiresAt().isAfter(authorization.approvedAt())
                || Duration.between(authorization.approvedAt(), authorization.expiresAt())
                .compareTo(MAXIMUM_DIAGNOSTIC_WINDOW) > 0) {
            fail("PROFILE_SUCCESSOR_DIAGNOSTIC_AUTHORIZATION_WINDOW_INVALID");
        }
        if (!authorization.cycleId().equals(manifest.cycleId())) {
            fail("PROFILE_SUCCESSOR_DIAGNOSTIC_CYCLE_MISMATCH");
        }
        if (!manifest.profileId().equals(authorization.profileId())
                || !manifest.profileSha256().equals(authorization.profileSha256())) {
            fail("PROFILE_SUCCESSOR_DIAGNOSTIC_PROFILE_MISMATCH");
        }
        if (!manifest.manifestIdentity().equals(authorization.manifestIdentity())) {
            fail("PROFILE_SUCCESSOR_DIAGNOSTIC_MANIFEST_MISMATCH");
        }
        if (!manifest.evaluatorIdentity().equals(authorization.evaluatorIdentity())) {
            fail("PROFILE_SUCCESSOR_DIAGNOSTIC_EVALUATOR_MISMATCH");
        }
        if (((ProfileRunBudgetPolicy.IMAGE_ONLY_V49_PROFILE_ID.equals(manifest.profileId())
                || ProfileRunBudgetPolicy.IMAGE_ONLY_V50_PROFILE_ID.equals(manifest.profileId())
                || ProfileRunBudgetPolicy.IMAGE_ONLY_V51_PROFILE_ID.equals(manifest.profileId())
                || ProfileRunBudgetPolicy.IMAGE_ONLY_V52_PROFILE_ID.equals(manifest.profileId()))
                && authorization.normalizationIdentity() == null)
                || (authorization.normalizationIdentity() != null
                && !manifest.normalizationIdentity().equals(
                authorization.normalizationIdentity()))) {
            fail("PROFILE_SUCCESSOR_DIAGNOSTIC_NORMALIZATION_MISMATCH");
        }
        if (!"DASHSCOPE".equals(authorization.provider())
                || !"qwen3.8-max".equals(authorization.model())
                || !"https://dashscope.aliyuncs.com/compatible-mode/v1".equals(
                authorization.providerBaseUrl())) {
            fail("PROFILE_SUCCESSOR_DIAGNOSTIC_PROVIDER_MISMATCH");
        }
        if (!manifest.inputProvenance().equals(authorization.inputProvenance())
                || !manifest.dataClassification().equals(authorization.dataClassification())) {
            fail("PROFILE_SUCCESSOR_DIAGNOSTIC_DATA_CLASS_MISMATCH");
        }
        if (!sameCases(List.of(manifest.diagnosticCase()), authorization.cases())) {
            fail("PROFILE_SUCCESSOR_DIAGNOSTIC_CASE_MISMATCH");
        }
        if (authorization.maximumRuns() != 1
                || authorization.maximumProviderCalls() != 5
                || authorization.maximumModelTokens() != 100_000L
                || authorization.maximumCostMicrosCny() != 3_000_000L
                || authorization.maximumProviderCallsPerRun() != 5
                || authorization.maximumCostPerRunMicrosCny() != 3_000_000L) {
            fail("PROFILE_SUCCESSOR_DIAGNOSTIC_CAPS_MISMATCH");
        }
        if (!"IMAGE_ONLY_PROFILE_SUCCESSOR_DIAGNOSTIC_1".equals(
                authorization.approvalScope())) {
            fail("PROFILE_SUCCESSOR_DIAGNOSTIC_SCOPE_MISMATCH");
        }
        return new CertificationPreflightProof(
                authorization.authorizationId(), authorization.cycleId(), authorization.stage(),
                authorization.profileSha256(), authorization.manifestIdentity(), 0, 0, 0, 0,
                false
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
