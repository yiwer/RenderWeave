package cn.hbads.renderweave.rendering.internal;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * renderweave-expression/1.0 AST（冻结票据 07 §5）。grammar precedence 从低到高：
 * {@code ||}、{@code &&}、equality、relational、{@code + -}、{@code *}、unary {@code ! -}、primary。
 * v1 排除 member/index access、object/list construction、filter/map/reduce、lambda、recursion、
 * regex、date arithmetic 与任意脚本；未支持功能在本 profile 中非法。
 */
sealed interface ExpressionAst permits
        ExpressionAst.TextLiteral,
        ExpressionAst.DecimalLiteral,
        ExpressionAst.BooleanLiteral,
        ExpressionAst.InputRead,
        ExpressionAst.Unary,
        ExpressionAst.Binary,
        ExpressionAst.Call {

    record TextLiteral(String value) implements ExpressionAst {
        public TextLiteral {
            Objects.requireNonNull(value, "value");
        }
    }

    record DecimalLiteral(BigDecimal value) implements ExpressionAst {
        public DecimalLiteral {
            Objects.requireNonNull(value, "value");
        }
    }

    record BooleanLiteral(boolean value) implements ExpressionAst {
    }

    record InputRead(String alias) implements ExpressionAst {
        public InputRead {
            Objects.requireNonNull(alias, "alias");
        }
    }

    enum UnaryOperator {
        NOT,
        NEGATE
    }

    record Unary(UnaryOperator operator, ExpressionAst operand) implements ExpressionAst {
        public Unary {
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(operand, "operand");
        }
    }

    enum BinaryOperator {
        OR,
        AND,
        EQ,
        NOT_EQ,
        LT,
        LT_EQ,
        GT,
        GT_EQ,
        ADD,
        SUBTRACT,
        MULTIPLY
    }

    record Binary(BinaryOperator operator, ExpressionAst left, ExpressionAst right) implements ExpressionAst {
        public Binary {
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
        }
    }

    enum Function {
        EXISTS("exists"),
        COALESCE("coalesce"),
        IF("if"),
        CONCAT("concat"),
        LENGTH("length"),
        DIVIDE("divide"),
        ROUND("round"),
        FORMAT_DECIMAL("formatDecimal"),
        FORMAT_DATE("formatDate"),
        FORMAT_TIME("formatTime");

        private final String wire;

        Function(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        static Function fromWire(String name) {
            for (var function : values()) {
                if (function.wire.equals(name)) {
                    return function;
                }
            }
            return null;
        }
    }

    record Call(Function function, List<ExpressionAst> arguments) implements ExpressionAst {
        public Call {
            Objects.requireNonNull(function, "function");
            arguments = List.copyOf(arguments);
        }
    }
}
