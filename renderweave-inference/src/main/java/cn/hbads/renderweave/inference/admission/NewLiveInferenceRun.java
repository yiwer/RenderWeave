package cn.hbads.renderweave.inference.admission;

import cn.hbads.renderweave.inference.run.NewInferenceRun;

import java.util.Objects;

/** One transaction command for run, exact manifest and immutable transfer confirmation. */
public record NewLiveInferenceRun(
        NewInferenceRun run,
        LiveInputManifest manifest,
        ExternalTransferNotice notice,
        ExternalTransferConfirmation confirmation
) {
    public NewLiveInferenceRun {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(notice, "notice");
        Objects.requireNonNull(confirmation, "confirmation");
        if (!run.runId().equals(confirmation.runId())
                || !run.requestFingerprint().equals(confirmation.requestFingerprint())
                || !run.normalizedInput().inputFingerprint().equals(manifest.sha256())
                || !confirmation.manifestVersion().equals(manifest.version())
                || !confirmation.manifestSha256().equals(manifest.sha256())
                || !confirmation.noticeIdentity().equals(notice.identity())
                || !run.normalizedInput().profileId().equals(confirmation.profileId())
                || !run.createdAt().equals(confirmation.confirmedAt())) {
            throw new IllegalArgumentException("Live run, manifest, notice and confirmation identities must agree");
        }
    }
}
