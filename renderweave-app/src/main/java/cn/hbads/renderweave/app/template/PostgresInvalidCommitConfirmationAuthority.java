package cn.hbads.renderweave.app.template;

import cn.hbads.renderweave.template.spi.InvalidCommitConfirmationAuthority;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.beans.factory.annotation.Autowired;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Objects;

/** PostgreSQL-backed five-minute opaque invalid-save confirmation authority. */
@Repository
public class PostgresInvalidCommitConfirmationAuthority
        implements InvalidCommitConfirmationAuthority {
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final int TOKEN_ATTEMPTS = 3;

    private final JdbcClient jdbc;
    private final Clock clock;
    private final SecureRandom random;

    @Autowired
    PostgresInvalidCommitConfirmationAuthority(JdbcClient jdbc) {
        this(jdbc, Clock.systemUTC(), new SecureRandom());
    }

    PostgresInvalidCommitConfirmationAuthority(
            JdbcClient jdbc,
            Clock clock,
            SecureRandom random
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
    }

    @Override
    public IssueOutcome issue(Claims claims) {
        Objects.requireNonNull(claims, "claims");
        var expiresAt = clock.instant().plus(TTL);
        for (int attempt = 0; attempt < TOKEN_ATTEMPTS; attempt++) {
            var token = randomToken();
            try {
                jdbc.sql("""
                                insert into template_invalid_commit_confirmation (
                                    confirmation_token, operation, actor_id, owner_scope,
                                    template_id, expected_revision, schema_key,
                                    schema_version_tag, content_hash, problem_fingerprint,
                                    dependency_snapshot_fingerprint, expires_at
                                ) values (
                                    :token, :operation, :actorId, :ownerScope,
                                    :templateId, :expectedRevision, :schemaKey,
                                    :schemaVersionTag, :contentHash, :problemFingerprint,
                                    :dependencyFingerprint, :expiresAt
                                )
                                """)
                        .param("token", token)
                        .param("operation", claims.operation().name())
                        .param("actorId", claims.actorId())
                        .param("ownerScope", claims.ownerScope().value())
                        .param("templateId", claims.templateId().value())
                        .param("expectedRevision", claims.expectedRevision())
                        .param("schemaKey", claims.staticSchema().schemaKey().value())
                        .param("schemaVersionTag", claims.staticSchema().versionTag().value())
                        .param("contentHash", claims.contentHash())
                        .param("problemFingerprint", claims.problemFingerprint())
                        .param("dependencyFingerprint",
                                claims.dependencySnapshotFingerprint())
                        .param("expiresAt", OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC))
                        .update();
                return new Issued(token, expiresAt);
            } catch (DuplicateKeyException collision) {
                // Retry with fresh entropy; never weaken or derive a token.
            } catch (DataAccessException unavailable) {
                return new IssueUnavailable();
            }
        }
        return new IssueUnavailable();
    }

    @Override
    public VerifyOutcome verify(String confirmationToken, Claims expectedClaims) {
        Objects.requireNonNull(expectedClaims, "expectedClaims");
        if (confirmationToken == null || !confirmationToken.matches("[0-9a-f]{64}")) {
            return new Invalid();
        }
        try {
            var stored = jdbc.sql("""
                            select operation, actor_id, owner_scope, template_id,
                                   expected_revision, schema_key, schema_version_tag,
                                   content_hash, problem_fingerprint,
                                   dependency_snapshot_fingerprint, expires_at
                            from template_invalid_commit_confirmation
                            where confirmation_token = :token
                            """)
                    .param("token", confirmationToken)
                    .query((resultSet, rowNumber) -> new StoredClaims(
                            resultSet.getString("operation"),
                            resultSet.getString("actor_id"),
                            resultSet.getString("owner_scope"),
                            resultSet.getString("template_id"),
                            resultSet.getLong("expected_revision"),
                            resultSet.getString("schema_key"),
                            resultSet.getString("schema_version_tag"),
                            resultSet.getString("content_hash"),
                            resultSet.getString("problem_fingerprint"),
                            resultSet.getString("dependency_snapshot_fingerprint"),
                            resultSet.getObject("expires_at", OffsetDateTime.class).toInstant()
                    ))
                    .optional();
            if (stored.isEmpty()) {
                return new Invalid();
            }
            var claims = stored.orElseThrow();
            if (!claims.expiresAt().isAfter(clock.instant())) {
                return new Expired();
            }
            return claims.matches(expectedClaims) ? new Verified() : new Stale();
        } catch (DataAccessException unavailable) {
            return new VerifyUnavailable();
        }
    }

    private String randomToken() {
        var bytes = new byte[32];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private record StoredClaims(
            String operation,
            String actorId,
            String ownerScope,
            String templateId,
            long expectedRevision,
            String schemaKey,
            String schemaVersionTag,
            String contentHash,
            String problemFingerprint,
            String dependencyFingerprint,
            Instant expiresAt
    ) {
        private boolean matches(Claims expected) {
            return operation.equals(expected.operation().name())
                    && actorId.equals(expected.actorId())
                    && ownerScope.equals(expected.ownerScope().value())
                    && templateId.equals(expected.templateId().value())
                    && expectedRevision == expected.expectedRevision()
                    && schemaKey.equals(expected.staticSchema().schemaKey().value())
                    && schemaVersionTag.equals(expected.staticSchema().versionTag().value())
                    && contentHash.equals(expected.contentHash())
                    && problemFingerprint.equals(expected.problemFingerprint())
                    && dependencyFingerprint.equals(
                    expected.dependencySnapshotFingerprint());
        }
    }
}
