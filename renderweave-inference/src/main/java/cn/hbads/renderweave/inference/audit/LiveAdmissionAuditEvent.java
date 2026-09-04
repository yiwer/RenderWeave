package cn.hbads.renderweave.inference.audit;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One payload-free Live Admission Audit fact. Only opaque identities, digests, fixed codes,
 * usage/cost and time may appear here; image bytes, filenames, OCR text, prompts, responses,
 * PII, secrets and chain-of-thought are forbidden by the admission contract.
 */
public record LiveAdmissionAuditEvent(
        UUID runId,
        int sequence,
        String eventCode,
        String actorId,
        UUID confirmationId,
        UUID reservationId,
        UUID callAuthorizationId,
        Integer attemptOrdinal,
        String inputFingerprint,
        String profileId,
        String profileSha256,
        String decisionCode,
        Long usageInputTokens,
        Long usageOutputTokens,
        Long costMicrosCny,
        Instant occurredAt,
        String previousEventDigest,
        String eventDigest
) {
    public LiveAdmissionAuditEvent {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(eventCode, "eventCode");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(previousEventDigest, "previousEventDigest");
        Objects.requireNonNull(eventDigest, "eventDigest");
        // PostgreSQL timestamptz carries microsecond resolution; truncate so persisted and
        // replayed digests always agree regardless of platform clock granularity.
        occurredAt = occurredAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if (sequence < 1) {
            throw new IllegalArgumentException("Audit sequence starts at 1");
        }
        if (!LiveAdmissionAuditChain.EVENT_CODES.contains(eventCode)) {
            throw new IllegalArgumentException("Audit event code is not in the closed set");
        }
        requireOpaque(actorId, "actorId");
        requireSha256(inputFingerprint, "inputFingerprint");
        requireSha256(profileSha256, "profileSha256");
        requireDigestOrPlaceholder(previousEventDigest, "previousEventDigest");
        requireDigestOrPlaceholder(eventDigest, "eventDigest");
        if (profileId != null && !profileId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,191}")) {
            throw new IllegalArgumentException("Audit profile identity is not opaque-safe");
        }
        if (decisionCode != null && !decisionCode.matches("[A-Z][A-Z0-9_]{2,95}")) {
            throw new IllegalArgumentException("Audit decision code is invalid");
        }
        if (attemptOrdinal != null && (attemptOrdinal < 0 || attemptOrdinal > 11)) {
            throw new IllegalArgumentException("Audit attempt ordinal is out of range");
        }
        requireNonNegative(usageInputTokens, "usageInputTokens");
        requireNonNegative(usageOutputTokens, "usageOutputTokens");
        requireNonNegative(costMicrosCny, "costMicrosCny");
    }

    public Optional<String> optionalActorId() {
        return Optional.ofNullable(actorId);
    }

    public Optional<UUID> optionalConfirmationId() {
        return Optional.ofNullable(confirmationId);
    }

    public Optional<UUID> optionalReservationId() {
        return Optional.ofNullable(reservationId);
    }

    public Optional<UUID> optionalCallAuthorizationId() {
        return Optional.ofNullable(callAuthorizationId);
    }

    public Optional<Integer> optionalAttemptOrdinal() {
        return Optional.ofNullable(attemptOrdinal);
    }

    private static void requireOpaque(String value, String name) {
        if (value != null && !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,191}")) {
            throw new IllegalArgumentException("Audit " + name + " is not an opaque identity");
        }
    }

    private static void requireSha256(String value, String name) {
        if (value != null && !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Audit " + name + " is not a lowercase SHA-256 hex");
        }
    }

    private static void requireDigestOrPlaceholder(String value, String name) {
        if (!value.matches("[0-9a-f]{64}") && !value.isEmpty()) {
            throw new IllegalArgumentException("Audit " + name + " is not a lowercase SHA-256 hex");
        }
    }

    private static void requireNonNegative(Long value, String name) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException("Audit " + name + " must be non-negative");
        }
    }
}
