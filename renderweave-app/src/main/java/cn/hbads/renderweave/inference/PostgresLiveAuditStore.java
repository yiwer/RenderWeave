package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.audit.LiveAdmissionAuditAppender;
import cn.hbads.renderweave.inference.audit.LiveAdmissionAuditChain;
import cn.hbads.renderweave.inference.audit.LiveAdmissionAuditEvent;
import cn.hbads.renderweave.inference.audit.LiveAdmissionAuditReader;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * PostgreSQL projection of the Live Admission Audit. Appends participate in the caller's
 * transaction so call authorization, reservation and audit commit as one fact.
 */
@Repository
public class PostgresLiveAuditStore implements LiveAdmissionAuditReader, LiveAdmissionAuditAppender {
    private final JdbcClient jdbcClient;

    public PostgresLiveAuditStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public List<LiveAdmissionAuditEvent> eventsForRun(UUID runId) {
        return jdbcClient.sql("""
                        select run_id, sequence, event_code, actor_id,
                               confirmation_id, reservation_id, call_authorization_id,
                               attempt_ordinal, input_fingerprint, profile_id, profile_sha256,
                               decision_code, usage_input_tokens, usage_output_tokens,
                               cost_micros_cny, occurred_at, previous_event_digest, event_digest
                        from live_admission_audit_event
                        where run_id = :runId
                        order by sequence
                        """)
                .param("runId", runId)
                .query(PostgresLiveAuditStore::mapEvent)
                .list();
    }

    /**
     * Appends one event to the run's chain inside the caller's transaction, returning the
     * persisted chained event with its sequence and recomputed digest.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LiveAdmissionAuditEvent append(LiveAdmissionAuditEvent unsigned) {
        jdbcClient.sql("select pg_advisory_xact_lock(hashtextextended(:lockKey, 0))")
                .param("lockKey", "live-admission-audit:" + unsigned.runId())
                .query((resultSet, rowNumber) -> resultSet.getObject(1))
                .single();
        var last = jdbcClient.sql("""
                        select sequence, event_digest
                        from live_admission_audit_event
                        where run_id = :runId
                        order by sequence desc
                        limit 1
                        """)
                .param("runId", unsigned.runId())
                .query((resultSet, rowNumber) -> new Object[] {
                        resultSet.getInt("sequence"), resultSet.getString("event_digest")
                })
                .optional();
        var sequence = last.map(row -> (Integer) row[0] + 1).orElse(1);
        var previous = last.map(row -> (String) row[1]).orElse(LiveAdmissionAuditChain.GENESIS_DIGEST);
        var sequenced = new LiveAdmissionAuditEvent(
                unsigned.runId(), sequence, unsigned.eventCode(), unsigned.actorId(),
                unsigned.confirmationId(), unsigned.reservationId(), unsigned.callAuthorizationId(),
                unsigned.attemptOrdinal(), unsigned.inputFingerprint(), unsigned.profileId(),
                unsigned.profileSha256(), unsigned.decisionCode(), unsigned.usageInputTokens(),
                unsigned.usageOutputTokens(), unsigned.costMicrosCny(), unsigned.occurredAt(),
                previous, unsigned.eventDigest()
        );
        var chained = LiveAdmissionAuditChain.chained(sequenced, previous);
        jdbcClient.sql("""
                        insert into live_admission_audit_event (
                            run_id, sequence, event_code, actor_id,
                            confirmation_id, reservation_id, call_authorization_id,
                            attempt_ordinal, input_fingerprint, profile_id, profile_sha256,
                            decision_code, usage_input_tokens, usage_output_tokens,
                            cost_micros_cny, occurred_at, previous_event_digest, event_digest
                        ) values (
                            :runId, :sequence, :eventCode, :actorId,
                            :confirmationId, :reservationId, :callAuthorizationId,
                            :attemptOrdinal, :inputFingerprint, :profileId, :profileSha256,
                            :decisionCode, :usageInputTokens, :usageOutputTokens,
                            :costMicrosCny, :occurredAt, :previousEventDigest, :eventDigest
                        )
                        """)
                .param("runId", chained.runId())
                .param("sequence", chained.sequence())
                .param("eventCode", chained.eventCode())
                .param("actorId", chained.actorId())
                .param("confirmationId", chained.confirmationId())
                .param("reservationId", chained.reservationId())
                .param("callAuthorizationId", chained.callAuthorizationId())
                .param("attemptOrdinal", chained.attemptOrdinal())
                .param("inputFingerprint", chained.inputFingerprint())
                .param("profileId", chained.profileId())
                .param("profileSha256", chained.profileSha256())
                .param("decisionCode", chained.decisionCode())
                .param("usageInputTokens", chained.usageInputTokens())
                .param("usageOutputTokens", chained.usageOutputTokens())
                .param("costMicrosCny", chained.costMicrosCny())
                .param("occurredAt", offset(chained.occurredAt()))
                .param("previousEventDigest", chained.previousEventDigest())
                .param("eventDigest", chained.eventDigest())
                .update();
        if (chained.sequence() != sequence) {
            throw new IllegalStateException("Live admission audit sequence raced");
        }
        return chained;
    }

    private static LiveAdmissionAuditEvent mapEvent(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new LiveAdmissionAuditEvent(
                resultSet.getObject("run_id", UUID.class),
                resultSet.getInt("sequence"),
                resultSet.getString("event_code"),
                resultSet.getString("actor_id"),
                resultSet.getObject("confirmation_id", UUID.class),
                resultSet.getObject("reservation_id", UUID.class),
                resultSet.getObject("call_authorization_id", UUID.class),
                (Integer) resultSet.getObject("attempt_ordinal"),
                trimmed(resultSet.getString("input_fingerprint")),
                resultSet.getString("profile_id"),
                trimmed(resultSet.getString("profile_sha256")),
                resultSet.getString("decision_code"),
                (Long) resultSet.getObject("usage_input_tokens"),
                (Long) resultSet.getObject("usage_output_tokens"),
                (Long) resultSet.getObject("cost_micros_cny"),
                instant(resultSet, "occurred_at"),
                trimmed(resultSet.getString("previous_event_digest")),
                trimmed(resultSet.getString("event_digest"))
        );
    }

    private static String trimmed(String value) {
        return value == null ? null : value.trim();
    }

    private static OffsetDateTime offset(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }
}
