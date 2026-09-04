package cn.hbads.renderweave.inference.audit;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class LiveAdmissionAuditChainTest {
    private static final UUID RUN_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final Instant T0 = Instant.parse("2026-08-18T09:00:00Z");

    @Test
    void chainedEventsReplayToOkVerdict() {
        var chain = chain(3);
        assertEquals(LiveAdmissionAuditChain.Verdict.OK, LiveAdmissionAuditChain.verify(chain));
        assertEquals(LiveAdmissionAuditChain.GENESIS_DIGEST, chain.getFirst().previousEventDigest());
        for (var event : chain) {
            assertEquals(LiveAdmissionAuditChain.digest(event), event.eventDigest());
        }
    }

    @Test
    void emptyChainIsOk() {
        assertEquals(LiveAdmissionAuditChain.Verdict.OK, LiveAdmissionAuditChain.verify(List.of()));
    }

    @Test
    void duplicatedSequenceFailsClosed() {
        var chain = chain(3);
        var duplicated = new ArrayList<>(chain);
        duplicated.add(chain.get(1));
        assertEquals(LiveAdmissionAuditChain.Verdict.DUPLICATE, LiveAdmissionAuditChain.verify(duplicated));
    }

    @Test
    void reorderedStorageFailsClosed() {
        var chain = chain(3);
        var reordered = new ArrayList<>(List.of(chain.get(0), chain.get(2), chain.get(1)));
        assertEquals(LiveAdmissionAuditChain.Verdict.REORDERED, LiveAdmissionAuditChain.verify(reordered));
    }

    @Test
    void deletedMiddleEventFailsClosed() {
        var chain = chain(4);
        var deleted = new ArrayList<>(chain);
        deleted.remove(1);
        assertEquals(LiveAdmissionAuditChain.Verdict.MISSING, LiveAdmissionAuditChain.verify(deleted));
    }

    @Test
    void missingGenesisEventFailsClosed() {
        var chain = chain(3);
        var tail = new ArrayList<>(chain.subList(1, 3));
        assertEquals(LiveAdmissionAuditChain.Verdict.MISSING, LiveAdmissionAuditChain.verify(tail));
    }

    @Test
    void tamperedCostFailsClosed() {
        var chain = new ArrayList<>(chain(2));
        var victim = chain.get(1);
        chain.set(1, new LiveAdmissionAuditEvent(
                victim.runId(), victim.sequence(), victim.eventCode(), victim.actorId(),
                victim.confirmationId(), victim.reservationId(), victim.callAuthorizationId(),
                victim.attemptOrdinal(), victim.inputFingerprint(), victim.profileId(),
                victim.profileSha256(), victim.decisionCode(), victim.usageInputTokens(),
                victim.usageOutputTokens(), 999_999L, victim.occurredAt(),
                victim.previousEventDigest(), victim.eventDigest()
        ));
        assertEquals(LiveAdmissionAuditChain.Verdict.TAMPERED, LiveAdmissionAuditChain.verify(chain));
    }

    @Test
    void forgedPreviousDigestFailsClosed() {
        var chain = new ArrayList<>(chain(2));
        var victim = chain.get(1);
        chain.set(1, new LiveAdmissionAuditEvent(
                victim.runId(), victim.sequence(), victim.eventCode(), victim.actorId(),
                victim.confirmationId(), victim.reservationId(), victim.callAuthorizationId(),
                victim.attemptOrdinal(), victim.inputFingerprint(), victim.profileId(),
                victim.profileSha256(), victim.decisionCode(), victim.usageInputTokens(),
                victim.usageOutputTokens(), victim.costMicrosCny(), victim.occurredAt(),
                "0".repeat(64), victim.eventDigest()
        ));
        assertEquals(LiveAdmissionAuditChain.Verdict.TAMPERED, LiveAdmissionAuditChain.verify(chain));
    }

    @Test
    void digestCommitsToEveryPayloadFreeField() {
        var event = unsigned(1, "CALL_AUTHORIZED");
        var chained = LiveAdmissionAuditChain.chained(event, LiveAdmissionAuditChain.GENESIS_DIGEST);
        var mutated = new LiveAdmissionAuditEvent(
                chained.runId(), chained.sequence(), "CALL_DISPATCH_FAILED", chained.actorId(),
                chained.confirmationId(), chained.reservationId(), chained.callAuthorizationId(),
                chained.attemptOrdinal(), chained.inputFingerprint(), chained.profileId(),
                chained.profileSha256(), chained.decisionCode(), chained.usageInputTokens(),
                chained.usageOutputTokens(), chained.costMicrosCny(), chained.occurredAt(),
                chained.previousEventDigest(), chained.eventDigest()
        );
        assertNotEquals(LiveAdmissionAuditChain.digest(chained), LiveAdmissionAuditChain.digest(mutated));
    }

    private static List<LiveAdmissionAuditEvent> chain(int length) {
        var events = new ArrayList<LiveAdmissionAuditEvent>();
        var previous = LiveAdmissionAuditChain.GENESIS_DIGEST;
        for (var sequence = 1; sequence <= length; sequence++) {
            var chained = LiveAdmissionAuditChain.chained(
                    unsigned(sequence, sequence == 1 ? "CALL_AUTHORIZED" : "CALL_DISPATCH_SUCCEEDED"),
                    previous
            );
            events.add(chained);
            previous = chained.eventDigest();
        }
        return events;
    }

    private static LiveAdmissionAuditEvent unsigned(int sequence, String code) {
        return new LiveAdmissionAuditEvent(
                RUN_ID, sequence, code, "actor-opaque-001",
                null, null, null, sequence - 1,
                "a".repeat(64), "dashscope-qwen37-flash-v1", "b".repeat(64),
                null, 1000L, 500L, 42L, T0.plusSeconds(sequence), "", ""
        );
    }
}
