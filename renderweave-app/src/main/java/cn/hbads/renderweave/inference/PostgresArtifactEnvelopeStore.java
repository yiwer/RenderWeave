package cn.hbads.renderweave.inference;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.function.Supplier;

@Repository
public class PostgresArtifactEnvelopeStore implements ArtifactEnvelopeStore {
    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactions;

    public PostgresArtifactEnvelopeStore(
            JdbcClient jdbcClient,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcClient = jdbcClient;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public <T> T withArtifactLock(String artifactId, Supplier<T> work) {
        return transactions.execute(status -> {
            jdbcClient.sql("select pg_advisory_xact_lock(hashtextextended(:identity, 0))")
                    .param("identity", "artifact-envelope:" + artifactId)
                    .query((resultSet, rowNumber) -> resultSet.getObject(1))
                    .single();
            return work.get();
        });
    }

    @Override
    public Optional<ArtifactEnvelope> find(String artifactId) {
        return jdbcClient.sql("""
                        select artifact_id, envelope_version, payload_algorithm, wrapping_algorithm,
                               ciphertext_locator, ciphertext_sha256, payload_nonce, payload_tag,
                               kek_id, wrapped_dek, wrapping_nonce, wrapping_tag,
                               created_at, rewrapped_at
                        from inference_artifact_envelope
                        where artifact_id = :artifactId
                        """)
                .param("artifactId", artifactId)
                .query(PostgresArtifactEnvelopeStore::map)
                .optional();
    }

    @Override
    public void insert(ArtifactEnvelope value) {
        jdbcClient.sql("""
                        insert into inference_artifact_envelope (
                            artifact_id, envelope_version, payload_algorithm, wrapping_algorithm,
                            ciphertext_locator, ciphertext_sha256, payload_nonce, payload_tag,
                            kek_id, wrapped_dek, wrapping_nonce, wrapping_tag,
                            created_at, rewrapped_at
                        ) values (
                            :artifactId, :envelopeVersion, :payloadAlgorithm, :wrappingAlgorithm,
                            :ciphertextLocator, :ciphertextSha256, :payloadNonce, :payloadTag,
                            :kekId, :wrappedDek, :wrappingNonce, :wrappingTag,
                            :createdAt, :rewrappedAt
                        )
                        """)
                .param("artifactId", value.artifactId())
                .param("envelopeVersion", value.envelopeVersion())
                .param("payloadAlgorithm", value.payloadAlgorithm())
                .param("wrappingAlgorithm", value.wrappingAlgorithm())
                .param("ciphertextLocator", value.ciphertextLocator())
                .param("ciphertextSha256", value.ciphertextSha256())
                .param("payloadNonce", value.payloadNonce())
                .param("payloadTag", value.payloadTag())
                .param("kekId", value.kekId())
                .param("wrappedDek", value.wrappedDek())
                .param("wrappingNonce", value.wrappingNonce())
                .param("wrappingTag", value.wrappingTag())
                .param("createdAt", offset(value.createdAt()))
                .param("rewrappedAt", offset(value.rewrappedAt()))
                .update();
    }

    @Override
    public void updateWrappedKey(ArtifactEnvelope value) {
        var updated = jdbcClient.sql("""
                        update inference_artifact_envelope
                        set kek_id = :kekId,
                            wrapped_dek = :wrappedDek,
                            wrapping_nonce = :wrappingNonce,
                            wrapping_tag = :wrappingTag,
                            rewrapped_at = :rewrappedAt
                        where artifact_id = :artifactId
                        """)
                .param("artifactId", value.artifactId())
                .param("kekId", value.kekId())
                .param("wrappedDek", value.wrappedDek())
                .param("wrappingNonce", value.wrappingNonce())
                .param("wrappingTag", value.wrappingTag())
                .param("rewrappedAt", offset(value.rewrappedAt()))
                .update();
        if (updated != 1) throw new IllegalStateException("Artifact envelope disappeared during re-wrap");
    }

    @Override
    public void protectForAdmission(String artifactId, Instant observedAt, Instant expiresAt) {
        if (!expiresAt.isAfter(observedAt)
                || expiresAt.isAfter(observedAt.plus(java.time.Duration.ofMinutes(15)))) {
            throw new IllegalArgumentException("Artifact admission protection must be positive and at most 15 minutes");
        }
        jdbcClient.sql("""
                        insert into inference_artifact_ingest_lease (artifact_id, observed_at, expires_at)
                        values (:artifactId, :observedAt, :expiresAt)
                        on conflict (artifact_id) do update
                        set observed_at = greatest(inference_artifact_ingest_lease.observed_at, excluded.observed_at),
                            expires_at = greatest(inference_artifact_ingest_lease.expires_at, excluded.expires_at)
                        """)
                .param("artifactId", artifactId)
                .param("observedAt", offset(observedAt))
                .param("expiresAt", offset(expiresAt))
                .update();
    }

    @Override
    public void releaseAdmissionProtection(String artifactId) {
        jdbcClient.sql("delete from inference_artifact_ingest_lease where artifact_id = :artifactId")
                .param("artifactId", artifactId)
                .update();
    }

    @Override
    public boolean delete(String artifactId) {
        return jdbcClient.sql("delete from inference_artifact_envelope where artifact_id = :artifactId")
                .param("artifactId", artifactId)
                .update() == 1;
    }

    @Override
    public long countByKekId(String kekId) {
        return jdbcClient.sql("select count(*) from inference_artifact_envelope where kek_id = :kekId")
                .param("kekId", kekId)
                .query(Long.class)
                .single();
    }

    private static ArtifactEnvelope map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ArtifactEnvelope(
                resultSet.getString("artifact_id"),
                resultSet.getString("envelope_version"),
                resultSet.getString("payload_algorithm"),
                resultSet.getString("wrapping_algorithm"),
                resultSet.getString("ciphertext_locator"),
                resultSet.getString("ciphertext_sha256"),
                resultSet.getBytes("payload_nonce"),
                resultSet.getBytes("payload_tag"),
                resultSet.getString("kek_id"),
                resultSet.getBytes("wrapped_dek"),
                resultSet.getBytes("wrapping_nonce"),
                resultSet.getBytes("wrapping_tag"),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("rewrapped_at", OffsetDateTime.class).toInstant()
        );
    }

    private static OffsetDateTime offset(java.time.Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
