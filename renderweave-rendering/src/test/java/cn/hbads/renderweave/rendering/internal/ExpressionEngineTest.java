package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.rendering.api.EvaluationStage;
import cn.hbads.renderweave.rendering.api.RenderingProblem.ProblemCode;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.ArrayNode;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.ObjectNode;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.Text;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpressionEngineTest {

    private static final ExpressionAnalyzer.InputDeclaration TEXT =
            new ExpressionAnalyzer.InputDeclaration(new DesignValueDecoder.BaseType("text"), false);
    private static final ExpressionAnalyzer.InputDeclaration DECIMAL =
            new ExpressionAnalyzer.InputDeclaration(new DesignValueDecoder.BaseType("decimal"), false);
    private static final ExpressionAnalyzer.InputDeclaration BOOLEAN =
            new ExpressionAnalyzer.InputDeclaration(new DesignValueDecoder.BaseType("boolean"), false);
    private static final ExpressionAnalyzer.InputDeclaration DATE =
            new ExpressionAnalyzer.InputDeclaration(new DesignValueDecoder.BaseType("date"), false);
    private static final ExpressionAnalyzer.InputDeclaration MAYBE_TEXT =
            new ExpressionAnalyzer.InputDeclaration(new DesignValueDecoder.BaseType("text"), true);

    // ------------------------------------------------------------------
    // parsing
    // ------------------------------------------------------------------

    @Test
    void oversizedSourceIsRejected() {
        var source = ("'" + "a".repeat(70_000) + "'").getBytes(StandardCharsets.UTF_8);
        var result = ExpressionParser.parse(source);
        var rejected = assertInstanceOf(ExpressionParser.ParseRejected.class, result);
        assertEquals(ExpressionParser.ParseFailureKind.SOURCE_LIMIT_EXCEEDED,
                rejected.failure().kind());
    }

    @Test
    void astBudgetIsRejected() {
        var builder = new StringBuilder("1");
        for (int index = 0; index < 4_200; index++) {
            builder.append(" + 1");
        }
        var result = ExpressionParser.parse(builder.toString().getBytes(StandardCharsets.UTF_8));
        var rejected = assertInstanceOf(ExpressionParser.ParseRejected.class, result);
        assertEquals(ExpressionParser.ParseFailureKind.AST_LIMIT_EXCEEDED,
                rejected.failure().kind());
    }

    @Test
    void trailingContentIsSyntaxInvalid() {
        var result = ExpressionParser.parse("1 + 2)".getBytes(StandardCharsets.UTF_8));
        assertInstanceOf(ExpressionParser.ParseRejected.class, result);
    }

    @Test
    void textLiteralEscapesDecode() {
        var source = "'a\\\\b\\'\\n" + "\\" + "u{1F600}'";
        var outcome = runOk(source, Map.of());
        assertEquals(new DesignValue.Text("a\\b'\n😀"), value(outcome));
    }

    // ------------------------------------------------------------------
    // decimal semantics
    // ------------------------------------------------------------------

    @Test
    void decimalArithmeticIsExact() {
        var outcome = runOk("0.1 + 0.2", Map.of());
        assertEquals(0, new BigDecimal("0.3").compareTo(decimal(outcome)));
    }

    @Test
    void negativeZeroEqualsZero() {
        var outcome = runOk("-0 == 0", Map.of());
        assertEquals(new DesignValue.Bool(true), value(outcome));
    }

    @Test
    void decimalEqualityIgnoresScale() {
        var outcome = runOk("1.0 == 1.00", Map.of());
        assertEquals(new DesignValue.Bool(true), value(outcome));
    }

    @Test
    void unaryMinusAndPrecedence() {
        assertEquals(0, new BigDecimal("7").compareTo(decimal(runOk("1 + 2 * 3", Map.of()))));
        assertEquals(0, new BigDecimal("-5").compareTo(decimal(runOk("-(2 + 3)", Map.of()))));
    }

    @Test
    void divideByZeroIsError() {
        var outcome = run("divide(1, 0, 2, 'HALF_UP')", Map.of(), Map.of());
        var error = assertInstanceOf(ExpressionEvaluator.EvalError.class, outcome);
        assertEquals(ExpressionEvaluator.RuntimeFailureKind.DIVISION_BY_ZERO,
                error.failure().kind());
    }

    @Test
    void divideUsesLiteralScaleAndMode() {
        assertEquals(0, new BigDecimal("0.66")
                .compareTo(decimal(runOk("divide(2, 3, 2, 'DOWN')", Map.of()))));
        assertEquals(0, new BigDecimal("0.67")
                .compareTo(decimal(runOk("divide(2, 3, 2, 'HALF_UP')", Map.of()))));
    }

    @Test
    void roundUsesLiteralScaleAndMode() {
        assertEquals(0, new BigDecimal("2.68")
                .compareTo(decimal(runOk("round(2.675, 2, 'HALF_EVEN')", Map.of()))));
    }

    @Test
    void intermediateDecimalBudgetIsEnforced() {
        var source = "9".repeat(200) + " * " + "9".repeat(200);
        var outcome = run(source, Map.of(), Map.of());
        var error = assertInstanceOf(ExpressionEvaluator.EvalError.class, outcome);
        assertEquals(ExpressionEvaluator.RuntimeFailureKind.DECIMAL_LIMIT_EXCEEDED,
                error.failure().kind());
    }

    // ------------------------------------------------------------------
    // lazy logic, ABSENT and refinement
    // ------------------------------------------------------------------

    @Test
    void andShortCircuitsWithoutDemandingRight() {
        var supplied = new HashMap<String, ExpressionEvaluator.EvalOutcome>();
        supplied.put("a", bool(false));
        var outcome = run("input.a && input.missing",
                Map.of("a", BOOLEAN, "missing", BOOLEAN), supplied);
        assertEquals(new DesignValue.Bool(false), value(outcome));
    }

    @Test
    void orShortCircuitsWithoutDemandingRight() {
        var supplied = new HashMap<String, ExpressionEvaluator.EvalOutcome>();
        supplied.put("a", bool(true));
        var outcome = run("input.a || input.missing",
                Map.of("a", BOOLEAN, "missing", BOOLEAN), supplied);
        assertEquals(new DesignValue.Bool(true), value(outcome));
    }

    @Test
    void existsCoalesceAndAbsentPropagation() {
        var decls = Map.of("x", MAYBE_TEXT);
        var supplied = new HashMap<String, ExpressionEvaluator.EvalOutcome>();
        supplied.put("x", new ExpressionEvaluator.EvalAbsent());

        assertEquals(new DesignValue.Bool(false), value(runOk("exists(input.x)", decls, supplied)));
        assertEquals(new DesignValue.Text("fallback"),
                value(runOk("coalesce(input.x, 'fallback')", decls, supplied)));
        var error = assertInstanceOf(
                ExpressionEvaluator.EvalError.class, run("length(input.x)", decls, supplied));
        assertEquals(ExpressionEvaluator.RuntimeFailureKind.ABSENT_DEMANDED,
                error.failure().kind());
    }

    @Test
    void ifChoosesBranchLazily() {
        var supplied = new HashMap<String, ExpressionEvaluator.EvalOutcome>();
        supplied.put("flag", bool(true));
        var outcome = run("if(input.flag, 'yes', input.missing)",
                Map.of("flag", BOOLEAN, "missing", TEXT), supplied);
        assertEquals(new DesignValue.Text("yes"), value(outcome));
    }

    @Test
    void refinementAllowsConcreteUseAfterExists() {
        var analysis = ExpressionAnalyzer.analyze(
                parse("exists(input.x) && input.x == 'a'"),
                Map.of("x", MAYBE_TEXT));
        assertInstanceOf(ExpressionAnalyzer.Analyzed.class, analysis);
    }

    @Test
    void negatedExistsRefinementAppliesToOrRhs() {
        var analysis = ExpressionAnalyzer.analyze(
                parse("!exists(input.x) || input.x == 'a'"),
                Map.of("x", MAYBE_TEXT));
        assertInstanceOf(ExpressionAnalyzer.Analyzed.class, analysis);
    }

    @Test
    void equalityOnPossiblyAbsentOperandIsStaticError() {
        var analysis = ExpressionAnalyzer.analyze(
                parse("input.x == 'a'"), Map.of("x", MAYBE_TEXT));
        var rejected = assertInstanceOf(ExpressionAnalyzer.AnalysisRejected.class, analysis);
        assertEquals(ExpressionAnalyzer.StaticFailureKind.ABSENT_NOT_TOTALIZED,
                rejected.failures().get(0).kind());
    }

    // ------------------------------------------------------------------
    // text / list / date functions
    // ------------------------------------------------------------------

    @Test
    void concatAndLengthUseCodePoints() {
        assertEquals(new DesignValue.Text("ab😀"),
                value(runOk("concat('ab', '😀')", Map.of())));
        assertEquals(0, new BigDecimal("3")
                .compareTo(decimal(runOk("length('a😀c')", Map.of()))));
    }

    @Test
    void dateRelationalAndFormatting() {
        var decls = Map.of("a", DATE, "b", DATE);
        var supplied = new HashMap<String, ExpressionEvaluator.EvalOutcome>();
        supplied.put("a", new ExpressionEvaluator.EvalValue(new DesignValue.Date("2026-01-02")));
        supplied.put("b", new ExpressionEvaluator.EvalValue(new DesignValue.Date("2026-01-01")));
        assertEquals(new DesignValue.Bool(true),
                value(runOk("input.a > input.b", decls, supplied)));
        assertEquals(new DesignValue.Text("2026-08-20"),
                value(runOk("formatDate(input.d)", Map.of("d", DATE),
                        Map.of("d", new ExpressionEvaluator.EvalValue(
                                new DesignValue.Date("2026-08-20"))))));
    }

    @Test
    void formatDecimalStripsTrailingZerosBeyondMin() {
        assertEquals(new DesignValue.Text("3.1"),
                value(runOk("formatDecimal(3.1000, 1, 4, 'HALF_UP')", Map.of())));
        assertEquals(new DesignValue.Text("3.10"),
                value(runOk("formatDecimal(3.1, 2, 4, 'HALF_UP')", Map.of())));
        assertEquals(new DesignValue.Text("3"),
                value(runOk("formatDecimal(3.14, 0, 0, 'HALF_UP')", Map.of())));
    }

    // ------------------------------------------------------------------
    // static analysis
    // ------------------------------------------------------------------

    @Test
    void unknownAliasIsStaticError() {
        var analysis = ExpressionAnalyzer.analyze(parse("input.nope"), Map.of());
        var rejected = assertInstanceOf(ExpressionAnalyzer.AnalysisRejected.class, analysis);
        assertEquals(ExpressionAnalyzer.StaticFailureKind.UNKNOWN_INPUT,
                rejected.failures().get(0).kind());
    }

    @Test
    void unusedInputIsStaticError() {
        var analysis = ExpressionAnalyzer.analyze(parse("'x'"), Map.of("a", TEXT));
        var rejected = assertInstanceOf(ExpressionAnalyzer.AnalysisRejected.class, analysis);
        assertEquals(ExpressionAnalyzer.StaticFailureKind.UNUSED_INPUT,
                rejected.failures().get(0).kind());
    }

    @Test
    void textAdditionIsStaticError() {
        var analysis = ExpressionAnalyzer.analyze(parse("'a' + 'b'"), Map.of());
        var rejected = assertInstanceOf(ExpressionAnalyzer.AnalysisRejected.class, analysis);
        assertEquals(ExpressionAnalyzer.StaticFailureKind.TYPE_MISMATCH,
                rejected.failures().get(0).kind());
    }

    @Test
    void roundingModeMustBeFrozenLiteral() {
        var analysis = ExpressionAnalyzer.analyze(
                parse("divide(1, 2, 2, 'HALF_ODD')"), Map.of());
        var rejected = assertInstanceOf(ExpressionAnalyzer.AnalysisRejected.class, analysis);
        assertEquals(ExpressionAnalyzer.StaticFailureKind.INVALID_ROUNDING_MODE,
                rejected.failures().get(0).kind());
    }

    @Test
    void scaleMustBeCompileTimeLiteral() {
        var supplied = new HashMap<String, ExpressionEvaluator.EvalOutcome>();
        supplied.put("s", new ExpressionEvaluator.EvalValue(new DesignValue.Decimal(BigDecimal.ONE)));
        var analysis = ExpressionAnalyzer.analyze(
                parse("divide(1, 2, input.s, 'HALF_UP')"), Map.of("s", DECIMAL));
        var rejected = assertInstanceOf(ExpressionAnalyzer.AnalysisRejected.class, analysis);
        assertEquals(ExpressionAnalyzer.StaticFailureKind.COMPILE_TIME_LITERAL_REQUIRED,
                rejected.failures().get(0).kind());
    }

    @Test
    void explicitRoundingScaleAtFrozenMaximumIsStaticallyAdmitted() {
        for (var source : java.util.List.of(
                "divide(1, 2, 64, 'HALF_UP')",
                "round(1, 64, 'HALF_EVEN')",
                "formatDecimal(1, 64, 64, 'DOWN')")) {
            assertInstanceOf(ExpressionAnalyzer.Analyzed.class,
                    ExpressionAnalyzer.analyze(parse(source), Map.of()), source);
        }
    }

    @Test
    void everyExplicitRoundingScaleAboveFrozenMaximumReturnsCapacityOutcome() {
        for (var source : List.of(
                "divide(1, 2, 65, 'HALF_UP')",
                "round(1, 65, 'HALF_EVEN')",
                "formatDecimal(1, 65, 65, 'DOWN')",
                "formatDecimal(1, 0, 65, 'UP')")) {
            var result = ExpressionAnalyzer.analyze(parse(source), Map.of());
            var limited = assertInstanceOf(
                    ExpressionAnalyzer.AnalysisLimitExceeded.class, result, source);
            assertEquals(EvaluationStage.TEMPLATE_CLOSURE, limited.problem().stage());
            assertEquals(ProblemCode.EXPRESSION_LIMIT_EXCEEDED, limited.problem().code());
            assertEquals("expression.explicitRoundingScaleMax",
                    limited.problem().limitId().orElseThrow().value());
        }
    }

    @Test
    void definitionEnginePreservesScaleCapacityIdentityBeforeLazyEvaluation() {
        var definitionId = "00000000-0000-4000-8000-0000000000d1";
        var definition = new ObjectNode(Map.of(
                "definitionId", new Text(definitionId),
                "kind", new Text("expression"),
                "inputs", new ArrayNode(List.of()),
                "source", new Text("round(1, 65, 'HALF_UP')")));
        var engine = new DefinitionEngine(List.of(definition));
        var capabilityDemands = new java.util.concurrent.atomic.AtomicInteger();
        var scope = new DefinitionEngine.ResolutionScope() {
            @Override
            public TypedObject context() {
                return null;
            }

            @Override
            public Map<String, DesignValue> customs() {
                return Map.of();
            }

            @Override
            public DefinitionEngine.LoopFrames loopFrames() {
                return DefinitionEngine.LoopFrames.EMPTY;
            }

            @Override
            public DefinitionEngine definitions() {
                return engine;
            }

            @Override
            public DefinitionEngine.CapabilityProvider capabilities() {
                return (capability, operation, position) -> {
                    capabilityDemands.incrementAndGet();
                    throw new AssertionError("capacity rejection must precede lazy input demand");
                };
            }

            @Override
            public CapabilityCallPosition.RuntimePath capabilityPath() {
                return CapabilityCallPosition.root(
                        "00000000-0000-4000-8000-000000000001", 1);
            }
        };

        var outcome = engine.resolveDefinition(definitionId, scope);

        var error = assertInstanceOf(ExpressionEvaluator.EvalError.class, outcome);
        assertEquals(ExpressionEvaluator.RuntimeFailureKind.EXPRESSION_LIMIT_EXCEEDED,
                error.failure().kind());
        assertEquals("expression.explicitRoundingScaleMax", error.failure().limitId());
        assertEquals(0, capabilityDemands.get());
    }

    @Test
    void inputsMemoizeWithinOneEvaluation() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var ast = parse("input.a + input.a");
        var analysis = ExpressionAnalyzer.analyze(ast, Map.of("a", DECIMAL));
        assertInstanceOf(ExpressionAnalyzer.Analyzed.class, analysis);
        var outcome = ExpressionEvaluator.evaluate(ast, alias -> {
            calls.incrementAndGet();
            return new ExpressionEvaluator.EvalValue(new DesignValue.Decimal(BigDecimal.TWO));
        });
        assertEquals(0, new BigDecimal("4").compareTo(decimal(outcome)));
        assertEquals(1, calls.get());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static ExpressionAst parse(String source) {
        var result = ExpressionParser.parse(source.getBytes(StandardCharsets.UTF_8));
        return ((ExpressionParser.ParsedAst) result).ast();
    }

    private static ExpressionEvaluator.EvalOutcome runOk(
            String source, Map<String, ExpressionAnalyzer.InputDeclaration> decls) {
        return runOk(source, decls, Map.of());
    }

    private static ExpressionEvaluator.EvalOutcome runOk(
            String source,
            Map<String, ExpressionAnalyzer.InputDeclaration> decls,
            Map<String, ExpressionEvaluator.EvalOutcome> supplied
    ) {
        var outcome = run(source, decls, supplied);
        assertInstanceOf(ExpressionEvaluator.EvalValue.class, outcome);
        return outcome;
    }

    private static ExpressionEvaluator.EvalOutcome run(
            String source,
            Map<String, ExpressionAnalyzer.InputDeclaration> decls,
            Map<String, ExpressionEvaluator.EvalOutcome> supplied
    ) {
        var ast = parse(source);
        var analysis = ExpressionAnalyzer.analyze(ast, decls);
        assertInstanceOf(ExpressionAnalyzer.Analyzed.class, analysis);
        return ExpressionEvaluator.evaluate(ast, alias -> {
            var value = supplied.get(alias);
            assertTrue(value != null, "supplier must cover alias " + alias);
            return value;
        });
    }

    private static DesignValue value(ExpressionEvaluator.EvalOutcome outcome) {
        return ((ExpressionEvaluator.EvalValue) outcome).value();
    }

    private static BigDecimal decimal(ExpressionEvaluator.EvalOutcome outcome) {
        return ((DesignValue.Decimal) value(outcome)).value();
    }

    private static ExpressionEvaluator.EvalOutcome bool(boolean flag) {
        return new ExpressionEvaluator.EvalValue(new DesignValue.Bool(flag));
    }
}
