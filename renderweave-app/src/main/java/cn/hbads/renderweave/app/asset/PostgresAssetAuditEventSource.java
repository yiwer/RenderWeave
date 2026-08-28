package cn.hbads.renderweave.app.asset;

import cn.hbads.renderweave.asset.api.AssetApplication;
import cn.hbads.renderweave.asset.spi.AssetAuditEventSource;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Objects;

/** PostgreSQL adapter for the Asset-owned immutable mutation fact stream. */
final class PostgresAssetAuditEventSource implements AssetAuditEventSource {

    private final JdbcClient jdbc;

    PostgresAssetAuditEventSource(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public EventPage readAfter(long exclusiveEventId, int limit) {
        if (exclusiveEventId < 0) {
            throw new IllegalArgumentException("exclusiveEventId must not be negative");
        }
        int admittedLimit = AssetAuditEventSource.requireReadLimit(limit);
        var events = jdbc.sql("""
                        select event_id, asset_id, operation_type
                        from asset_audit_event
                        where event_id > :cursor
                        order by event_id
                        limit :limit
                        """)
                .param("cursor", exclusiveEventId)
                .param("limit", admittedLimit)
                .query((resultSet, rowNumber) -> new MutationEvent(
                        resultSet.getLong("event_id"),
                        AssetApplication.AssetId.of(resultSet.getString("asset_id")),
                        MutationOperation.valueOf(resultSet.getString("operation_type"))
                ))
                .list();
        return new EventPage(events, admittedLimit);
    }
}
