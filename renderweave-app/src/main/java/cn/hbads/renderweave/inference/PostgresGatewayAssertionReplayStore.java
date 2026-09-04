package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.admission.GatewayAssertionReplayStore;
import cn.hbads.renderweave.inference.admission.GatewayRequestIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

@Repository
public class PostgresGatewayAssertionReplayStore implements GatewayAssertionReplayStore {
    private final JdbcClient jdbcClient;

    public PostgresGatewayAssertionReplayStore(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
    }

    @Override
    @Transactional
    public boolean consume(GatewayRequestIdentity identity, Instant acceptedAt) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(acceptedAt, "acceptedAt");
        return jdbcClient.sql("""
                        insert into gateway_assertion_replay (
                            jti, actor_id, request_id, method, request_path,
                            idempotency_key_digest, key_id, issued_at, expires_at, accepted_at
                        ) values (
                            :jti, :actorId, :requestId, :method, :requestPath,
                            :idempotencyKeyDigest, :keyId, :issuedAt, :expiresAt, :acceptedAt
                        ) on conflict (jti) do nothing
                        """)
                .param("jti", identity.jti())
                .param("actorId", identity.actorId())
                .param("requestId", identity.requestId())
                .param("method", identity.method())
                .param("requestPath", identity.path())
                .param("idempotencyKeyDigest", identity.idempotencyKeyDigest())
                .param("keyId", identity.keyId())
                .param("issuedAt", offset(identity.issuedAt()))
                .param("expiresAt", offset(identity.expiresAt()))
                .param("acceptedAt", offset(acceptedAt))
                .update() == 1;
    }

    private static OffsetDateTime offset(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }
}
