package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.provider.ProviderBudgetReservation;
import cn.hbads.renderweave.inference.provider.ProviderBudgetSnapshot;
import cn.hbads.renderweave.inference.provider.ProviderBudgetStore;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Adds the cross-process authorization ledger in front of the transactional database budget. */
final class JournaledCertificationBudgetStore implements ProviderBudgetStore {
    private final LiveCertificationAuthorization authorization;
    private final LiveCertificationJournal journal;
    private final ProviderBudgetStore delegate;

    JournaledCertificationBudgetStore(
            LiveCertificationAuthorization authorization,
            LiveCertificationJournal journal,
            ProviderBudgetStore delegate
    ) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public ProviderBudgetReservation reserve(
            String budgetKey,
            UUID runId,
            int attemptOrdinal,
            long maximumCostMicrosCny,
            Instant now
    ) {
        authorization.requireOpen(now);
        var journalReservationId = journal.prepareReservation(
                budgetKey, runId, attemptOrdinal, maximumCostMicrosCny, now
        );
        var reserved = delegate.reserve(
                budgetKey, runId, attemptOrdinal, maximumCostMicrosCny, now
        );
        journal.bindReservation(journalReservationId, reserved.reservationId(), now);
        return reserved;
    }

    @Override
    public void settle(UUID reservationId, long actualCostMicrosCny, Instant now) {
        journal.settleByDelegate(reservationId, actualCostMicrosCny, now);
        delegate.settle(reservationId, actualCostMicrosCny, now);
    }

    @Override
    public ProviderBudgetSnapshot snapshot(String budgetKey) {
        return journal.snapshot(budgetKey);
    }
}
