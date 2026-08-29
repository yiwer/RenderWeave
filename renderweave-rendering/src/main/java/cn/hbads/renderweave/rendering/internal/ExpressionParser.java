package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.rendering.api.RenderingProblem;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * renderweave-expression/1.0 词法与语法分析。source 是 exact UTF-8 文本：可多行、无注释、
 * 大小写敏感；whitespace/newline 不归一化。text literal 使用单引号与冻结受限 escape
 * （反斜杠反斜杠、反斜杠单引号、换行类 n/r/t、以及反斜杠 u{scalar} 码点转义）；
 * decimal literal 使用 JSON number/exponent 词法。
 */
final class ExpressionParser {

    private static final DesignInputExpressionCapacityGuard CAPACITY_GUARD =
            new DesignInputExpressionCapacityGuard();

    enum ParseFailureKind {
        SYNTAX_INVALID
    }

    record ParseFailure(ParseFailureKind kind, int position, String limitId) {
    }

    sealed interface ParseResult permits ParsedAst, ParseRejected, ParseLimitExceeded {
    }

    record ParsedAst(ExpressionAst ast) implements ParseResult {
    }

    record ParseRejected(ParseFailure failure) implements ParseResult {
    }

    record ParseLimitExceeded(RenderingProblem problem) implements ParseResult {
        ParseLimitExceeded {
            Objects.requireNonNull(problem, "problem");
        }
    }

    private final String source;
    private int position;
    private int nodeCount;
    private ParseFailure failure;
    private RenderingProblem capacityProblem;

    private ExpressionParser(String source) {
        this.source = source;
    }

    static ParseResult parse(byte[] sourceUtf8) {
        return parse(sourceUtf8, CAPACITY_GUARD.newSourceBudget());
    }

    static ParseResult parse(
            byte[] sourceUtf8,
            DesignInputExpressionCapacityGuard.SourceBudget sourceBudget
    ) {
        Objects.requireNonNull(sourceBudget, "sourceBudget");
        var capacityProblem = sourceBudget.admit(sourceUtf8.length);
        if (capacityProblem.isPresent()) {
            return new ParseLimitExceeded(capacityProblem.orElseThrow());
        }
        var parser = new ExpressionParser(new String(sourceUtf8, StandardCharsets.UTF_8));
        var expression = parser.parseExpression();
        if (parser.capacityProblem != null) {
            return new ParseLimitExceeded(parser.capacityProblem);
        }
        if (parser.failure != null) {
            return new ParseRejected(parser.failure);
        }
        parser.skipWhitespace();
        if (parser.position < parser.source.length()) {
            return new ParseRejected(new ParseFailure(
                    ParseFailureKind.SYNTAX_INVALID, parser.position, null));
        }
        return new ParsedAst(expression);
    }

    private ParseFailure reject(ParseFailureKind kind, int at, String limitId) {
        if (failure == null) {
            failure = new ParseFailure(kind, at, limitId);
        }
        return failure;
    }

    private ExpressionAst count(ExpressionAst node) {
        if (capacityProblem != null) {
            return node;
        }
        nodeCount++;
        CAPACITY_GUARD.admit(
                        DesignInputExpressionCapacityGuard.Limit.AST_NODES_PER_EXPRESSION,
                        nodeCount)
                .ifPresent(problem -> capacityProblem = problem);
        return node;
    }

    private boolean failed() {
        return failure != null || capacityProblem != null;
    }

    // ------------------------------------------------------------------
    // precedence climbing: || < && < equality < relational < + - < * < unary < primary
    // ------------------------------------------------------------------

    private ExpressionAst parseExpression() {
        return parseOr();
    }

    private ExpressionAst parseOr() {
        var left = parseAnd();
        while (!failed() && peekOperator("||")) {
            consumeOperator("||");
            var right = parseAnd();
            left = count(new ExpressionAst.Binary(ExpressionAst.BinaryOperator.OR, left, right));
        }
        return left;
    }

    private ExpressionAst parseAnd() {
        var left = parseEquality();
        while (!failed() && peekOperator("&&")) {
            consumeOperator("&&");
            var right = parseEquality();
            left = count(new ExpressionAst.Binary(ExpressionAst.BinaryOperator.AND, left, right));
        }
        return left;
    }

    private ExpressionAst parseEquality() {
        var left = parseRelational();
        while (!failed()) {
            if (peekOperator("==")) {
                consumeOperator("==");
                left = count(new ExpressionAst.Binary(
                        ExpressionAst.BinaryOperator.EQ, left, parseRelational()));
            } else if (peekOperator("!=")) {
                consumeOperator("!=");
                left = count(new ExpressionAst.Binary(
                        ExpressionAst.BinaryOperator.NOT_EQ, left, parseRelational()));
            } else {
                return left;
            }
        }
        return left;
    }

    private ExpressionAst parseRelational() {
        var left = parseAdditive();
        while (!failed()) {
            if (peekOperator("<=")) {
                consumeOperator("<=");
                left = count(new ExpressionAst.Binary(
                        ExpressionAst.BinaryOperator.LT_EQ, left, parseAdditive()));
            } else if (peekOperator(">=")) {
                consumeOperator(">=");
                left = count(new ExpressionAst.Binary(
                        ExpressionAst.BinaryOperator.GT_EQ, left, parseAdditive()));
            } else if (peekOperator("<")) {
                consumeOperator("<");
                left = count(new ExpressionAst.Binary(
                        ExpressionAst.BinaryOperator.LT, left, parseAdditive()));
            } else if (peekOperator(">")) {
                consumeOperator(">");
                left = count(new ExpressionAst.Binary(
                        ExpressionAst.BinaryOperator.GT, left, parseAdditive()));
            } else {
                return left;
            }
        }
        return left;
    }

