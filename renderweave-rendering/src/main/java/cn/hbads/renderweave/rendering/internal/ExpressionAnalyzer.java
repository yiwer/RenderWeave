package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.template.api.DesignSemanticAuthority.ExpressionAst;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * renderweave-expression/1.0 静态分析：全部 Expression 即使未被消费也完成语法、类型、domain、
 * cycle 与依赖检查；未选择分支不调用 capability 但仍静态验证。strict typing：无隐式转换、
 * list invariant、imageRef/fontRef 不互换。presence refinement 只支持 {@code exists(input.x)}
 * 及其直接 {@code !}，并在 {@code exists(x) && RHS}、{@code !exists(x) || RHS} 中收窄 RHS。
 */
final class ExpressionAnalyzer {

    /** 一个 Expression input 的静态声明：类型 + 是否 MAY_BE_ABSENT。 */
    record InputDeclaration(DesignValueDecoder.DesignValueType type, boolean mayBeAbsent) {
        InputDeclaration {
            Objects.requireNonNull(type, "type");
        }
    }

    /** 分析后的 expression 输出类型。 */
    record OutputType(DesignValueDecoder.DesignValueType type, boolean mayBeAbsent) {
    }

    enum StaticFailureKind {
        UNKNOWN_INPUT,
        UNUSED_INPUT,
        TYPE_MISMATCH,
        ARITY_INVALID,
        COMPILE_TIME_LITERAL_REQUIRED,
        INVALID_ROUNDING_MODE,
        ABSENT_NOT_TOTALIZED
    }

    record StaticFailure(StaticFailureKind kind, String detail) {
    }

    sealed interface AnalysisResult permits Analyzed, AnalysisRejected {
    }

    record Analyzed(OutputType outputType) implements AnalysisResult {
    }

    record AnalysisRejected(List<StaticFailure> failures) implements AnalysisResult {
        AnalysisRejected {
            failures = List.copyOf(failures);
        }
    }

    private final Map<String, InputDeclaration> inputs;
    private final Set<String> usedAliases = new HashSet<>();
    private final Set<String> presentRefined = new HashSet<>();

    private ExpressionAnalyzer(Map<String, InputDeclaration> inputs) {
        this.inputs = inputs;
    }

    static AnalysisResult analyze(ExpressionAst ast, Map<String, InputDeclaration> inputs) {
        Objects.requireNonNull(ast, "ast");
        Objects.requireNonNull(inputs, "inputs");
        var analyzer = new ExpressionAnalyzer(inputs);
        var failures = new java.util.ArrayList<StaticFailure>();
        var type = analyzer.typeOf(ast, failures);
        for (var alias : inputs.keySet()) {
            if (!analyzer.usedAliases.contains(alias)) {
                failures.add(new StaticFailure(StaticFailureKind.UNUSED_INPUT, alias));
            }
        }
        if (!failures.isEmpty()) {
            return new AnalysisRejected(failures);
        }
        return new Analyzed(new OutputType(type.type(), type.mayBeAbsent()));
    }

    private record LocalType(DesignValueDecoder.DesignValueType type, boolean mayBeAbsent) {
    }

    private LocalType typeOf(ExpressionAst node, List<StaticFailure> failures) {
        if (node instanceof ExpressionAst.TextLiteral) {
            return new LocalType(new DesignValueDecoder.BaseType("text"), false);
        }
        if (node instanceof ExpressionAst.DecimalLiteral) {
            return new LocalType(new DesignValueDecoder.BaseType("decimal"), false);
        }
        if (node instanceof ExpressionAst.BooleanLiteral) {
            return new LocalType(new DesignValueDecoder.BaseType("boolean"), false);
        }
        if (node instanceof ExpressionAst.InputRead read) {
            usedAliases.add(read.alias());
            var declaration = inputs.get(read.alias());
            if (declaration == null) {
                failures.add(new StaticFailure(StaticFailureKind.UNKNOWN_INPUT, read.alias()));
                return new LocalType(new DesignValueDecoder.BaseType("text"), true);
            }
            boolean absent = declaration.mayBeAbsent() && !presentRefined.contains(read.alias());
            return new LocalType(declaration.type(), absent);
        }
        if (node instanceof ExpressionAst.Unary unary) {
            var operand = typeOf(unary.operand(), failures);
            var required = unary.operator() == ExpressionAst.UnaryOperator.NOT
                    ? "boolean" : "decimal";
            if (!isBase(operand.type(), required)) {
                failures.add(new StaticFailure(StaticFailureKind.TYPE_MISMATCH, required));
            }
            return new LocalType(new DesignValueDecoder.BaseType(required), false);
        }
        if (node instanceof ExpressionAst.Binary binary) {
            return typeBinary(binary, failures);
        }
        if (node instanceof ExpressionAst.Call call) {
            return typeCall(call, failures);
        }
        failures.add(new StaticFailure(StaticFailureKind.TYPE_MISMATCH, "unknown"));
        return new LocalType(new DesignValueDecoder.BaseType("text"), true);
    }

