package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.audit.AuditIntegrityProbe;
import cn.hbads.renderweave.inference.audit.LiveAdmissionAuditChain;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Replays the most recent audit chains and checks write permission; any anomaly projects
 * AUDIT_INTEGRITY_UNAVAILABLE so new live calls fail closed.
 */
@Repository
public class PostgresAuditIntegrityProbe implements AuditIntegrityProbe {
    static final int RECENT_RUN_SAMPLE = 32;

    private final PostgresLiveAuditStore auditStore;
    private final JdbcClient jdbcClient;

    public PostgresAuditIntegrityProbe(PostgresLiveAuditStore auditStore, JdbcClient jdbcClient) {
        this.auditStore = auditStore;
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Snapshot snapshot() {
        var writable = jdbcClient.sql("""
                        select has_table_privilege(current_user, 'live_admission_audit_event', 'INSERT')
                        """)
                .query(Boolean.class)
                .single();
        if (!Boolean.TRUE.equals(writable)) {
            return new Snapshot(false, "AUDIT_INTEGRITY_UNAVAILABLE", 0);
        }
        var recentRuns = jdbcClient.sql("""
                        select run_id
                        from live_admission_audit_event
                        group by run_id
                        order by max(occurred_at) desc
                        limit :sample
                        """)
                .param("sample", RECENT_RUN_SAMPLE)
                .query((resultSet, rowNumber) -> resultSet.getObject("run_id", UUID.class))
                .list();
        var verified = 0;
        for (var runId : recentRuns) {
            var verdict = LiveAdmissionAuditChain.verify(auditStore.eventsForRun(runId));
            if (verdict != LiveAdmissionAuditChain.Verdict.OK) {
                return new Snapshot(false, "AUDIT_INTEGRITY_UNAVAILABLE", verified);
            }
            verified++;
        }
        return new Snapshot(true, null, verified);
    }
}
