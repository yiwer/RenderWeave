package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.admission.GatewayAssertionAuthority;
import cn.hbads.renderweave.inference.admission.GatewayRequestIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
class PostgresGatewayAssertionReplayStoreTest {
    private static final Instant T0 = Instant.parse("2026-08-18T08:00:00Z");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private PostgresGatewayAssertionReplayStore store;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void clear() {
        jdbcClient.sql("truncate table gateway_assertion_replay").update();
    }

    @Test
    void exactOpaqueMutationIdentityIsConsumedOnlyOnce() {
        var identity = identity("jti-once");

        assertThat(store.consume(identity, T0.plusSeconds(1))).isTrue();
        assertThat(store.consume(identity, T0.plusSeconds(2))).isFalse();

        var row = jdbcClient.sql("""
                        select actor_id, request_id, method, request_path,
                               idempotency_key_digest, key_id
                        from gateway_assertion_replay where jti = :jti
                        """)
                .param("jti", identity.jti())
                .query((resultSet, rowNumber) -> new String[] {
                        resultSet.getString("actor_id"), resultSet.getString("request_id"),
                        resultSet.getString("method"), resultSet.getString("request_path"),
                        resultSet.getString("idempotency_key_digest"), resultSet.getString("key_id")
                })
                .single();
        assertThat(row).containsExactly(
                identity.actorId(), identity.requestId(), identity.method(), identity.path(),
                identity.idempotencyKeyDigest(), identity.keyId()
        );
    }

    @Test
    void concurrentConsumersHaveOneDurableWinner() throws Exception {
        var identity = identity("jti-race");
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> {
                start.await();
                return store.consume(identity, T0.plusSeconds(1));
            });
            var second = executor.submit(() -> {
                start.await();
                return store.consume(identity, T0.plusSeconds(1));
            });
            start.countDown();
            assertThat(java.util.List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(jdbcClient.sql("select count(*) from gateway_assertion_replay")
                .query(Long.class).single()).isEqualTo(1L);
    }

    private static GatewayRequestIdentity identity(String jti) {
        return new GatewayRequestIdentity(
                "actor-opaque-001", "request-opaque-001", jti,
                "POST", "/api/v1/inference-runs/live",
                GatewayAssertionAuthority.idempotencyKeyDigest("idem-001"),
                T0, T0.plusSeconds(60), "gateway-2026-08-a"
        );
    }
}