    private LocalType typeBinary(ExpressionAst.Binary binary, List<StaticFailure> failures) {
        var operator = binary.operator();
        if (operator == ExpressionAst.BinaryOperator.AND) {
            var left = typeOf(binary.left(), failures);
            if (!isBase(left.type(), "boolean")) {
                failures.add(new StaticFailure(StaticFailureKind.TYPE_MISMATCH, "boolean"));
            }
            var refined = refinementOf(binary.left(), true);
            var right = withRefinement(refined, () -> typeOf(binary.right(), failures));
            if (!isBase(right.type(), "boolean")) {
                failures.add(new StaticFailure(StaticFailureKind.TYPE_MISMATCH, "boolean"));
            }
            return new LocalType(new DesignValueDecoder.BaseType("boolean"), false);
        }
        if (operator == ExpressionAst.BinaryOperator.OR) {
            var left = typeOf(binary.left(), failures);
            if (!isBase(left.type(), "boolean")) {
                failures.add(new StaticFailure(StaticFailureKind.TYPE_MISMATCH, "boolean"));
            }
            var refined = refinementOfNegatedExists(binary.left());
            var right = withRefinement(refined, () -> typeOf(binary.right(), failures));
            if (!isBase(right.type(), "boolean")) {
                failures.add(new StaticFailure(StaticFailureKind.TYPE_MISMATCH, "boolean"));
            }
            return new LocalType(new DesignValueDecoder.BaseType("boolean"), false);
        }
        var left = typeOf(binary.left(), failures);
        var right = typeOf(binary.right(), failures);
        return switch (operator) {
            case EQ, NOT_EQ -> {
                if (left.type() instanceof DesignValueDecoder.ListOf
                        || right.type() instanceof DesignValueDecoder.ListOf) {
                    failures.add(new StaticFailure(StaticFailureKind.TYPE_MISMATCH, "scalar"));
                } else if (!sameBase(left.type(), right.type())) {
                    failures.add(new StaticFailure(StaticFailureKind.TYPE_MISMATCH, "same-type"));
                } else if (left.mayBeAbsent() || right.mayBeAbsent()) {
                    failures.add(new StaticFailure(
                            StaticFailureKind.ABSENT_NOT_TOTALIZED, "equality"));
                }
                yield new LocalType(new DesignValueDecoder.BaseType("boolean"), false);
            }
            case LT, LT_EQ, GT, GT_EQ -> {
                if (!sameBase(left.type(), right.type())
                        || !(isBase(left.type(), "decimal")
                        || isBase(left.type(), "date")
                        || isBase(left.type(), "time"))) {
                    failures.add(new StaticFailure(
                            StaticFailureKind.TYPE_MISMATCH, "decimal/date/time same-type"));
                } else if (left.mayBeAbsent() || right.mayBeAbsent()) {
                    failures.add(new StaticFailure(
                            StaticFailureKind.ABSENT_NOT_TOTALIZED, "relational"));
                }
                yield new LocalType(new DesignValueDecoder.BaseType("boolean"), false);
            }
            case ADD, SUBTRACT, MULTIPLY -> {
                if (!isBase(left.type(), "decimal") || !isBase(right.type(), "decimal")) {
                    failures.add(new StaticFailure(StaticFailureKind.TYPE_MISMATCH, "decimal"));
                }
                yield new LocalType(new DesignValueDecoder.BaseType("decimal"), false);
            }
            default -> new LocalType(new DesignValueDecoder.BaseType("boolean"), false);
        };
    }

