package cn.hbads.renderweave.app.template;

import cn.hbads.renderweave.template.api.TemplateApplication;
import cn.hbads.renderweave.template.api.TemplateReadinessAuthority;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Replayable STALE consumer over {@code asset_audit_event} (T12a facts): content-changing
 * events (CONTENT_REPLACE/CONTENT_RESTORE/DELETE/RESTORE) mark every ACTIVE Template whose
 * current-only projection references the asset as STALE, then the readiness authority
 * rechecks them (STALE is the transient fact; the recheck lands READY or INVALID).
 * Metadata-only events never trigger STALE; same-content no-ops emit no event at all.
 */
class TemplateAssetStaleConsumer {

    private static final Set<String> CONTENT_CHANGING_OPERATIONS = Set.of(
            "CONTENT_REPLACE", "CONTENT_RESTORE", "DELETE", "RESTORE"
    );

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final TemplateReadinessAuthority readinessAuthority;

    TemplateAssetStaleConsumer(
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            TemplateReadinessAuthority readinessAuthority
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.readinessAuthority = Objects.requireNonNull(readinessAuthority, "readinessAuthority");
    }

    /** Consume new audit events; returns how many templates were marked STALE. */
    public int consumePending() {
        return Objects.requireNonNull(transactions.execute(status -> {
            var cursor = jdbc.sql("""
                            select last_event_id
                            from template_asset_stale_cursor
                            where singleton
                            """)
                    .query(Long.class)
                    .single();
            var ids = jdbc.sql("""
                            select event_id, asset_id, operation_type
                            from asset_audit_event
                            where event_id > :cursor
                            order by event_id
                            limit 200
                            """)
                    .param("cursor", cursor)
                    .query((resultSet, rowNumber) -> new Object[]{
                            resultSet.getLong("event_id"),
                            resultSet.getString("asset_id"),
                            resultSet.getString("operation_type")
                    })
                    .list();
            long maxEventId = cursor;
            var staleTemplates = new HashSet<String>();
            for (var event : ids) {
                long eventId = (Long) event[0];
                maxEventId = Math.max(maxEventId, eventId);
                if (!CONTENT_CHANGING_OPERATIONS.contains(event[2])) {
                    continue;
                }
                var referencing = jdbc.sql("""
                                select distinct r.template_id
                                from template_asset_reference r
                                join template_aggregate a
                                  on a.template_id = r.template_id
                                 and a.lifecycle = 'ACTIVE'
                                where r.asset_id = :assetId
                                """)
                        .param("assetId", event[1])
                        .query((resultSet, rowNumber) -> resultSet.getString("template_id"))
                        .list();
                staleTemplates.addAll(referencing);
            }
            jdbc.sql("update template_asset_stale_cursor set last_event_id = :eventId where singleton")
                    .param("eventId", maxEventId)
                    .update();
            for (var templateId : staleTemplates) {
                jdbc.sql("""
                                update template_aggregate
                                set readiness = 'STALE',
                                    updated_at = clock_timestamp()
                                where template_id = :templateId
                                  and lifecycle = 'ACTIVE'
                                """)
                        .param("templateId", templateId)
                        .update();
            }
            return staleTemplates.size();
        }));
    }

    /** Recheck every STALE ACTIVE Template current into READY or INVALID. */
    public void recheckStale() {
        var staleIds = jdbc.sql("""
                        select template_id
                        from template_aggregate
                        where readiness = 'STALE'
                          and lifecycle = 'ACTIVE'
                        """)
                .query((resultSet, rowNumber) -> resultSet.getString("template_id"))
                .list();
        for (var templateId : staleIds) {
            readinessAuthority.recheck(TemplateApplication.TemplateId.of(templateId));
        }
    }

}
