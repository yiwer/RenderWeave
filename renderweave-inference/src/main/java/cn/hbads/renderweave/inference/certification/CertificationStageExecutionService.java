package cn.hbads.renderweave.inference.certification;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Converts an exact J1 plus durable reservation into narrowly scoped per-call permits. */
public final class CertificationStageExecutionService {
    private final CertificationStageExecutionStore store;
    private final ImageOnlyCertificationPreflight preflight;

    public CertificationStageExecutionService(CertificationStageExecutionStore store) {
        this(store, new ImageOnlyCertificationPreflight());
    }

    CertificationStageExecutionService(
            CertificationStageExecutionStore store,
            ImageOnlyCertificationPreflight preflight
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.preflight = Objects.requireNonNull(preflight, "preflight");
    }

    public void openStage(
            ImageOnlyCertificationAuthorization authorization,
            FrozenCertificationCycle cycle,
            FrozenImageOnlyCertificationManifest manifest,
            ProfileCertificationProgress progress,
            Instant now
    ) {
        preflight.requireProviderZeroProof(authorization, cycle, manifest, progress, now);
        store.open(authorization, now);
    }

    public void startRun(
            String authorizationId,
            UUID runId,
            AuthorizedCertificationCase authorizedCase,
            Instant now
    ) {
        store.startRun(authorizationId, runId, authorizedCase, now);
    }

    public void openProfileSuccessorDiagnostic(
            ImageOnlyCertificationAuthorization authorization,
            ProfileSuccessorDiagnosticManifest manifest,
            Instant now
    ) {
        preflight.requireProfileSuccessorDiagnosticProviderZeroProof(
                authorization, manifest, now
        );
        store.open(authorization, now);
    }

    public CertificationProviderCallPermit reserveCall(
            String authorizationId,
            UUID runId,
            int attemptOrdinal,
            long maximumModelTokens,
            long maximumCostMicrosCny,
            Instant now
    ) {
        return store.reserveCall(authorizationId, runId, attemptOrdinal,
                maximumModelTokens, maximumCostMicrosCny, now);
    }

    public void settleCall(
            UUID reservationId,
            long actualModelTokens,
            long actualCostMicrosCny,
            Instant now
    ) {
        store.settleCall(reservationId, actualModelTokens, actualCostMicrosCny, now);
    }

    public void closeStage(String authorizationId, String reasonCode, Instant now) {
        store.close(authorizationId, reasonCode, now);
    }

    public CertificationStageLedgerSnapshot snapshot(String authorizationId) {
        return store.snapshot(authorizationId);
    }
}
