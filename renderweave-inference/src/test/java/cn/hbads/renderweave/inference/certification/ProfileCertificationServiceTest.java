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
    private static final String SHA = "a9fe98e1cfa4b7cc126db1f74601fdebe60526a1c999924daf189ed5f1ac5eb0";

    @Test
    void stagesAreOrderedAndAnyFailureClosesTheCycleWithoutPatchRerun() {
        var store = new MemoryStore();
        var service = new ProfileCertificationService(store);
        var manifest = manifest();
        var cycle = cycle(manifest);
        service.start(cycle, manifest);
        assertEquals(CertificationStage.CANARY_5, service.progress(cycle.cycleId()).nextStage());

        assertReason("PROFILE_CERTIFICATION_STAGE_REORDERED", () -> service.recordStage(
                cycle.cycleId(), outcome(CertificationStage.DEV_20, 18), T0.plusSeconds(1)));
        service.recordStage(cycle.cycleId(), outcome(CertificationStage.CANARY_5, 4),
                T0.plusSeconds(2));
        assertEquals(ProfileCertificationStatus.FAILED, service.status(cycle.cycleId()));
        assertReason("PROFILE_CERTIFICATION_CYCLE_TERMINAL", () -> service.recordStage(
                cycle.cycleId(), outcome(CertificationStage.CANARY_5, 5), T0.plusSeconds(3)));
        assertReason("PROFILE_CERTIFICATION_GRANT_NOT_READY", () -> service.grant(
                cycle.cycleId(), "production-policy-j1:test", grantEvidence(), T0.plusSeconds(4)));
        assertEquals(List.of(0, 1), store.events(cycle.cycleId()).stream()
                .map(ProfileCertificationEvent::sequence).toList());
    }

    @Test
    void grantAndRevokeAreAppendOnlyEventsOutsideImmutableProfileBytes() {
        var store = new MemoryStore();
        var service = new ProfileCertificationService(store);
        var manifest = manifest();
        var cycle = cycle(manifest);
        service.start(cycle, manifest);
        service.recordStage(cycle.cycleId(), outcome(CertificationStage.CANARY_5, 5), T0.plusSeconds(1));
        assertEquals(CertificationStage.DEV_20, service.progress(cycle.cycleId()).nextStage());
        service.recordStage(cycle.cycleId(), outcome(CertificationStage.DEV_20, 18), T0.plusSeconds(2));
        service.recordStage(cycle.cycleId(), outcome(CertificationStage.FINAL_60, 54), T0.plusSeconds(3));
        service.grant(cycle.cycleId(), "production-policy-j1:owner-20260817",
                grantEvidence(), T0.plusSeconds(4));

        var active = service.requireRecord(cycle.cycleId());
        assertEquals(ProfileCertificationStatus.GRANTED, active.status());
        assertEquals(SHA, active.profileSha256());
        assertEquals(Map.of(
                CertificationStage.CANARY_5, 5,
                CertificationStage.DEV_20, 18,
                CertificationStage.FINAL_60, 54
        ), active.acceptedCases());
        assertEquals(Map.of(
                CertificationStage.CANARY_5, 5,
                CertificationStage.DEV_20, 18,
                CertificationStage.FINAL_60, 54
        ), active.acceptanceThresholds());
        assertEquals(Map.of(
                CertificationStage.CANARY_5, stageEvidence(CertificationStage.CANARY_5),
                CertificationStage.DEV_20, stageEvidence(CertificationStage.DEV_20),
                CertificationStage.FINAL_60, stageEvidence(CertificationStage.FINAL_60)
        ), active.stageEvidenceIdentities());
        assertEquals(CertificationAuthorityInventory.loadCanonical().canonicalSha256(),
                active.authorityInventorySha256());

        service.revoke(cycle.cycleId(), "PROFILE_IDENTITY_DRIFT",
                revocationEvidence(), T0.plusSeconds(5));
        assertEquals(ProfileCertificationStatus.REVOKED,
                service.requireRecord(cycle.cycleId()).status());
        assertReason("PROFILE_CERTIFICATION_CYCLE_TERMINAL", () -> service.revoke(
                cycle.cycleId(), "SECOND_REVOKE", revocationEvidence(), T0.plusSeconds(6)));
        assertEquals(List.of(0, 1, 2, 3, 4, 5), store.events(cycle.cycleId()).stream()
                .map(ProfileCertificationEvent::sequence).toList());
    }

    @Test
    void historicalV46CanNeverReceiveANewProductionGrant() {
        var store = new MemoryStore();
        var service = new ProfileCertificationService(store);
        var manifest = manifest(
                cn.hbads.renderweave.inference.provider.ProfileRunBudgetPolicy
                        .IMAGE_ONLY_V46_PROFILE_ID,
                "22f561c88b30fabbf3ba660bcfe203fb570975f770ff122f2ce1c7216454ac0c");
        var cycle = cycle(manifest);
        service.start(cycle, manifest);
        service.recordStage(cycle.cycleId(), outcome(CertificationStage.CANARY_5, 5),
                T0.plusSeconds(1));
        service.recordStage(cycle.cycleId(), outcome(CertificationStage.DEV_20, 18),
                T0.plusSeconds(2));
        service.recordStage(cycle.cycleId(), outcome(CertificationStage.FINAL_60, 54),
                T0.plusSeconds(3));

        assertReason("PROFILE_CERTIFICATION_PROFILE_SUPERSEDED", () -> service.grant(
                cycle.cycleId(), "production-policy-j1:owner-20260817",
                grantEvidence(), T0.plusSeconds(4)));
        assertEquals(ProfileCertificationStatus.READY_TO_GRANT,
                service.status(cycle.cycleId()));
    }

    @Test
    void startBindsTheCanonicalInventoryAndManifestAndReplayRejectsIdentityDrift() {
        var manifest = manifest();
        var store = new MemoryStore();
        var service = new ProfileCertificationService(store);
        var driftedInventory = new FrozenCertificationCycle(
                UUID.randomUUID(), manifest.profileId(), manifest.profileSha256(),
                manifest.manifestIdentity(), manifest.evaluatorIdentity(), "f".repeat(64), T0);
        assertReason("PROFILE_CERTIFICATION_AUTHORITY_INVENTORY_DRIFT",
                () -> service.start(driftedInventory, manifest));

        var cycle = cycle(manifest);
        service.start(cycle, manifest);
        var start = store.events(cycle.cycleId()).getFirst();
        store.append(new ProfileCertificationEvent(
                UUID.randomUUID(), cycle.cycleId(), 1, start.profileId(), "f".repeat(64),
                start.manifestIdentity(), start.evaluatorIdentity(), start.authorityInventorySha256(),
                ProfileCertificationEvent.EventType.STAGE_PASSED,
                CertificationStage.CANARY_5, 5, 5, 5,
                stageEvidence(CertificationStage.CANARY_5),
                null, null, T0.plusSeconds(1)));
        assertReason("PROFILE_CERTIFICATION_EVENT_IDENTITY_DRIFT",
                () -> service.status(cycle.cycleId()));
    }

    private static FrozenCertificationCycle cycle(FrozenImageOnlyCertificationManifest manifest) {
        return new FrozenCertificationCycle(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                manifest.profileId(), manifest.profileSha256(), manifest.manifestIdentity(),
                manifest.evaluatorIdentity(),
                CertificationAuthorityInventory.loadCanonical().canonicalSha256(), T0
        );
    }

    private static FrozenImageOnlyCertificationManifest manifest() {
        return manifest(
                cn.hbads.renderweave.inference.provider.ProfileRunBudgetPolicy
                        .IMAGE_ONLY_V47_PROFILE_ID,
                SHA);
    }

    private static FrozenImageOnlyCertificationManifest manifest(
            String profileId,
            String profileSha256
    ) {
        var canaries = new ArrayList<CertificationCanaryCase>();
        for (var index = 1; index <= 5; index++) {
            canaries.add(new CertificationCanaryCase("owner-canary-" + index,
                    String.format("%064x", index)));
        }
        return new ImageOnlyCertificationManifestFactory().create(
                profileId, profileSha256, canaries, "image-only-certification-seed-v1");
    }

    private static CertificationStageOutcome outcome(CertificationStage stage, int accepted) {
        return new CertificationStageOutcome(stage, accepted, stage.caseCount(),
                stageEvidence(stage));
    }

    private static String stageEvidence(CertificationStage stage) {
        return "renderweave-image-only-certification-stage-evidence/1.0:"
                + switch (stage) {
                    case CANARY_5 -> "a".repeat(64);
                    case DEV_20 -> "b".repeat(64);
                    case FINAL_60 -> "c".repeat(64);
                    case PROFILE_SUCCESSOR_DIAGNOSTIC_1 ->
                            throw new IllegalArgumentException("diagnostic is not scored");
                };
    }

    private static String grantEvidence() {
        return "renderweave-image-only-certification-grant-evidence/1.0:" + "d".repeat(64);
    }

    private static String revocationEvidence() {
        return "renderweave-image-only-certification-revocation-evidence/1.0:" + "e".repeat(64);
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
