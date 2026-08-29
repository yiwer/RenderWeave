package cn.hbads.renderweave.rendering.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * renderweave-expression/1.0 求值：普通参数从左到右；{@code && || if coalesce} 惰性；
 * Expression input 惰性并在单次 Expression evaluation 内 memoize；未选择分支不求值。
 * ABSENT 只有 {@code exists/coalesce/if} 处理，其余 demand 产生 ERROR；ERROR first-fail 传播。
 * decimal 任意精度 exact；中间值受 expression 预算组约束（256 位精度、±128 scale）。
 */
final class ExpressionEvaluator {

    static final int MAX_INTERMEDIATE_PRECISION_DIGITS = 256;
    static final int MAX_INTERMEDIATE_SCALE = 128;

    sealed interface EvalOutcome permits EvalValue, EvalAbsent, EvalError {
    }

    record EvalValue(DesignValue value) implements EvalOutcome {
        EvalValue {
            Objects.requireNonNull(value, "value");
        }

        boolean valueBool() {
            return ((DesignValue.Bool) value).value();
        }

        BigDecimal valueDecimal() {
            return ((DesignValue.Decimal) value).value();
        }
    }

    record EvalAbsent() implements EvalOutcome {
    }

    record EvalError(RuntimeFailure failure) implements EvalOutcome {
        EvalError {
            Objects.requireNonNull(failure, "failure");
        }
    }

    enum RuntimeFailureKind {
        ABSENT_DEMANDED,
        CAPABILITY_BUDGET_EXCEEDED,
        CAPABILITY_RESULT_INVALID,
        EXPRESSION_LIMIT_EXCEEDED,
        DIVISION_BY_ZERO,
        DECIMAL_LIMIT_EXCEEDED,
        TYPE_FAULT
    }

    record RuntimeFailure(RuntimeFailureKind kind, String limitId) {
    }

    /** 惰性 input 供给；capability/definition 错误经此传播。 */
    interface InputSupplier {
        EvalOutcome supply(String alias);
    }

    private final InputSupplier inputs;
    private final Map<String, EvalOutcome> memo = new HashMap<>();

    private ExpressionEvaluator(InputSupplier inputs) {
        this.inputs = inputs;
    }

    static EvalOutcome evaluate(ExpressionAst ast, InputSupplier inputs) {
        Objects.requireNonNull(ast, "ast");
        Objects.requireNonNull(inputs, "inputs");
        return new ExpressionEvaluator(inputs).eval(ast);
    }

    private EvalOutcome eval(ExpressionAst node) {
        if (node instanceof ExpressionAst.TextLiteral literal) {
            return new EvalValue(new DesignValue.Text(literal.value()));
        }
        if (node instanceof ExpressionAst.DecimalLiteral literal) {
            return new EvalValue(new DesignValue.Decimal(literal.value()));
        }
        if (node instanceof ExpressionAst.BooleanLiteral literal) {
            return new EvalValue(new DesignValue.Bool(literal.value()));
        }
        if (node instanceof ExpressionAst.InputRead read) {
            return memo.computeIfAbsent(read.alias(), inputs::supply);
        }
        if (node instanceof ExpressionAst.Unary unary) {
            return evalUnary(unary);
        }
        if (node instanceof ExpressionAst.Binary binary) {
            return evalBinary(binary);
        }
        if (node instanceof ExpressionAst.Call call) {
            return evalCall(call);
        }
        return new EvalError(new RuntimeFailure(RuntimeFailureKind.TYPE_FAULT, null));
    }

    private EvalOutcome evalUnary(ExpressionAst.Unary unary) {
        var operand = eval(unary.operand());
        if (operand instanceof EvalError) {
            return operand;
        }
        if (operand instanceof EvalAbsent) {
            return absentDemanded();
        }
        var value = ((EvalValue) operand).value();
        if (unary.operator() == ExpressionAst.UnaryOperator.NOT) {
            if (value instanceof DesignValue.Bool bool) {
                return new EvalValue(new DesignValue.Bool(!bool.value()));
            }
            return typeFault();
        }
        if (value instanceof DesignValue.Decimal decimal) {
            return checkedDecimal(decimal.value().negate());
        }
        return typeFault();
    }

