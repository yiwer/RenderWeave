package cn.hbads.renderweave.template.internal;

import cn.hbads.renderweave.template.api.DesignSemanticAuthority.ExpressionAst;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * renderweave-expression/1.0 词法与语法分析。source 是 exact UTF-8 文本：可多行、无注释、
 * 大小写敏感；whitespace/newline 不归一化。text literal 使用单引号与冻结受限 escape
 * （反斜杠反斜杠、反斜杠单引号、换行类 n/r/t、以及反斜杠 u{scalar} 码点转义）；
 * decimal literal 使用 JSON number/exponent 词法。
 */
final class ExpressionParser {

    enum ParseFailureKind {
        SYNTAX_INVALID
    }

    record ParseFailure(ParseFailureKind kind, int position) {
    }

    sealed interface ParseResult permits ParsedAst, ParseRejected {
    }

    record ParsedAst(ExpressionAst ast) implements ParseResult {
    }

    record ParseRejected(ParseFailure failure) implements ParseResult {
    }

    private final String source;
    private final AstNodeReservation astNodes;
    private int position;
    private long nodeCount;
    private ParseFailure failure;

    private ExpressionParser(String source, AstNodeReservation astNodes) {
        this.source = source;
        this.astNodes = astNodes;
    }

    static ParseResult parse(byte[] sourceUtf8, AstNodeReservation astNodes)
            throws DesignDslFailureException {
        var parser = new ExpressionParser(
                new String(sourceUtf8, StandardCharsets.UTF_8), astNodes);
        try {
            var expression = parser.parseExpression();
            if (parser.failure != null) {
                return new ParseRejected(parser.failure);
            }
            parser.skipWhitespace();
            if (parser.position < parser.source.length()) {
                return new ParseRejected(new ParseFailure(
                        ParseFailureKind.SYNTAX_INVALID, parser.position));
            }
            return new ParsedAst(expression);
        } catch (AstReservationRejected rejected) {
            throw rejected.failure();
        }
    }

    private ParseFailure reject(ParseFailureKind kind, int at) {
        if (failure == null) {
            failure = new ParseFailure(kind, at);
        }
        return failure;
    }

    private void reserveNode() {
        long candidate;
        try {
            candidate = Math.addExact(nodeCount, 1L);
            astNodes.reserve(candidate);
        } catch (DesignDslFailureException rejected) {
            throw new AstReservationRejected(rejected);
        } catch (ArithmeticException overflow) {
            throw new IllegalStateException("Expression AST node counter overflow", overflow);
        }
        nodeCount = candidate;
    }

    private ExpressionAst textLiteral(String value) {
        reserveNode();
        return new ExpressionAst.TextLiteral(value);
    }

    private ExpressionAst decimalLiteral(BigDecimal value) {
        reserveNode();
        return new ExpressionAst.DecimalLiteral(value);
    }

    private ExpressionAst booleanLiteral(boolean value) {
        reserveNode();
        return new ExpressionAst.BooleanLiteral(value);
    }

    private ExpressionAst inputRead(String alias) {
        reserveNode();
        return new ExpressionAst.InputRead(alias);
    }

    private ExpressionAst unary(
            ExpressionAst.UnaryOperator operator,
            ExpressionAst operand
    ) {
        reserveNode();
        return new ExpressionAst.Unary(operator, operand);
    }

    private ExpressionAst binary(
            ExpressionAst.BinaryOperator operator,
            ExpressionAst left,
            ExpressionAst right
    ) {
        reserveNode();
        return new ExpressionAst.Binary(operator, left, right);
    }

    private ExpressionAst call(
            ExpressionAst.Function function,
            List<ExpressionAst> arguments
    ) {
        reserveNode();
        return new ExpressionAst.Call(function, arguments);
    }

    @FunctionalInterface
    interface AstNodeReservation {
        void reserve(long perExpressionCandidate) throws DesignDslFailureException;
    }

    private static final class AstReservationRejected extends RuntimeException {
        private final DesignDslFailureException failure;

        private AstReservationRejected(DesignDslFailureException failure) {
            super(null, failure, false, false);
            this.failure = failure;
        }

        private DesignDslFailureException failure() {
            return failure;
        }
    }

    // ------------------------------------------------------------------
    // precedence climbing: || < && < equality < relational < + - < * < unary < primary
    // ------------------------------------------------------------------

    private ExpressionAst parseExpression() {
        return parseOr();
    }

    private ExpressionAst parseOr() {
        var left = parseAnd();
        while (failure == null && peekOperator("||")) {
            consumeOperator("||");
            var right = parseAnd();
            left = binary(ExpressionAst.BinaryOperator.OR, left, right);
        }
        return left;
    }

