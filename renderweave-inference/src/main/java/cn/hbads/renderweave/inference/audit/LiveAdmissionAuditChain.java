package cn.hbads.renderweave.inference.audit;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * Domain-separated digest chain for the Live Admission Audit. Each event digest commits to the
 * exact previous digest, so any duplicate, reorder, deletion, tamper or gap in a run's sequence
 * fails independent replay.
 */
public final class LiveAdmissionAuditChain {
    public static final String DIGEST_DOMAIN = "renderweave-live-admission-audit/1.0/event";
    public static final String GENESIS_DOMAIN = "renderweave-live-admission-audit/1.0/genesis";
    public static final String GENESIS_DIGEST = sha256(GENESIS_DOMAIN);

    public static final Set<String> EVENT_CODES = Set.of(
            "LIVE_RUN_ADMITTED",
            "ADMISSION_REJECTED_POLICY",
            "ADMISSION_REJECTED_EGRESS",
            "RUN_DRAINED_POLICY",
            "RUN_DRAINED_EGRESS",
            "CALL_AUTHORIZED",
            "CALL_DISPATCH_SUCCEEDED",
            "CALL_DISPATCH_FAILED",
            "CALL_ATTEMPT_AMBIGUOUS"
    );

    private LiveAdmissionAuditChain() { }

    public static String genesisDigest() {
        return GENESIS_DIGEST;
    }

    /** Recomputes the domain-separated digest of one event, ignoring its stored digest field. */
    public static String digest(LiveAdmissionAuditEvent event) {
        return sha256(
                DIGEST_DOMAIN,
                event.runId().toString(),
                Integer.toString(event.sequence()),
                event.eventCode(),
                event.actorId() == null ? "" : event.actorId(),
                event.confirmationId() == null ? "" : event.confirmationId().toString(),
                event.reservationId() == null ? "" : event.reservationId().toString(),
                event.callAuthorizationId() == null ? "" : event.callAuthorizationId().toString(),
                event.attemptOrdinal() == null ? "" : Integer.toString(event.attemptOrdinal()),
                event.inputFingerprint() == null ? "" : event.inputFingerprint(),
                event.profileId() == null ? "" : event.profileId(),
                event.profileSha256() == null ? "" : event.profileSha256(),
                event.decisionCode() == null ? "" : event.decisionCode(),
                event.usageInputTokens() == null ? "" : Long.toString(event.usageInputTokens()),
                event.usageOutputTokens() == null ? "" : Long.toString(event.usageOutputTokens()),
                event.costMicrosCny() == null ? "" : Long.toString(event.costMicrosCny()),
                Long.toString(event.occurredAt().getEpochSecond()),
                Integer.toString(event.occurredAt().getNano()),
                event.previousEventDigest()
        );
    }

    /** Chains {@code event} after {@code previousDigest} and returns the recomputed digest. */
    public static LiveAdmissionAuditEvent chained(LiveAdmissionAuditEvent event, String previousDigest) {
        var chained = new LiveAdmissionAuditEvent(
                event.runId(), event.sequence(), event.eventCode(), event.actorId(),
                event.confirmationId(), event.reservationId(), event.callAuthorizationId(),
                event.attemptOrdinal(), event.inputFingerprint(), event.profileId(),
                event.profileSha256(), event.decisionCode(), event.usageInputTokens(),
                event.usageOutputTokens(), event.costMicrosCny(), event.occurredAt(),
                previousDigest, ""
        );
        return new LiveAdmissionAuditEvent(
                chained.runId(), chained.sequence(), chained.eventCode(), chained.actorId(),
                chained.confirmationId(), chained.reservationId(), chained.callAuthorizationId(),
                chained.attemptOrdinal(), chained.inputFingerprint(), chained.profileId(),
                chained.profileSha256(), chained.decisionCode(), chained.usageInputTokens(),
                chained.usageOutputTokens(), chained.costMicrosCny(), chained.occurredAt(),
                previousDigest, digest(chained)
        );
    }

    /** Independent replay verdict for one run's audit events. */
    public enum Verdict {
        OK,
        MISSING,
        DUPLICATE,
        REORDERED,
        TAMPERED
    }

    /**
     * Replays a run's audit events and classifies the chain. Events may arrive in storage order;
     * the replay never trusts stored digests without recomputing them.
     */
    public static Verdict verify(List<LiveAdmissionAuditEvent> storedOrder) {
        if (storedOrder.isEmpty()) return Verdict.OK;
        for (var event : storedOrder) {
            if (!event.runId().equals(storedOrder.getFirst().runId())) {
                return Verdict.REORDERED;
            }
        }
        var sorted = new ArrayList<>(storedOrder);
        sorted.sort(Comparator.comparingInt(LiveAdmissionAuditEvent::sequence));
        var seen = new java.util.HashSet<Integer>();
        for (var event : sorted) {
            if (!seen.add(event.sequence())) return Verdict.DUPLICATE;
        }
        if (sorted.getFirst().sequence() != 1 || sorted.getLast().sequence() != sorted.size()) {
            return Verdict.MISSING;
        }
        for (var index = 1; index < storedOrder.size(); index++) {
            if (storedOrder.get(index).sequence() <= storedOrder.get(index - 1).sequence()) {
                return Verdict.REORDERED;
            }
        }
        var expectedPrevious = GENESIS_DIGEST;
        for (var event : sorted) {
            if (!expectedPrevious.equals(event.previousEventDigest())) return Verdict.TAMPERED;
            var recomputed = digest(event);
            if (!recomputed.equals(event.eventDigest())) return Verdict.TAMPERED;
            expectedPrevious = recomputed;
        }
        return Verdict.OK;
    }

    private static String sha256(String domain, String... values) {
        var digest = digest();
        update(digest, domain);
        for (var value : values) {
            update(digest, value);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void update(MessageDigest digest, String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JVM", impossible);
        }
    }
}
