package cn.hbads.renderweave.inference.certification;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static cn.hbads.renderweave.inference.certification.ProfileCertificationEvent.EventType.CERTIFICATION_GRANTED;
import static cn.hbads.renderweave.inference.certification.ProfileCertificationEvent.EventType.CERTIFICATION_REVOKED;
import static cn.hbads.renderweave.inference.certification.ProfileCertificationEvent.EventType.CYCLE_FAILED;
import static cn.hbads.renderweave.inference.certification.ProfileCertificationEvent.EventType.CYCLE_STARTED;
import static cn.hbads.renderweave.inference.certification.ProfileCertificationEvent.EventType.STAGE_PASSED;

/** Append-only certification authority; callers cannot rewrite stages or Profile bytes. */
public final class ProfileCertificationService {
    private final ProfileCertificationStore store;

    public ProfileCertificationService(ProfileCertificationStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public void start(
            FrozenCertificationCycle cycle,
            FrozenImageOnlyCertificationManifest manifest
    ) {
        Objects.requireNonNull(cycle, "cycle");
        Objects.requireNonNull(manifest, "manifest");
        if (!store.events(cycle.cycleId()).isEmpty()) fail("PROFILE_CERTIFICATION_CYCLE_EXISTS");
        var inventory = CertificationAuthorityInventory.loadCanonical();
        if (!inventory.canonicalSha256().equals(cycle.authorityInventorySha256())) {
            fail("PROFILE_CERTIFICATION_AUTHORITY_INVENTORY_DRIFT");
        }
        if (!cycle.profileId().equals(manifest.profileId())
                || !cycle.profileSha256().equals(manifest.profileSha256())
                || !cycle.manifestIdentity().equals(manifest.manifestIdentity())
                || !cycle.evaluatorIdentity().equals(manifest.evaluatorIdentity())) {
            fail("PROFILE_CERTIFICATION_MANIFEST_DRIFT");
        }
        store.append(event(cycle, 0, CYCLE_STARTED, null, null, null,
                cycle.manifestIdentity(), null, null, cycle.createdAt()));
    }

    public void recordStage(UUID cycleId, CertificationStageOutcome outcome, Instant recordedAt) {
        Objects.requireNonNull(outcome, "outcome");
        var events = requireEvents(cycleId);
        var current = status(events);
        if (current == ProfileCertificationStatus.FAILED
                || current == ProfileCertificationStatus.GRANTED
                || current == ProfileCertificationStatus.REVOKED) {
            fail("PROFILE_CERTIFICATION_CYCLE_TERMINAL");
        }
        var passedCount = (int) events.stream().filter(item -> item.eventType() == STAGE_PASSED).count();
        var ordered = CertificationStage.scoredStages();
        if (passedCount >= ordered.length || outcome.stage() != ordered[passedCount]) {
            fail("PROFILE_CERTIFICATION_STAGE_REORDERED");
        }
        requireMonotonicTime(events, recordedAt);
        var start = events.getFirst();
        store.append(copy(start, events.size(), outcome.passed() ? STAGE_PASSED : CYCLE_FAILED,
                outcome.stage(), outcome.acceptedCases(), outcome.totalCases(), outcome.evidenceIdentity(),
                null, outcome.passed() ? null : "STAGE_THRESHOLD_NOT_MET", recordedAt));
    }

    public void grant(UUID cycleId, String productionPolicyAuthorityReference,
                      String evidenceIdentity, Instant recordedAt) {
        var events = requireEvents(cycleId);
        if (!cn.hbads.renderweave.inference.provider.ProfileRunBudgetPolicy
                .IMAGE_ONLY_V47_PROFILE_ID.equals(events.getFirst().profileId())) {
            fail("PROFILE_CERTIFICATION_PROFILE_SUPERSEDED");
        }
        if (status(events) != ProfileCertificationStatus.READY_TO_GRANT) {
            fail("PROFILE_CERTIFICATION_GRANT_NOT_READY");
        }
        if (productionPolicyAuthorityReference == null
                || !productionPolicyAuthorityReference.matches(
                "production-policy-j1:[a-z0-9][a-z0-9-]{2,95}")) {
            fail("PROFILE_CERTIFICATION_PRODUCTION_POLICY_J1_INVALID");
        }
        if (evidenceIdentity == null || !evidenceIdentity.matches(
                "renderweave-image-only-certification-grant-evidence/1\\.0:[0-9a-f]{64}")) {
            fail("PROFILE_CERTIFICATION_GRANT_EVIDENCE_INVALID");
        }
        requireMonotonicTime(events, recordedAt);
        store.append(copy(events.getFirst(), events.size(), CERTIFICATION_GRANTED,
                null, null, null, evidenceIdentity, productionPolicyAuthorityReference, null, recordedAt));
    }

    public void revoke(UUID cycleId, String reasonCode, String evidenceIdentity, Instant recordedAt) {
        var events = requireEvents(cycleId);
        if (status(events) != ProfileCertificationStatus.GRANTED) {
            fail("PROFILE_CERTIFICATION_CYCLE_TERMINAL");
        }
        if (evidenceIdentity == null || !evidenceIdentity.matches(
                "renderweave-image-only-certification-revocation-evidence/1\\.0:[0-9a-f]{64}")) {
            fail("PROFILE_CERTIFICATION_REVOCATION_EVIDENCE_INVALID");
        }
        requireMonotonicTime(events, recordedAt);
        store.append(copy(events.getFirst(), events.size(), CERTIFICATION_REVOKED,
                null, null, null, evidenceIdentity, null, reasonCode, recordedAt));
    }

    public ProfileCertificationStatus status(UUID cycleId) {
        return status(requireEvents(cycleId));
    }

    public ProfileCertificationProgress progress(UUID cycleId) {
        var events = requireEvents(cycleId);
        var current = status(events);
        CertificationStage next = null;
        if (current == ProfileCertificationStatus.IN_PROGRESS) {
            var passed = (int) events.stream()
                    .filter(item -> item.eventType() == STAGE_PASSED).count();
            next = CertificationStage.scoredStages()[passed];
        }
        var start = events.getFirst();
        return new ProfileCertificationProgress(cycleId, current, next,
                start.profileSha256(), start.manifestIdentity(), start.evaluatorIdentity(),
                start.authorityInventorySha256());
    }

    public ProfileCertificationRecord requireRecord(UUID cycleId) {
        var events = requireEvents(cycleId);
        var grant = events.stream().filter(item -> item.eventType() == CERTIFICATION_GRANTED)
                .findFirst().orElseThrow(() -> new ProfileCertificationViolation(
                        "PROFILE_CERTIFICATION_RECORD_NOT_GRANTED"));
        var accepted = new EnumMap<CertificationStage, Integer>(CertificationStage.class);
        var thresholds = new EnumMap<CertificationStage, Integer>(CertificationStage.class);
        var stageEvidence = new EnumMap<CertificationStage, String>(CertificationStage.class);
        events.stream().filter(item -> item.eventType() == STAGE_PASSED)
                .forEach(item -> {
                    accepted.put(item.stage(), item.acceptedCases());
                    thresholds.put(item.stage(), item.acceptanceThreshold());
                    stageEvidence.put(item.stage(), item.evidenceIdentity());
                });
        var revoke = events.stream().filter(item -> item.eventType() == CERTIFICATION_REVOKED).findFirst();
        return new ProfileCertificationRecord(
                grant.cycleId(), grant.profileId(), grant.profileSha256(), grant.manifestIdentity(),
                grant.evaluatorIdentity(), grant.authorityInventorySha256(), revoke.isPresent()
                ? ProfileCertificationStatus.REVOKED : ProfileCertificationStatus.GRANTED,
                accepted, thresholds, stageEvidence,
                grant.authorityReference(), grant.evidenceIdentity(), grant.recordedAt(),
                revoke.map(ProfileCertificationEvent::recordedAt),
                revoke.map(ProfileCertificationEvent::reasonCode)
        );
    }

    private List<ProfileCertificationEvent> requireEvents(UUID cycleId) {
        Objects.requireNonNull(cycleId, "cycleId");
        var events = store.events(cycleId);
        if (events.isEmpty()) fail("PROFILE_CERTIFICATION_CYCLE_UNKNOWN");
        var start = events.getFirst();
        if (start.eventType() != CYCLE_STARTED) {
            fail("PROFILE_CERTIFICATION_EVENT_HISTORY_INVALID");
        }
        if (!CertificationAuthorityInventory.loadCanonical().canonicalSha256()
                .equals(start.authorityInventorySha256())) {
            fail("PROFILE_CERTIFICATION_AUTHORITY_INVENTORY_DRIFT");
        }
        var passedStages = 0;
        var granted = false;
        var terminal = false;
        for (var index = 0; index < events.size(); index++) {
            var event = events.get(index);
            if (event.sequence() != index) fail("PROFILE_CERTIFICATION_EVENT_SEQUENCE_INVALID");
            if (!event.cycleId().equals(cycleId)
                    || !event.profileId().equals(start.profileId())
                    || !event.profileSha256().equals(start.profileSha256())
                    || !event.manifestIdentity().equals(start.manifestIdentity())
                    || !event.evaluatorIdentity().equals(start.evaluatorIdentity())
                    || !event.authorityInventorySha256().equals(start.authorityInventorySha256())) {
                fail("PROFILE_CERTIFICATION_EVENT_IDENTITY_DRIFT");
            }
            if (index > 0 && event.recordedAt().isBefore(events.get(index - 1).recordedAt())) {
                fail("PROFILE_CERTIFICATION_EVENT_TIME_REORDERED");
            }
            if (index == 0) continue;
            if (terminal) fail("PROFILE_CERTIFICATION_EVENT_HISTORY_INVALID");
            switch (event.eventType()) {
                case CYCLE_STARTED -> fail("PROFILE_CERTIFICATION_EVENT_HISTORY_INVALID");
                case STAGE_PASSED -> {
                    requireExpectedStage(event, passedStages);
                    if (event.acceptedCases() < event.stage().acceptanceThreshold()) {
                        fail("PROFILE_CERTIFICATION_EVENT_HISTORY_INVALID");
                    }
                    passedStages++;
                }
                case CYCLE_FAILED -> {
                    requireExpectedStage(event, passedStages);
                    if (event.acceptedCases() >= event.stage().acceptanceThreshold()) {
                        fail("PROFILE_CERTIFICATION_EVENT_HISTORY_INVALID");
                    }
                    terminal = true;
                }
                case CERTIFICATION_GRANTED -> {
                    if (passedStages != CertificationStage.scoredStages().length || granted
                            || event.authorityReference() == null
                            || !event.authorityReference().matches(
                            "production-policy-j1:[a-z0-9][a-z0-9-]{2,95}")) {
                        fail("PROFILE_CERTIFICATION_EVENT_HISTORY_INVALID");
                    }
                    granted = true;
                }
                case CERTIFICATION_REVOKED -> {
                    if (!granted) fail("PROFILE_CERTIFICATION_EVENT_HISTORY_INVALID");
                    terminal = true;
                }
            }
        }
        return events;
    }

    private static void requireExpectedStage(ProfileCertificationEvent event, int passedStages) {
        if (passedStages >= CertificationStage.scoredStages().length
                || event.stage() != CertificationStage.scoredStages()[passedStages]) {
            fail("PROFILE_CERTIFICATION_EVENT_HISTORY_INVALID");
        }
    }

    private static ProfileCertificationStatus status(List<ProfileCertificationEvent> events) {
        if (events.stream().anyMatch(item -> item.eventType() == CERTIFICATION_REVOKED)) {
            return ProfileCertificationStatus.REVOKED;
        }
        if (events.stream().anyMatch(item -> item.eventType() == CERTIFICATION_GRANTED)) {
            return ProfileCertificationStatus.GRANTED;
        }
        if (events.stream().anyMatch(item -> item.eventType() == CYCLE_FAILED)) {
            return ProfileCertificationStatus.FAILED;
        }
        var passed = events.stream().filter(item -> item.eventType() == STAGE_PASSED).count();
        return passed == CertificationStage.scoredStages().length
                ? ProfileCertificationStatus.READY_TO_GRANT : ProfileCertificationStatus.IN_PROGRESS;
    }

    private static void requireMonotonicTime(List<ProfileCertificationEvent> events, Instant recordedAt) {
        Objects.requireNonNull(recordedAt, "recordedAt");
        if (recordedAt.isBefore(events.getLast().recordedAt())) {
            fail("PROFILE_CERTIFICATION_EVENT_TIME_REORDERED");
        }
    }

    private static ProfileCertificationEvent event(
            FrozenCertificationCycle cycle, int sequence, ProfileCertificationEvent.EventType type,
            CertificationStage stage, Integer accepted, Integer total, String evidence,
            String authority, String reason, Instant at
    ) {
        return new ProfileCertificationEvent(UUID.randomUUID(), cycle.cycleId(), sequence,
                cycle.profileId(), cycle.profileSha256(), cycle.manifestIdentity(), cycle.evaluatorIdentity(),
                cycle.authorityInventorySha256(), type, stage, accepted, total,
                stage == null ? null : stage.acceptanceThreshold(), evidence,
                authority, reason, at);
    }

    private static ProfileCertificationEvent copy(
            ProfileCertificationEvent start, int sequence, ProfileCertificationEvent.EventType type,
            CertificationStage stage, Integer accepted, Integer total, String evidence,
            String authority, String reason, Instant at
    ) {
        return new ProfileCertificationEvent(UUID.randomUUID(), start.cycleId(), sequence,
                start.profileId(), start.profileSha256(), start.manifestIdentity(), start.evaluatorIdentity(),
                start.authorityInventorySha256(), type, stage, accepted, total,
                stage == null ? null : stage.acceptanceThreshold(), evidence,
                authority, reason, at);
    }

    private static void fail(String reasonCode) {
        throw new ProfileCertificationViolation(reasonCode);
    }
}
