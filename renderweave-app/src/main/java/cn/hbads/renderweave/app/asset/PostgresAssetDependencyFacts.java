package cn.hbads.renderweave.app.asset;

import cn.hbads.renderweave.app.coordination.AssetDependencyFacts;
import cn.hbads.renderweave.template.spi.DependencyResolution;
import cn.hbads.renderweave.template.spi.OwnerScopeAuthority;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Asset-owned PostgreSQL adapter for exact Template dependency facts. */
@Component
final class PostgresAssetDependencyFacts implements AssetDependencyFacts {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    PostgresAssetDependencyFacts(
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactions.setReadOnly(true);
    }

    @Override
    public DependencyResolution.AssetResolution resolve(String assetId) {
        try {
            return transactions.execute(status -> jdbc.sql("""
                            select owner_scope, kind, lifecycle, asset_revision,
                                   current_content_version
                            from asset_aggregate
                            where asset_id = :assetId
                            """)
                    .param("assetId", assetId)
                    .query((resultSet, rowNumber) -> new DependencyResolution.AssetResolved(
                            new DependencyResolution.AssetState(
                                    new OwnerScopeAuthority.OwnerScope(
                                            resultSet.getString("owner_scope")),
                                    resultSet.getString("kind"),
                                    DependencyResolution.Lifecycle.valueOf(
                                            resultSet.getString("lifecycle")),
                                    resultSet.getLong("asset_revision"),
                                    resultSet.getLong("current_content_version")
                            )))
                    .optional()
                    .<DependencyResolution.AssetResolution>map(value -> value)
                    .orElseGet(DependencyResolution.AssetMissing::new));
        } catch (DataAccessException | IllegalArgumentException unavailable) {
            return new DependencyResolution.AssetUnavailable();
        }
    }
}
