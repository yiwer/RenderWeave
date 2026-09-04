package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.admission.ImageOnlyAdmissionPolicy;
import cn.hbads.renderweave.inference.admission.ImageOnlyAdmissionPolicyStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Append-only PostgreSQL projection of the IMAGE_ONLY admission switch. The current state is
 * always the newest version; history is never rewritten.
 */
@Repository
public class PostgresImageOnlyAdmissionPolicyStore implements ImageOnlyAdmissionPolicyStore {
    private final JdbcClient jdbcClient;

    public PostgresImageOnlyAdmissionPolicyStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public ImageOnlyAdmissionPolicy.Snapshot current() {
        return jdbcClient.sql("""
                        select policy_version, enabled, changed_by, change_reason, changed_at
                        from image_only_admission_policy
                        order by policy_version desc
                        limit 1
                        """)
                .query(PostgresImageOnlyAdmissionPolicyStore::mapSnapshot)
                .single();
    }

    @Override
    @Transactional
    public ImageOnlyAdmissionPolicy.Snapshot append(
            boolean enabled, String opsIdentity, String reason, Instant at
    ) {
        if (!ImageOnlyAdmissionPolicy.CHANGE_REASONS.contains(reason)) {
            throw new IllegalArgumentException("Policy change reason is not in the closed set");
        }
        jdbcClient.sql("select pg_advisory_xact_lock(hashtextextended('image-only-admission-policy', 0))")
                .query((resultSet, rowNumber) -> resultSet.getObject(1))
                .single();
        var version = jdbcClient.sql("""
                        select coalesce(max(policy_version), 0) + 1
                        from image_only_admission_policy
                        """)
                .query(Integer.class)
                .single();
        var snapshot = new ImageOnlyAdmissionPolicy.Snapshot(version, enabled, opsIdentity, reason, at);
        jdbcClient.sql("""
                        insert into image_only_admission_policy (
                            policy_version, enabled, changed_by, change_reason, changed_at
                        ) values (
                            :version, :enabled, :changedBy, :changeReason, :changedAt
                        )
                        """)
                .param("version", snapshot.version())
                .param("enabled", snapshot.enabled())
                .param("changedBy", snapshot.changedBy())
                .param("changeReason", snapshot.changeReason())
                .param("changedAt", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .update();
        return snapshot;
    }

    private static ImageOnlyAdmissionPolicy.Snapshot mapSnapshot(
            ResultSet resultSet, int rowNumber
    ) throws SQLException {
        return new ImageOnlyAdmissionPolicy.Snapshot(
                resultSet.getInt("policy_version"),
                resultSet.getBoolean("enabled"),
                resultSet.getString("changed_by"),
                resultSet.getString("change_reason"),
                resultSet.getObject("changed_at", OffsetDateTime.class).toInstant()
        );
    }
}
