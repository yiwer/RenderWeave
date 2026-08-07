package cn.hbads.renderweave.schema.definition;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaDefinitionJsonParserTest {

    private final SchemaDefinitionJsonParser parser = new SchemaDefinitionJsonParser();
    private final SchemaDefinitionJsonWriter writer = new SchemaDefinitionJsonWriter();

    @Test
    void parsesTheSmallestUsefulTextSchemaAndNormalizesMetadata() {
        var definition = parser.parse("""
                {
                  "dslVersion": "renderweave-schema/1.0",
                  "displayName": "  商品卡片  ",
                  "description": "   ",
                  "fields": [
                    {
                      "fieldKey": "商品/名称~原文",
                      "displayName": "  商品名称  ",
                      "required": true,
                      "value": {
                        "type": "text",
                        "constraints": {"minLength": 1, "maxLength": 80}
                      }
                    }
                  ]
                }
                """);

        assertEquals(SchemaDefinition.DSL_VERSION, definition.dslVersion());
        assertEquals("商品卡片", definition.displayName());
        assertTrue(definition.description().isEmpty());
        assertEquals(1, definition.fields().size());

        var field = definition.fields().getFirst();
        assertEquals("商品/名称~原文", field.fieldKey().value());
        assertEquals("商品~1名称~0原文", field.fieldKey().jsonPointerSegment());
        assertEquals("商品名称", field.displayName().orElseThrow());
        assertTrue(field.required());

        var value = assertInstanceOf(TextValue.class, field.value());
        assertEquals(1, value.constraints().minLength().orElseThrow());
        assertEquals(80, value.constraints().maxLength().orElseThrow());
        assertFalse(value.constraints().pattern().isPresent());
    }

    @Test
    void rejectsUnknownMembersAtEveryDslLevelIncludingFieldId() {
        var cases = List.of(
                new InvalidCase("""
                        {"dslVersion":"renderweave-schema/1.0","displayName":"商品","fields":[],"identity":{}}
                        """, "/identity"),
                new InvalidCase("""
                        {"dslVersion":"renderweave-schema/1.0","displayName":"商品","fields":[
                          {"fieldKey":"title","fieldId":"must-not-exist","required":false,"value":{"type":"text"}}
                        ]}
                        """, "/fields/0/fieldId"),
                new InvalidCase("""
                        {"dslVersion":"renderweave-schema/1.0","displayName":"商品","fields":[
                          {"fieldKey":"title","required":false,"value":{"type":"text","mystery":true}}
                        ]}
                        """, "/fields/0/value/mystery"),
                new InvalidCase("""
                        {"dslVersion":"renderweave-schema/1.0","displayName":"商品","fields":[
                          {"fieldKey":"title","required":false,"value":{"type":"text","constraints":{"minimum":1}}}
                        ]}
                        """, "/fields/0/value/constraints/minimum")
        );

        for (var invalidCase : cases) {
            var error = assertThrows(InvalidSchemaDefinitionException.class,
                    () -> parser.parse(invalidCase.json()));
            assertTrue(error.problems().stream().anyMatch(problem ->
                            problem.code().equals("DSL_UNKNOWN_MEMBER")
                                    && problem.pointer().equals(invalidCase.pointer())),
                    () -> "Expected unknown-member problem at " + invalidCase.pointer() + ", got " + error.problems());
        }
    }

    @Test
    void rejectsDuplicateJsonMembersBeforeTheyCanBeOverwritten() {
        var error = assertThrows(InvalidSchemaDefinitionException.class, () -> parser.parse("""
                {
                  "dslVersion":"renderweave-schema/1.0",
                  "displayName":"商品",
                  "displayName":"被覆盖",
                  "fields":[]
                }
                """));

        assertEquals("DSL_DUPLICATE_MEMBER", error.problems().getFirst().code());
    }

    @Test
    void rejectsDuplicateAndInvalidFieldKeys() {
        var duplicate = assertThrows(InvalidSchemaDefinitionException.class, () -> parser.parse("""
                {"dslVersion":"renderweave-schema/1.0","displayName":"商品","fields":[
                  {"fieldKey":"title","required":false,"value":{"type":"text"}},
                  {"fieldKey":"title","required":true,"value":{"type":"text"}}
                ]}
                """));
        assertProblem(duplicate, "FIELD_KEY_DUPLICATE", "/fields/1/fieldKey");

        var invalid = assertThrows(InvalidSchemaDefinitionException.class, () -> parser.parse("""
                {"dslVersion":"renderweave-schema/1.0","displayName":"商品","fields":[
                  {"fieldKey":"line\\nfeed","required":false,"value":{"type":"text"}}
                ]}
                """));
        assertProblem(invalid, "FIELD_KEY_INVALID", "/fields/0/fieldKey");
    }

    @Test
    void rejectsUnsupportedVersionMissingRequiredMembersAndEmptyConstraints() {
        var version = assertThrows(InvalidSchemaDefinitionException.class, () -> parser.parse("""
                {"dslVersion":"renderweave-schema/2.0","displayName":"商品","fields":[]}
                """));
        assertProblem(version, "DSL_VERSION_UNSUPPORTED", "/dslVersion");

        var missing = assertThrows(InvalidSchemaDefinitionException.class, () -> parser.parse("""
                {"dslVersion":"renderweave-schema/1.0","displayName":"商品","fields":[
                  {"fieldKey":"title","value":{"type":"text"}}
                ]}
                """));
        assertProblem(missing, "DSL_REQUIRED_MEMBER_MISSING", "/fields/0/required");

        var emptyConstraints = assertThrows(InvalidSchemaDefinitionException.class, () -> parser.parse("""
                {"dslVersion":"renderweave-schema/1.0","displayName":"商品","fields":[
                  {"fieldKey":"title","required":false,"value":{"type":"text","constraints":{}}}
                ]}
                """));
        assertProblem(emptyConstraints, "DSL_EMPTY_CONSTRAINTS", "/fields/0/value/constraints");
    }

    @Test
    void writesTheNormalizedDefinitionWithoutAbsentOrEmptyMembers() {
        var definition = parser.parse("""
                {"dslVersion":"renderweave-schema/1.0","displayName":"  商品  ","description":"  ","fields":[
                  {"fieldKey":"title","displayName":" 标题 ","description":"  ","required":false,
                   "value":{"type":"text"}}
                ]}
                """);

        assertEquals(
                """
                {"dslVersion":"renderweave-schema/1.0","displayName":"商品","fields":[{"fieldKey":"title","displayName":"标题","required":false,"value":{"type":"text"}}]}""",
                writer.write(definition)
        );
    }

    private static void assertProblem(
            InvalidSchemaDefinitionException error,
            String code,
            String pointer
    ) {
        assertTrue(error.problems().stream().anyMatch(problem ->
                        problem.code().equals(code) && problem.pointer().equals(pointer)),
                () -> "Expected " + code + " at " + pointer + ", got " + error.problems());
    }

    private record InvalidCase(String json, String pointer) {
    }
}
