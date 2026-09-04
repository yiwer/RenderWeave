package cn.hbads.renderweave.inference.certification;

import java.time.Instant;
import java.util.UUID;

/** Durable, atomic calls/tokens/cost boundary for one exact certification-stage J1. */
public interface CertificationStageExecutionStore {
    void open(ImageOnlyCertificationAuthorization authorization, Instant openedAt);

    void startRun(
            String authorizationId,
            UUID runId,
            AuthorizedCertificationCase authorizedCase,
            Instant startedAt
    );

    CertificationProviderCallPermit reserveCall(
            String authorizationId,
            UUID runId,
            int attemptOrdinal,
            long maximumModelTokens,
            long maximumCostMicrosCny,
            Instant reservedAt
    );

    void settleCall(
            UUID reservationId,
            long actualModelTokens,
            long actualCostMicrosCny,
            Instant settledAt
    );

    void close(String authorizationId, String reasonCode, Instant closedAt);

    CertificationStageLedgerSnapshot snapshot(String authorizationId);
}