    private EvalOutcome evalBinary(ExpressionAst.Binary binary) {
        var operator = binary.operator();
        if (operator == ExpressionAst.BinaryOperator.AND) {
            var left = eval(binary.left());
            var flag = demandBool(left);
            if (flag instanceof EvalError) {
                return flag;
            }
            if (!((EvalValue) flag).valueBool()) {
                return flag;
            }
            return demandBool(eval(binary.right()));
        }
        if (operator == ExpressionAst.BinaryOperator.OR) {
            var left = eval(binary.left());
            var flag = demandBool(left);
            if (flag instanceof EvalError) {
                return flag;
            }
            if (((EvalValue) flag).valueBool()) {
                return flag;
            }
            return demandBool(eval(binary.right()));
        }
        var left = eval(binary.left());
        if (left instanceof EvalError) {
            return left;
        }
        var right = eval(binary.right());
        if (right instanceof EvalError) {
            return right;
        }
        if (left instanceof EvalAbsent || right instanceof EvalAbsent) {
            return absentDemanded();
        }
        var leftValue = ((EvalValue) left).value();
        var rightValue = ((EvalValue) right).value();
        return switch (operator) {
            case EQ -> comparisonResult(compareEquality(leftValue, rightValue));
            case NOT_EQ -> comparisonResult(!compareEquality(leftValue, rightValue));
            case LT -> comparisonResult(compareOrdered(leftValue, rightValue) < 0);
            case LT_EQ -> comparisonResult(compareOrdered(leftValue, rightValue) <= 0);
            case GT -> comparisonResult(compareOrdered(leftValue, rightValue) > 0);
            case GT_EQ -> comparisonResult(compareOrdered(leftValue, rightValue) >= 0);
            case ADD -> arithmetic(leftValue, rightValue, '+');
            case SUBTRACT -> arithmetic(leftValue, rightValue, '-');
            case MULTIPLY -> arithmetic(leftValue, rightValue, '*');
            default -> typeFault();
        };
    }

    private EvalOutcome evalCall(ExpressionAst.Call call) {
        return switch (call.function()) {
            case EXISTS -> {
                var operand = eval(call.arguments().get(0));
                if (operand instanceof EvalError) {
                    yield operand;
                }
                yield new EvalValue(new DesignValue.Bool(operand instanceof EvalValue));
            }
            case COALESCE -> {
                var first = eval(call.arguments().get(0));
                yield first instanceof EvalAbsent ? eval(call.arguments().get(1)) : first;
            }
            case IF -> {
                var condition = demandBool(eval(call.arguments().get(0)));
                if (condition instanceof EvalError) {
                    yield condition;
                }
                yield ((EvalValue) condition).valueBool()
                        ? eval(call.arguments().get(1))
                        : eval(call.arguments().get(2));
            }
            case CONCAT -> {
                var builder = new StringBuilder();
                var fault = false;
                EvalOutcome failed = null;
                for (var argument : call.arguments()) {
                    var outcome = eval(argument);
                    if (outcome instanceof EvalError) {
                        failed = outcome;
                        fault = true;
                        break;
                    }
                    if (outcome instanceof EvalAbsent) {
                        failed = absentDemanded();
                        fault = true;
                        break;
                    }
                    var value = ((EvalValue) outcome).value();
                    if (!(value instanceof DesignValue.Text text)) {
                        failed = typeFault();
                        fault = true;
                        break;
                    }
                    builder.append(text.value());
                }
                yield fault ? failed : new EvalValue(new DesignValue.Text(builder.toString()));
            }
            case LENGTH -> {
                var outcome = eval(call.arguments().get(0));
                if (outcome instanceof EvalError) {
                    yield outcome;
                }
                if (outcome instanceof EvalAbsent) {
                    yield absentDemanded();
                }
                var value = ((EvalValue) outcome).value();
                if (value instanceof DesignValue.Text text) {
                    yield new EvalValue(new DesignValue.Decimal(BigDecimal.valueOf(
                            text.value().codePointCount(0, text.value().length()))));
                }
                if (value instanceof DesignValue.ListValue list) {
                    yield new EvalValue(new DesignValue.Decimal(
                            BigDecimal.valueOf(list.items().size())));
                }
                yield typeFault();
            }
            case DIVIDE -> divide(call);
            case ROUND -> round(call);
            case FORMAT_DECIMAL -> formatDecimal(call);
            case FORMAT_DATE -> formatIdentity(call, "date");
            case FORMAT_TIME -> formatIdentity(call, "time");
        };
    }

