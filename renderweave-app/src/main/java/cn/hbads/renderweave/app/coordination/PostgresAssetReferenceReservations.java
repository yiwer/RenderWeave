package cn.hbads.renderweave.app.coordination;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.Objects;

/**
 * PostgreSQL transaction-scoped implementation of the Template-owned Asset reference
 * reservation contract. The advisory key is ephemeral coordination state: no context
 * reads another context's table and each caller keeps its own transaction.
 */
@Component
public final class PostgresAssetReferenceReservations {
    private static final String LOCK_DOMAIN = "renderweave-asset-reference/1:";

    private final JdbcClient jdbc;

    public PostgresAssetReferenceReservations(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** Acquire shared reservations in canonical order within the current transaction. */
    public void acquireShared(Collection<String> assetIds) {
        requireTransaction();
        Objects.requireNonNull(assetIds, "assetIds").stream()
                .map(PostgresAssetReferenceReservations::requireCanonicalAssetId)
                .distinct()
                .sorted()
                .forEach(assetId -> acquire("pg_advisory_xact_lock_shared", assetId));
    }

    /** Acquire one exclusive reservation within the current transaction. */
    public void acquireExclusive(String assetId) {
        requireTransaction();
        acquire("pg_advisory_xact_lock", requireCanonicalAssetId(assetId));
    }

    private void acquire(String function, String assetId) {
        jdbc.sql("select " + function + "(hashtextextended(:lockName, 0))")
                .param("lockName", LOCK_DOMAIN + assetId)
                .query((resultSet, rowNumber) -> Boolean.TRUE)
                .single();
    }

    private static String requireCanonicalAssetId(String assetId) {
        Objects.requireNonNull(assetId, "assetId");
        var canonical = java.util.UUID.fromString(assetId).toString();
        if (!canonical.equals(assetId)) {
            throw new IllegalArgumentException("assetId must be canonical UUID");
        }
        return canonical;
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Asset reference reservation requires a transaction");
        }
    }
}
