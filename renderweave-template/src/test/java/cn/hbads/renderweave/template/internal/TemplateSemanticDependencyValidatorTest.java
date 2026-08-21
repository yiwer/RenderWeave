package cn.hbads.renderweave.template.internal;

import cn.hbads.renderweave.schema.api.StaticSchemaAuthority;
import cn.hbads.renderweave.schema.definition.ArrayConstraints;
import cn.hbads.renderweave.schema.definition.ArrayValue;
import cn.hbads.renderweave.schema.definition.BooleanConstraints;
import cn.hbads.renderweave.schema.definition.BooleanValue;
import cn.hbads.renderweave.schema.definition.DecimalConstraints;
import cn.hbads.renderweave.schema.definition.DecimalValue;
import cn.hbads.renderweave.schema.definition.ReferenceValue;
import cn.hbads.renderweave.schema.definition.SchemaDefinition;
import cn.hbads.renderweave.schema.definition.SchemaField;
import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.schema.definition.TextConstraints;
import cn.hbads.renderweave.schema.definition.TextValue;
import cn.hbads.renderweave.schema.identity.FieldKey;
import cn.hbads.renderweave.schema.identity.SchemaKey;
import cn.hbads.renderweave.schema.identity.VersionTag;
import cn.hbads.renderweave.template.api.DesignDslAuthority;
import cn.hbads.renderweave.template.api.TemplateApplication;
import cn.hbads.renderweave.template.spi.DependencyResolution;
import cn.hbads.renderweave.template.spi.OwnerScopeAuthority;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateSemanticDependencyValidatorTest {
    private static final StaticSchemaRef ROOT_SCHEMA = schemaRef("root");
    private static final StaticSchemaRef CARD_SCHEMA = schemaRef("card");
    private static final StaticSchemaRef OTHER_SCHEMA = schemaRef("other");
    private static final StaticSchemaRef BASIC_DECIMAL = new StaticSchemaRef(
            SchemaKey.systemProvided("system-basic-decimal"), VersionTag.of("v1"));
    private static final StaticSchemaRef EMPTY_SCHEMA = new StaticSchemaRef(
            SchemaKey.systemProvided("system-empty"), VersionTag.of("v1"));
    private static final OwnerScopeAuthority.OwnerScope OWNER =
            new OwnerScopeAuthority.OwnerScope("owner-a");
    private static final String CHILD_ID = "00000000-0000-4000-8000-0000000000b1";
    private static final String OTHER_CHILD_ID = "00000000-0000-4000-8000-0000000000b2";
    private static final String LOOP_ID = "00000000-0000-4000-8000-0000000000aa";
    private static final String PUBLIC_TEXT = "00000000-0000-4000-8000-0000000000c1";
    private static final String PRIVATE_DECIMAL = "00000000-0000-4000-8000-0000000000c2";
    private static final String MISSING_TARGET = "00000000-0000-4000-8000-0000000000c3";
    private static final String PARENT_DECIMAL = "00000000-0000-4000-8000-0000000000d1";

    private final DesignDslAuthority designs = new CanonicalDesignDslAuthority();
    private final Schemas schemas = new Schemas(Map.of(
            ROOT_SCHEMA, schema(
                    field("title", true, new TextValue(TextConstraints.none())),
                    field("enabled", false, new BooleanValue(BooleanConstraints.none())),
                    field("card", false, new ReferenceValue(CARD_SCHEMA)),
                    field("cards", true, new ArrayValue(
                            ArrayConstraints.none(), new ReferenceValue(CARD_SCHEMA))),
                    field("numbers", true, new ArrayValue(
                            ArrayConstraints.none(), new DecimalValue(DecimalConstraints.none())))
            ),
            CARD_SCHEMA, schema(field("name", true, new TextValue(TextConstraints.none()))),
            OTHER_SCHEMA, schema(field("other", true, new TextValue(TextConstraints.none()))),
            BASIC_DECIMAL, schema(
                    field("index", true, new DecimalValue(DecimalConstraints.none())),
                    field("value", true, new DecimalValue(DecimalConstraints.none()))),
            EMPTY_SCHEMA, schema()
    ));

    @Test
    void missingAndConsumerIncompatibleSchemaPathsAreDependencyProblems() {
        var canonical = canonical("[]", """
                [{"nodeId":"00000000-0000-4000-8000-000000000011","kind":"conditional",
                  "bindings":[],"condition":{"kind":"context","domain":"invocation","pointer":"/missing"},
                  "absentPolicy":"FALSE","placement":{"type":"ABSOLUTE","xMm":0,"yMm":0,
                  "widthMode":"HUG_CONTENT","heightMode":"HUG_CONTENT"},"children":[
                    {"nodeId":"00000000-0000-4000-8000-000000000012","kind":"frame","bindings":[],
                     "placement":{"type":"ABSOLUTE","xMm":0,"yMm":0,"widthMode":"HUG_CONTENT",
                     "heightMode":"HUG_CONTENT"},"children":[]}]},
                 {"nodeId":"00000000-0000-4000-8000-000000000013","kind":"conditional",
                  "bindings":[],"condition":{"kind":"context","domain":"invocation","pointer":"/title"},
                  "absentPolicy":"ERROR","placement":{"type":"ABSOLUTE","xMm":0,"yMm":0,
                  "widthMode":"HUG_CONTENT","heightMode":"HUG_CONTENT"},"children":[
                    {"nodeId":"00000000-0000-4000-8000-000000000014","kind":"frame","bindings":[],
                     "placement":{"type":"ABSOLUTE","xMm":0,"yMm":0,"widthMode":"HUG_CONTENT",
                     "heightMode":"HUG_CONTENT"},"children":[]}]}]
                """);

        var result = validator().validate(canonical, ROOT_SCHEMA, Map.of());

        assertFalse(result.hard());
        assertEquals(
                List.of(
                        "TEMPLATE_STATIC_SCHEMA_PATH_NOT_FOUND",
                        "TEMPLATE_CONDITION_TYPE_MISMATCH"
                ),
                result.problems().stream()
                        .map(TemplateApplication.ValidationProblem::code)
                        .toList()
        );
        assertTrue(result.problems().stream().allMatch(problem ->
                problem.category() == TemplateApplication.ProblemCategory.DEPENDENCY));
    }

    @Test
    void repeatBuildsExactReferenceItemContextAndRejectsOutOfScopeLoopUse() {
        var child = child(CHILD_ID, CARD_SCHEMA, "[]");
        var canonical = canonical("[]", """
                [{"nodeId":"00000000-0000-4000-8000-000000000021","kind":"repeat",
                  "loopId":"%s","bindings":[],
                  "items":{"kind":"context","domain":"invocation","pointer":"/cards"},
                  "absentPolicy":"EMPTY","itemLayout":{"kind":"STACK","direction":"ROW"},
                  "instanceLayout":{"kind":"STACK","direction":"ROW"},
                  "placement":{"type":"ABSOLUTE","xMm":0,"yMm":0,
                  "widthMode":"HUG_CONTENT","heightMode":"HUG_CONTENT"},"children":[
                    %s]},
                 %s]
                """.formatted(
                LOOP_ID,
                templateUse("00000000-0000-4000-8000-000000000022",
                        "00000000-0000-4000-8000-0000000000a1", CHILD_ID,
                        "{\"kind\":\"context\",\"domain\":{\"kind\":\"loop\",\"loopId\":\""
                                + LOOP_ID + "\"},\"pointer\":\"\",\"contextAbsentPolicy\":\"ERROR\"}",
                        "[]", "PACK"),
                templateUse("00000000-0000-4000-8000-000000000023",
                        "00000000-0000-4000-8000-0000000000a2", CHILD_ID,
                        "{\"kind\":\"context\",\"domain\":{\"kind\":\"loop\",\"loopId\":\""
                                + LOOP_ID + "\"},\"pointer\":\"\",\"contextAbsentPolicy\":\"ERROR\"}",
                        "[]", "ABSOLUTE")));

        var result = validator().validate(canonical, ROOT_SCHEMA, Map.of(CHILD_ID, child));

        assertTrue(result.hard());
        assertEquals(List.of("TEMPLATE_LEXICAL_DOMAIN_INVALID"),
                result.problems().stream()
                        .map(TemplateApplication.ValidationProblem::code)
                        .toList());
        assertEquals("/designRoot/children/1/contextSelector/domain/loopId",
                result.problems().getFirst().canonicalPointer());
    }

    @Test
    void repeatItemsCannotReadTheirOwnLoopFrame() {
        var canonical = canonical("[]", """
                [{"nodeId":"00000000-0000-4000-8000-000000000031","kind":"repeat",
                  "loopId":"%s","bindings":[],
                  "items":{"kind":"context","domain":{"kind":"loop","loopId":"%s"},"pointer":"/numbers"},
                  "absentPolicy":"EMPTY","itemLayout":{"kind":"STACK","direction":"ROW"},
                  "instanceLayout":{"kind":"STACK","direction":"ROW"},
                  "placement":{"type":"ABSOLUTE","xMm":0,"yMm":0,
                  "widthMode":"HUG_CONTENT","heightMode":"HUG_CONTENT"},"children":[
                    {"nodeId":"00000000-0000-4000-8000-000000000032","kind":"frame","bindings":[],
                     "placement":{"type":"PACK","widthMode":"HUG_CONTENT","heightMode":"HUG_CONTENT"},
                     "children":[]}]}]
                """.formatted(LOOP_ID, LOOP_ID));

        var result = validator().validate(canonical, ROOT_SCHEMA, Map.of());

        assertTrue(result.hard());
        assertEquals("TEMPLATE_LEXICAL_DOMAIN_INVALID", result.problems().getFirst().code());
        assertEquals("/designRoot/children/0/items/domain/loopId",
                result.problems().getFirst().canonicalPointer());
    }

    @Test
    void scalarRepeatBuildsSystemBasicContextForDescendantBindings() {
        var canonical = canonical("[]", """
                [{"nodeId":"00000000-0000-4000-8000-000000000035","kind":"repeat",
                  "loopId":"%s","bindings":[],
                  "items":{"kind":"context","domain":"invocation","pointer":"/numbers"},
                  "absentPolicy":"EMPTY","itemLayout":{"kind":"STACK","direction":"ROW"},
                  "instanceLayout":{"kind":"STACK","direction":"ROW"},
                  "placement":{"type":"ABSOLUTE","xMm":0,"yMm":0,
                  "widthMode":"HUG_CONTENT","heightMode":"HUG_CONTENT"},"children":[
                    {"nodeId":"00000000-0000-4000-8000-000000000036","kind":"frame",
                     "bindings":[{"bindingId":"00000000-0000-4000-8000-0000000000e1",
                     "targetPropertyRef":{"rootPropertyId":"placement","selectors":[
                     {"kind":"member","name":"widthMm"}]},
                     "source":{"kind":"context","domain":{"kind":"loop","loopId":"%s"},
                     "pointer":"/value"}}],
                     "placement":{"type":"PACK","widthMode":"FIXED","widthMm":10,
                     "heightMode":"HUG_CONTENT"},"children":[]}]}]
                """.formatted(LOOP_ID, LOOP_ID));

        var result = validator().validate(canonical, ROOT_SCHEMA, Map.of());

        assertFalse(result.hard());
        assertTrue(result.problems().isEmpty());
    }

    @Test
    void arrayTraversalAndScalarRepeatItemsAreDependencyProblemsInStablePointerOrder() {
        var otherLoopId = "00000000-0000-4000-8000-0000000000ab";
        var canonical = canonical("[]", """
                [{"nodeId":"00000000-0000-4000-8000-000000000091","kind":"conditional",
                  "bindings":[],"condition":{"kind":"context","domain":"invocation",
                  "pointer":"/cards/0/name"},"absentPolicy":"FALSE",
                  "placement":{"type":"ABSOLUTE","xMm":0,"yMm":0,
                  "widthMode":"HUG_CONTENT","heightMode":"HUG_CONTENT"},"children":[
                    {"nodeId":"00000000-0000-4000-8000-000000000092","kind":"frame",
                     "bindings":[],"placement":{"type":"ABSOLUTE","xMm":0,"yMm":0,
                     "widthMode":"HUG_CONTENT","heightMode":"HUG_CONTENT"},"children":[]}]},
                 {"nodeId":"00000000-0000-4000-8000-000000000093","kind":"repeat",
                  "loopId":"%s","bindings":[],
                  "items":{"kind":"context","domain":"invocation","pointer":"/title"},
                  "absentPolicy":"EMPTY","itemLayout":{"kind":"STACK","direction":"ROW"},
                  "instanceLayout":{"kind":"STACK","direction":"ROW"},
                  "placement":{"type":"ABSOLUTE","xMm":0,"yMm":0,
                  "widthMode":"HUG_CONTENT","heightMode":"HUG_CONTENT"},"children":[
                    {"nodeId":"00000000-0000-4000-8000-000000000094","kind":"frame",
                     "bindings":[],"placement":{"type":"PACK","widthMode":"HUG_CONTENT",
                     "heightMode":"HUG_CONTENT"},"children":[]}]}]
                """.formatted(otherLoopId));

        var result = validator().validate(canonical, ROOT_SCHEMA, Map.of());

        assertFalse(result.hard());
        assertEquals(
                List.of(
                        "TEMPLATE_STATIC_SCHEMA_PATH_NOT_FOUND",
                        "TEMPLATE_REPEAT_ITEMS_TYPE_MISMATCH"
                ),
                result.problems().stream()
                        .map(TemplateApplication.ValidationProblem::code)
                        .toList()
        );
        assertEquals(
                List.of(
                        "/designRoot/children/0/condition/pointer",
                        "/designRoot/children/1/items"
                ),
                result.problems().stream()
                        .map(TemplateApplication.ValidationProblem::canonicalPointer)
                        .toList()
        );
    }

    @Test
    void mappingAndBindingConsumersClassifySchemaTypeAndPresenceDrift() {
        var definitions = """
                [{"definitionId":"00000000-0000-4000-8000-0000000000e3",
                  "kind":"mapping","displayName":"Mapped title","domain":"invocation",
                  "output":"text","input":{"kind":"context","domain":"invocation",
                  "pointer":"/title"},"cases":[{"operator":"EQ",
                  "operand":{"valueType":"decimal","value":1},
                  "then":{"kind":"literal","valueType":"text","value":"one"}}],
                  "otherwise":{"kind":"literal","valueType":"text","value":"other"}}]
                """;
        var children = """
                [{"nodeId":"00000000-0000-4000-8000-000000000037","kind":"frame",
                  "opacity":1,"visible":true,
                  "bindings":[
                    {"bindingId":"00000000-0000-4000-8000-0000000000e4",
                     "targetPropertyRef":{"rootPropertyId":"opacity","selectors":[]},
                     "source":{"kind":"context","domain":"invocation","pointer":"/title"}},
                    {"bindingId":"00000000-0000-4000-8000-0000000000e5",
                     "targetPropertyRef":{"rootPropertyId":"visible","selectors":[]},
                     "source":{"kind":"context","domain":"invocation","pointer":"/enabled"}}],
                  "placement":{"type":"ABSOLUTE","xMm":0,"yMm":0,
                  "widthMode":"HUG_CONTENT","heightMode":"HUG_CONTENT"},"children":[]}]
                """;

        var result = validator().validate(canonical(definitions, children), ROOT_SCHEMA, Map.of());

        assertFalse(result.hard());
        assertEquals(
                List.of(
                        "TEMPLATE_MAPPING_INPUT_TYPE_MISMATCH",
                        "TEMPLATE_VALUE_SOURCE_TYPE_MISMATCH",
                        "TEMPLATE_VALUE_SOURCE_MAY_BE_ABSENT"
                ),
                result.problems().stream()
                        .map(TemplateApplication.ValidationProblem::code)
                        .toList()
        );
    }

    @Test
    void selectorRequiresExactChildSchemaAndEmptyRequiresSystemEmpty() {
        var otherChild = child(OTHER_CHILD_ID, OTHER_SCHEMA, "[]");
        var canonical = canonical("[]", """
                [%s,%s]
                """.formatted(
                templateUse("00000000-0000-4000-8000-000000000041",
                        "00000000-0000-4000-8000-0000000000a3", OTHER_CHILD_ID,
                        "{\"kind\":\"context\",\"domain\":{\"kind\":\"invocation\"},"
                                + "\"pointer\":\"/card\",\"contextAbsentPolicy\":\"SKIP\"}",
                        "[]", "ABSOLUTE"),
                templateUse("00000000-0000-4000-8000-000000000042",
                        "00000000-0000-4000-8000-0000000000a4", OTHER_CHILD_ID,
                        "{\"kind\":\"empty\"}", "[]", "ABSOLUTE")));

        var result = validator().validate(
                canonical, ROOT_SCHEMA, Map.of(OTHER_CHILD_ID, otherChild));

        assertFalse(result.hard());
        assertEquals(
                List.of(
                        "TEMPLATE_USE_CONTEXT_SCHEMA_MISMATCH",
                        "TEMPLATE_USE_EMPTY_CONTEXT_SCHEMA_MISMATCH"
                ),
                result.problems().stream()
                        .map(TemplateApplication.ValidationProblem::code)
                        .toList()
        );
    }

    @Test
    void fillsRequireExistingPublicCustomTargetAndExactSourceType() {
        var childDefinitions = """
                [{"definitionId":"%s","kind":"custom","displayName":"Public text",
                  "exposure":"PUBLIC","valueType":"text","defaultValue":"x"},
                 {"definitionId":"%s","kind":"custom","displayName":"Private decimal",
                  "exposure":"PRIVATE","valueType":"decimal","defaultValue":1}]
                """.formatted(PUBLIC_TEXT, PRIVATE_DECIMAL);
        var child = child(CHILD_ID, EMPTY_SCHEMA, childDefinitions);
        var parentDefinitions = """
                [{"definitionId":"%s","kind":"custom","displayName":"Parent decimal",
                  "exposure":"PRIVATE","valueType":"decimal","defaultValue":1}]
                """.formatted(PARENT_DECIMAL);
        var fills = """
                [{"targetDefinitionId":"%s","source":{"kind":"context","domain":"invocation","pointer":"/title"}},
                 {"targetDefinitionId":"%s","source":{"kind":"context","domain":"invocation","pointer":"/title"}},
                 {"targetDefinitionId":"%s","source":{"kind":"definition","definitionId":"%s"}}]
                """.formatted(MISSING_TARGET, PRIVATE_DECIMAL, PUBLIC_TEXT, PARENT_DECIMAL);
        var canonical = canonical(parentDefinitions,
                "[" + templateUse("00000000-0000-4000-8000-000000000051",
                        "00000000-0000-4000-8000-0000000000a5", CHILD_ID,
                        "{\"kind\":\"empty\"}", fills, "ABSOLUTE") + "]");

        var result = validator().validate(canonical, ROOT_SCHEMA, Map.of(CHILD_ID, child));

        assertFalse(result.hard());
        assertEquals(
                List.of(
                        "TEMPLATE_USE_FILL_TYPE_MISMATCH",
                        "TEMPLATE_USE_FILL_TARGET_NOT_PUBLIC",
                        "TEMPLATE_USE_FILL_TARGET_MISSING"
                ),
                result.problems().stream()
                        .map(TemplateApplication.ValidationProblem::code)
                        .toList()
        );
    }

    @Test
    void optionalNestedPathMayFillAndChildContentIntegrityIsHard() {
        var child = child(CHILD_ID, EMPTY_SCHEMA, """
                [{"definitionId":"%s","kind":"custom","displayName":"Public text",
                  "exposure":"PUBLIC","valueType":"text","defaultValue":"x"}]
                """.formatted(PUBLIC_TEXT));
        var fills = """
                [{"targetDefinitionId":"%s","source":{"kind":"context","domain":"invocation",
                  "pointer":"/card/name"}}]
                """.formatted(PUBLIC_TEXT);
        var canonical = canonical("[]",
                "[" + templateUse("00000000-0000-4000-8000-000000000061",
                        "00000000-0000-4000-8000-0000000000a6", CHILD_ID,
                        "{\"kind\":\"empty\"}", fills, "ABSOLUTE") + "]");

        var valid = validator().validate(canonical, ROOT_SCHEMA, Map.of(CHILD_ID, child));
        assertTrue(valid.problems().isEmpty());

        var corrupt = new DependencyResolution.TemplateState(
                child.templateId(), child.ownerScope(), child.currentRevision(), child.lifecycle(),
                child.readiness(), child.staticSchema(),
                "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                child.uses(), child.canonicalDesignDsl()
        );
        var invalid = validator().validate(canonical, ROOT_SCHEMA, Map.of(CHILD_ID, corrupt));
        assertTrue(invalid.hard());
        assertEquals("TEMPLATE_DEPENDENCY_INTEGRITY_MISMATCH",
                invalid.problems().getFirst().code());
        assertEquals("/designRoot/children/0/templateRef/templateId",
                invalid.problems().getFirst().canonicalPointer());
    }

    @Test
    void semanticProblemDiscoveryIsDeterministicAndHandsOverflowToTheSharedBudget() {
        var children = new StringBuilder("[");
        for (int index = 0; index <= TemplateProblemBudget.MAX_ITEMS; index++) {
            if (index > 0) {
                children.append(',');
            }
            children.append("""
                    {"nodeId":"%s","kind":"conditional","bindings":[],
                     "condition":{"kind":"context","domain":"invocation",
                     "pointer":"/missing-%03d"},"absentPolicy":"FALSE",
                     "placement":{"type":"ABSOLUTE","xMm":0,"yMm":0,
                     "widthMode":"HUG_CONTENT","heightMode":"HUG_CONTENT"},"children":[
                       {"nodeId":"%s","kind":"frame","bindings":[],
                        "placement":{"type":"ABSOLUTE","xMm":0,"yMm":0,
                        "widthMode":"HUG_CONTENT","heightMode":"HUG_CONTENT"},"children":[]}]}
                    """.formatted(uuid(1_000 + index), index, uuid(10_000 + index)));
        }
        children.append(']');
        var canonical = canonical("[]", children.toString());

        var first = validator().validate(canonical, ROOT_SCHEMA, Map.of());
        var second = validator().validate(canonical, ROOT_SCHEMA, Map.of());

        assertEquals(first, second);
        assertTrue(first.hard());
        assertEquals(TemplateProblemBudget.MAX_ITEMS + 1, first.problems().size());
        var bounded = TemplateProblemBudget.bounded(first.problems());
        assertTrue(bounded.truncated());
        assertEquals(TemplateProblemBudget.MAX_ITEMS, bounded.problems().size());
        assertEquals("PROBLEM_LIMIT_REACHED", bounded.problems().getLast().code());
        assertEquals(List.of("ITEMS"), bounded.problems().getLast().messageArgs());
    }

    private TemplateSemanticDependencyValidator validator() {
        return new TemplateSemanticDependencyValidator(schemas, designs);
    }

    private DependencyResolution.TemplateState child(
            String templateId,
            StaticSchemaRef staticSchema,
            String definitions
    ) {
        var canonical = canonical(definitions, "[]");
        var admitted = (DesignDslAuthority.Admitted) designs.admit(canonical);
        return new DependencyResolution.TemplateState(
                templateId,
                OWNER,
                4,
                DependencyResolution.Lifecycle.ACTIVE,
                TemplateApplication.Readiness.READY,
                staticSchema,
                admitted.contentHash(),
                List.of(),
                new String(admitted.canonicalUtf8(), StandardCharsets.UTF_8)
        );
    }

    private byte[] canonical(String definitions, String children) {
        var raw = """
                {"dslVersion":"renderweave-design/1.0",
                 "expressionProfile":"renderweave-expression/1.0",
                 "displayName":"Semantic validation fixture",
                 "definitions":%s,
                 "designRoot":{"nodeId":"00000000-0000-4000-8000-000000000001",
                 "kind":"canvas","widthMm":210,"heightMm":297,"bindings":[],"children":%s}}
                """.formatted(definitions, children).getBytes(StandardCharsets.UTF_8);
        var admission = designs.admit(raw);
        assertTrue(admission instanceof DesignDslAuthority.Admitted,
                () -> "fixture must admit: " + admission);
        return ((DesignDslAuthority.Admitted) admission).canonicalUtf8();
    }

    private static String templateUse(
            String nodeId,
            String useId,
            String targetTemplateId,
            String selector,
            String fills,
            String placementKind
    ) {
        var placement = "PACK".equals(placementKind)
                ? "{\"type\":\"PACK\",\"widthMode\":\"HUG_CONTENT\",\"heightMode\":\"HUG_CONTENT\"}"
                : "{\"type\":\"ABSOLUTE\",\"xMm\":0,\"yMm\":0,"
                + "\"widthMode\":\"HUG_CONTENT\",\"heightMode\":\"HUG_CONTENT\"}";
        return """
                {"nodeId":"%s","kind":"templateUse","bindings":[],"useId":"%s",
                 "templateRef":{"templateId":"%s"},"contextSelector":%s,"fills":%s,
                 "placement":%s}
                """.formatted(nodeId, useId, targetTemplateId, selector, fills, placement);
    }

    private static StaticSchemaRef schemaRef(String key) {
        return new StaticSchemaRef(SchemaKey.userProvided(key), VersionTag.of("v1"));
    }

    private static String uuid(int suffix) {
        return "00000000-0000-4000-8000-%012x".formatted(suffix);
    }

    private static SchemaDefinition schema(SchemaField... fields) {
        return new SchemaDefinition(
                SchemaDefinition.DSL_VERSION,
                "Fixture",
                Optional.empty(),
                List.of(fields)
        );
    }

    private static SchemaField field(
            String key,
            boolean required,
            cn.hbads.renderweave.schema.definition.ValueDescriptor value
    ) {
        return new SchemaField(
                FieldKey.of(key), Optional.empty(), Optional.empty(), required, value);
    }

    private record Schemas(Map<StaticSchemaRef, SchemaDefinition> definitions)
            implements StaticSchemaAuthority {
        @Override
        public Resolution resolve(StaticSchemaRef reference) {
            var definition = definitions.get(reference);
            return definition == null
                    ? new NotFound()
                    : new Resolved(reference, definition);
        }
    }
}
