package cn.hbads.renderweave.inference.certification;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProfileCertificationServiceTest {
    private static final Instant T0 = Instant.parse("2026-08-17T06:30:00Z");
    private static final String SHA = "22f561c88b30fabbf3ba660bcfe203fb570975f770ff122f2ce1c7216454ac0c";

    @Test
    void stagesAreOrderedAndAnyFailureClosesTheCycleWithoutPatchRerun() {
        var store = new MemoryStore();
        var service = new ProfileCertificationService(store);
        var cycle = cycle();
        service.start(cycle);

        assertReason("PROFILE_CERTIFICATION_STAGE_REORDERED", () -> service.recordStage(
                cycle.cycleId(), outcome(CertificationStage.DEV_20, 18), T0.plusSeconds(1)));
        service.recordStage(cycle.cycleId(), outcome(CertificationStage.CANARY_5, 4),
                T0.plusSeconds(2));
        assertEquals(ProfileCertificationStatus.FAILED, service.status(cycle.cycleId()));
        assertReason("PROFILE_CERTIFICATION_CYCLE_TERMINAL", () -> service.recordStage(
                cycle.cycleId(), outcome(CertificationStage.CANARY_5, 5), T0.plusSeconds(3)));
        assertReason("PROFILE_CERTIFICATION_GRANT_NOT_READY", () -> service.grant(
                cycle.cycleId(), "production-policy-j1:test", "evidence:grant", T0.plusSeconds(4)));
        assertEquals(List.of(0, 1), store.events(cycle.cycleId()).stream()
                .map(ProfileCertificationEvent::sequence).toList());
    }

    @Test
    void grantAndRevokeAreAppendOnlyEventsOutsideImmutableProfileBytes() {
        var store = new MemoryStore();
        var service = new ProfileCertificationService(store);
        var cycle = cycle();
        service.start(cycle);
        service.recordStage(cycle.cycleId(), outcome(CertificationStage.CANARY_5, 5), T0.plusSeconds(1));
        service.recordStage(cycle.cycleId(), outcome(CertificationStage.DEV_20, 18), T0.plusSeconds(2));
        service.recordStage(cycle.cycleId(), outcome(CertificationStage.FINAL_60, 54), T0.plusSeconds(3));
        service.grant(cycle.cycleId(), "production-policy-j1:owner-20260817",
                "evidence:independent-replay", T0.plusSeconds(4));

        var active = service.requireRecord(cycle.cycleId());
        assertEquals(ProfileCertificationStatus.GRANTED, active.status());
        assertEquals(SHA, active.profileSha256());
        assertEquals(Map.of(
                CertificationStage.CANARY_5, 5,
                CertificationStage.DEV_20, 18,
                CertificationStage.FINAL_60, 54
        ), active.acceptedCases());

        service.revoke(cycle.cycleId(), "PROFILE_IDENTITY_DRIFT",
                "evidence:revocation", T0.plusSeconds(5));
        assertEquals(ProfileCertificationStatus.REVOKED,
                service.requireRecord(cycle.cycleId()).status());
        assertReason("PROFILE_CERTIFICATION_CYCLE_TERMINAL", () -> service.revoke(
                cycle.cycleId(), "SECOND_REVOKE", "evidence:second", T0.plusSeconds(6)));
        assertEquals(List.of(0, 1, 2, 3, 4, 5), store.events(cycle.cycleId()).stream()
                .map(ProfileCertificationEvent::sequence).toList());
    }

    private static FrozenCertificationCycle cycle() {
        return new FrozenCertificationCycle(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "dashscope-qwen38-max-product-v46-hybrid-generic", SHA,
                "renderweave-image-only-certification-manifest/1.0:" + "a".repeat(64),
                "renderweave-image-only-certification-evaluator/1.0:" + "b".repeat(64), T0
        );
    }

    private static CertificationStageOutcome outcome(CertificationStage stage, int accepted) {
        return new CertificationStageOutcome(stage, accepted, stage.caseCount(),
                "evidence:" + stage.name().toLowerCase());
    }

    private static void assertReason(String reason, Runnable action) {
        var failure = assertThrows(ProfileCertificationViolation.class, action::run);
        assertEquals(reason, failure.reasonCode());
    }

    private static final class MemoryStore implements ProfileCertificationStore {
        private final Map<UUID, List<ProfileCertificationEvent>> byCycle = new java.util.HashMap<>();

        @Override
        public void append(ProfileCertificationEvent event) {
            var events = byCycle.computeIfAbsent(event.cycleId(), ignored -> new ArrayList<>());
            if (events.stream().anyMatch(existing -> existing.sequence() == event.sequence())) {
                throw new IllegalStateException("duplicate sequence");
            }
            events.add(event);
        }

        @Override
        public List<ProfileCertificationEvent> events(UUID cycleId) {
            return List.copyOf(byCycle.getOrDefault(cycleId, List.of()));
        }
    }
}
