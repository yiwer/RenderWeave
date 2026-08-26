package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.template.api.DesignInputExpressionCapacityAuthority;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.ExpressionAst;
import cn.hbads.renderweave.template.internal.TemplateModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ExpressionDecimalCapacityEvaluationTest {

    @Test
    void operationAndObservableDecimalUseTheInjectedAuthorityWithNormalizedFacts() {
        var recording = new RecordingAuthority();

        var outcome = evaluate("1e32 * 1e32", recording);

        assertInstanceOf(ExpressionEvaluator.EvalValue.class, outcome);
        assertEquals(List.of(
                observation("expression.intermediateDecimalPrecisionDigits", "1"),
                observation("expression.intermediateDecimalScaleMin", "-64"),
                observation("expression.intermediateDecimalScaleMax", "-64"),
                observation("expression.admittedDecimalPrecisionDigits", "1"),
                observation("expression.admittedDecimalScaleMin", "-64"),
                observation("expression.admittedDecimalScaleMax", "-64")
        ), recording.observations);
    }

    @ParameterizedTest
    @MethodSource("defaultLimitCases")
    void defaultAuthorityRejectsTheExactRuntimeAxis(String source, String expectedLimitId) {
        var error = assertInstanceOf(
                ExpressionEvaluator.EvalError.class,
                evaluate(source, TemplateModule.designInputExpressionCapacityAuthority()));

        assertEquals(ExpressionEvaluator.RuntimeFailureKind.DECIMAL_LIMIT_EXCEEDED,
                error.failure().kind());
        assertEquals(expectedLimitId, error.failure().limitId());
    }

    @Test
    void formatDecimalChecksItsRoundedIntermediateBeforeFormattingText() {
        var source = "formatDecimal(" + "9".repeat(300) + ", 0, 0, 'DOWN')";

        var error = assertInstanceOf(
                ExpressionEvaluator.EvalError.class,
                evaluate(source, TemplateModule.designInputExpressionCapacityAuthority()));

        assertEquals("expression.intermediateDecimalPrecisionDigits",
                error.failure().limitId());
    }

    @ParameterizedTest
    @MethodSource("decimalProducingOperations")
    void everyDecimalProducingOperationReservesIntermediateCapacity(String source) {
        var recording = new RecordingAuthority();

        assertInstanceOf(ExpressionEvaluator.EvalValue.class, evaluate(source, recording));

        assertEquals(1, recording.observations.stream().filter(observation ->
                "expression.intermediateDecimalPrecisionDigits"
                        .equals(observation.limitId())).count());
    }

    @Test
    void runtimeZeroAlwaysUsesCanonicalPrecisionAndScaleFacts() {
        var recording = new RecordingAuthority();

        assertInstanceOf(ExpressionEvaluator.EvalValue.class,
                evaluate("0e200 * 1", recording));

        assertEquals(List.of(
                observation("expression.intermediateDecimalPrecisionDigits", "1"),
                observation("expression.intermediateDecimalScaleMin", "0"),
                observation("expression.intermediateDecimalScaleMax", "0"),
                observation("expression.admittedDecimalPrecisionDigits", "1"),
                observation("expression.admittedDecimalScaleMin", "0"),
                observation("expression.admittedDecimalScaleMax", "0")
        ), recording.observations);
    }

    @Test
    void unselectedBranchDoesNotReserveIntermediateDecimalCapacity() {
        var recording = new RecordingAuthority();
        var overflow = "9".repeat(100) + " * " + "9".repeat(100)
                + " * " + "9".repeat(100);

        var outcome = evaluate("if(false, " + overflow + ", 1)", recording);

        assertInstanceOf(ExpressionEvaluator.EvalValue.class, outcome);
        assertFalse(recording.observations.stream()
                .anyMatch(value -> value.limitId().startsWith("expression.intermediateDecimal")));
        assertEquals(List.of(
                observation("expression.admittedDecimalPrecisionDigits", "1"),
                observation("expression.admittedDecimalScaleMin", "0"),
                observation("expression.admittedDecimalScaleMax", "0")
        ), recording.observations);
    }

    @ParameterizedTest
    @MethodSource("failClosedRuntimeCases")
    void injectedAuthorityFailureModesFailClosedAtTheExactRuntimeAxis(
            String expectedLimitId,
            FailureMode mode
    ) {
        var authority = new FailingAuthority(expectedLimitId, mode);

        var error = assertInstanceOf(
                ExpressionEvaluator.EvalError.class,
                evaluate("1 * 1", authority));

        assertEquals(ExpressionEvaluator.RuntimeFailureKind.DECIMAL_LIMIT_EXCEEDED,
                error.failure().kind());
        assertEquals(expectedLimitId, error.failure().limitId());
        assertEquals(expectedLimitId,
                authority.observations.get(authority.observations.size() - 1).limitId(),
                "failed capacity decision must stop all downstream reservations");
    }

    private static Stream<Arguments> defaultLimitCases() {
        var hundredDigits = "9".repeat(100);
        var precisionBoundary = "9".repeat(128);
        return Stream.of(
                Arguments.of(
                        hundredDigits + " * " + hundredDigits + " * " + hundredDigits,
                        "expression.intermediateDecimalPrecisionDigits"),
                Arguments.of("1e64 * 1e64 * 10",
                        "expression.intermediateDecimalScaleMin"),
                Arguments.of("1e-64 * 1e-64 * 0.1",
                        "expression.intermediateDecimalScaleMax"),
                Arguments.of(precisionBoundary + " * " + precisionBoundary,
                        "expression.admittedDecimalPrecisionDigits"),
                Arguments.of("1e64 * 1e64",
                        "expression.admittedDecimalScaleMin"),
                Arguments.of("1e-64 * 1e-64",
                        "expression.admittedDecimalScaleMax")
        );
    }

    private static Stream<Arguments> decimalProducingOperations() {
        return Stream.of(
                Arguments.of("-1"),
                Arguments.of("1 + 1"),
                Arguments.of("2 - 1"),
                Arguments.of("2 * 3"),
                Arguments.of("divide(2, 3, 2, 'HALF_UP')"),
                Arguments.of("round(2.675, 2, 'HALF_EVEN')"),
                Arguments.of("length('abc')")
        );
    }

    private static Stream<Arguments> failClosedRuntimeCases() {
        return Stream.of(
                "expression.intermediateDecimalPrecisionDigits",
                "expression.intermediateDecimalScaleMin",
                "expression.intermediateDecimalScaleMax",
                "expression.admittedDecimalPrecisionDigits",
                "expression.admittedDecimalScaleMin",
                "expression.admittedDecimalScaleMax"
        ).flatMap(limitId -> Stream.of(FailureMode.values())
                .map(mode -> Arguments.of(limitId, mode)));
    }

    private static ExpressionEvaluator.EvalOutcome evaluate(
            String source,
            DesignInputExpressionCapacityAuthority authority
    ) {
        var ast = parse(source);
        assertInstanceOf(ExpressionAnalyzer.Analyzed.class,
                ExpressionAnalyzer.analyze(ast, Map.of()));
        return ExpressionEvaluator.evaluate(
                ast,
                alias -> new ExpressionEvaluator.EvalError(
                        new ExpressionEvaluator.RuntimeFailure(
                                ExpressionEvaluator.RuntimeFailureKind.TYPE_FAULT, null)),
                authority);
    }

    private static ExpressionAst parse(String source) {
        var outcome = TemplateModule.designSemanticAuthority().interpret(expressionDesign(source));
        var interpreted = assertInstanceOf(DesignSemanticAuthority.Interpreted.class, outcome);
        return interpreted.expressionsByDefinitionId().get(
                "00000000-0000-4000-8000-0000000000e1");
    }

    private static byte[] expressionDesign(String source) {
        return ("""
                {"definitions":[{"definitionId":"00000000-0000-4000-8000-0000000000e1",\
                "displayName":"Expression",\
                "domain":"invocation",\
                "inputs":[],\
                "kind":"expression",\
                "output":"decimal",\
                "source":"%s"}],\
                "designRoot":{"bindings":[],"children":[],"heightMm":297,"kind":"canvas",\
                "nodeId":"00000000-0000-4000-8000-000000000001","widthMm":210},\
                "displayName":"Expression fixture",\
                "dslVersion":"renderweave-design/1.0",\
                "expressionProfile":"renderweave-expression/1.0"}
                """.formatted(source)).getBytes(StandardCharsets.UTF_8);
    }

    private static DesignInputExpressionCapacityAuthority.Observation observation(
            String limitId,
            String value
    ) {
        return new DesignInputExpressionCapacityAuthority.Observation(limitId, value);
    }

    private static final class RecordingAuthority
            implements DesignInputExpressionCapacityAuthority {
        private final List<Observation> observations = new ArrayList<>();

        @Override
        public Decision evaluate(Observation observation) {
            observations.add(observation);
            return TemplateModule.designInputExpressionCapacityAuthority().evaluate(observation);
        }
    }

    private enum FailureMode {
        REJECT,
        INVALID,
        THROW
    }

    private static final class FailingAuthority
            implements DesignInputExpressionCapacityAuthority {
        private final String failedLimitId;
        private final FailureMode mode;
        private final List<Observation> observations = new ArrayList<>();

        private FailingAuthority(String failedLimitId, FailureMode mode) {
            this.failedLimitId = failedLimitId;
            this.mode = mode;
        }

        @Override
        public Decision evaluate(Observation observation) {
            observations.add(observation);
            if (!failedLimitId.equals(observation.limitId())) {
                return TemplateModule.designInputExpressionCapacityAuthority()
                        .evaluate(observation);
            }
            return switch (mode) {
                case REJECT -> new Rejected(new Terminal(
                        "EXPRESSION_LIMIT_EXCEEDED",
                        "EXPRESSION_PARSE_AND_STATIC_ANALYSIS",
                        "TEMPLATE_CLOSURE",
                        "ZERO_WRITE_AND_DOWNSTREAM",
                        List.of("evaluationStarts=0")));
                case INVALID -> new Invalid(InvalidReason.INVALID_OBSERVED_VALUE);
                case THROW -> throw new IllegalStateException("capacity authority unavailable");
            };
        }
    }
}
