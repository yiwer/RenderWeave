package cn.hbads.renderweave.inference.certification;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A single durable reservation. It is never produced by the Provider-zero preflight alone. */
public record CertificationProviderCallPermit(
        UUID reservationId,
        String authorizationId,
        UUID cycleId,
        CertificationStage stage,
        UUID runId,
        String caseId,
        int attemptOrdinal,
        long reservedModelTokens,
        long reservedCostMicrosCny,
        Instant expiresAt,
        boolean grantsProviderEgress
) {
    public CertificationProviderCallPermit {
        Objects.requireNonNull(reservationId, "reservationId");
        if (authorizationId == null || authorizationId.isBlank()) {
            throw new IllegalArgumentException("CERTIFICATION_CALL_PERMIT_AUTHORIZATION_INVALID");
        }
        Objects.requireNonNull(cycleId, "cycleId");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(runId, "runId");
        if (caseId == null || caseId.isBlank() || attemptOrdinal < 0 || attemptOrdinal >= 12
                || reservedModelTokens < 1 || reservedCostMicrosCny < 1) {
            throw new IllegalArgumentException("CERTIFICATION_CALL_PERMIT_BOUNDS_INVALID");
        }
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!grantsProviderEgress) {
            throw new IllegalArgumentException("CERTIFICATION_CALL_PERMIT_MUST_GRANT_EGRESS");
        }
    }
}
