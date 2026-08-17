package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.certification.CertificationStage;
import cn.hbads.renderweave.inference.certification.ProfileCertificationEvent;
import cn.hbads.renderweave.inference.certification.ProfileCertificationStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Repository
public class PostgresProfileCertificationStore implements ProfileCertificationStore {
    private final JdbcClient jdbcClient;

    public PostgresProfileCertificationStore(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
    }

    @Override
    @Transactional
    public void append(ProfileCertificationEvent event) {
        Objects.requireNonNull(event, "event");
        jdbcClient.sql("""
                        insert into profile_certification_event (
                            event_id, cycle_id, sequence_no, profile_id, profile_sha256,
                            manifest_identity, evaluator_identity, event_type, stage,
                            accepted_cases, total_cases, evidence_identity,
                            authority_reference, reason_code, recorded_at
                        ) values (
                            :eventId, :cycleId, :sequenceNo, :profileId, :profileSha256,
                            :manifestIdentity, :evaluatorIdentity, :eventType, :stage,
                            :acceptedCases, :totalCases, :evidenceIdentity,
                            :authorityReference, :reasonCode, :recordedAt
                        )
                        """)
                .param("eventId", event.eventId())
                .param("cycleId", event.cycleId())
                .param("sequenceNo", event.sequence())
                .param("profileId", event.profileId())
                .param("profileSha256", event.profileSha256())
                .param("manifestIdentity", event.manifestIdentity())
                .param("evaluatorIdentity", event.evaluatorIdentity())
                .param("eventType", event.eventType().name())
                .param("stage", event.stage() == null ? null : event.stage().name())
                .param("acceptedCases", event.acceptedCases())
                .param("totalCases", event.totalCases())
                .param("evidenceIdentity", event.evidenceIdentity())
                .param("authorityReference", event.authorityReference())
                .param("reasonCode", event.reasonCode())
                .param("recordedAt", OffsetDateTime.ofInstant(event.recordedAt(), ZoneOffset.UTC))
                .update();
    }

    @Override
    public List<ProfileCertificationEvent> events(UUID cycleId) {
        Objects.requireNonNull(cycleId, "cycleId");
        return jdbcClient.sql("""
                        select event_id, cycle_id, sequence_no, profile_id, profile_sha256,
                               manifest_identity, evaluator_identity, event_type, stage,
                               accepted_cases, total_cases, evidence_identity,
                               authority_reference, reason_code, recorded_at
                        from profile_certification_event
                        where cycle_id = :cycleId
                        order by sequence_no
                        """)
                .param("cycleId", cycleId)
                .query((resultSet, rowNumber) -> new ProfileCertificationEvent(
                        resultSet.getObject("event_id", UUID.class),
                        resultSet.getObject("cycle_id", UUID.class),
                        resultSet.getInt("sequence_no"),
                        resultSet.getString("profile_id"),
                        resultSet.getString("profile_sha256"),
                        resultSet.getString("manifest_identity"),
                        resultSet.getString("evaluator_identity"),
                        ProfileCertificationEvent.EventType.valueOf(resultSet.getString("event_type")),
                        nullableStage(resultSet.getString("stage")),
                        resultSet.getObject("accepted_cases", Integer.class),
                        resultSet.getObject("total_cases", Integer.class),
                        resultSet.getString("evidence_identity"),
                        resultSet.getString("authority_reference"),
                        resultSet.getString("reason_code"),
                        resultSet.getObject("recorded_at", OffsetDateTime.class).toInstant()
                ))
                .list();
    }

    private static CertificationStage nullableStage(String value) {
        return value == null ? null : CertificationStage.valueOf(value);
    }
}