    private EvalOutcome divide(ExpressionAst.Call call) {
        var numerator = demandDecimal(eval(call.arguments().get(0)));
        if (numerator instanceof EvalError) {
            return numerator;
        }
        var denominator = demandDecimal(eval(call.arguments().get(1)));
        if (denominator instanceof EvalError) {
            return denominator;
        }
        var divisor = ((EvalValue) denominator).valueDecimal();
        if (divisor.signum() == 0) {
            return new EvalError(new RuntimeFailure(RuntimeFailureKind.DIVISION_BY_ZERO, null));
        }
        int scale = literalInt(call.arguments().get(2));
        var mode = roundingMode(call.arguments().get(3));
        return checkedDecimal(((EvalValue) numerator).valueDecimal()
                .divide(divisor, scale, mode));
    }

    private EvalOutcome round(ExpressionAst.Call call) {
        var operand = demandDecimal(eval(call.arguments().get(0)));
        if (operand instanceof EvalError) {
            return operand;
        }
        int scale = literalInt(call.arguments().get(1));
        var mode = roundingMode(call.arguments().get(2));
        return checkedDecimal(((EvalValue) operand).valueDecimal().setScale(scale, mode));
    }

    private EvalOutcome formatDecimal(ExpressionAst.Call call) {
        var operand = demandDecimal(eval(call.arguments().get(0)));
        if (operand instanceof EvalError) {
            return operand;
        }
        int min = literalInt(call.arguments().get(1));
        int max = literalInt(call.arguments().get(2));
        var mode = roundingMode(call.arguments().get(3));
        var rounded = ((EvalValue) operand).valueDecimal().setScale(max, mode);
        var plain = rounded.toPlainString();
        int dot = plain.indexOf('.');
        if (dot < 0) {
            return new EvalValue(new DesignValue.Text(plain));
        }
        var integerPart = plain.substring(0, dot);
        var fraction = new StringBuilder(plain.substring(dot + 1));
        while (fraction.length() > min && fraction.charAt(fraction.length() - 1) == '0') {
            fraction.deleteCharAt(fraction.length() - 1);
        }
        var formatted = fraction.length() == 0
                ? integerPart
                : integerPart + "." + fraction;
        return new EvalValue(new DesignValue.Text(formatted));
    }

    private EvalOutcome formatIdentity(ExpressionAst.Call call, String requiredType) {
        var operand = eval(call.arguments().get(0));
        if (operand instanceof EvalError) {
            return operand;
        }
        if (operand instanceof EvalAbsent) {
            return absentDemanded();
        }
        var value = ((EvalValue) operand).value();
        if ("date".equals(requiredType) && value instanceof DesignValue.Date date) {
            return new EvalValue(new DesignValue.Text(date.value()));
        }
        if ("time".equals(requiredType) && value instanceof DesignValue.Time time) {
            return new EvalValue(new DesignValue.Text(time.value()));
        }
        return typeFault();
    }

    private EvalOutcome demandBool(EvalOutcome outcome) {
        if (outcome instanceof EvalError) {
            return outcome;
        }
        if (outcome instanceof EvalAbsent) {
            return absentDemanded();
        }
        if (((EvalValue) outcome).value() instanceof DesignValue.Bool) {
            return outcome;
        }
        return typeFault();
    }

    private EvalOutcome demandDecimal(EvalOutcome outcome) {
        if (outcome instanceof EvalError) {
            return outcome;
        }
        if (outcome instanceof EvalAbsent) {
            return absentDemanded();
        }
        if (((EvalValue) outcome).value() instanceof DesignValue.Decimal) {
            return outcome;
        }
        return typeFault();
    }

