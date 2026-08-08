package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.provider.ProviderBudgetExceededException;
import cn.hbads.renderweave.inference.provider.ProviderBudgetReservation;
import cn.hbads.renderweave.inference.provider.ProviderBudgetSnapshot;
import cn.hbads.renderweave.inference.provider.ProviderBudgetStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

@Repository
public class PostgresProviderBudgetStore implements ProviderBudgetStore {
    private final JdbcClient jdbcClient;

    public PostgresProviderBudgetStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    @Transactional
    public ProviderBudgetReservation reserve(
            String budgetKey,
            UUID runId,
            int attemptOrdinal,
            long maximumCostMicrosCny,
            Instant now
    ) {
        validateReservation(budgetKey, runId, attemptOrdinal, maximumCostMicrosCny, now);
        var budget = jdbcClient.sql("""
                        select maximum_attempts, maximum_cost_micros_cny
                        from inference_provider_budget
                        where budget_key = :budgetKey
                        for update
                        """)
                .param("budgetKey", budgetKey)
                .query((resultSet, rowNumber) -> new BudgetLimit(
                        resultSet.getInt("maximum_attempts"),
                        resultSet.getLong("maximum_cost_micros_cny")
                ))
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Unknown provider budget: " + budgetKey));

        var duplicate = jdbcClient.sql("""
                        select count(*) from inference_provider_reservation
                        where run_id = :runId and attempt_ordinal = :attemptOrdinal
                        """)
                .param("runId", runId)
                .param("attemptOrdinal", attemptOrdinal)
                .query(Long.class)
                .single();
        if (duplicate > 0) {
            throw new ProviderBudgetExceededException("PROVIDER_ATTEMPT_ALREADY_RESERVED");
        }

        var consumed = consumption(budgetKey);
        if (consumed.attempts() >= budget.maximumAttempts()) {
            throw new ProviderBudgetExceededException("PROVIDER_ATTEMPT_BUDGET_EXHAUSTED");
        }
        if (maximumCostMicrosCny > budget.maximumCostMicrosCny() - consumed.costMicrosCny()) {
            throw new ProviderBudgetExceededException("PROVIDER_COST_BUDGET_EXHAUSTED");
        }

        var reservationId = UUID.randomUUID();
        jdbcClient.sql("""
                        insert into inference_provider_reservation (
                            reservation_id, budget_key, run_id, attempt_ordinal,
                            reserved_cost_micros_cny, state, created_at
                        ) values (
                            :reservationId, :budgetKey, :runId, :attemptOrdinal,
                            :reservedCost, 'RESERVED', :createdAt
                        )
                        """)
                .param("reservationId", reservationId)
                .param("budgetKey", budgetKey)
                .param("runId", runId)
                .param("attemptOrdinal", attemptOrdinal)
                .param("reservedCost", maximumCostMicrosCny)
                .param("createdAt", offset(now))
                .update();
        return new ProviderBudgetReservation(
                reservationId, budgetKey, runId, attemptOrdinal, maximumCostMicrosCny
        );
    }

    @Override
    @Transactional
    public void settle(UUID reservationId, long actualCostMicrosCny, Instant now) {
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(now, "now");
        if (actualCostMicrosCny < 0) throw new IllegalArgumentException("actualCostMicrosCny must not be negative");
        var current = jdbcClient.sql("""
                        select reserved_cost_micros_cny, actual_cost_micros_cny, state
                        from inference_provider_reservation
                        where reservation_id = :reservationId
                        for update
                        """)
                .param("reservationId", reservationId)
                .query((resultSet, rowNumber) -> new ReservationState(
                        resultSet.getLong("reserved_cost_micros_cny"),
                        resultSet.getObject("actual_cost_micros_cny", Long.class),
                        resultSet.getString("state")
                ))
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Unknown provider budget reservation"));
        if (actualCostMicrosCny > current.reservedCostMicrosCny()) {
            throw new IllegalArgumentException("Actual cost exceeds the fail-closed reservation");
        }
        if ("SETTLED".equals(current.state())) {
            if (!Objects.equals(current.actualCostMicrosCny(), actualCostMicrosCny)) {
                throw new IllegalStateException("Provider budget reservation was already settled differently");
            }
            return;
        }
        jdbcClient.sql("""
                        update inference_provider_reservation
                        set actual_cost_micros_cny = :actualCost,
                            state = 'SETTLED', settled_at = :settledAt
                        where reservation_id = :reservationId and state = 'RESERVED'
                        """)
                .param("actualCost", actualCostMicrosCny)
                .param("settledAt", offset(now))
                .param("reservationId", reservationId)
                .update();
    }

    @Override
    public ProviderBudgetSnapshot snapshot(String budgetKey) {
        var budget = jdbcClient.sql("""
                        select maximum_attempts, maximum_cost_micros_cny
                        from inference_provider_budget where budget_key = :budgetKey
                        """)
                .param("budgetKey", budgetKey)
                .query((resultSet, rowNumber) -> new BudgetLimit(
                        resultSet.getInt("maximum_attempts"),
                        resultSet.getLong("maximum_cost_micros_cny")
                ))
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Unknown provider budget: " + budgetKey));
        var consumed = consumption(budgetKey);
        return new ProviderBudgetSnapshot(
                budgetKey, budget.maximumAttempts(), consumed.attempts(),
                budget.maximumCostMicrosCny(), consumed.costMicrosCny()
        );
    }

    private Consumption consumption(String budgetKey) {
        return jdbcClient.sql("""
                        select count(*) as attempts,
                               coalesce(sum(coalesce(actual_cost_micros_cny, reserved_cost_micros_cny)), 0) as cost
                        from inference_provider_reservation
                        where budget_key = :budgetKey
                        """)
                .param("budgetKey", budgetKey)
                .query((resultSet, rowNumber) -> new Consumption(
                        resultSet.getInt("attempts"), resultSet.getLong("cost")
                ))
                .single();
    }

    private static void validateReservation(
            String budgetKey,
            UUID runId,
            int attemptOrdinal,
            long maximumCostMicrosCny,
            Instant now
    ) {
        if (budgetKey == null || !budgetKey.matches("[a-z0-9][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException("budgetKey is invalid");
        }
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(now, "now");
        if (attemptOrdinal < 0 || attemptOrdinal > 2 || maximumCostMicrosCny <= 0) {
            throw new IllegalArgumentException("Provider reservation bounds are invalid");
        }
    }

    private static OffsetDateTime offset(Instant instant) {
        return OffsetDateTime.ofInstant(instant, java.time.ZoneOffset.UTC);
    }

    private record BudgetLimit(int maximumAttempts, long maximumCostMicrosCny) { }
    private record Consumption(int attempts, long costMicrosCny) { }
    private record ReservationState(long reservedCostMicrosCny, Long actualCostMicrosCny, String state) { }
}
