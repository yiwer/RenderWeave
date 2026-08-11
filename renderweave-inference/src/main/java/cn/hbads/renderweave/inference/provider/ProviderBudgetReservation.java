package cn.hbads.renderweave.inference.provider;

import java.util.Objects;
import java.util.UUID;

public record ProviderBudgetReservation(
        UUID reservationId,
        String budgetKey,
        UUID runId,
        int attemptOrdinal,
        long reservedCostMicrosCny
) {
    public static final int MAXIMUM_PROVIDER_CALLS = 7;
    public static final int MAXIMUM_ATTEMPT_ORDINAL = MAXIMUM_PROVIDER_CALLS - 1;

    public ProviderBudgetReservation {
        Objects.requireNonNull(reservationId, "reservationId");
        if (budgetKey == null || !budgetKey.matches("[a-z0-9][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException("budgetKey is invalid");
        }
        Objects.requireNonNull(runId, "runId");
        if (attemptOrdinal < 0 || attemptOrdinal > MAXIMUM_ATTEMPT_ORDINAL
                || reservedCostMicrosCny <= 0) {
            throw new IllegalArgumentException("Reservation bounds are invalid");
        }
    }
}
