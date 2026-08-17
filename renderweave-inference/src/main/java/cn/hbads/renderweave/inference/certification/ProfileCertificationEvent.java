package cn.hbads.renderweave.inference.certification;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ProfileCertificationEvent(
        UUID eventId,
        UUID cycleId,
        int sequence,
        String profileId,
        String profileSha256,
        String manifestIdentity,
        String evaluatorIdentity,
        String authorityInventorySha256,
        EventType eventType,
        CertificationStage stage,
        Integer acceptedCases,
        Integer totalCases,
        Integer acceptanceThreshold,
        String evidenceIdentity,
        String authorityReference,
        String reasonCode,
        Instant recordedAt
) {
    public ProfileCertificationEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(cycleId, "cycleId");
        if (sequence < 0) throw new IllegalArgumentException("PROFILE_CERTIFICATION_SEQUENCE_INVALID");
        new FrozenCertificationCycle(cycleId, profileId, profileSha256, manifestIdentity,
                evaluatorIdentity, authorityInventorySha256,
                Objects.requireNonNull(recordedAt, "recordedAt"));
        Objects.requireNonNull(eventType, "eventType");
        if (evidenceIdentity == null || !evidenceIdentity.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{2,255}")) {
            throw new IllegalArgumentException("PROFILE_CERTIFICATION_EVIDENCE_IDENTITY_INVALID");
        }
        var stageEvent = eventType == EventType.STAGE_PASSED || eventType == EventType.CYCLE_FAILED;
        if (stageEvent != (stage != null && acceptedCases != null && totalCases != null
                && acceptanceThreshold != null)) {
            throw new IllegalArgumentException("PROFILE_CERTIFICATION_EVENT_STAGE_SHAPE_INVALID");
        }
        if (stageEvent) {
            new CertificationStageOutcome(stage, acceptedCases, totalCases, evidenceIdentity);
            if (acceptanceThreshold != stage.acceptanceThreshold()) {
                throw new IllegalArgumentException("PROFILE_CERTIFICATION_EVENT_THRESHOLD_DRIFT");
            }
        }
        if (eventType == EventType.CYCLE_STARTED && !evidenceIdentity.equals(manifestIdentity)) {
            throw new IllegalArgumentException("PROFILE_CERTIFICATION_START_EVIDENCE_INVALID");
        }
        if (eventType == EventType.CERTIFICATION_GRANTED && !evidenceIdentity.matches(
                "renderweave-image-only-certification-grant-evidence/1\\.0:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("PROFILE_CERTIFICATION_GRANT_EVIDENCE_INVALID");
        }
        if (eventType == EventType.CERTIFICATION_REVOKED && !evidenceIdentity.matches(
                "renderweave-image-only-certification-revocation-evidence/1\\.0:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("PROFILE_CERTIFICATION_REVOCATION_EVIDENCE_INVALID");
        }
        if ((eventType == EventType.CERTIFICATION_GRANTED) != (authorityReference != null)) {
            throw new IllegalArgumentException("PROFILE_CERTIFICATION_EVENT_AUTHORITY_SHAPE_INVALID");
        }
        if (authorityReference != null
                && !authorityReference.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{2,255}")) {
            throw new IllegalArgumentException("PROFILE_CERTIFICATION_AUTHORITY_REFERENCE_INVALID");
        }
        if ((eventType == EventType.CYCLE_FAILED || eventType == EventType.CERTIFICATION_REVOKED)
                != (reasonCode != null)) {
            throw new IllegalArgumentException("PROFILE_CERTIFICATION_EVENT_REASON_SHAPE_INVALID");
        }
        if (reasonCode != null && !reasonCode.matches("[A-Z][A-Z0-9_]{2,127}")) {
            throw new IllegalArgumentException("PROFILE_CERTIFICATION_REASON_INVALID");
        }
    }

    public enum EventType {
        CYCLE_STARTED,
        STAGE_PASSED,
        CYCLE_FAILED,
        CERTIFICATION_GRANTED,
        CERTIFICATION_REVOKED
    }
}
