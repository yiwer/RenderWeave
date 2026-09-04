package cn.hbads.renderweave.inference.certification;

import cn.hbads.renderweave.inference.provider.ProfileRunBudgetPolicy;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record CertificationStageLedgerSnapshot(
        String authorizationId,
        UUID cycleId,
        String profileId,
        String profileSha256,
        CertificationStage stage,
        CertificationStageLedgerStatus status,
        int maximumRuns,
        int startedRuns,
        int maximumProviderCalls,
        int providerCalls,
        long maximumModelTokens,
        long exposedModelTokens,
        long maximumCostMicrosCny,
        long exposedCostMicrosCny,
        int maximumProviderCallsPerRun,
        long maximumCostPerRunMicrosCny,
        int unsettledReservations,
        Instant effectiveAt,
        Instant expiresAt,
        Instant openedAt,
        Instant closedAt,
        String closureReason
) {
    public CertificationStageLedgerSnapshot {
        if (authorizationId == null || authorizationId.isBlank()) {
            throw new IllegalArgumentException("CERTIFICATION_STAGE_SNAPSHOT_ID_INVALID");
        }
        Objects.requireNonNull(cycleId, "cycleId");
        if (!ProfileRunBudgetPolicy.isImageOnlyCertificationProfile(profileId)) {
            throw new IllegalArgumentException("CERTIFICATION_STAGE_SNAPSHOT_PROFILE_INVALID");
        }
        CertificationCanaryCase.requireSha(profileSha256);
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(effectiveAt, "effectiveAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(openedAt, "openedAt");
        if (maximumRuns < 1 || startedRuns < 0 || startedRuns > maximumRuns
                || maximumProviderCalls < 1 || providerCalls < 0
                || providerCalls > maximumProviderCalls
                || maximumModelTokens < 1 || exposedModelTokens < 0
                || exposedModelTokens > maximumModelTokens
                || maximumCostMicrosCny < 1 || exposedCostMicrosCny < 0
                || exposedCostMicrosCny > maximumCostMicrosCny
                || maximumProviderCallsPerRun < 1 || maximumProviderCallsPerRun > 12
                || maximumCostPerRunMicrosCny < 1
                || maximumCostPerRunMicrosCny > 6_000_000L
                || unsettledReservations < 0 || unsettledReservations > providerCalls) {
            throw new IllegalArgumentException("CERTIFICATION_STAGE_SNAPSHOT_BOUNDS_INVALID");
        }
        if (status == CertificationStageLedgerStatus.CLOSED) {
            Objects.requireNonNull(closedAt, "closedAt");
            if (closureReason == null || closureReason.isBlank()) {
                throw new IllegalArgumentException("CERTIFICATION_STAGE_SNAPSHOT_CLOSURE_INVALID");
            }
        } else if (closedAt != null || closureReason != null) {
            throw new IllegalArgumentException("CERTIFICATION_STAGE_SNAPSHOT_CLOSURE_INVALID");
        }
    }
}
