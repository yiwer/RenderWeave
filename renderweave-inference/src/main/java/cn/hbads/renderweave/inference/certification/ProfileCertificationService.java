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

    public void start(FrozenCertificationCycle cycle) {
        Objects.requireNonNull(cycle, "cycle");
        if (!store.events(cycle.cycleId()).isEmpty()) fail("PROFILE_CERTIFICATION_CYCLE_EXISTS");
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
        var ordered = CertificationStage.values();
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
        if (status(events) != ProfileCertificationStatus.READY_TO_GRANT) {
            fail("PROFILE_CERTIFICATION_GRANT_NOT_READY");
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
        requireMonotonicTime(events, recordedAt);
        store.append(copy(events.getFirst(), events.size(), CERTIFICATION_REVOKED,
                null, null, null, evidenceIdentity, null, reasonCode, recordedAt));
    }

    public ProfileCertificationStatus status(UUID cycleId) {
        return status(requireEvents(cycleId));
    }

    public ProfileCertificationRecord requireRecord(UUID cycleId) {
        var events = requireEvents(cycleId);
        var grant = events.stream().filter(item -> item.eventType() == CERTIFICATION_GRANTED)
                .findFirst().orElseThrow(() -> new ProfileCertificationViolation(
                        "PROFILE_CERTIFICATION_RECORD_NOT_GRANTED"));
        var accepted = new EnumMap<CertificationStage, Integer>(CertificationStage.class);
        events.stream().filter(item -> item.eventType() == STAGE_PASSED)
                .forEach(item -> accepted.put(item.stage(), item.acceptedCases()));
        var revoke = events.stream().filter(item -> item.eventType() == CERTIFICATION_REVOKED).findFirst();
        return new ProfileCertificationRecord(
                grant.cycleId(), grant.profileId(), grant.profileSha256(), grant.manifestIdentity(),
                grant.evaluatorIdentity(), revoke.isPresent()
                ? ProfileCertificationStatus.REVOKED : ProfileCertificationStatus.GRANTED,
                accepted, grant.authorityReference(), grant.evidenceIdentity(), grant.recordedAt(),
                revoke.map(ProfileCertificationEvent::recordedAt),
                revoke.map(ProfileCertificationEvent::reasonCode)
        );
    }

    private List<ProfileCertificationEvent> requireEvents(UUID cycleId) {
        Objects.requireNonNull(cycleId, "cycleId");
        var events = store.events(cycleId);
        if (events.isEmpty()) fail("PROFILE_CERTIFICATION_CYCLE_UNKNOWN");
        for (var index = 0; index < events.size(); index++) {
            if (events.get(index).sequence() != index) fail("PROFILE_CERTIFICATION_EVENT_SEQUENCE_INVALID");
        }
        return events;
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
        return passed == CertificationStage.values().length
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
                type, stage, accepted, total, evidence, authority, reason, at);
    }

    private static ProfileCertificationEvent copy(
            ProfileCertificationEvent start, int sequence, ProfileCertificationEvent.EventType type,
            CertificationStage stage, Integer accepted, Integer total, String evidence,
            String authority, String reason, Instant at
    ) {
        return new ProfileCertificationEvent(UUID.randomUUID(), start.cycleId(), sequence,
                start.profileId(), start.profileSha256(), start.manifestIdentity(), start.evaluatorIdentity(),
                type, stage, accepted, total, evidence, authority, reason, at);
    }

    private static void fail(String reasonCode) {
        throw new ProfileCertificationViolation(reasonCode);
    }
}