    private static boolean compareEquality(DesignValue left, DesignValue right) {
        if (left instanceof DesignValue.Text a && right instanceof DesignValue.Text b) {
            return a.value().equals(b.value());
        }
        if (left instanceof DesignValue.Decimal a && right instanceof DesignValue.Decimal b) {
            return a.value().compareTo(b.value()) == 0;
        }
        if (left instanceof DesignValue.Bool a && right instanceof DesignValue.Bool b) {
            return a.value() == b.value();
        }
        if (left instanceof DesignValue.Date a && right instanceof DesignValue.Date b) {
            return a.value().equals(b.value());
        }
        if (left instanceof DesignValue.Time a && right instanceof DesignValue.Time b) {
            return a.value().equals(b.value());
        }
        if (left instanceof DesignValue.Color a && right instanceof DesignValue.Color b) {
            return a.value().equals(b.value());
        }
        if (left instanceof DesignValue.ImageRef a && right instanceof DesignValue.ImageRef b) {
            return a.assetId().equals(b.assetId());
        }
        if (left instanceof DesignValue.FontRef a && right instanceof DesignValue.FontRef b) {
            return a.assetId().equals(b.assetId());
        }
        return false;
    }

    private static int compareOrdered(DesignValue left, DesignValue right) {
        if (left instanceof DesignValue.Decimal a && right instanceof DesignValue.Decimal b) {
            return a.value().compareTo(b.value());
        }
        if (left instanceof DesignValue.Date a && right instanceof DesignValue.Date b) {
            return a.value().compareTo(b.value());
        }
        if (left instanceof DesignValue.Time a && right instanceof DesignValue.Time b) {
            return a.value().compareTo(b.value());
        }
        return 0;
    }

    private EvalOutcome arithmetic(DesignValue left, DesignValue right, char operator) {
        if (!(left instanceof DesignValue.Decimal a) || !(right instanceof DesignValue.Decimal b)) {
            return typeFault();
        }
        var result = switch (operator) {
            case '+' -> a.value().add(b.value());
            case '-' -> a.value().subtract(b.value());
            default -> a.value().multiply(b.value());
        };
        return checkedDecimal(result);
    }

    private static EvalOutcome comparisonResult(boolean flag) {
        return new EvalValue(new DesignValue.Bool(flag));
    }

    private static EvalOutcome checkedDecimal(BigDecimal result) {
        if (result.precision() > MAX_INTERMEDIATE_PRECISION_DIGITS) {
            return new EvalError(new RuntimeFailure(
                    RuntimeFailureKind.DECIMAL_LIMIT_EXCEEDED,
                    "expression.intermediateDecimalPrecisionDigits"));
        }
        if (result.scale() > MAX_INTERMEDIATE_SCALE || result.scale() < -MAX_INTERMEDIATE_SCALE) {
            return new EvalError(new RuntimeFailure(
                    RuntimeFailureKind.DECIMAL_LIMIT_EXCEEDED,
                    "expression.intermediateDecimalScaleMax"));
        }
        return new EvalValue(new DesignValue.Decimal(result));
    }

    private static EvalOutcome absentDemanded() {
        return new EvalError(new RuntimeFailure(RuntimeFailureKind.ABSENT_DEMANDED, null));
    }

    private static EvalOutcome typeFault() {
        return new EvalError(new RuntimeFailure(RuntimeFailureKind.TYPE_FAULT, null));
    }

    private static int literalInt(ExpressionAst node) {
        if (node instanceof ExpressionAst.DecimalLiteral literal) {
            return literal.value().intValueExact();
        }
        throw new IllegalStateException("analyzer must enforce compile-time literals");
    }

    private static RoundingMode roundingMode(ExpressionAst node) {
        if (node instanceof ExpressionAst.TextLiteral literal) {
            return switch (literal.value()) {
                case "HALF_UP" -> RoundingMode.HALF_UP;
                case "HALF_EVEN" -> RoundingMode.HALF_EVEN;
                case "DOWN" -> RoundingMode.DOWN;
                case "UP" -> RoundingMode.UP;
                default -> throw new IllegalStateException(
                        "analyzer must enforce rounding modes");
            };
        }
        throw new IllegalStateException("analyzer must enforce compile-time literals");
    }
}
