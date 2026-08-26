package cn.hbads.renderweave.template.internal;

import cn.hbads.renderweave.template.api.DesignDslAuthority;
import cn.hbads.renderweave.template.api.DesignInputExpressionCapacityAuthority;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpressionDecimalCapacityReservationTest {

    @Test
    void admittedDecimalUsesNormalizedFactsBeforeAstAllocation() {
        var recording = new RecordingAuthority();

        assertInstanceOf(
                DesignDslAuthority.Admitted.class,
                new CanonicalDesignDslAuthority(recording)
                        .admit(expressionDesign("1.2300"))
        );

        int precision = recording.observations.indexOf(observation(
                "expression.admittedDecimalPrecisionDigits", "3"));
        int scaleMin = recording.observations.indexOf(observation(
                "expression.admittedDecimalScaleMin", "2"));
        int scaleMax = recording.observations.indexOf(observation(
                "expression.admittedDecimalScaleMax", "2"));
        int astAllocation = recording.observations.indexOf(observation(
                "expression.astNodesPerExpression", "1"));

        assertTrue(precision >= 0, "normalized precision must be observed");
        assertTrue(scaleMin > precision, "scale-min follows precision");
        assertTrue(scaleMax > scaleMin, "scale-max follows scale-min");
        assertTrue(astAllocation > scaleMax, "decimal capacity precedes AST allocation");
    }

    @Test
    void explicitRoundingScaleIsReservedBeforeCallAllocation() {
        var recording = new RecordingAuthority();

        assertInstanceOf(
                DesignDslAuthority.Admitted.class,
                new CanonicalDesignDslAuthority(recording)
                        .admit(expressionDesign("round(1, 7, 'HALF_UP')"))
        );

        int scale = recording.observations.indexOf(observation(
                "expression.explicitRoundingScaleMax", "7"));
        int callAllocation = recording.observations.indexOf(observation(
                "expression.astNodesPerExpression", "4"));

        assertTrue(scale >= 0, "explicit rounding scale must be observed");
        assertTrue(callAllocation > scale, "scale capacity precedes call AST allocation");
    }

    @Test
    void formatDecimalReservesBothAuthoredScales() {
        var recording = new RecordingAuthority();

        assertInstanceOf(
                DesignDslAuthority.Admitted.class,
                new CanonicalDesignDslAuthority(recording)
                        .admit(expressionDesign("formatDecimal(1, 2, 4, 'HALF_UP')"))
        );

        assertTrue(recording.observations.contains(observation(
                "expression.explicitRoundingScaleMax", "2")));
        assertTrue(recording.observations.contains(observation(
                "expression.explicitRoundingScaleMax", "4")));
    }

    @Test
    void divideReservesItsAuthoredScale() {
        var recording = new RecordingAuthority();

        assertInstanceOf(
                DesignDslAuthority.Admitted.class,
                new CanonicalDesignDslAuthority(recording)
                        .admit(expressionDesign("divide(1, 3, 6, 'HALF_UP')"))
        );

        assertTrue(recording.observations.contains(observation(
                "expression.explicitRoundingScaleMax", "6")));
    }

    @Test
    void decimalInStaticallyUnselectedBranchIsStillReservedAtAdmission() {
        assertRejected(
                "if(false, 1e65, 0)",
                DesignDslAuthority.Limit.EXPRESSION_ADMITTED_DECIMAL_SCALE_MIN);
    }

    @Test
    void normalizedBoundaryValuesAreAdmitted() {
        var atPrecisionLimit = "9".repeat(128);

        assertInstanceOf(DesignDslAuthority.Admitted.class,
                admit(atPrecisionLimit));
        assertInstanceOf(DesignDslAuthority.Admitted.class,
                admit("1e64"));
        assertInstanceOf(DesignDslAuthority.Admitted.class,
                admit("1e-64"));
        assertInstanceOf(DesignDslAuthority.Admitted.class,
                admit("0e999"));
        assertInstanceOf(DesignDslAuthority.Admitted.class,
                admit("round(1.2300, 64, 'HALF_EVEN')"));
    }

    @Test
    void defaultAuthorityRejectsEachAdmissionAxisWithExactLimit() {
        assertRejected(
                "9".repeat(129),
                DesignDslAuthority.Limit.EXPRESSION_ADMITTED_DECIMAL_PRECISION_DIGITS);
        assertRejected(
                "1e65",
                DesignDslAuthority.Limit.EXPRESSION_ADMITTED_DECIMAL_SCALE_MIN);
        assertRejected(
                "1e-65",
                DesignDslAuthority.Limit.EXPRESSION_ADMITTED_DECIMAL_SCALE_MAX);
        assertRejected(
                "round(1, 65, 'HALF_UP')",
                DesignDslAuthority.Limit.EXPRESSION_EXPLICIT_ROUNDING_SCALE_MAX);
    }

    @ParameterizedTest
    @MethodSource("failClosedAdmissionCases")
    void authorityFailureModesFailClosedAtTheExactAdmissionAxis(
            String source,
            DesignDslAuthority.Limit expectedLimit,
            FailureMode mode
    ) {
        var authority = new FailingAuthority(expectedLimit.id(), mode);

        var rejected = assertInstanceOf(
                DesignDslAuthority.Rejected.class,
                new CanonicalDesignDslAuthority(authority)
                        .admit(expressionDesign(source)));

        assertEquals(DesignDslAuthority.FailureCode.DESIGN_DSL_LIMIT_EXCEEDED,
                rejected.code());
        assertEquals(DesignDslAuthority.FailureStage.DESIGN_SEMANTIC_VALIDATION,
                rejected.stage());
        assertEquals("/definitions/0/source", rejected.pointer());
        assertEquals(Optional.of(expectedLimit), rejected.limit());
        assertEquals(expectedLimit.id(),
                authority.observations.get(authority.observations.size() - 1).limitId(),
                "the failed observation must be terminal with no downstream reservation");
    }

    private static Stream<Arguments> failClosedAdmissionCases() {
        return Stream.of(
                admissionCase("1",
                        DesignDslAuthority.Limit
                                .EXPRESSION_ADMITTED_DECIMAL_PRECISION_DIGITS),
                admissionCase("1e1",
                        DesignDslAuthority.Limit.EXPRESSION_ADMITTED_DECIMAL_SCALE_MIN),
                admissionCase("1e-1",
                        DesignDslAuthority.Limit.EXPRESSION_ADMITTED_DECIMAL_SCALE_MAX),
                admissionCase("round(1, 1, 'HALF_UP')",
                        DesignDslAuthority.Limit.EXPRESSION_EXPLICIT_ROUNDING_SCALE_MAX)
        ).flatMap(identity -> Stream.of(FailureMode.values())
                .map(mode -> Arguments.of(identity.source(), identity.limit(), mode)));
    }

    private static AdmissionCase admissionCase(
            String source,
            DesignDslAuthority.Limit limit
    ) {
        return new AdmissionCase(source, limit);
    }

    private static DesignDslAuthority.Admission admit(String source) {
        return new CanonicalDesignDslAuthority(
                CanonicalDesignInputExpressionCapacityAuthority.INSTANCE)
                .admit(expressionDesign(source));
    }

    private static void assertRejected(String source, DesignDslAuthority.Limit expectedLimit) {
        var rejected = assertInstanceOf(
                DesignDslAuthority.Rejected.class,
                admit(source));

        assertEquals(DesignDslAuthority.FailureCode.DESIGN_DSL_LIMIT_EXCEEDED,
                rejected.code());
        assertEquals(DesignDslAuthority.FailureStage.DESIGN_SEMANTIC_VALIDATION,
                rejected.stage());
        assertEquals("/definitions/0/source", rejected.pointer());
        assertEquals(Optional.of(expectedLimit), rejected.limit());
    }

    private static byte[] expressionDesign(String source) {
        return ("""
                {
                  "dslVersion":"renderweave-design/1.0",
                  "expressionProfile":"renderweave-expression/1.0",
                  "displayName":"Decimal capacity fixture",
                  "definitions":[{
                    "definitionId":"00000000-0000-4000-8000-0000000000e1",
                    "kind":"expression",
                    "displayName":"Expression",
                    "domain":"invocation",
                    "output":"decimal",
                    "inputs":[],
                    "source":"%s"
                  }],
                  "designRoot":{
                    "nodeId":"00000000-0000-4000-8000-000000000001",
                    "kind":"canvas",
                    "widthMm":210,
                    "heightMm":297,
                    "bindings":[],
                    "children":[]
                  }
                }
                """.formatted(jsonEscape(source))).getBytes(StandardCharsets.UTF_8);
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static DesignInputExpressionCapacityAuthority.Observation observation(
            String limitId,
            String observedValue
    ) {
        return new DesignInputExpressionCapacityAuthority.Observation(limitId, observedValue);
    }

    private static final class RecordingAuthority
            implements DesignInputExpressionCapacityAuthority {
        private final List<Observation> observations = new ArrayList<>();

        @Override
        public Decision evaluate(Observation observation) {
            observations.add(observation);
            return CanonicalDesignInputExpressionCapacityAuthority.INSTANCE.evaluate(observation);
        }
    }

    private enum FailureMode {
        REJECT,
        INVALID,
        THROW
    }

    private record AdmissionCase(String source, DesignDslAuthority.Limit limit) {
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
                return CanonicalDesignInputExpressionCapacityAuthority.INSTANCE
                        .evaluate(observation);
            }
            return switch (mode) {
                case REJECT -> new Rejected(new Terminal(
                        "EXPRESSION_LIMIT_EXCEEDED",
                        "EXPRESSION_PARSE_AND_STATIC_ANALYSIS",
                        "TEMPLATE_CLOSURE",
                        "ZERO_WRITE_AND_DOWNSTREAM",
                        List.of("templateWrites=0")));
                case INVALID -> new Invalid(InvalidReason.INVALID_OBSERVED_VALUE);
                case THROW -> throw new IllegalStateException("capacity authority unavailable");
            };
        }
    }
}
