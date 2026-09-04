package cn.hbads.renderweave.inference.admission;

import java.time.Instant;
import java.util.Objects;

/** Replays immutable confirmation constraints at dequeue and before every Provider request. */
public final class ExternalTransferConfirmationGuard {
    public void authorizeProviderRequest(
            ExternalTransferConfirmation confirmation,
            ExternalTransferNotice.Identity currentNotice,
            String actualProfileSha256,
            LiveInputManifest actualManifest,
            boolean firstProviderAttemptAlreadyDispatched,
            ProviderAttemptKnowledge attemptKnowledge,
            Instant now
    ) {
        Objects.requireNonNull(confirmation, "confirmation");
        Objects.requireNonNull(currentNotice, "currentNotice");
        Objects.requireNonNull(actualManifest, "actualManifest");
        Objects.requireNonNull(attemptKnowledge, "attemptKnowledge");
        Objects.requireNonNull(now, "now");
        if (attemptKnowledge == ProviderAttemptKnowledge.AMBIGUOUS) {
            throw problem(
                    "LIVE_PROVIDER_ATTEMPT_AMBIGUOUS",
                    "An ambiguous Provider attempt cannot be replayed automatically."
            );
        }
        if (!confirmation.profileSha256().equals(actualProfileSha256)) {
            throw problem("LIVE_PROFILE_IDENTITY_MISMATCH", "The exact Profile identity has changed.");
        }
        if (!confirmation.manifestVersion().equals(actualManifest.version())
                || !confirmation.manifestSha256().equals(actualManifest.sha256())) {
            throw problem("LIVE_INPUT_MANIFEST_MISMATCH", "The exact normalized input manifest has changed.");
        }
        if (!firstProviderAttemptAlreadyDispatched) {
            if (!confirmation.noticeIdentity().equals(currentNotice)) {
                throw problem("LIVE_TRANSFER_NOTICE_STALE", "The external-transfer notice is stale.");
            }
            if (now.isAfter(confirmation.dispatchNotAfter())) {
                throw problem("LIVE_CONFIRMATION_EXPIRED", "The first Provider dispatch window has expired.");
            }
        }
        if (now.isAfter(confirmation.providerCallsNotAfter())) {
            throw problem("LIVE_PROVIDER_CALL_WINDOW_EXPIRED", "The Provider call window has expired.");
        }
    }

    private static LiveAdmissionProblem problem(String code, String message) {
        return new LiveAdmissionProblem(code, message);
    }

    public enum ProviderAttemptKnowledge {
        CLEAR,
        AMBIGUOUS
    }
}