    private ExpressionAst parseAnd() {
        var left = parseEquality();
        while (failure == null && peekOperator("&&")) {
            consumeOperator("&&");
            var right = parseEquality();
            left = binary(ExpressionAst.BinaryOperator.AND, left, right);
        }
        return left;
    }

    private ExpressionAst parseEquality() {
        var left = parseRelational();
        while (failure == null) {
            if (peekOperator("==")) {
                consumeOperator("==");
                left = binary(
                        ExpressionAst.BinaryOperator.EQ, left, parseRelational());
            } else if (peekOperator("!=")) {
                consumeOperator("!=");
                left = binary(
                        ExpressionAst.BinaryOperator.NOT_EQ, left, parseRelational());
            } else {
                return left;
            }
        }
        return left;
    }

    private ExpressionAst parseRelational() {
        var left = parseAdditive();
        while (failure == null) {
            if (peekOperator("<=")) {
                consumeOperator("<=");
                left = binary(
                        ExpressionAst.BinaryOperator.LT_EQ, left, parseAdditive());
            } else if (peekOperator(">=")) {
                consumeOperator(">=");
                left = binary(
                        ExpressionAst.BinaryOperator.GT_EQ, left, parseAdditive());
            } else if (peekOperator("<")) {
                consumeOperator("<");
                left = binary(
                        ExpressionAst.BinaryOperator.LT, left, parseAdditive());
            } else if (peekOperator(">")) {
                consumeOperator(">");
                left = binary(
                        ExpressionAst.BinaryOperator.GT, left, parseAdditive());
            } else {
                return left;
            }
        }
        return left;
    }

    private ExpressionAst parseAdditive() {
        var left = parseMultiplicative();
        while (failure == null) {
            if (peekOperator("+")) {
                consumeOperator("+");
                left = binary(
                        ExpressionAst.BinaryOperator.ADD, left, parseMultiplicative());
            } else if (peekOperator("-")) {
                consumeOperator("-");
                left = binary(
                        ExpressionAst.BinaryOperator.SUBTRACT, left, parseMultiplicative());
            } else {
                return left;
            }
        }
        return left;
    }

    private ExpressionAst parseMultiplicative() {
        var left = parseUnary();
        while (failure == null && peekOperator("*")) {
            consumeOperator("*");
            left = binary(
                    ExpressionAst.BinaryOperator.MULTIPLY, left, parseUnary());
        }
        return left;
    }

    private ExpressionAst parseUnary() {
        skipWhitespace();
        if (peekOperator("!")) {
            consumeOperator("!");
            return unary(ExpressionAst.UnaryOperator.NOT, parseUnary());
        }
        if (peekOperator("-")) {
            consumeOperator("-");
            return unary(ExpressionAst.UnaryOperator.NEGATE, parseUnary());
        }
        return parsePrimary();
    }

    private ExpressionAst parsePrimary() {
        if (failure != null) {
            return null;
        }
        skipWhitespace();
        if (position >= source.length()) {
            reject(ParseFailureKind.SYNTAX_INVALID, position);
            return null;
        }
        char current = source.charAt(position);
        if (current == '(') {
            position++;
            var inner = parseExpression();
            if (failure != null) {
                return null;
            }
            if (!peekOperator(")")) {
                reject(ParseFailureKind.SYNTAX_INVALID, position);
                return null;
            }
            consumeOperator(")");
            return inner;
        }
        if (current == '\'') {
            return parseTextLiteral();
        }
        if (current == '-' || (current >= '0' && current <= '9')) {
            return parseDecimalLiteral();
        }
        if (isIdentifierStart(current)) {
            return parseIdentifierLed();
        }
        reject(ParseFailureKind.SYNTAX_INVALID, position);
        return null;
    }

    private ExpressionAst parseIdentifierLed() {
        var name = readIdentifier();
        if (name == null) {
            return null;
        }
        skipWhitespace();
        if (position < source.length() && source.charAt(position) == '(') {
            var function = ExpressionAst.Function.fromWire(name);
            if (function == null) {
                reject(ParseFailureKind.SYNTAX_INVALID, position);
                return null;
            }
            position++;
            var arguments = new ArrayList<ExpressionAst>();
            skipWhitespace();
            if (peekOperator(")")) {
                consumeOperator(")");
                return call(function, arguments);
            }
            while (true) {
                var argument = parseExpression();
                if (failure != null) {
                    return null;
                }
                arguments.add(argument);
                skipWhitespace();
                if (peekOperator(",")) {
                    consumeOperator(",");
                    continue;
                }
                if (peekOperator(")")) {
                    consumeOperator(")");
                    return call(function, arguments);
                }
                reject(ParseFailureKind.SYNTAX_INVALID, position);
                return null;
            }
        }
        if ("true".equals(name)) {
            return booleanLiteral(true);
        }
        if ("false".equals(name)) {
            return booleanLiteral(false);
        }
        if ("input".equals(name)) {
            if (!peekOperator(".")) {
                reject(ParseFailureKind.SYNTAX_INVALID, position);
                return null;
            }
            consumeOperator(".");
            var alias = readIdentifier();
            if (alias == null) {
                return null;
            }
            return inputRead(alias);
        }
        reject(ParseFailureKind.SYNTAX_INVALID, position);
        return null;
    }

