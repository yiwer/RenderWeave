package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.certification.CertificationStage;
import cn.hbads.renderweave.inference.certification.CertificationStageOutcome;
import cn.hbads.renderweave.inference.certification.FrozenCertificationCycle;
import cn.hbads.renderweave.inference.certification.ProfileCertificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
class PostgresProfileCertificationStoreTest {
    private static final Instant T0 = Instant.parse("2026-08-17T07:00:00Z");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private PostgresProfileCertificationStore store;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void clearEvents() {
        jdbcClient.sql("truncate table profile_certification_event").update();
    }

    @Test
    void eventsRoundTripInOrderAndCannotBeUpdatedDeletedOrDuplicated() {
        var cycle = new FrozenCertificationCycle(
                UUID.randomUUID(), "dashscope-qwen38-max-product-v46-hybrid-generic",
                "22f561c88b30fabbf3ba660bcfe203fb570975f770ff122f2ce1c7216454ac0c",
                "renderweave-image-only-certification-manifest/1.0:" + "a".repeat(64),
                "renderweave-image-only-certification-evaluator/1.0:" + "b".repeat(64), T0
        );
        var service = new ProfileCertificationService(store);
        service.start(cycle);
        service.recordStage(cycle.cycleId(), new CertificationStageOutcome(
                CertificationStage.CANARY_5, 5, 5, "evidence:canary"), T0.plusSeconds(1));

        var events = store.events(cycle.cycleId());
        assertThat(events).extracting(item -> item.sequence()).containsExactly(0, 1);
        assertThatThrownBy(() -> store.append(events.getFirst()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcClient.sql("""
                        update profile_certification_event set reason_code = 'MUTATED'
                        where event_id = :eventId
                        """).param("eventId", events.getFirst().eventId()).update())
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcClient.sql("""
                        delete from profile_certification_event where event_id = :eventId
                        """).param("eventId", events.getFirst().eventId()).update())
                .isInstanceOf(DataAccessException.class);
        assertThat(store.events(cycle.cycleId())).isEqualTo(events);
    }
}
