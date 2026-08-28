package cn.hbads.renderweave.app.template;

import cn.hbads.renderweave.asset.spi.AssetAuditEventSource;
import cn.hbads.renderweave.template.api.TemplateApplication;
import cn.hbads.renderweave.template.api.TemplateReadinessAuthority;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashSet;
import java.util.Objects;

/**
 * Replayable STALE consumer over Asset-owned mutation facts: content-changing
 * events (CONTENT_REPLACE/CONTENT_RESTORE/DELETE/RESTORE) mark every ACTIVE Template whose
 * current-only projection references the asset as STALE, then the readiness authority
 * rechecks them (STALE is the transient fact; the recheck lands READY or INVALID).
 * Metadata-only events never trigger STALE; same-content no-ops emit no event at all.
 */
class TemplateAssetStaleConsumer {

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final AssetAuditEventSource assetAuditEvents;
    private final TemplateReadinessAuthority readinessAuthority;

    TemplateAssetStaleConsumer(
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            AssetAuditEventSource assetAuditEvents,
            TemplateReadinessAuthority readinessAuthority
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.assetAuditEvents = Objects.requireNonNull(assetAuditEvents, "assetAuditEvents");
        this.readinessAuthority = Objects.requireNonNull(readinessAuthority, "readinessAuthority");
    }

    /** Consume new audit events; returns how many templates were marked STALE. */
    public int consumePending() {
        long observedCursor = jdbc.sql("""
                        select last_event_id
                        from template_asset_stale_cursor
                        where singleton
                        """)
                .query(Long.class)
                .single();
        var page = assetAuditEvents.readAfter(
                observedCursor, AssetAuditEventSource.MAXIMUM_READ_LIMIT);
        return Objects.requireNonNull(transactions.execute(status -> {
            long cursor = jdbc.sql("""
                            select last_event_id
                            from template_asset_stale_cursor
                            where singleton
                            """)
                    .query(Long.class)
                    .single();
            if (cursor != observedCursor) {
                return 0;
            }
            long maxEventId = cursor;
            var staleTemplates = new HashSet<String>();
            for (var event : page.events()) {
                long eventId = event.eventId();
                maxEventId = Math.max(maxEventId, eventId);
                if (!changesContent(event.operation())) {
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
                        .param("assetId", event.assetId().value())
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

    private static boolean changesContent(AssetAuditEventSource.MutationOperation operation) {
        return switch (operation) {
            case CONTENT_REPLACE, CONTENT_RESTORE, DELETE, RESTORE -> true;
            case CREATE, METADATA_UPDATE -> false;
        };
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
