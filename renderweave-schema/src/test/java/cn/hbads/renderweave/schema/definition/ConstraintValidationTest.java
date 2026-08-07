package cn.hbads.renderweave.schema.definition;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstraintValidationTest {

    private final SchemaDefinitionJsonParser parser = new SchemaDefinitionJsonParser();

    @Test
    void rejectsConstraintConflictsInvalidRangesAndInvalidLiterals() {
        var cases = List.of(
                invalid("text", "{\"enum\":[\"a\"],\"const\":\"a\"}", "CONSTRAINT_CONFLICT", "/constraints"),
                invalid("text", "{\"enum\":[]}", "CONSTRAINT_ENUM_INVALID", "/constraints/enum"),
                invalid("text", "{\"enum\":[\"same\",\"same\"]}", "CONSTRAINT_ENUM_DUPLICATE", "/constraints/enum/1"),
                invalid("text", "{\"minLength\":3,\"const\":\"ab\"}", "CONSTRAINT_LITERAL_VIOLATION", "/constraints/const"),
                invalid("decimal", "{\"min\":1,\"exclusiveMin\":2}", "CONSTRAINT_CONFLICT", "/constraints"),
                invalid("decimal", "{\"min\":2,\"exclusiveMax\":2}", "CONSTRAINT_RANGE_INVALID", "/constraints"),
                invalid("decimal", "{\"multipleOf\":0}", "CONSTRAINT_VALUE_INVALID", "/constraints/multipleOf"),
                invalid("decimal", "{\"enum\":[1,1.0]}", "CONSTRAINT_ENUM_DUPLICATE", "/constraints/enum/1"),
                invalid("decimal", "{\"min\":1,\"const\":0.9}", "CONSTRAINT_LITERAL_VIOLATION", "/constraints/const"),
                invalid("date", "{\"const\":\"2026-02-29\"}", "CONSTRAINT_VALUE_INVALID", "/constraints/const"),
                invalid("date", "{\"min\":\"2026-03-21\",\"max\":\"2026-03-20\"}", "CONSTRAINT_RANGE_INVALID", "/constraints"),
                invalid("time", "{\"const\":\"16:32\"}", "CONSTRAINT_VALUE_INVALID", "/constraints/const"),
                invalid("time", "{\"exclusiveMin\":\"16:32:00\",\"max\":\"16:32:00\"}", "CONSTRAINT_RANGE_INVALID", "/constraints"),
                invalid("boolean", "{\"enum\":[true]}", "DSL_UNKNOWN_MEMBER", "/constraints/enum"),
                invalid("array", "{\"minItems\":10001}", "CONSTRAINT_VALUE_INVALID", "/constraints/minItems"),
                invalid("array", "{\"minItems\":4,\"maxItems\":3}", "CONSTRAINT_RANGE_INVALID", "/constraints")
        );

        for (var invalidCase : cases) {
            var error = assertThrows(InvalidSchemaDefinitionException.class,
                    () -> parse(invalidCase.type(), invalidCase.constraints()));
            assertTrue(error.problems().stream().anyMatch(problem ->
                            problem.code().equals(invalidCase.code())
                                    && problem.pointer().equals("/fields/0/value" + invalidCase.pointer())),
                    () -> invalidCase + " -> " + error.problems());
        }
    }

    @Test
    void rejectsWrongLiteralJsonTypesAndClosedValueShapes() {
        var cases = List.of(
                new JsonCase("{\"type\":\"decimal\",\"constraints\":{\"const\":\"1.2\"}}", "/constraints/const"),
                new JsonCase("{\"type\":\"date\",\"constraints\":{\"const\":20260321}}", "/constraints/const"),
                new JsonCase("{\"type\":\"boolean\",\"constraints\":{\"const\":null}}", "/constraints/const"),
                new JsonCase("{\"type\":\"reference\",\"constraints\":{\"const\":1},\"ref\":{\"schemaKey\":\"child\"}}", "/constraints"),
                new JsonCase("{\"type\":\"reference\",\"ref\":{\"schemaKey\":\"child\",\"latest\":true}}", "/ref/latest"),
                new JsonCase("{\"type\":\"array\",\"items\":null}", "/items")
        );

        for (var invalidCase : cases) {
            var error = assertThrows(InvalidSchemaDefinitionException.class,
                    () -> parseValue(invalidCase.value()));
            assertTrue(error.problems().stream().anyMatch(problem ->
                            problem.pointer().equals("/fields/0/value" + invalidCase.pointer())),
                    () -> invalidCase + " -> " + error.problems());
        }
    }

    private void parse(String type, String constraints) {
        parseValue("{\"type\":\"" + type + "\",\"constraints\":" + constraints
                + (type.equals("array") ? ",\"items\":{\"type\":\"text\"}" : "") + "}");
    }

    private void parseValue(String value) {
        parser.parse("""
                {"dslVersion":"renderweave-schema/1.0","displayName":"约束","fields":[
                  {"fieldKey":"value","required":false,"value":%s}
                ]}
                """.formatted(value));
    }

    private static InvalidCase invalid(String type, String constraints, String code, String pointer) {
        return new InvalidCase(type, constraints, code, pointer);
    }

    private record InvalidCase(String type, String constraints, String code, String pointer) {
    }

    private record JsonCase(String value, String pointer) {
    }
}
