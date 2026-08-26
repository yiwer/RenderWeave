package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.template.api.DesignInputExpressionCapacityAuthority;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Rendering-owned strict-JSON recursive descent parser with counting budgets.
 * Identity encoding only: the body must be valid UTF-8; numbers retain raw tokens;
 * duplicate object members and trailing content are rejected.
 */
final class RenderJsonParser {

    interface CapacityBudget {
        String limitIdPrefix();

        CapacityReservation reserve(String suffix, long observedValue);
    }

    private sealed interface CapacityReservation
            permits CapacityAccepted, CapacityRejected, CapacityInvalid {
    }

    private enum CapacityAccepted implements CapacityReservation {
        INSTANCE
    }

    private record CapacityRejected(
            String limitId,
            DesignInputExpressionCapacityAuthority.Terminal terminal
    ) implements CapacityReservation {
    }

    private record CapacityInvalid() implements CapacityReservation {
    }

    private static final CapacityInvalid CAPACITY_INVALID = new CapacityInvalid();

    /** Counting budget with the capacity-group name used as limitId prefix. */
    record JsonBudget(
            String limitIdPrefix,
            long maxUtf8Bytes,
            int maxDepth,
            int maxObjectMembers,
            int maxArrayItems,
            long maxTotalValuesAndContainers,
            long maxStringUtf8Bytes,
            int maxNumberTokenBytes
    ) implements CapacityBudget {
        JsonBudget {
            Objects.requireNonNull(limitIdPrefix, "limitIdPrefix");
        }

        @Override
        public CapacityReservation reserve(String suffix, long observedValue) {
            long maximum = switch (suffix) {
                case "utf8Bytes" -> maxUtf8Bytes;
                case "jsonDepth" -> maxDepth;
                case "objectMembers" -> maxObjectMembers;
                case "arrayItems" -> maxArrayItems;
                case "totalValuesAndContainers" -> maxTotalValuesAndContainers;
                case "stringUtf8Bytes" -> maxStringUtf8Bytes;
                case "numberTokenBytes" -> maxNumberTokenBytes;
                default -> throw new IllegalArgumentException("unknown JSON budget axis: " + suffix);
            };
            return observedValue <= maximum
                    ? CapacityAccepted.INSTANCE
                    : new CapacityRejected(limitIdPrefix + "." + suffix, null);
        }
    }

    record AuthorityBudget(
            String limitIdPrefix,
            DesignInputExpressionCapacityAuthority authority
    ) implements CapacityBudget {
        AuthorityBudget {
            Objects.requireNonNull(limitIdPrefix, "limitIdPrefix");
            Objects.requireNonNull(authority, "authority");
        }

        @Override
        public CapacityReservation reserve(String suffix, long observedValue) {
            var limitId = limitIdPrefix + "." + suffix;
            final DesignInputExpressionCapacityAuthority.Decision decision;
            try {
                decision = authority.evaluate(
                        new DesignInputExpressionCapacityAuthority.Observation(
                                limitId,
                                Long.toString(observedValue)
                        )
                );
            } catch (RuntimeException unavailable) {
                return CAPACITY_INVALID;
            }
            return switch (decision) {
                case DesignInputExpressionCapacityAuthority.Accepted ignored ->
                        CapacityAccepted.INSTANCE;
                case DesignInputExpressionCapacityAuthority.Rejected rejected ->
                        new CapacityRejected(limitId, rejected.terminal());
                case DesignInputExpressionCapacityAuthority.Invalid ignored -> CAPACITY_INVALID;
                case null -> CAPACITY_INVALID;
            };
        }
    }

    enum FailureKind {
        CONTENT_ENCODING_UNSUPPORTED,
        SYNTAX_INVALID,
        DUPLICATE_MEMBER,
        LIMIT_EXCEEDED,
        CAPACITY_AUTHORITY_INVALID,
        VALUE_EXPECTED
    }

    record JsonParseFailure(
            FailureKind kind,
            String pointer,
            String limitId,
            DesignInputExpressionCapacityAuthority.Terminal terminal
    ) {
        JsonParseFailure(FailureKind kind, String pointer, String limitId) {
            this(kind, pointer, limitId, null);
        }
    }

    sealed interface ParseResult permits Parsed, ParseRejected {
    }

    record Parsed(RenderJson value) implements ParseResult {
    }

    record ParseRejected(JsonParseFailure failure) implements ParseResult {
    }

    private final byte[] body;
    private final CapacityBudget budget;
    private int position;
    private long totalValues;

    private RenderJsonParser(byte[] body, CapacityBudget budget) {
        this.body = body;
        this.budget = budget;
    }

