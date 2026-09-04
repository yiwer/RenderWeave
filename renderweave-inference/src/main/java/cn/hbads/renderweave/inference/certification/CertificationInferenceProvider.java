package cn.hbads.renderweave.inference.certification;

import cn.hbads.renderweave.inference.provider.InferenceProvider;
import cn.hbads.renderweave.inference.provider.ProviderCostEstimator;
import cn.hbads.renderweave.inference.provider.ProviderInferenceRequest;
import cn.hbads.renderweave.inference.provider.ProviderInferenceResponse;
import cn.hbads.renderweave.inference.profile.InferenceProfileRegistry;

import java.time.Clock;
import java.util.Objects;

/**
 * Certification-only Provider boundary. The durable authorization reservation commits before
 * delegate.complete can observe request bytes; failed/ambiguous calls retain their reservation.
 */
public final class CertificationInferenceProvider implements InferenceProvider {
    private final InferenceProvider delegate;
    private final CertificationStageExecutionService execution;
    private final String authorizationId;
    private final Clock clock;
    private final InferenceProfileRegistry profiles = new InferenceProfileRegistry();

    public CertificationInferenceProvider(
            InferenceProvider delegate,
            CertificationStageExecutionService execution,
            String authorizationId,
            Clock clock
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.execution = Objects.requireNonNull(execution, "execution");
        if (authorizationId == null
                || !authorizationId.matches("[a-z0-9][a-z0-9-]{2,95}")) {
            throw new IllegalArgumentException("CERTIFICATION_PROVIDER_AUTHORIZATION_ID_INVALID");
        }
        this.authorizationId = authorizationId;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public boolean configured() {
        return delegate.configured();
    }

    @Override
    public ProviderInferenceResponse complete(ProviderInferenceRequest request) {
        Objects.requireNonNull(request, "request");
        var ledger = execution.snapshot(authorizationId);
        var exactProfile = profiles.require(ledger.profileId());
        if (!ledger.profileSha256().equals(exactProfile.canonicalSha256())
                || !request.profile().equals(exactProfile.profile())) {
            throw new CertificationStageLedgerViolation(
                    "CERTIFICATION_PROVIDER_PROFILE_MISMATCH");
        }
        var maximumTokens = ProviderCostEstimator.maximumRequestTokens(request);
        var maximumCost = ProviderCostEstimator.maximumRequestCostMicrosCny(request);
        var permit = execution.reserveCall(
                authorizationId, request.runId(), request.attemptOrdinal(),
                maximumTokens, maximumCost, clock.instant());
        if (!permit.grantsProviderEgress()) {
            throw new CertificationStageLedgerViolation(
                    "CERTIFICATION_PROVIDER_EGRESS_NOT_GRANTED");
        }
        var response = delegate.complete(request);
        var actualTokens = Math.addExact(
                response.usage().inputTokens(), response.usage().outputTokens());
        var actualCost = ProviderCostEstimator.estimateMicrosCny(
                request.profile(), response.usage());
        execution.settleCall(
                permit.reservationId(), actualTokens, actualCost, clock.instant());
        return response;
    }
}