    private ExpressionAst parseAdditive() {
        var left = parseMultiplicative();
        while (!failed()) {
            if (peekOperator("+")) {
                consumeOperator("+");
                left = count(new ExpressionAst.Binary(
                        ExpressionAst.BinaryOperator.ADD, left, parseMultiplicative()));
            } else if (peekOperator("-")) {
                consumeOperator("-");
                left = count(new ExpressionAst.Binary(
                        ExpressionAst.BinaryOperator.SUBTRACT, left, parseMultiplicative()));
            } else {
                return left;
            }
        }
        return left;
    }

    private ExpressionAst parseMultiplicative() {
        var left = parseUnary();
        while (!failed() && peekOperator("*")) {
            consumeOperator("*");
            left = count(new ExpressionAst.Binary(
                    ExpressionAst.BinaryOperator.MULTIPLY, left, parseUnary()));
        }
        return left;
    }

    private ExpressionAst parseUnary() {
        skipWhitespace();
        if (peekOperator("!")) {
            consumeOperator("!");
            return count(new ExpressionAst.Unary(
                    ExpressionAst.UnaryOperator.NOT, parseUnary()));
        }
        if (peekOperator("-")) {
            consumeOperator("-");
            return count(new ExpressionAst.Unary(
                    ExpressionAst.UnaryOperator.NEGATE, parseUnary()));
        }
        return parsePrimary();
    }

    private ExpressionAst parsePrimary() {
        if (failed()) {
            return null;
        }
        skipWhitespace();
        if (position >= source.length()) {
            reject(ParseFailureKind.SYNTAX_INVALID, position, null);
            return null;
        }
        char current = source.charAt(position);
        if (current == '(') {
            position++;
            var inner = parseExpression();
            if (failed()) {
                return null;
            }
            if (!peekOperator(")")) {
                reject(ParseFailureKind.SYNTAX_INVALID, position, null);
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
        reject(ParseFailureKind.SYNTAX_INVALID, position, null);
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
                reject(ParseFailureKind.SYNTAX_INVALID, position, null);
                return null;
            }
            position++;
            var arguments = new ArrayList<ExpressionAst>();
            skipWhitespace();
            if (peekOperator(")")) {
                consumeOperator(")");
                return count(new ExpressionAst.Call(function, arguments));
            }
            while (true) {
                var argument = parseExpression();
                if (failed()) {
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
                    return count(new ExpressionAst.Call(function, arguments));
                }
                reject(ParseFailureKind.SYNTAX_INVALID, position, null);
                return null;
            }
        }
        if ("true".equals(name)) {
            return count(new ExpressionAst.BooleanLiteral(true));
        }
        if ("false".equals(name)) {
            return count(new ExpressionAst.BooleanLiteral(false));
        }
        if ("input".equals(name)) {
            if (!peekOperator(".")) {
                reject(ParseFailureKind.SYNTAX_INVALID, position, null);
                return null;
            }
            consumeOperator(".");
            var alias = readIdentifier();
            if (alias == null) {
                return null;
            }
            return count(new ExpressionAst.InputRead(alias));
        }
        reject(ParseFailureKind.SYNTAX_INVALID, position, null);
        return null;
    }

    private ExpressionAst parseTextLiteral() {
        position++;
        var builder = new StringBuilder();
        while (true) {
            if (position >= source.length()) {
                reject(ParseFailureKind.SYNTAX_INVALID, position, null);
                return null;
            }
            char current = source.charAt(position);
            if (current == '\'') {
                position++;
                return count(new ExpressionAst.TextLiteral(builder.toString()));
            }
            if (current == '\\') {
                position++;
                if (position >= source.length()) {
                    reject(ParseFailureKind.SYNTAX_INVALID, position, null);
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
                            reject(ParseFailureKind.SYNTAX_INVALID, position, null);
                            return null;
                        }
                        position++;
                        int start = position;
                        while (position < source.length() && source.charAt(position) != '}') {
                            position++;
                        }
                        if (position >= source.length() || position == start) {
                            reject(ParseFailureKind.SYNTAX_INVALID, position, null);
                            return null;
                        }
                        var hex = source.substring(start, position);
                        int codePoint;
                        try {
                            codePoint = Integer.parseInt(hex, 16);
                        } catch (NumberFormatException invalid) {
                            reject(ParseFailureKind.SYNTAX_INVALID, start, null);
                            return null;
                        }
                        if (!Character.isValidCodePoint(codePoint)
                                || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
                            reject(ParseFailureKind.SYNTAX_INVALID, start, null);
                            return null;
                        }
                        builder.appendCodePoint(codePoint);
                        // position sits on '}'; falls through to the shared advance below.
                    }
                    default -> {
                        reject(ParseFailureKind.SYNTAX_INVALID, position, null);
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
            reject(ParseFailureKind.SYNTAX_INVALID, position, null);
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
                reject(ParseFailureKind.SYNTAX_INVALID, position, null);
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
                reject(ParseFailureKind.SYNTAX_INVALID, position, null);
                return null;
            }
        }
        var token = source.substring(start, position);
        return count(new ExpressionAst.DecimalLiteral(new BigDecimal(token)));
    }

    private String readIdentifier() {
        skipWhitespace();
        if (position >= source.length() || !isIdentifierStart(source.charAt(position))) {
            reject(ParseFailureKind.SYNTAX_INVALID, position, null);
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
