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
        SOURCE_UTF8_BYTES_PER_EXPRESSION(
                "expression.sourceUtf8BytesPerExpression",
                65_536,
                ProblemCode.EXPRESSION_LIMIT_EXCEEDED,
                EvaluationStage.TEMPLATE_CLOSURE),
        SOURCE_UTF8_BYTES_TOTAL(
                "expression.sourceUtf8BytesTotal",
                1_048_576,
                ProblemCode.EXPRESSION_LIMIT_EXCEEDED,
                EvaluationStage.TEMPLATE_CLOSURE),
        INPUTS_PER_EXPRESSION(
                "expression.inputsPerExpression",
                32,
                ProblemCode.EXPRESSION_LIMIT_EXCEEDED,
                EvaluationStage.TEMPLATE_CLOSURE),
        INPUTS_TOTAL(
                "expression.inputsTotal",
                4_096,
                ProblemCode.EXPRESSION_LIMIT_EXCEEDED,
                EvaluationStage.TEMPLATE_CLOSURE),
        MAPPING_CASES_PER_DEFINITION(
                "expression.mappingCasesPerDefinition",
                256,
                ProblemCode.EXPRESSION_LIMIT_EXCEEDED,
                EvaluationStage.TEMPLATE_CLOSURE),
        MAPPING_CASES_TOTAL(
                "expression.mappingCasesTotal",
                8_192,
                ProblemCode.EXPRESSION_LIMIT_EXCEEDED,
                EvaluationStage.TEMPLATE_CLOSURE),
        AST_NODES_PER_EXPRESSION(
                "expression.astNodesPerExpression",
                4_096,
                ProblemCode.EXPRESSION_LIMIT_EXCEEDED,
                EvaluationStage.TEMPLATE_CLOSURE),
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

    final class SourceBudget {
        private BigInteger totalUtf8Bytes = BigInteger.ZERO;

        Optional<RenderingProblem> admit(long sourceUtf8Bytes) {
            var perExpressionProblem = DesignInputExpressionCapacityGuard.this.admit(
                    Limit.SOURCE_UTF8_BYTES_PER_EXPRESSION,
                    sourceUtf8Bytes);
            if (perExpressionProblem.isPresent()) {
                return perExpressionProblem;
            }
            totalUtf8Bytes = totalUtf8Bytes.add(BigInteger.valueOf(sourceUtf8Bytes));
            return DesignInputExpressionCapacityGuard.this.admit(
                    Limit.SOURCE_UTF8_BYTES_TOTAL,
                    totalUtf8Bytes);
        }
    }

    final class InputBudget {
        private BigInteger totalInputs = BigInteger.ZERO;

        Optional<RenderingProblem> admit(long expressionInputs) {
            var perExpressionProblem = DesignInputExpressionCapacityGuard.this.admit(
                    Limit.INPUTS_PER_EXPRESSION,
                    expressionInputs);
            if (perExpressionProblem.isPresent()) {
                return perExpressionProblem;
            }
            var projectedTotal = totalInputs.add(BigInteger.valueOf(expressionInputs));
            var totalProblem = DesignInputExpressionCapacityGuard.this.admit(
                    Limit.INPUTS_TOTAL,
                    projectedTotal);
            if (totalProblem.isEmpty()) {
                totalInputs = projectedTotal;
            }
            return totalProblem;
        }
    }

    final class MappingCaseBudget {
        private BigInteger totalCases = BigInteger.ZERO;

        Optional<RenderingProblem> admit(long definitionCases) {
            var perDefinitionProblem = DesignInputExpressionCapacityGuard.this.admit(
                    Limit.MAPPING_CASES_PER_DEFINITION,
                    definitionCases);
            if (perDefinitionProblem.isPresent()) {
                return perDefinitionProblem;
            }
            var projectedTotal = totalCases.add(BigInteger.valueOf(definitionCases));
            var totalProblem = DesignInputExpressionCapacityGuard.this.admit(
                    Limit.MAPPING_CASES_TOTAL,
                    projectedTotal);
            if (totalProblem.isEmpty()) {
                totalCases = projectedTotal;
            }
            return totalProblem;
        }
    }

    SourceBudget newSourceBudget() {
        return new SourceBudget();
    }

    InputBudget newInputBudget() {
        return new InputBudget();
    }

    MappingCaseBudget newMappingCaseBudget() {
        return new MappingCaseBudget();
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