    static ParseResult parse(byte[] body, CapacityBudget budget) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(budget, "budget");
        var bodyReservation = budget.reserve("utf8Bytes", body.length);
        if (!(bodyReservation instanceof CapacityAccepted)) {
            return new ParseRejected(capacityFailure(bodyReservation, ""));
        }
        if (!isValidUtf8(body)) {
            return new ParseRejected(new JsonParseFailure(
                    FailureKind.CONTENT_ENCODING_UNSUPPORTED, "", null));
        }
        var parser = new RenderJsonParser(body, budget);
        var value = parser.parseValue(0, "");
        if (value == null) {
            return new ParseRejected(parser.failure);
        }
        parser.skipWhitespace();
        if (parser.position < parser.body.length) {
            return new ParseRejected(new JsonParseFailure(
                    FailureKind.SYNTAX_INVALID, "", null));
        }
        return new Parsed(value);
    }

    private JsonParseFailure failure;

    private JsonParseFailure reject(FailureKind kind, String pointer, String limitId) {
        return reject(kind, pointer, limitId, null);
    }

    private JsonParseFailure reject(
            FailureKind kind,
            String pointer,
            String limitId,
            DesignInputExpressionCapacityAuthority.Terminal terminal
    ) {
        if (failure == null) {
            failure = new JsonParseFailure(kind, pointer, limitId, terminal);
        }
        return failure;
    }

    private boolean reserve(String suffix, long observedValue, String pointer) {
        var reservation = budget.reserve(suffix, observedValue);
        if (reservation instanceof CapacityAccepted) {
            return true;
        }
        failure = capacityFailure(reservation, pointer);
        return false;
    }

    private static JsonParseFailure capacityFailure(
            CapacityReservation reservation,
            String pointer
    ) {
        if (reservation instanceof CapacityRejected rejected) {
            return new JsonParseFailure(
                    FailureKind.LIMIT_EXCEEDED,
                    pointer,
                    rejected.limitId(),
                    rejected.terminal()
            );
        }
        return new JsonParseFailure(
                FailureKind.CAPACITY_AUTHORITY_INVALID,
                pointer,
                null
        );
    }

    private RenderJson parseValue(int depth, String pointer) {
        if (failure != null) {
            return null;
        }
        long candidateTotal = totalValues + 1;
        if (!reserve("totalValuesAndContainers", candidateTotal, pointer)) {
            return null;
        }
        totalValues = candidateTotal;
        skipWhitespace();
        if (position >= body.length) {
            reject(FailureKind.VALUE_EXPECTED, pointer, null);
            return null;
        }
        byte marker = body[position];
        return switch (marker) {
            case '{' -> parseObject(depth, pointer);
            case '[' -> parseArray(depth, pointer);
            case '"' -> parseString(pointer);
            case 't' -> parseLiteral("true", new RenderJson.BooleanValue(true, position, position + 4), pointer);
            case 'f' -> parseLiteral("false", new RenderJson.BooleanValue(false, position, position + 5), pointer);
            case 'n' -> parseLiteral("null", new RenderJson.NullValue(position, position + 4), pointer);
            default -> parseNumber(pointer);
        };
    }

    private RenderJson parseLiteral(String literal, RenderJson value, String pointer) {
        if (position + literal.length() > body.length) {
            reject(FailureKind.SYNTAX_INVALID, pointer, null);
            return null;
        }
        for (int index = 0; index < literal.length(); index++) {
            if (body[position + index] != (byte) literal.charAt(index)) {
                reject(FailureKind.SYNTAX_INVALID, pointer, null);
                return null;
            }
        }
        position += literal.length();
        return value;
    }

    private RenderJson parseObject(int depth, String pointer) {
        if (!reserve("jsonDepth", depth + 1L, pointer)) {
            return null;
        }
        int start = position;
        position++;
        var members = new LinkedHashMap<String, RenderJson>();
        skipWhitespace();
        if (position < body.length && body[position] == '}') {
            position++;
            return new RenderJson.ObjectValue(members, start, position);
        }
        while (true) {
            skipWhitespace();
            if (position >= body.length || body[position] != '"') {
                reject(FailureKind.SYNTAX_INVALID, pointer, null);
                return null;
            }
            var name = parseString(pointer);
            if (name == null) {
                return null;
            }
            var memberName = ((RenderJson.StringValue) name).value();
            skipWhitespace();
            if (position >= body.length || body[position] != ':') {
                reject(FailureKind.SYNTAX_INVALID, pointer, null);
                return null;
            }
            position++;
            var memberPointer = pointer + "/" + escapePointer(memberName);
            var value = parseValue(depth + 1, memberPointer);
            if (value == null) {
                return null;
            }
            if (members.containsKey(memberName)) {
                reject(FailureKind.DUPLICATE_MEMBER, memberPointer, null);
                return null;
            }
            if (!reserve("objectMembers", members.size() + 1L, pointer)) {
                return null;
            }
            members.put(memberName, value);
            skipWhitespace();
            if (position >= body.length) {
                reject(FailureKind.SYNTAX_INVALID, pointer, null);
                return null;
            }
            if (body[position] == ',') {
                position++;
                continue;
            }
            if (body[position] == '}') {
                position++;
                return new RenderJson.ObjectValue(members, start, position);
            }
            reject(FailureKind.SYNTAX_INVALID, pointer, null);
            return null;
        }
    }

    private RenderJson parseArray(int depth, String pointer) {
        if (!reserve("jsonDepth", depth + 1L, pointer)) {
            return null;
        }
        int start = position;
        position++;
        var items = new ArrayList<RenderJson>();
        skipWhitespace();
        if (position < body.length && body[position] == ']') {
            position++;
            return new RenderJson.ArrayValue(items, start, position);
        }
        while (true) {
            var item = parseValue(depth + 1, pointer + "/" + items.size());
            if (item == null) {
                return null;
            }
            if (!reserve("arrayItems", items.size() + 1L, pointer)) {
                return null;
            }
            items.add(item);
            skipWhitespace();
            if (position >= body.length) {
                reject(FailureKind.SYNTAX_INVALID, pointer, null);
                return null;
            }
            if (body[position] == ',') {
                position++;
                continue;
            }
            if (body[position] == ']') {
                position++;
                return new RenderJson.ArrayValue(items, start, position);
            }
            reject(FailureKind.SYNTAX_INVALID, pointer, null);
            return null;
        }
    }

    private RenderJson parseString(String pointer) {
        int start = position;
        position++;
        var decoded = new StringBuilder();
        long utf8Bytes = 0;
        while (true) {
            if (position >= body.length) {
                reject(FailureKind.SYNTAX_INVALID, pointer, null);
                return null;
            }
            byte current = body[position];
            if (current == '"') {
                if (!reserve("stringUtf8Bytes", utf8Bytes, pointer)) {
                    return null;
                }
                position++;
                return new RenderJson.StringValue(decoded.toString(), start, position);
            }
            if (current == '\\') {
                position++;
                if (position >= body.length) {
                    reject(FailureKind.SYNTAX_INVALID, pointer, null);
                    return null;
                }
                byte escaped = body[position];
                switch (escaped) {
                    case '"' -> decoded.append('"');
                    case '\\' -> decoded.append('\\');
                    case '/' -> decoded.append('/');
                    case 'b' -> decoded.append('\b');
                    case 'f' -> decoded.append('\f');
                    case 'n' -> decoded.append('\n');
                    case 'r' -> decoded.append('\r');
                    case 't' -> decoded.append('\t');
                    case 'u' -> {
                        int codeUnit = readHex4(pointer);
                        if (codeUnit < 0) {
                            return null;
                        }
                        if (Character.isHighSurrogate((char) codeUnit)) {
                            if (position + 6 <= body.length
                                    && body[position + 1] == '\\'
                                    && body[position + 2] == 'u') {
                                position += 2;
                                int low = readHex4(pointer);
                                if (low < 0) {
                                    return null;
                                }
                                if (!Character.isLowSurrogate((char) low)) {
                                    reject(FailureKind.SYNTAX_INVALID, pointer, null);
                                    return null;
                                }
                                int codePoint = Character.toCodePoint((char) codeUnit, (char) low);
                                decoded.appendCodePoint(codePoint);
                                utf8Bytes += utf8Length(codePoint);
                                position++;
                                continue;
                            }
                            reject(FailureKind.SYNTAX_INVALID, pointer, null);
                            return null;
                        }
                        if (Character.isLowSurrogate((char) codeUnit)) {
                            reject(FailureKind.SYNTAX_INVALID, pointer, null);
                            return null;
                        }
                        decoded.append((char) codeUnit);
                        utf8Bytes += utf8Length(codeUnit);
                        position++;
                        continue;
                    }
                    default -> {
                        reject(FailureKind.SYNTAX_INVALID, pointer, null);
                        return null;
                    }
                }
                utf8Bytes += utf8Length(decoded.codePointAt(decoded.length() - 1));
                position++;
                continue;
            }
            if ((current & 0x80) == 0) {
                if (current < 0x20) {
                    reject(FailureKind.SYNTAX_INVALID, pointer, null);
                    return null;
                }
                decoded.append((char) current);
                utf8Bytes += 1;
                position++;
                continue;
            }
            int codePoint = readUtf8CodePoint(pointer);
            if (codePoint < 0) {
                return null;
            }
            decoded.appendCodePoint(codePoint);
            utf8Bytes += utf8Length(codePoint);
        }
    }

    /** Reads a 4-hex escape without consuming the trailing position increment of the caller. */
    private int readHex4(String pointer) {
        if (position + 4 >= body.length + 4 - 4 && position + 4 > body.length) {
            reject(FailureKind.SYNTAX_INVALID, pointer, null);
            return -1;
        }
        int value = 0;
        for (int index = 1; index <= 4; index++) {
            if (position + index >= body.length) {
                reject(FailureKind.SYNTAX_INVALID, pointer, null);
                return -1;
            }
            int digit = Character.digit((char) body[position + index], 16);
            if (digit < 0) {
                reject(FailureKind.SYNTAX_INVALID, pointer, null);
                return -1;
            }
            value = (value << 4) | digit;
        }
        position += 4;
        return value;
    }

    private int readUtf8CodePoint(String pointer) {
        int lead = body[position] & 0xFF;
        int length;
        if (lead >= 0xC2 && lead <= 0xDF) {
            length = 2;
        } else if (lead >= 0xE0 && lead <= 0xEF) {
            length = 3;
        } else if (lead >= 0xF0 && lead <= 0xF4) {
            length = 4;
        } else {
            reject(FailureKind.CONTENT_ENCODING_UNSUPPORTED, pointer, null);
            return -1;
        }
        if (position + length > body.length) {
            reject(FailureKind.CONTENT_ENCODING_UNSUPPORTED, pointer, null);
            return -1;
        }
        byte[] slice = new byte[length];
        System.arraycopy(body, position, slice, 0, length);
        var decoded = new String(slice, StandardCharsets.UTF_8);
        if (decoded.length() == 0 || decoded.codePointCount(0, decoded.length()) != 1
                || decoded.charAt(0) == '�') {
            reject(FailureKind.CONTENT_ENCODING_UNSUPPORTED, pointer, null);
            return -1;
        }
        int codePoint = decoded.codePointAt(0);
        position += length;
        return codePoint;
    }

    private static int utf8Length(int codePoint) {
        if (codePoint < 0x80) {
            return 1;
        }
        if (codePoint < 0x800) {
            return 2;
        }
        if (codePoint < 0x10000) {
            return 3;
        }
        return 4;
    }

    private RenderJson parseNumber(String pointer) {
        int start = position;
        if (position < body.length && body[position] == '-') {
            position++;
        }
        if (!readDigits(false, pointer)) {
            return null;
        }
        if (position < body.length && body[position] == '.') {
            position++;
            if (!readDigits(true, pointer)) {
                return null;
            }
        }
        if (position < body.length && (body[position] == 'e' || body[position] == 'E')) {
            position++;
            if (position < body.length && (body[position] == '+' || body[position] == '-')) {
                position++;
            }
            if (!readDigits(true, pointer)) {
                return null;
            }
        }
        int length = position - start;
        if (!reserve("numberTokenBytes", length, pointer)) {
            return null;
        }
        var token = new String(body, start, length, StandardCharsets.US_ASCII);
        return new RenderJson.NumberValue(token, start, position);
    }

    private boolean readDigits(boolean fractional, String pointer) {
        int begin = position;
        while (position < body.length && body[position] >= '0' && body[position] <= '9') {
            position++;
        }
        if (position == begin) {
            reject(FailureKind.SYNTAX_INVALID, pointer, null);
            return false;
        }
        if (!fractional && position - begin > 1 && body[begin] == '0') {
            reject(FailureKind.SYNTAX_INVALID, pointer, null);
            return false;
        }
        return true;
    }

    private void skipWhitespace() {
        while (position < body.length) {
            byte current = body[position];
            if (current == ' ' || current == '\t' || current == '\n' || current == '\r') {
                position++;
            } else {
                return;
            }
        }
    }

    private static boolean isValidUtf8(byte[] bytes) {
        int index = 0;
        while (index < bytes.length) {
            int lead = bytes[index] & 0xFF;
            int length;
            if (lead < 0x80) {
                length = 1;
            } else if (lead >= 0xC2 && lead <= 0xDF) {
                length = 2;
            } else if (lead >= 0xE0 && lead <= 0xEF) {
                length = 3;
            } else if (lead >= 0xF0 && lead <= 0xF4) {
                length = 4;
            } else {
                return false;
            }
            if (index + length > bytes.length) {
                return false;
            }
            for (int continuation = 1; continuation < length; continuation++) {
                if ((bytes[index + continuation] & 0xC0) != 0x80) {
                    return false;
                }
            }
            if (length == 3) {
                int second = bytes[index + 1] & 0xFF;
                if (lead == 0xE0 && second < 0xA0) {
                    return false;
                }
                if (lead == 0xED && second > 0x9F) {
                    return false;
                }
            }
            if (length == 4) {
                int second = bytes[index + 1] & 0xFF;
                if (lead == 0xF0 && second < 0x90) {
                    return false;
                }
                if (lead == 0xF4 && second > 0x8F) {
                    return false;
                }
            }
            index += length;
        }
        return true;
    }

    private static String escapePointer(String segment) {
        return segment.replace("~", "~0").replace("/", "~1");
    }
}
