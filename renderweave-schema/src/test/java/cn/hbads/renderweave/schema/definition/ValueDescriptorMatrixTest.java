package cn.hbads.renderweave.schema.definition;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValueDescriptorMatrixTest {

    private final SchemaDefinitionJsonParser parser = new SchemaDefinitionJsonParser();
    private final SchemaDefinitionJsonWriter writer = new SchemaDefinitionJsonWriter();

    @Test
    void parsesAndRoundTripsEveryV1ValueDescriptor() {
        var definition = parser.parse("""
                {"dslVersion":"renderweave-schema/1.0","displayName":"完整类型","fields":[
                  {"fieldKey":"title","required":true,"value":{"type":"text","constraints":{
                    "minLength":1,"maxLength":80,"pattern":"^[A-Z].+","enum":["Alpha","Beta" ]}}},
                  {"fieldKey":"price","required":false,"value":{"type":"decimal","constraints":{
                    "min":1.2300,"exclusiveMax":1e3,"multipleOf":0.0100,"enum":[1.23,2.50]}}},
                  {"fieldKey":"day","required":false,"value":{"type":"date","constraints":{
                    "min":"2026-01-01","max":"2026-12-31","const":"2026-03-21"}}},
                  {"fieldKey":"clock","required":false,"value":{"type":"time","constraints":{
                    "exclusiveMin":"08:00:00","max":"23:59:59","const":"16:32:00"}}},
                  {"fieldKey":"enabled","required":false,"value":{"type":"boolean","constraints":{"const":true}}},
                  {"fieldKey":"owner","required":false,"value":{"type":"reference","ref":{"schemaKey":"customer"}}},
                  {"fieldKey":"preset","required":false,"value":{"type":"reference","ref":{
                    "schemaKey":"system-text","versionTag":"v1"}}},
                  {"fieldKey":"tags","required":false,"value":{"type":"array","constraints":{
                    "minItems":1,"maxItems":20,"uniqueItems":true},"items":{"type":"text","constraints":{"maxLength":24}}}}
                ]}
                """);

        assertEquals(8, definition.fields().size());
        assertInstanceOf(TextValue.class, definition.fields().get(0).value());

        var decimal = assertInstanceOf(DecimalValue.class, definition.fields().get(1).value());
        assertEquals(new BigDecimal("1.23"), decimal.constraints().min().orElseThrow());
        assertEquals(new BigDecimal("1E+3"), decimal.constraints().exclusiveMax().orElseThrow());
        assertEquals(new BigDecimal("0.01"), decimal.constraints().multipleOf().orElseThrow());

        var date = assertInstanceOf(DateValue.class, definition.fields().get(2).value());
        assertEquals(LocalDate.of(2026, 3, 21), date.constraints().constValue().orElseThrow());
        var time = assertInstanceOf(TimeValue.class, definition.fields().get(3).value());
        assertEquals(LocalTime.of(16, 32), time.constraints().constValue().orElseThrow());
        assertTrue(assertInstanceOf(BooleanValue.class, definition.fields().get(4).value())
                .constraints().constValue().orElseThrow());
        assertInstanceOf(SchemaRef.class,
                assertInstanceOf(ReferenceValue.class, definition.fields().get(5).value()).ref());
        assertInstanceOf(StaticSchemaRef.class,
                assertInstanceOf(ReferenceValue.class, definition.fields().get(6).value()).ref());
        assertInstanceOf(TextValue.class,
                assertInstanceOf(ArrayValue.class, definition.fields().get(7).value()).items());

        var normalized = writer.write(definition);
        assertTrue(normalized.contains("\"min\":1.23"), normalized);
        assertTrue(normalized.contains("\"exclusiveMax\":1000"), normalized);
        assertTrue(normalized.contains("\"multipleOf\":0.01"), normalized);
        assertEquals(definition, parser.parse(normalized));
    }

    @Test
    void acceptsScalarArrayItemsAndRejectsNestedOrObjectUniqueness() {
        for (var item : new String[]{
                "{\"type\":\"text\"}",
                "{\"type\":\"decimal\"}",
                "{\"type\":\"date\"}",
                "{\"type\":\"time\"}",
                "{\"type\":\"boolean\"}",
                "{\"type\":\"reference\",\"ref\":{\"schemaKey\":\"child\"}}"
        }) {
            var value = parseOnlyValue("{\"type\":\"array\",\"items\":" + item + "}");
            assertInstanceOf(ArrayValue.class, value);
        }

        assertProblem(
                "{\"type\":\"array\",\"items\":{\"type\":\"array\",\"items\":{\"type\":\"text\"}}}",
                "ARRAY_NESTED_UNSUPPORTED",
                "/fields/0/value/items/type"
        );
        assertProblem(
                "{\"type\":\"array\",\"constraints\":{\"uniqueItems\":true},\"items\":{\"type\":\"reference\",\"ref\":{\"schemaKey\":\"child\"}}}",
                "ARRAY_UNIQUE_ITEMS_UNSUPPORTED",
                "/fields/0/value/constraints/uniqueItems"
        );
    }

    private ValueDescriptor parseOnlyValue(String value) {
        return parser.parse("""
                {"dslVersion":"renderweave-schema/1.0","displayName":"测试","fields":[
                  {"fieldKey":"value","required":false,"value":%s}
                ]}
                """.formatted(value)).fields().getFirst().value();
    }

    private void assertProblem(String value, String code, String pointer) {
        try {
            parseOnlyValue(value);
        } catch (InvalidSchemaDefinitionException error) {
            assertTrue(error.problems().stream().anyMatch(problem ->
                    problem.code().equals(code) && problem.pointer().equals(pointer)), error.problems().toString());
            return;
        }
        throw new AssertionError("Expected " + code);
    }
}
