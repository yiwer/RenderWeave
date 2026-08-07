package cn.hbads.renderweave.schema.definition;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecimalAndRegexBoundaryTest {

    private final SchemaDefinitionJsonParser parser = new SchemaDefinitionJsonParser();
    private final SchemaDefinitionJsonWriter writer = new SchemaDefinitionJsonWriter();

    @Test
    void rejectsUnsafeOrNonIntersectionRegexConstructs() {
        for (var pattern : List.of(
                "(a+)+",
                "(?=a)a",
                "(?i)a",
                "(a)\\1",
                "[a-z&&[^q]]",
                "a++",
                "[unterminated"
        )) {
            var error = assertThrows(InvalidSchemaDefinitionException.class,
                    () -> parseTextPattern(pattern));
            assertTrue(error.problems().stream().anyMatch(problem ->
                            problem.code().equals("REGEX_UNSAFE") || problem.code().equals("REGEX_INVALID")),
                    () -> pattern + " -> " + error.problems());
        }
    }

    @Test
    void usesSubstringRegexSemanticsWhenCheckingEnumAndConst() {
        parseValue("text", "{\"pattern\":\"[0-9]+\",\"const\":\"sku-42-blue\"}");

        var error = assertThrows(InvalidSchemaDefinitionException.class,
                () -> parseValue("text", "{\"pattern\":\"^[0-9]+$\",\"const\":\"sku-42-blue\"}"));
        assertProblem(error, "CONSTRAINT_LITERAL_VIOLATION", "/fields/0/value/constraints/const");
    }

    @Test
    void enforcesDecimalTokenPrecisionScaleAndCanonicalRoundTrips() {
        var tooPrecise = "1".repeat(129);
        assertDecimalProblem(tooPrecise, "CONSTRAINT_VALUE_INVALID");
        assertDecimalProblem("0." + "0".repeat(64) + "1", "CONSTRAINT_VALUE_INVALID");

        var zero = parseValue("decimal", "{\"const\":-0.0000}");
        assertTrue(writer.write(zero).contains("\"const\":0"));

        var random = new Random(0x5EEDL);
        for (int index = 0; index < 100; index++) {
            var whole = random.nextInt(1_000_000);
            var fraction = random.nextInt(10_000);
            var token = whole + "." + String.format("%04d", fraction) + "00";
            var first = parseValue("decimal", "{\"const\":" + token + "}");
            var normalized = writer.write(first);
            assertEquals(first, parser.parse(normalized), () -> token + " -> " + normalized);
        }
    }

    private SchemaDefinition parseTextPattern(String pattern) {
        return parseValue("text", "{\"pattern\":" + jsonString(pattern) + "}");
    }

    private void assertDecimalProblem(String token, String code) {
        var error = assertThrows(InvalidSchemaDefinitionException.class,
                () -> parseValue("decimal", "{\"const\":" + token + "}"));
        assertTrue(error.problems().stream().anyMatch(problem -> problem.code().equals(code)), error.problems().toString());
    }

    private SchemaDefinition parseValue(String type, String constraints) {
        return parser.parse("""
                {"dslVersion":"renderweave-schema/1.0","displayName":"边界","fields":[
                  {"fieldKey":"value","required":false,"value":{"type":"%s","constraints":%s}}
                ]}
                """.formatted(type, constraints));
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void assertProblem(InvalidSchemaDefinitionException error, String code, String pointer) {
        assertTrue(error.problems().stream().anyMatch(problem ->
                problem.code().equals(code) && problem.pointer().equals(pointer)), error.problems().toString());
    }
}
