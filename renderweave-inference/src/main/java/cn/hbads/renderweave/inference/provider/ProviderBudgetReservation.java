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
    /** Neutral persistence/replay ceiling; each immutable Profile owns its executable cap. */
    public static final int REPRESENTATIONAL_MAXIMUM_PROVIDER_CALLS = 12;
    public static final int REPRESENTATIONAL_MAXIMUM_ATTEMPT_ORDINAL =
            REPRESENTATIONAL_MAXIMUM_PROVIDER_CALLS - 1;

    public ProviderBudgetReservation {
        Objects.requireNonNull(reservationId, "reservationId");
        if (budgetKey == null || !budgetKey.matches("[a-z0-9][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException("budgetKey is invalid");
        }
        Objects.requireNonNull(runId, "runId");
        if (attemptOrdinal < 0 || attemptOrdinal > REPRESENTATIONAL_MAXIMUM_ATTEMPT_ORDINAL
                || reservedCostMicrosCny <= 0) {
            throw new IllegalArgumentException("Reservation bounds are invalid");
        }
    }
}
