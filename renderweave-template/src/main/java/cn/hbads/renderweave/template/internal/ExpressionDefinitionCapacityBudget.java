package cn.hbads.renderweave.template.internal;

import cn.hbads.renderweave.template.api.DesignDslAuthority;
import cn.hbads.renderweave.template.api.DesignInputExpressionCapacityAuthority;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/**
 * Request-local budget for the static Expression-definition facts that are fully known
 * during DesignDSL admission. Thresholds and terminal semantics remain owned by the
 * shared Template capacity authority.
 */
final class ExpressionDefinitionCapacityBudget {

    private final DesignInputExpressionCapacityAuthority capacity;
    private long sourceUtf8BytesTotal;
    private long inputsTotal;
    private long mappingCasesTotal;
    private long astNodesTotal;

    ExpressionDefinitionCapacityBudget(DesignInputExpressionCapacityAuthority capacity) {
        this.capacity = Objects.requireNonNull(capacity, "capacity");
    }

    void reserveSourceUtf8Bytes(long bytes, String pointer)
            throws DesignDslFailureException {
        reserve(DesignDslAuthority.Limit.EXPRESSION_SOURCE_UTF8_BYTES_PER_EXPRESSION,
                bytes, pointer);
        sourceUtf8BytesTotal = add(
                sourceUtf8BytesTotal,
                bytes,
                DesignDslAuthority.Limit.EXPRESSION_SOURCE_UTF8_BYTES_TOTAL,
                pointer
        );
        reserve(DesignDslAuthority.Limit.EXPRESSION_SOURCE_UTF8_BYTES_TOTAL,
                sourceUtf8BytesTotal, pointer);
    }

    void reserveInputs(long count, String pointer) throws DesignDslFailureException {
        reserve(DesignDslAuthority.Limit.EXPRESSION_INPUTS_PER_EXPRESSION, count, pointer);
        inputsTotal = add(
                inputsTotal,
                count,
                DesignDslAuthority.Limit.EXPRESSION_INPUTS_TOTAL,
                pointer
        );
        reserve(DesignDslAuthority.Limit.EXPRESSION_INPUTS_TOTAL, inputsTotal, pointer);
    }

    void reserveMappingCases(long count, String pointer) throws DesignDslFailureException {
        reserve(DesignDslAuthority.Limit.EXPRESSION_MAPPING_CASES_PER_DEFINITION,
                count, pointer);
        mappingCasesTotal = add(
                mappingCasesTotal,
                count,
                DesignDslAuthority.Limit.EXPRESSION_MAPPING_CASES_TOTAL,
                pointer
        );
        reserve(DesignDslAuthority.Limit.EXPRESSION_MAPPING_CASES_TOTAL,
                mappingCasesTotal, pointer);
    }

    void reserveAstNode(long perExpressionCandidate, String pointer)
            throws DesignDslFailureException {
        reserve(DesignDslAuthority.Limit.EXPRESSION_AST_NODES_PER_EXPRESSION,
                perExpressionCandidate, pointer);
        var totalCandidate = add(
                astNodesTotal,
                1L,
                DesignDslAuthority.Limit.EXPRESSION_AST_NODES_TOTAL,
                pointer
        );
        reserve(DesignDslAuthority.Limit.EXPRESSION_AST_NODES_TOTAL,
                totalCandidate, pointer);
        astNodesTotal = totalCandidate;
    }

    void reserveDefinitionGraphEdges(long count, String pointer)
            throws DesignDslFailureException {
        reserve(DesignDslAuthority.Limit.EXPRESSION_DEFINITION_GRAPH_EDGES, count, pointer);
    }

    void reserveDefinitionChainDepth(long depth, String pointer)
            throws DesignDslFailureException {
        reserve(DesignDslAuthority.Limit.EXPRESSION_DEFINITION_CHAIN_DEPTH, depth, pointer);
    }

    void reserveAdmittedDecimal(BigDecimal value, String pointer)
            throws DesignDslFailureException {
        var normalized = value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
        reserve(DesignDslAuthority.Limit.EXPRESSION_ADMITTED_DECIMAL_PRECISION_DIGITS,
                normalized.precision(), pointer);
        reserve(DesignDslAuthority.Limit.EXPRESSION_ADMITTED_DECIMAL_SCALE_MIN,
                normalized.scale(), pointer);
        reserve(DesignDslAuthority.Limit.EXPRESSION_ADMITTED_DECIMAL_SCALE_MAX,
                normalized.scale(), pointer);
    }

    void reserveExplicitRoundingScale(BigDecimal value, String pointer)
            throws DesignDslFailureException {
        var normalized = value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
        reserve(DesignDslAuthority.Limit.EXPRESSION_EXPLICIT_ROUNDING_SCALE_MAX,
                normalized.toPlainString(), pointer);
    }

    private long add(
            long current,
            long increment,
            DesignDslAuthority.Limit limit,
            String pointer
    ) throws DesignDslFailureException {
        try {
            return Math.addExact(current, increment);
        } catch (ArithmeticException overflow) {
            reject(limit, pointer);
            throw new IllegalStateException("unreachable expression capacity rejection");
        }
    }

    private void reserve(
            DesignDslAuthority.Limit limit,
            long observedValue,
            String pointer
    ) throws DesignDslFailureException {
        reserve(limit, Long.toString(observedValue), pointer);
    }

    private void reserve(
            DesignDslAuthority.Limit limit,
            String observedValue,
            String pointer
    ) throws DesignDslFailureException {
        DesignInputExpressionCapacityAuthority.Decision decision;
        try {
            decision = capacity.evaluate(new DesignInputExpressionCapacityAuthority.Observation(
                    limit.id(), observedValue));
        } catch (RuntimeException unavailable) {
            reject(limit, pointer);
            throw new IllegalStateException("unreachable expression capacity rejection");
        }
        if (!(decision instanceof DesignInputExpressionCapacityAuthority.Accepted)) {
            reject(limit, pointer);
        }
    }

    private void reject(DesignDslAuthority.Limit limit, String pointer)
            throws DesignDslFailureException {
        throw new DesignDslFailureException(new DesignDslAuthority.Rejected(
                DesignDslAuthority.FailureCode.DESIGN_DSL_LIMIT_EXCEEDED,
                DesignDslAuthority.FailureStage.DESIGN_SEMANTIC_VALIDATION,
                pointer,
                Optional.of(limit)
        ));
    }
}
