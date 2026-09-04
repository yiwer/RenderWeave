package cn.hbads.renderweave.inference.admission;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The transient Provider permit produced only after the atomic call-authorization transaction
 * commits. A permit is not a cached boolean, not a retry right and never crosses runs.
 */
public record ProviderCallPermit(
        UUID callAuthorizationId,
        UUID reservationId,
        UUID runId,
        int attemptOrdinal,
        int policyVersion,
        String egressPermitIdentity,
        Instant authorizedAt,
        Instant providerCallsNotAfter
) {
    public ProviderCallPermit {
        Objects.requireNonNull(callAuthorizationId, "callAuthorizationId");
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(egressPermitIdentity, "egressPermitIdentity");
        Objects.requireNonNull(authorizedAt, "authorizedAt");
        Objects.requireNonNull(providerCallsNotAfter, "providerCallsNotAfter");
        if (attemptOrdinal < 0 || attemptOrdinal > 11) {
            throw new IllegalArgumentException("attemptOrdinal is out of range");
        }
        if (policyVersion < 1) {
            throw new IllegalArgumentException("policyVersion starts at 1");
        }
        if (!providerCallsNotAfter.isAfter(authorizedAt)) {
            throw new IllegalArgumentException("providerCallsNotAfter must follow authorization");
        }
    }
}