    private ExpressionAst parseTextLiteral() {
        position++;
        var builder = new StringBuilder();
        while (true) {
            if (position >= source.length()) {
                reject(ParseFailureKind.SYNTAX_INVALID, position);
                return null;
            }
            char current = source.charAt(position);
            if (current == '\'') {
                position++;
                return textLiteral(builder.toString());
            }
            if (current == '\\') {
                position++;
                if (position >= source.length()) {
                    reject(ParseFailureKind.SYNTAX_INVALID, position);
                    return null;
                }
                char escaped = source.charAt(position);
                switch (escaped) {
                    case '\\' -> builder.append('\\');
                    case '\'' -> builder.append('\'');
                    case 'n' -> builder.append('\n');
                    case 'r' -> builder.append('\r');
                    case 't' -> builder.append('\t');
                    case 'u' -> {
                        position++;
                        if (position >= source.length() || source.charAt(position) != '{') {
                            reject(ParseFailureKind.SYNTAX_INVALID, position);
                            return null;
                        }
                        position++;
                        int start = position;
                        while (position < source.length() && source.charAt(position) != '}') {
                            position++;
                        }
                        if (position >= source.length() || position == start) {
                            reject(ParseFailureKind.SYNTAX_INVALID, position);
                            return null;
                        }
                        var hex = source.substring(start, position);
                        int codePoint;
                        try {
                            codePoint = Integer.parseInt(hex, 16);
                        } catch (NumberFormatException invalid) {
                            reject(ParseFailureKind.SYNTAX_INVALID, start);
                            return null;
                        }
                        if (!Character.isValidCodePoint(codePoint)
                                || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
                            reject(ParseFailureKind.SYNTAX_INVALID, start);
                            return null;
                        }
                        builder.appendCodePoint(codePoint);
                        // position sits on '}'; falls through to the shared advance below.
                    }
                    default -> {
                        reject(ParseFailureKind.SYNTAX_INVALID, position);
                        return null;
                    }
                }
                position++;
                continue;
            }
            builder.append(current);
            position++;
        }
    }

    private ExpressionAst parseDecimalLiteral() {
        int start = position;
        if (position < source.length() && source.charAt(position) == '-') {
            position++;
        }
        int integerStart = position;
        while (position < source.length()
                && source.charAt(position) >= '0' && source.charAt(position) <= '9') {
            position++;
        }
        if (position == integerStart) {
            reject(ParseFailureKind.SYNTAX_INVALID, position);
            return null;
        }
        if (position < source.length() && source.charAt(position) == '.') {
            position++;
            int fractionStart = position;
            while (position < source.length()
                    && source.charAt(position) >= '0' && source.charAt(position) <= '9') {
                position++;
            }
            if (position == fractionStart) {
                reject(ParseFailureKind.SYNTAX_INVALID, position);
                return null;
            }
        }
        if (position < source.length()
                && (source.charAt(position) == 'e' || source.charAt(position) == 'E')) {
            position++;
            if (position < source.length()
                    && (source.charAt(position) == '+' || source.charAt(position) == '-')) {
                position++;
            }
            int exponentStart = position;
            while (position < source.length()
                    && source.charAt(position) >= '0' && source.charAt(position) <= '9') {
                position++;
            }
            if (position == exponentStart) {
                reject(ParseFailureKind.SYNTAX_INVALID, position);
                return null;
            }
        }
        var token = source.substring(start, position);
        return decimalLiteral(new BigDecimal(token));
    }

    private String readIdentifier() {
        skipWhitespace();
        if (position >= source.length() || !isIdentifierStart(source.charAt(position))) {
            reject(ParseFailureKind.SYNTAX_INVALID, position);
            return null;
        }
        int start = position;
        while (position < source.length() && isIdentifierPart(source.charAt(position))) {
            position++;
        }
        return source.substring(start, position);
    }

    private static boolean isIdentifierStart(char value) {
        return (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z') || value == '_';
    }

    private static boolean isIdentifierPart(char value) {
        return isIdentifierStart(value) || (value >= '0' && value <= '9');
    }

    private boolean peekOperator(String operator) {
        skipWhitespace();
        return source.startsWith(operator, position);
    }

    private void consumeOperator(String operator) {
        position += operator.length();
    }

    private void skipWhitespace() {
        while (position < source.length()) {
            char current = source.charAt(position);
            if (current == ' ' || current == '\t' || current == '\n' || current == '\r') {
                position++;
            } else {
                return;
            }
        }
    }
}
