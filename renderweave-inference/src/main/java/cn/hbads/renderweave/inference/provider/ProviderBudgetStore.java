package cn.hbads.renderweave.inference.provider;

import java.time.Instant;
import java.util.UUID;

/** Fail-closed durable authorization boundary checked before every external call. */
public interface ProviderBudgetStore {
    ProviderBudgetReservation reserve(
            String budgetKey,
            UUID runId,
            int attemptOrdinal,
            long maximumCostMicrosCny,
            Instant now
    );

    void settle(UUID reservationId, long actualCostMicrosCny, Instant now);

    ProviderBudgetSnapshot snapshot(String budgetKey);
}
