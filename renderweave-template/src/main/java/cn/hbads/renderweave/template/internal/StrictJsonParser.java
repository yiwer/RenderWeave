package cn.hbads.renderweave.template.internal;

import cn.hbads.renderweave.template.api.DesignDslAuthority;
import cn.hbads.renderweave.template.api.DesignInputExpressionCapacityAuthority;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;

final class StrictJsonParser {

    private static final int TECHNICAL_MAX_JSON_DEPTH = 128;
    private static final int TECHNICAL_MAX_TOKEN_LENGTH = 32 * 1024 * 1024;

    private static final JsonFactory JSON = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(TECHNICAL_MAX_JSON_DEPTH)
                    .maxStringLength(TECHNICAL_MAX_TOKEN_LENGTH)
                    .maxNameLength(TECHNICAL_MAX_TOKEN_LENGTH)
                    .maxNumberLength(TECHNICAL_MAX_TOKEN_LENGTH)
                    .build())
            .build();

    private final DesignInputExpressionCapacityAuthority capacity;

    StrictJsonParser() {
        this(CanonicalDesignInputExpressionCapacityAuthority.INSTANCE);
    }

    StrictJsonParser(DesignInputExpressionCapacityAuthority capacity) {
        this.capacity = Objects.requireNonNull(capacity, "capacity");
    }

    JsonValue parse(byte[] rawUtf8) throws DesignDslFailureException {
        if (rawUtf8 == null) {
            throw failure(DesignDslAuthority.FailureCode.DESIGN_JSON_INVALID);
        }
        reserve(DesignDslAuthority.Limit.RAW_UTF8_BYTES, rawUtf8.length);
        verifyUtf8(rawUtf8);
        try (var parser = JSON.createParser(ObjectReadContext.empty(), rawUtf8)) {
            var budget = new ParseBudget();
            var first = parser.nextToken();
            if (first == null) {
                throw failure(DesignDslAuthority.FailureCode.DESIGN_JSON_INVALID);
            }
            var result = read(parser, first, 0, budget);
            if (parser.nextToken() != null) {
                throw failure(DesignDslAuthority.FailureCode.DESIGN_JSON_INVALID);
            }
            return result;
        } catch (JacksonException exception) {
            throw failure(DesignDslAuthority.FailureCode.DESIGN_JSON_INVALID);
        } catch (IOException exception) {
            throw failure(DesignDslAuthority.FailureCode.DESIGN_JSON_INVALID);
        }
    }

    private void verifyUtf8(byte[] rawUtf8) throws DesignDslFailureException {
        if (rawUtf8.length >= 3
                && rawUtf8[0] == (byte) 0xef
                && rawUtf8[1] == (byte) 0xbb
                && rawUtf8[2] == (byte) 0xbf) {
            throw failure(DesignDslAuthority.FailureCode.DESIGN_UTF8_INVALID);
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(rawUtf8));
        } catch (java.nio.charset.CharacterCodingException exception) {
            throw failure(DesignDslAuthority.FailureCode.DESIGN_UTF8_INVALID);
        }
    }

    private JsonValue read(
            JsonParser parser,
            JsonToken token,
            int containerDepth,
            ParseBudget budget
    ) throws IOException,
            DesignDslFailureException {
        budget.reserveValue();
        return switch (token) {
            case START_OBJECT -> readObject(parser, reserveDepth(containerDepth), budget);
            case START_ARRAY -> readArray(parser, reserveDepth(containerDepth), budget);
            case VALUE_STRING -> new JsonValue.StringValue(boundedString(parser.getString()));
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> new JsonValue.NumberValue(
                    boundedNumber(parser.getString())
            );
            case VALUE_TRUE -> new JsonValue.BooleanValue(true);
            case VALUE_FALSE -> new JsonValue.BooleanValue(false);
            case VALUE_NULL -> JsonValue.NullValue.INSTANCE;
            default -> throw failure(DesignDslAuthority.FailureCode.DESIGN_JSON_INVALID);
        };
    }

    private JsonValue.ObjectValue readObject(
            JsonParser parser,
            int containerDepth,
            ParseBudget budget
    ) throws IOException,
            DesignDslFailureException {
        var members = new LinkedHashMap<String, JsonValue>();
        int memberCount = 0;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            var name = parser.currentName();
            memberCount++;
            reserve(DesignDslAuthority.Limit.OBJECT_MEMBERS, memberCount);
            boundedMemberName(name);
            if (members.containsKey(name)) {
                throw failure(DesignDslAuthority.FailureCode.DESIGN_DUPLICATE_MEMBER);
            }
            var valueToken = parser.nextToken();
            members.put(name, read(parser, valueToken, containerDepth, budget));
        }
        return new JsonValue.ObjectValue(members);
    }

    private JsonValue.ArrayValue readArray(
            JsonParser parser,
            int containerDepth,
            ParseBudget budget
    ) throws IOException,
            DesignDslFailureException {
        var items = new ArrayList<JsonValue>();
        JsonToken token;
        while ((token = parser.nextToken()) != JsonToken.END_ARRAY) {
            reserve(DesignDslAuthority.Limit.ARRAY_ITEMS, (long) items.size() + 1);
            items.add(read(parser, token, containerDepth, budget));
        }
        return new JsonValue.ArrayValue(items);
    }

    private int reserveDepth(int currentDepth) throws DesignDslFailureException {
        int nextDepth = currentDepth + 1;
        reserve(DesignDslAuthority.Limit.JSON_DEPTH, nextDepth);
        return nextDepth;
    }

    private String boundedString(String value) throws DesignDslFailureException {
        verifyScalars(value);
        reserve(
                DesignDslAuthority.Limit.STRING_UTF8_BYTES,
                value.getBytes(StandardCharsets.UTF_8).length
        );
        return value;
    }

    private void boundedMemberName(String value) throws DesignDslFailureException {
        verifyScalars(value);
        reserve(
                DesignDslAuthority.Limit.MEMBER_NAME_UTF8_BYTES,
                value.getBytes(StandardCharsets.UTF_8).length
        );
    }

    private String boundedNumber(String token) throws DesignDslFailureException {
        reserve(
                DesignDslAuthority.Limit.NUMBER_TOKEN_BYTES,
                token.getBytes(StandardCharsets.US_ASCII).length
        );
        return token;
    }

    private void reserve(DesignDslAuthority.Limit limit, long observedValue)
            throws DesignDslFailureException {
        var decision = capacity.evaluate(new DesignInputExpressionCapacityAuthority.Observation(
                limit.id(),
                Long.toString(observedValue)
        ));
        if (!(decision instanceof DesignInputExpressionCapacityAuthority.Accepted)) {
            throw limit(limit);
        }
    }

    private void verifyScalars(String value) throws DesignDslFailureException {
        for (int index = 0; index < value.length(); index++) {
            var current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw failure(DesignDslAuthority.FailureCode.DESIGN_UTF8_INVALID);
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                throw failure(DesignDslAuthority.FailureCode.DESIGN_UTF8_INVALID);
            }
        }
    }

    private static DesignDslFailureException failure(DesignDslAuthority.FailureCode code) {
        return new DesignDslFailureException(new DesignDslAuthority.Rejected(
                code,
                DesignDslAuthority.FailureStage.DESIGN_PARSE,
                "",
                Optional.empty()
        ));
    }

    private static DesignDslFailureException limit(DesignDslAuthority.Limit limit) {
        return new DesignDslFailureException(new DesignDslAuthority.Rejected(
                DesignDslAuthority.FailureCode.DESIGN_DSL_LIMIT_EXCEEDED,
                DesignDslAuthority.FailureStage.DESIGN_PARSE,
                "",
                Optional.of(limit)
        ));
    }

    private final class ParseBudget {
        private int totalValuesAndContainers;

        private void reserveValue() throws DesignDslFailureException {
            totalValuesAndContainers++;
            reserve(
                    DesignDslAuthority.Limit.TOTAL_VALUES_AND_CONTAINERS,
                    totalValuesAndContainers
            );
        }
    }
}
