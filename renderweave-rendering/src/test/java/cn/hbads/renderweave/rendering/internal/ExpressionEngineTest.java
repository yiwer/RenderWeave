package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.template.api.DesignDslAuthority;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.ExpressionAst;
import cn.hbads.renderweave.template.internal.TemplateModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
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
        var source = "'" + "a".repeat(70_000) + "'";
        var rejected = assertInstanceOf(
                DesignDslAuthority.Rejected.class,
                TemplateModule.designDslAuthority().admit(expressionDesign(source)));
        assertEquals("expression.sourceUtf8BytesPerExpression",
                rejected.limit().orElseThrow().id());
    }

    @Test
    void astBudgetIsRejected() {
        var builder = new StringBuilder("1");
        for (int index = 0; index < 4_200; index++) {
            builder.append(" + 1");
        }
        var rejected = assertInstanceOf(
                DesignDslAuthority.Rejected.class,
                TemplateModule.designDslAuthority().admit(expressionDesign(builder.toString())));
        assertEquals("expression.astNodesPerExpression",
                rejected.limit().orElseThrow().id());
    }

    @Test
    void trailingContentIsSyntaxInvalid() {
        var rejected = assertInstanceOf(
                DesignDslAuthority.Rejected.class,
                TemplateModule.designDslAuthority().admit(expressionDesign("1 + 2)")));
        assertEquals(DesignDslAuthority.FailureCode.DESIGN_VALUE_INVALID, rejected.code());
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
    void inputsMemoizeWithinOneEvaluation() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var ast = parse("input.a + input.a");
        var analysis = ExpressionAnalyzer.analyze(ast, Map.of("a", DECIMAL));
        assertInstanceOf(ExpressionAnalyzer.Analyzed.class, analysis);
        var outcome = ExpressionEvaluator.evaluate(ast, alias -> {
            calls.incrementAndGet();
            return new ExpressionEvaluator.EvalValue(new DesignValue.Decimal(BigDecimal.TWO));
        }, TemplateModule.designInputExpressionCapacityAuthority());
        assertEquals(0, new BigDecimal("4").compareTo(decimal(outcome)));
        assertEquals(1, calls.get());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

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
                """.formatted(jsonEscape(source))).getBytes(StandardCharsets.UTF_8);
    }

    private static String jsonEscape(String value) {
        var escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> escaped.append(current);
            }
        }
        return escaped.toString();
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
        }, TemplateModule.designInputExpressionCapacityAuthority());
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
