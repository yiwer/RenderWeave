package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.rendering.api.EvaluationStage;
import cn.hbads.renderweave.rendering.api.RenderingProblem;
import cn.hbads.renderweave.rendering.api.RenderingProblem.LimitId;
import cn.hbads.renderweave.rendering.api.RenderingProblem.ProblemCode;

import java.math.BigInteger;
import java.util.Objects;
import java.util.Optional;

/**
 * Frozen {@code renderweave-design-input-expression-capacity-guard/1.0} seam.
 *
 * <p>The isolated design/input/expression fixture and the production Expression analyzer consume
 * this same catalog. The guard owns capacity identity and public Rendering taxonomy; grammar and
 * type validity remain the analyzer's responsibility.</p>
 */
final class DesignInputExpressionCapacityGuard {

    enum Limit {
        EXPLICIT_ROUNDING_SCALE_MAX(
                "expression.explicitRoundingScaleMax",
                64,
                ProblemCode.EXPRESSION_LIMIT_EXCEEDED,
                EvaluationStage.TEMPLATE_CLOSURE);

        private final String id;
        private final BigInteger maximumInclusive;
        private final ProblemCode problemCode;
        private final EvaluationStage publicStage;

        Limit(
                String id,
                long maximumInclusive,
                ProblemCode problemCode,
                EvaluationStage publicStage
        ) {
            this.id = id;
            this.maximumInclusive = BigInteger.valueOf(maximumInclusive);
            this.problemCode = problemCode;
            this.publicStage = publicStage;
        }
    }

    Optional<RenderingProblem> admit(Limit limit, long observedValue) {
        if (observedValue < 0) {
            throw new IllegalArgumentException("observedValue must be non-negative");
        }
        return admit(limit, BigInteger.valueOf(observedValue));
    }

    Optional<RenderingProblem> admit(Limit limit, BigInteger observedValue) {
        Objects.requireNonNull(limit, "limit");
        Objects.requireNonNull(observedValue, "observedValue");
        if (observedValue.signum() < 0) {
            throw new IllegalArgumentException("observedValue must be non-negative");
        }
        if (observedValue.compareTo(limit.maximumInclusive) <= 0) {
            return Optional.empty();
        }
        return Optional.of(RenderingProblem.ofLimit(
                limit.problemCode,
                limit.publicStage,
                new LimitId(limit.id)));
    }
}
