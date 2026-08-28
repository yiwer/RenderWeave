package cn.hbads.renderweave.app.rendering;

import cn.hbads.renderweave.rendering.api.Evaluator.RenderRequestId;
import cn.hbads.renderweave.rendering.spi.CapabilityStateStore;
import cn.hbads.renderweave.rendering.spi.CapabilityStateStore.SaveRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(properties = {
        "renderweave.rendering.capability-state.key="
                + "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=",
        "renderweave.template.single-owner.enabled=true",
        "renderweave.template.single-owner.owner-scope=test-owner",
        "renderweave.template.single-owner.capabilities=template.create,template.read"
})
class CapabilityStateStoreAdapterTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private PostgresCapabilityStateStore store;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void clearRecords() {
        jdbc.sql("truncate table rendering_capability_state").update();
    }

    private static SaveRequest request(String requestId, String fingerprint, long expiresAt) {
        return new SaveRequest(
                new RenderRequestId(requestId),
                fingerprint,
                "{\"clock\":1750000000}".getBytes(StandardCharsets.UTF_8),
                1_750_000_000L,
                expiresAt);
    }

    @Test
    void saveThenLoadRoundTripsEncryptedState() {
        var saved = (CapabilityStateStore.SaveOutcome.Stored) store.save(
                request("00000000-0000-4000-8000-000000000001", "sha256:fp-1", 9_999_999_999L));

        var loaded = (CapabilityStateStore.LoadOutcome.Loaded) store.load(
                new RenderRequestId("00000000-0000-4000-8000-000000000001"), "sha256:fp-1");

        assertThat(new String(loaded.sealedState(), StandardCharsets.UTF_8))
                .isEqualTo("{\"clock\":1750000000}");
        assertThat(loaded.expiresAtEpochSecond()).isEqualTo(9_999_999_999L);
    }

    @Test
    void cipherBytesNeverContainPlaintextState() {
        var saved = (CapabilityStateStore.SaveOutcome.Stored) store.save(
                request("00000000-0000-4000-8000-000000000002", "sha256:fp-2", 9_999_999_999L));

        var cipher = jdbc.sql("""
                        select state_cipher from rendering_capability_state
                        where capability_state_id = :id
                        """)
                .param("id", saved.id().value())
                .query((rs, rowNum) -> rs.getBytes("state_cipher"))
                .single();

        var cipherText = new String(cipher, StandardCharsets.ISO_8859_1);
        assertThat(cipherText).doesNotContain("clock");
    }

    @Test
    void sameKeySameFingerprintReplaysExistingId() {
        var first = (CapabilityStateStore.SaveOutcome.Stored) store.save(
                request("00000000-0000-4000-8000-000000000003", "sha256:fp-3", 9_999_999_999L));
        var replayed = (CapabilityStateStore.SaveOutcome.Replayed) store.save(
                request("00000000-0000-4000-8000-000000000003", "sha256:fp-3", 9_999_999_999L));

        assertThat(replayed.id()).isEqualTo(first.id());
    }

    @Test
    void concurrentSameRequestLinearizesToStoredAndReplayed() throws Exception {
        var request = request(
                "00000000-0000-4000-8000-000000000007",
                "sha256:fp-7",
                9_999_999_999L);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> store.save(request));
            var second = executor.submit(() -> store.save(request));
            var outcomes = java.util.List.of(first.get(), second.get());

            assertThat(outcomes).anyMatch(CapabilityStateStore.SaveOutcome.Stored.class::isInstance);
            assertThat(outcomes).anyMatch(CapabilityStateStore.SaveOutcome.Replayed.class::isInstance);
        }
    }

    @Test
    void sameKeyDifferentFingerprintConflicts() {
        store.save(request("00000000-0000-4000-8000-000000000004", "sha256:fp-4", 9_999_999_999L));

        var outcome = store.save(request("00000000-0000-4000-8000-000000000004", "sha256:other", 9_999_999_999L));

        assertThat(outcome)
                .isInstanceOf(CapabilityStateStore.SaveOutcome.FingerprintConflict.class);
    }

    @Test
    void loadWithWrongFingerprintConflicts() {
        var saved = (CapabilityStateStore.SaveOutcome.Stored) store.save(
                request("00000000-0000-4000-8000-000000000005", "sha256:fp-5", 9_999_999_999L));

        var outcome = store.load(
                new RenderRequestId("00000000-0000-4000-8000-000000000005"),
                "sha256:intruder");

        assertThat(outcome)
                .isInstanceOf(CapabilityStateStore.LoadOutcome.LoadFingerprintConflict.class);
    }

    @Test
    void unknownRequestIsMissing() {
        var outcome = store.load(
                new RenderRequestId("00000000-0000-4000-8000-000000000099"),
                "sha256:fp");
        assertThat(outcome).isInstanceOf(CapabilityStateStore.LoadOutcome.Missing.class);
    }

    @Test
    void expiredRecordIsMissingAndSweepRemovesIt() {
        var saved = (CapabilityStateStore.SaveOutcome.Stored) store.save(
                new SaveRequest(
                        new RenderRequestId("00000000-0000-4000-8000-000000000006"),
                        "sha256:fp-6",
                        "{\"clock\":1}".getBytes(StandardCharsets.UTF_8),
                        0L,
                        1L));

        var loadOutcome = store.load(
                new RenderRequestId("00000000-0000-4000-8000-000000000006"),
                "sha256:fp-6");
        assertThat(loadOutcome).isInstanceOf(CapabilityStateStore.LoadOutcome.Missing.class);

        var swept = store.sweepExpired();
        assertThat(swept).isGreaterThanOrEqualTo(1);
        var remaining = jdbc.sql("select count(*) as c from rendering_capability_state")
                .query((rs, rowNum) -> rs.getLong("c"))
                .single();
        assertThat(remaining).isZero();
    }
}
