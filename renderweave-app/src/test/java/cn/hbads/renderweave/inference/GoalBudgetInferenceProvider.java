package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.provider.InferenceProvider;
import cn.hbads.renderweave.inference.provider.ProviderCostEstimator;
import cn.hbads.renderweave.inference.provider.ProviderInferenceRequest;
import cn.hbads.renderweave.inference.provider.ProviderInferenceResponse;

import java.time.Clock;
import java.util.Objects;

/** Paid-harness decorator that closes the cross-ledger token gate immediately before delegation. */
final class GoalBudgetInferenceProvider implements InferenceProvider {
    private final VisualEvaluationAuthorization authorization;
    private final VisualEvaluationGoalBudget goalBudget;
    private final InferenceProvider delegate;
    private final Clock clock;

    GoalBudgetInferenceProvider(
            VisualEvaluationAuthorization authorization,
            VisualEvaluationGoalBudget goalBudget,
            InferenceProvider delegate,
            Clock clock
    ) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.goalBudget = Objects.requireNonNull(goalBudget, "goalBudget");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ProviderInferenceResponse complete(ProviderInferenceRequest request) {
        var reservation = goalBudget.reserve(authorization, request, clock.instant());
        var response = delegate.complete(request);
        goalBudget.settle(
                java.util.UUID.fromString(reservation.reservationId()), response.usage(),
                ProviderCostEstimator.estimateMicrosCny(request.profile(), response.usage()),
                clock.instant()
        );
        if (!request.profile().model().equals(response.model())) {
            throw new IllegalStateException("VISUAL_EVALUATION_PROVIDER_MODEL_MISMATCH");
        }
        return response;
    }

    @Override
    public boolean configured() {
        return delegate.configured();
    }
}
