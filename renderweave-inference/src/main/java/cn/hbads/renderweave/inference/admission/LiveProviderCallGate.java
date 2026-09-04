package cn.hbads.renderweave.inference.admission;

import cn.hbads.renderweave.inference.run.InferenceRunSnapshot;

/**
 * The single gate between the live worker and Provider egress. Every dispatch eligibility check,
 * atomic call authorization and settlement audit flows through this port; no byte may leave
 * without a committed permit.
 */
public interface LiveProviderCallGate {
    /**
     * Revalidates the dual switches for a claimed run before dispatch and between stage advances.
     * Throws a typed {@link LiveAdmissionProblem} when the run must drain to a stable terminal.
     */
    void requireDispatchEligible(InferenceRunSnapshot run);

    /**
     * Atomically persists call authorization, attempt identity, cost reservation and the audit
     * event in one PostgreSQL transaction. The returned permit exists only after commit.
     */
    ProviderCallPermit authorizeCall(ProviderCallAuthorizationCommand command);

    /** Settles the reservation and appends the dispatch outcome audit event atomically. */
    void recordDispatchOutcome(ProviderCallOutcomeCommand command);

    /** Appends the payload-free drain decision for a run terminated by a closed switch. */
    void recordDrain(java.util.UUID runId, String drainEventCode, java.time.Instant now);
}
