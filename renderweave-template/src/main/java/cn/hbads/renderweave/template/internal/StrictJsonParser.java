package cn.hbads.renderweave.template.internal;

import cn.hbads.renderweave.template.api.DesignDslAuthority;
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
import java.util.Optional;

final class StrictJsonParser {

    private static final int MAX_RAW_UTF8_BYTES = 16 * 1024 * 1024;
    private static final int MAX_JSON_DEPTH = 64;
    private static final int MAX_OBJECT_MEMBERS = 1_024;
    private static final int MAX_ARRAY_ITEMS = 100_000;
    private static final int MAX_TOTAL_VALUES_AND_CONTAINERS = 1_000_000;
    private static final int MAX_STRING_UTF8_BYTES = 1 * 1024 * 1024;
    private static final int MAX_MEMBER_NAME_UTF8_BYTES = 256;
    private static final int MAX_NUMBER_TOKEN_BYTES = 256;

    private static final JsonFactory JSON = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(MAX_JSON_DEPTH * 2)
                    .maxStringLength(MAX_RAW_UTF8_BYTES)
                    .maxNameLength(MAX_RAW_UTF8_BYTES)
                    .maxNumberLength(MAX_RAW_UTF8_BYTES)
                    .build())
            .build();

    JsonValue parse(byte[] rawUtf8) throws DesignDslFailureException {
        if (rawUtf8 == null) {
            throw failure(DesignDslAuthority.FailureCode.DESIGN_JSON_INVALID);
        }
        if (rawUtf8.length > MAX_RAW_UTF8_BYTES) {
            throw limit(DesignDslAuthority.Limit.RAW_UTF8_BYTES);
        }
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
            if (memberCount > MAX_OBJECT_MEMBERS) {
                throw limit(DesignDslAuthority.Limit.OBJECT_MEMBERS);
            }
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
            if (items.size() >= MAX_ARRAY_ITEMS) {
                throw limit(DesignDslAuthority.Limit.ARRAY_ITEMS);
            }
            items.add(read(parser, token, containerDepth, budget));
        }
        return new JsonValue.ArrayValue(items);
    }

    private int reserveDepth(int currentDepth) throws DesignDslFailureException {
        if (currentDepth >= MAX_JSON_DEPTH) {
            throw limit(DesignDslAuthority.Limit.JSON_DEPTH);
        }
        return currentDepth + 1;
    }

    private String boundedString(String value) throws DesignDslFailureException {
        verifyScalars(value);
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_STRING_UTF8_BYTES) {
            throw limit(DesignDslAuthority.Limit.STRING_UTF8_BYTES);
        }
        return value;
    }

    private void boundedMemberName(String value) throws DesignDslFailureException {
        verifyScalars(value);
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_MEMBER_NAME_UTF8_BYTES) {
            throw limit(DesignDslAuthority.Limit.MEMBER_NAME_UTF8_BYTES);
        }
    }

    private String boundedNumber(String token) throws DesignDslFailureException {
        if (token.length() > MAX_NUMBER_TOKEN_BYTES) {
            throw limit(DesignDslAuthority.Limit.NUMBER_TOKEN_BYTES);
        }
        return token;
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

    private static final class ParseBudget {
        private int totalValuesAndContainers;

        private void reserveValue() throws DesignDslFailureException {
            totalValuesAndContainers++;
            if (totalValuesAndContainers > MAX_TOTAL_VALUES_AND_CONTAINERS) {
                throw limit(DesignDslAuthority.Limit.TOTAL_VALUES_AND_CONTAINERS);
            }
        }
    }
}