    private LocalType typeCall(ExpressionAst.Call call, List<StaticFailure> failures) {
        var arguments = call.arguments();
        return switch (call.function()) {
            case EXISTS -> {
                if (arguments.size() != 1) {
                    failures.add(new StaticFailure(StaticFailureKind.ARITY_INVALID, "exists"));
                } else {
                    typeOf(arguments.get(0), failures);
                }
                yield new LocalType(new DesignValueDecoder.BaseType("boolean"), false);
            }
            case COALESCE -> {
                if (arguments.size() != 2) {
                    failures.add(new StaticFailure(StaticFailureKind.ARITY_INVALID, "coalesce"));
                    yield new LocalType(new DesignValueDecoder.BaseType("text"), true);
                }
                var first = typeOf(arguments.get(0), failures);
                var fallback = typeOf(arguments.get(1), failures);
                if (!sameBase(first.type(), fallback.type())) {
                    failures.add(new StaticFailure(StaticFailureKind.TYPE_MISMATCH, "same-type"));
                }
                if (fallback.type() instanceof DesignValueDecoder.ListOf) {
                    failures.add(new StaticFailure(StaticFailureKind.TYPE_MISMATCH, "base"));
                }
                yield new LocalType(first.type(), fallback.mayBeAbsent());
            }
            case IF -> {
                if (arguments.size() != 3) {
                    failures.add(new StaticFailure(StaticFailureKind.ARITY_INVALID, "if"));
                    yield new LocalType(new DesignValueDecoder.BaseType("text"), true);
                }
                var condition = typeOf(arguments.get(0), failures);
                if (!isBase(condition.type(), "boolean") || condition.mayBeAbsent()) {
                    failures.add(new StaticFailure(
                            StaticFailureKind.TYPE_MISMATCH, "concrete boolean"));
                }
                var then = typeOf(arguments.get(1), failures);
                var otherwise = typeOf(arguments.get(2), failures);
                if (!sameBase(then.type(), otherwise.type())) {
                    failures.add(new StaticFailure(StaticFailureKind.TYPE_MISMATCH, "same-type"));
                }
                yield new LocalType(then.type(), then.mayBeAbsent() || otherwise.mayBeAbsent());
            }
            case CONCAT -> {
                if (arguments.size() < 2) {
                    failures.add(new StaticFailure(StaticFailureKind.ARITY_INVALID, "concat"));
                }
                for (var argument : arguments) {
                    var type = typeOf(argument, failures);
                    if (!isBase(type.type(), "text") || type.mayBeAbsent()) {
                        failures.add(new StaticFailure(
                                StaticFailureKind.TYPE_MISMATCH, "concrete text"));
                    }
                }
                yield new LocalType(new DesignValueDecoder.BaseType("text"), false);
            }
            case LENGTH -> {
                if (arguments.size() != 1) {
                    failures.add(new StaticFailure(StaticFailureKind.ARITY_INVALID, "length"));
                } else {
                    typeOf(arguments.get(0), failures);
                }
                yield new LocalType(new DesignValueDecoder.BaseType("decimal"), false);
            }
            case DIVIDE -> {
                requireArity(arguments, 4, "divide", failures);
                typeDecimalOperand(arguments, 0, failures);
                typeDecimalOperand(arguments, 1, failures);
                requireNonNegativeIntegerLiteral(arguments, 2, "divide", failures);
                requireRoundingMode(arguments, 3, "divide", failures);
                yield new LocalType(new DesignValueDecoder.BaseType("decimal"), false);
            }
            case ROUND -> {
                requireArity(arguments, 3, "round", failures);
                typeDecimalOperand(arguments, 0, failures);
                requireNonNegativeIntegerLiteral(arguments, 1, "round", failures);
                requireRoundingMode(arguments, 2, "round", failures);
                yield new LocalType(new DesignValueDecoder.BaseType("decimal"), false);
            }
            case FORMAT_DECIMAL -> {
                requireArity(arguments, 4, "formatDecimal", failures);
                typeDecimalOperand(arguments, 0, failures);
                requireNonNegativeIntegerLiteral(arguments, 1, "formatDecimal", failures);
                requireNonNegativeIntegerLiteral(arguments, 2, "formatDecimal", failures);
                requireRoundingMode(arguments, 3, "formatDecimal", failures);
                yield new LocalType(new DesignValueDecoder.BaseType("text"), false);
            }
            case FORMAT_DATE -> {
                requireTypedOperand(arguments, 1, "date", "formatDate", failures);
                yield new LocalType(new DesignValueDecoder.BaseType("text"), false);
            }
            case FORMAT_TIME -> {
                requireTypedOperand(arguments, 1, "time", "formatTime", failures);
                yield new LocalType(new DesignValueDecoder.BaseType("text"), false);
            }
        };
    }

