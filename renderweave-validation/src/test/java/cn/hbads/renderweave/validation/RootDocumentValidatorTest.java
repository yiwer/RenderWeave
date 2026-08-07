package cn.hbads.renderweave.validation;

import cn.hbads.renderweave.schema.definition.SchemaDefinition;
import cn.hbads.renderweave.schema.definition.SchemaDefinitionJsonParser;
import cn.hbads.renderweave.schema.definition.SchemaField;
import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.schema.definition.TextConstraints;
import cn.hbads.renderweave.schema.definition.TextValue;
import cn.hbads.renderweave.schema.identity.FieldKey;
import cn.hbads.renderweave.schema.identity.SchemaKey;
import cn.hbads.renderweave.schema.identity.VersionTag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RootDocumentValidatorTest {

    private final SchemaDefinitionJsonParser definitions = new SchemaDefinitionJsonParser();
    private final ValidationBatchRequestParser requests = new ValidationBatchRequestParser();
    private final RootDocumentValidator validator = new RootDocumentValidator();

    @Test
    void acceptsAllSevenTypesNestedReferencesAndUnknownFields() {
        var childRef = new StaticSchemaRef(SchemaKey.userProvided("address"), VersionTag.of("v1"));
        var child = schema("""
                {
                  "dslVersion":"renderweave-schema/1.0",
                  "displayName":"Address",
                  "fields":[
                    {"fieldKey":"city","required":true,"value":{"type":"text","constraints":{"minLength":1}}}
                  ]
                }
                """);
        var root = schema("""
                {
                  "dslVersion":"renderweave-schema/1.0",
                  "displayName":"Root",
                  "fields":[
                    {"fieldKey":"name","required":true,"value":{"type":"text","constraints":{"pattern":"Ada"}}},
                    {"fieldKey":"amount","required":true,"value":{"type":"decimal","constraints":{"multipleOf":0.01}}},
                    {"fieldKey":"date","required":true,"value":{"type":"date","constraints":{"min":"2026-01-01"}}},
                    {"fieldKey":"time","required":true,"value":{"type":"time","constraints":{"max":"23:00:00"}}},
                    {"fieldKey":"active","required":true,"value":{"type":"boolean","constraints":{"const":true}}},
                    {"fieldKey":"address","required":true,"value":{"type":"reference","ref":{"schemaKey":"address","versionTag":"v1"}}},
                    {"fieldKey":"scores","required":true,"value":{"type":"array","constraints":{"minItems":1,"uniqueItems":true},"items":{"type":"decimal"}}}
                  ]
                }
                """);
        var target = staticTarget(root, "root", "v2", Map.of(childRef, child));
        var document = document("""
                {
                  "name":"Ada Lovelace",
                  "amount":12.30,
                  "date":"2026-03-21",
                  "time":"16:32:00",
                  "active":true,
                  "address":{"city":"London","unknown":{"nested":[null,1]}},
                  "scores":[1,2.0],
                  "unknownRoot":[{"anything":false}]
                }
                """);

        var result = validator.validate(0, document, target);

        assertTrue(result.valid());
        assertTrue(result.problems().isEmpty());
        assertFalse(result.truncated());
    }

    @Test
    void ordersContainerThenArrayIndexThenNestedFieldProblemsAndShortCircuitsTypes() {
        var childKey = SchemaKey.userProvided("child");
        var child = schema("""
                {
                  "dslVersion":"renderweave-schema/1.0",
                  "displayName":"Child",
                  "fields":[
                    {"fieldKey":"must","required":true,"value":{"type":"text"}}
                  ]
                }
                """);
        var root = schema("""
                {
                  "dslVersion":"renderweave-schema/1.0",
                  "displayName":"Root",
                  "fields":[
                    {"fieldKey":"missing","required":true,"value":{"type":"text"}},
                    {"fieldKey":"number","required":true,"value":{"type":"decimal","constraints":{"min":5}}},
                    {"fieldKey":"values","required":true,"value":{"type":"array","constraints":{"maxItems":2,"uniqueItems":true},"items":{"type":"decimal","constraints":{"min":5}}}},
                    {"fieldKey":"child","required":true,"value":{"type":"reference","ref":{"schemaKey":"child"}}},
                    {"fieldKey":"nullable","required":false,"value":{"type":"boolean"}}
                  ]
                }
                """);
        var rootKey = SchemaKey.userProvided("root");
        var rootIdentity = new ResolvedSchemaIdentity.DraftIdentity(rootKey, 7);
        var childIdentity = new ResolvedSchemaIdentity.DraftIdentity(childKey, 3);
        var drafts = new LinkedHashMap<SchemaKey, ResolvedSchema>();
        drafts.put(rootKey, new ResolvedSchema(rootIdentity, root));
        drafts.put(childKey, new ResolvedSchema(childIdentity, child));
        var target = new ResolvedValidationTarget(rootIdentity, drafts, Map.of());

        var result = validator.validate(0, document("""
                {
                  "number":"4",
                  "values":[3,3,"bad"],
                  "child":{},
                  "nullable":null
                }
                """), target);

        assertEquals(List.of(
                "REQUIRED_FIELD_MISSING",
                "VALUE_TYPE_MISMATCH",
                "ARRAY_MAX_ITEMS_VIOLATED",
                "DECIMAL_MIN_VIOLATED",
                "DECIMAL_MIN_VIOLATED",
                "ARRAY_UNIQUE_ITEMS_VIOLATED",
                "VALUE_TYPE_MISMATCH",
                "REQUIRED_FIELD_MISSING",
                "NULL_VALUE_UNSUPPORTED"
        ), result.problems().stream().map(ValidationProblem::code).toList());
        assertEquals("/values/1", result.problems().get(5).instancePath());
        assertEquals(
                "/schemas/draft/child/3/definition/fields/0/required",
                result.problems().get(7).schemaPath()
        );
        assertFalse(result.truncated());
    }

    @Test
    void enforcesExactDateTimeDecimalAndUnicodeSemantics() {
        var root = schema("""
                {
                  "dslVersion":"renderweave-schema/1.0",
                  "displayName":"Boundaries",
                  "fields":[
                    {"fieldKey":"text","required":true,"value":{"type":"text","constraints":{"minLength":3,"pattern":"xyz"}}},
                    {"fieldKey":"decimal","required":true,"value":{"type":"decimal","constraints":{"max":10}}},
                    {"fieldKey":"date","required":true,"value":{"type":"date"}},
                    {"fieldKey":"time","required":true,"value":{"type":"time"}}
                  ]
                }
                """);
        var result = validator.validate(0, document("""
                {"text":"😀x","decimal":1e65,"date":"2026-02-29","time":"16:32"}
                """), draftTarget(root));

        assertEquals(List.of(
                "TEXT_MIN_LENGTH_VIOLATED",
                "TEXT_PATTERN_VIOLATED",
                "DECIMAL_SCALE_OUT_OF_RANGE",
                "DATE_FORMAT_INVALID",
                "TIME_FORMAT_INVALID"
        ), result.problems().stream().map(ValidationProblem::code).toList());
    }

    @Test
    void decimalTypedEqualityDrivesEnumConstAndUniqueItems() {
        var root = schema("""
                {
                  "dslVersion":"renderweave-schema/1.0",
                  "displayName":"Decimals",
                  "fields":[
                    {"fieldKey":"choice","required":true,"value":{"type":"decimal","constraints":{"enum":[1.0,2]}}},
                    {"fieldKey":"items","required":true,"value":{"type":"array","constraints":{"uniqueItems":true},"items":{"type":"decimal"}}}
                  ]
                }
                """);
        var result = validator.validate(
                0,
                document("{\"choice\":1,\"items\":[1,1.00,2e0]}"),
                draftTarget(root)
        );

        assertEquals(List.of("ARRAY_UNIQUE_ITEMS_VIOLATED"),
                result.problems().stream().map(ValidationProblem::code).toList());
        assertEquals(Map.of("firstIndex", 0, "duplicateIndex", 1), result.problems().getFirst().messageArgs());
    }

    @Test
    void rejectsNonObjectRootAndCapsDepthFirstProblemsAtOneHundred() {
        var root = new SchemaDefinition(
                SchemaDefinition.DSL_VERSION,
                "Many required fields",
                Optional.empty(),
                requiredTextFields(150)
        );
        var scalar = validator.validate(0, document("[]"), draftTarget(root));
        assertEquals("ROOT_TYPE_UNSUPPORTED", scalar.problems().getFirst().code());

        var capped = validator.validate(1, document("{}"), draftTarget(root));
        assertEquals(RootDocumentValidator.MAX_PROBLEMS, capped.problems().size());
        assertTrue(capped.truncated());
        assertEquals("/field-099", capped.problems().getLast().instancePath());
    }

    @Test
    void interpretsEveryConstraintWithStableDeclaredPrecedence() {
        var root = schema("""
                {
                  "dslVersion":"renderweave-schema/1.0",
                  "displayName":"Constraint matrix",
                  "fields":[
                    {"fieldKey":"t-min","required":true,"value":{"type":"text","constraints":{"minLength":2}}},
                    {"fieldKey":"t-combo","required":true,"value":{"type":"text","constraints":{"maxLength":1,"pattern":"z","enum":["z"]}}},
                    {"fieldKey":"t-const","required":true,"value":{"type":"text","constraints":{"const":"yes"}}},
                    {"fieldKey":"d-combo","required":true,"value":{"type":"decimal","constraints":{"min":5,"multipleOf":2,"enum":[6]}}},
                    {"fieldKey":"d-emin","required":true,"value":{"type":"decimal","constraints":{"exclusiveMin":5}}},
                    {"fieldKey":"d-max","required":true,"value":{"type":"decimal","constraints":{"max":5}}},
                    {"fieldKey":"d-emax","required":true,"value":{"type":"decimal","constraints":{"exclusiveMax":5}}},
                    {"fieldKey":"d-const","required":true,"value":{"type":"decimal","constraints":{"const":5}}},
                    {"fieldKey":"date-combo","required":true,"value":{"type":"date","constraints":{"min":"2026-02-01","enum":["2026-03-01"]}}},
                    {"fieldKey":"date-emin","required":true,"value":{"type":"date","constraints":{"exclusiveMin":"2026-01-01"}}},
                    {"fieldKey":"date-max","required":true,"value":{"type":"date","constraints":{"max":"2026-01-01"}}},
                    {"fieldKey":"date-emax","required":true,"value":{"type":"date","constraints":{"exclusiveMax":"2026-01-01"}}},
                    {"fieldKey":"date-const","required":true,"value":{"type":"date","constraints":{"const":"2026-01-01"}}},
                    {"fieldKey":"time-combo","required":true,"value":{"type":"time","constraints":{"min":"12:00:00","enum":["13:00:00"]}}},
                    {"fieldKey":"time-emin","required":true,"value":{"type":"time","constraints":{"exclusiveMin":"12:00:00"}}},
                    {"fieldKey":"time-max","required":true,"value":{"type":"time","constraints":{"max":"12:00:00"}}},
                    {"fieldKey":"time-emax","required":true,"value":{"type":"time","constraints":{"exclusiveMax":"12:00:00"}}},
                    {"fieldKey":"time-const","required":true,"value":{"type":"time","constraints":{"const":"12:00:00"}}},
                    {"fieldKey":"bool","required":true,"value":{"type":"boolean","constraints":{"const":true}}},
                    {"fieldKey":"a-min","required":true,"value":{"type":"array","constraints":{"minItems":1},"items":{"type":"boolean"}}},
                    {"fieldKey":"a-max","required":true,"value":{"type":"array","constraints":{"maxItems":1},"items":{"type":"boolean"}}},
                    {"fieldKey":"a-unique","required":true,"value":{"type":"array","constraints":{"uniqueItems":true},"items":{"type":"text"}}}
                  ]
                }
                """);
        var result = validator.validate(0, document("""
                {
                  "t-min":"x","t-combo":"xx","t-const":"no",
                  "d-combo":3,"d-emin":5,"d-max":6,"d-emax":5,"d-const":6,
                  "date-combo":"2026-01-01","date-emin":"2026-01-01","date-max":"2026-01-02",
                  "date-emax":"2026-01-01","date-const":"2026-01-02",
                  "time-combo":"11:00:00","time-emin":"12:00:00","time-max":"12:00:01",
                  "time-emax":"12:00:00","time-const":"12:00:01",
                  "bool":false,"a-min":[],"a-max":[true,false],"a-unique":["x","x"]
                }
                """), draftTarget(root));

        assertEquals(List.of(
                "TEXT_MIN_LENGTH_VIOLATED",
                "TEXT_MAX_LENGTH_VIOLATED", "TEXT_PATTERN_VIOLATED", "TEXT_ENUM_VIOLATED",
                "TEXT_CONST_VIOLATED",
                "DECIMAL_MIN_VIOLATED", "DECIMAL_MULTIPLE_OF_VIOLATED", "DECIMAL_ENUM_VIOLATED",
                "DECIMAL_EXCLUSIVE_MIN_VIOLATED", "DECIMAL_MAX_VIOLATED",
                "DECIMAL_EXCLUSIVE_MAX_VIOLATED", "DECIMAL_CONST_VIOLATED",
                "DATE_MIN_VIOLATED", "DATE_ENUM_VIOLATED", "DATE_EXCLUSIVE_MIN_VIOLATED",
                "DATE_MAX_VIOLATED", "DATE_EXCLUSIVE_MAX_VIOLATED", "DATE_CONST_VIOLATED",
                "TIME_MIN_VIOLATED", "TIME_ENUM_VIOLATED", "TIME_EXCLUSIVE_MIN_VIOLATED",
                "TIME_MAX_VIOLATED", "TIME_EXCLUSIVE_MAX_VIOLATED", "TIME_CONST_VIOLATED",
                "BOOLEAN_CONST_VIOLATED",
                "ARRAY_MIN_ITEMS_VIOLATED", "ARRAY_MAX_ITEMS_VIOLATED", "ARRAY_UNIQUE_ITEMS_VIOLATED"
        ), result.problems().stream().map(ValidationProblem::code).toList());
    }

    @Test
    void enforcesRuntimeScalarBudgetsAndEscapesInstancePointers() {
        var root = schema("""
                {
                  "dslVersion":"renderweave-schema/1.0",
                  "displayName":"Runtime budgets",
                  "fields":[
                    {"fieldKey":"token","required":true,"value":{"type":"decimal"}},
                    {"fieldKey":"precision","required":true,"value":{"type":"decimal"}},
                    {"fieldKey":"a/b~c","required":true,"value":{"type":"text"}}
                  ]
                }
                """);
        var result = validator.validate(0, document(
                "{\"token\":" + "1".repeat(257)
                        + ",\"precision\":" + "9".repeat(129)
                        + ",\"a/b~c\":\"" + "x".repeat(RootDocumentValidator.MAX_TEXT_CODE_POINTS + 1)
                        + "\"}"
        ), draftTarget(root));

        assertEquals(List.of(
                "DECIMAL_TOKEN_TOO_LONG",
                "DECIMAL_PRECISION_EXCEEDED",
                "TEXT_CODE_POINT_LIMIT_EXCEEDED"
        ), result.problems().stream().map(ValidationProblem::code).toList());
        assertEquals("/a~1b~0c", result.problems().getLast().instancePath());
    }

    private ResolvedValidationTarget draftTarget(SchemaDefinition root) {
        var key = SchemaKey.userProvided("root");
        var identity = new ResolvedSchemaIdentity.DraftIdentity(key, 0);
        return new ResolvedValidationTarget(
                identity,
                Map.of(key, new ResolvedSchema(identity, root)),
                Map.of()
        );
    }

    private ResolvedValidationTarget staticTarget(
            SchemaDefinition root,
            String schemaKey,
            String versionTag,
            Map<StaticSchemaRef, SchemaDefinition> children
    ) {
        var reference = new StaticSchemaRef(SchemaKey.userProvided(schemaKey), VersionTag.of(versionTag));
        var identity = new ResolvedSchemaIdentity.StaticIdentity(reference);
        var statics = new LinkedHashMap<StaticSchemaRef, ResolvedSchema>();
        statics.put(reference, new ResolvedSchema(identity, root));
        children.forEach((childReference, definition) -> statics.put(
                childReference,
                new ResolvedSchema(new ResolvedSchemaIdentity.StaticIdentity(childReference), definition)
        ));
        return new ResolvedValidationTarget(identity, Map.of(), statics);
    }

    private SchemaDefinition schema(String json) {
        return definitions.parse(json);
    }

    private StrictJsonValue document(String json) {
        var request = ("{\"target\":{\"kind\":\"draft\",\"schemaKey\":\"root\"},"
                + "\"documents\":[{\"document\":" + json + "}]}")
                .getBytes(StandardCharsets.UTF_8);
        return requests.parse(request).documents().getFirst();
    }

    private static List<SchemaField> requiredTextFields(int count) {
        var fields = new ArrayList<SchemaField>();
        for (int index = 0; index < count; index++) {
            fields.add(new SchemaField(
                    FieldKey.of("field-%03d".formatted(index)),
                    Optional.empty(),
                    Optional.empty(),
                    true,
                    new TextValue(TextConstraints.none())
            ));
        }
        return fields;
    }
}