    private void requireArity(
            List<ExpressionAst> arguments, int arity, String name, List<StaticFailure> failures) {
        if (arguments.size() != arity) {
            failures.add(new StaticFailure(StaticFailureKind.ARITY_INVALID, name));
        }
    }

    private void typeDecimalOperand(
            List<ExpressionAst> arguments, int index, List<StaticFailure> failures) {
        if (index >= arguments.size()) {
            return;
        }
        var type = typeOf(arguments.get(index), failures);
        if (!isBase(type.type(), "decimal") || type.mayBeAbsent()) {
            failures.add(new StaticFailure(StaticFailureKind.TYPE_MISMATCH, "concrete decimal"));
        }
    }

    private void requireTypedOperand(
            List<ExpressionAst> arguments, int arity, String baseType,
            String name, List<StaticFailure> failures) {
        requireArity(arguments, arity, name, failures);
        if (arguments.isEmpty()) {
            return;
        }
        var type = typeOf(arguments.get(0), failures);
        if (!isBase(type.type(), baseType) || type.mayBeAbsent()) {
            failures.add(new StaticFailure(
                    StaticFailureKind.TYPE_MISMATCH, "concrete " + baseType));
        }
    }

    private void requireNonNegativeIntegerLiteral(
            List<ExpressionAst> arguments, int index, String name, List<StaticFailure> failures) {
        if (index >= arguments.size()) {
            return;
        }
        if (!(arguments.get(index) instanceof ExpressionAst.DecimalLiteral literal)
                || literal.value().signum() < 0
                || literal.value().stripTrailingZeros().scale() > 0) {
            failures.add(new StaticFailure(
                    StaticFailureKind.COMPILE_TIME_LITERAL_REQUIRED, name));
            return;
        }
    }

    private void requireRoundingMode(
            List<ExpressionAst> arguments, int index, String name, List<StaticFailure> failures) {
        if (index >= arguments.size()) {
            return;
        }
        if (!(arguments.get(index) instanceof ExpressionAst.TextLiteral literal)
                || !ROUNDING_MODES.contains(literal.value())) {
            failures.add(new StaticFailure(StaticFailureKind.INVALID_ROUNDING_MODE, name));
        }
    }

    static final Set<String> ROUNDING_MODES = Set.of("HALF_UP", "HALF_EVEN", "DOWN", "UP");

    /** {@code exists(input.x)} 的直接形式返回可收窄 alias，否则 null。 */
    private String refinementOf(ExpressionAst node, boolean positive) {
        var target = node;
        if (!positive) {
            if (node instanceof ExpressionAst.Unary unary
                    && unary.operator() == ExpressionAst.UnaryOperator.NOT) {
                target = unary.operand();
            } else {
                return null;
            }
        }
        if (target instanceof ExpressionAst.Call call
                && call.function() == ExpressionAst.Function.EXISTS
                && call.arguments().size() == 1
                && call.arguments().get(0) instanceof ExpressionAst.InputRead read) {
            return read.alias();
        }
        return null;
    }

    private String refinementOfNegatedExists(ExpressionAst node) {
        if (node instanceof ExpressionAst.Unary unary
                && unary.operator() == ExpressionAst.UnaryOperator.NOT) {
            return refinementOf(unary.operand(), true);
        }
        return null;
    }

    private LocalType withRefinement(String alias, java.util.function.Supplier<LocalType> body) {
        if (alias == null) {
            return body.get();
        }
        presentRefined.add(alias);
        try {
            return body.get();
        } finally {
            presentRefined.remove(alias);
        }
    }

    private static boolean isBase(DesignValueDecoder.DesignValueType type, String name) {
        return type instanceof DesignValueDecoder.BaseType base && base.name().equals(name);
    }

    private static boolean sameBase(
            DesignValueDecoder.DesignValueType left, DesignValueDecoder.DesignValueType right) {
        if (left instanceof DesignValueDecoder.BaseType leftBase
                && right instanceof DesignValueDecoder.BaseType rightBase) {
            return leftBase.name().equals(rightBase.name());
        }
        if (left instanceof DesignValueDecoder.ListOf leftList
                && right instanceof DesignValueDecoder.ListOf rightList) {
            return leftList.itemType().equals(rightList.itemType());
        }
        return false;
    }
}
