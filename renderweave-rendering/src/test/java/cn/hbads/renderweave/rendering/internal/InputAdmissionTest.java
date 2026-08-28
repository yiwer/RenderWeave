package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.rendering.api.EvaluationStage;
import cn.hbads.renderweave.rendering.api.RenderingProblem;
import cn.hbads.renderweave.template.api.TemplateClosureAuthority;
import cn.hbads.renderweave.schema.definition.ArrayConstraints;
import cn.hbads.renderweave.schema.definition.ArrayValue;
import cn.hbads.renderweave.schema.definition.BooleanConstraints;
import cn.hbads.renderweave.schema.definition.BooleanValue;
import cn.hbads.renderweave.schema.definition.DateConstraints;
import cn.hbads.renderweave.schema.definition.DateValue;
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
import cn.hbads.renderweave.validation.ResolvedSchema;
import cn.hbads.renderweave.validation.ResolvedSchemaIdentity;
import cn.hbads.renderweave.validation.ResolvedValidationTarget;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InputAdmissionTest {

    private static final String PUBLIC_DEFINITION_ID = "00000000-0000-4000-8000-0000000000d1";
    private static final String PRIVATE_DEFINITION_ID = "00000000-0000-4000-8000-0000000000d2";

    private static final StaticSchemaRef ROOT_SCHEMA = new StaticSchemaRef(
            SchemaKey.systemProvided("system-test-root"), VersionTag.of("v1"));
    private static final StaticSchemaRef PHOTO_SCHEMA = new StaticSchemaRef(
            SchemaKey.systemProvided("system-test-photo"), VersionTag.of("v1"));

    @Test
    void validInputFormsAdmittedTypedViewAndCustoms() {
        var body = ("{\"rootDocument\":{"
                + "\"name\":\"alpha\",\"age\":30.50,\"active\":true,"
                + "\"birthday\":\"2026-01-02\","
                + "\"photo\":{\"title\":\"front\",\"width\":10},"
                + "\"tags\":[\"x\",\"y\"]"
                + "},\"customValues\":[{\"definitionId\":\"" + PUBLIC_DEFINITION_ID
                + "\",\"value\":\"win\"}]}").getBytes(StandardCharsets.UTF_8);

        var result = InputAdmission.admit(body, snapshot(), resolver());

        var admitted = assertInstanceOf(InputAdmission.AdmissionAdmitted.class, result);
        var input = admitted.input();
        assertEquals(ROOT_SCHEMA, input.staticSchemaRef());

        var root = input.rootDocument();
        assertEquals(ROOT_SCHEMA, root.reference());
        assertEquals("alpha", ((TypedValue.Text) root.fields().get("name").orElseThrow()).value());
        assertEquals(0, new BigDecimal("30.50")
                .compareTo(((TypedValue.Decimal) root.fields().get("age").orElseThrow()).value()));
        assertEquals(true, ((TypedValue.Bool) root.fields().get("active").orElseThrow()).value());
        assertEquals("2026-01-02", ((TypedValue.Date) root.fields().get("birthday").orElseThrow()).value());

        var photo = assertInstanceOf(TypedValue.Nested.class, root.fields().get("photo").orElseThrow());
        assertEquals(PHOTO_SCHEMA, photo.reference());
        assertEquals("front",
                ((TypedValue.Text) photo.object().fields().get("title").orElseThrow()).value());

        var tags = assertInstanceOf(TypedValue.Array.class, root.fields().get("tags").orElseThrow());
        assertEquals(2, tags.items().size());

        assertEquals(new DesignValue.Text("win"), input.customs().get(PUBLIC_DEFINITION_ID));
        assertEquals(new DesignValue.Decimal(new BigDecimal("5")),
                input.customs().get(PRIVATE_DEFINITION_ID));
        assertEquals(Map.of(PUBLIC_DEFINITION_ID, new DesignValue.Text("win")),
                input.externalCustomOverrides());
    }

    @Test
    void missingOptionalFieldsAreTypedAbsent() {
        var body = "{\"rootDocument\":{\"name\":\"alpha\"}}".getBytes(StandardCharsets.UTF_8);

        var admitted = assertInstanceOf(
                InputAdmission.AdmissionAdmitted.class,
                InputAdmission.admit(body, snapshot(), resolver()));

        var fields = admitted.input().rootDocument().fields();
        assertTrue(fields.get("age").isEmpty());
        assertTrue(fields.get("photo").isEmpty());
        assertTrue(fields.get("tags").isEmpty());
    }

    @Test
    void unknownFieldsStayOutOfTheTypedView() {
        var body = "{\"rootDocument\":{\"name\":\"alpha\",\"surprise\":42}}"
                .getBytes(StandardCharsets.UTF_8);

        var admitted = assertInstanceOf(
                InputAdmission.AdmissionAdmitted.class,
                InputAdmission.admit(body, snapshot(), resolver()));

        assertEquals(6, admitted.input().rootDocument().fields().size());
        assertTrue(!admitted.input().rootDocument().fields().containsKey("surprise"));
    }

    @Test
    void schemaViolationRejectsAtInputAdmission() {
        var body = "{\"rootDocument\":{\"age\":3}}".getBytes(StandardCharsets.UTF_8);

        var rejected = assertInstanceOf(
                InputAdmission.AdmissionRejected.class,
                InputAdmission.admit(body, snapshot(), resolver()));

        var problem = rejected.problems().get(0);
        assertEquals(EvaluationStage.INPUT_ADMISSION, problem.stage());
        assertEquals(RenderingProblem.ProblemCode.EVALUATION_FAILED, problem.code());
    }

    @Test
    void explicitNullForDeclaredFieldRejects() {
        var body = "{\"rootDocument\":{\"name\":\"alpha\",\"age\":null}}".getBytes(StandardCharsets.UTF_8);

        assertInstanceOf(
                InputAdmission.AdmissionRejected.class,
                InputAdmission.admit(body, snapshot(), resolver()));
    }

    @Test
    void unknownCustomTargetIsSilentlyIgnored() {
        var body = ("{\"rootDocument\":{\"name\":\"alpha\"},"
                + "\"customValues\":[{\"definitionId\":\"00000000-0000-4000-8000-0000000000f9\","
                + "\"value\":\"x\"}]}").getBytes(StandardCharsets.UTF_8);

        var admitted = assertInstanceOf(
                InputAdmission.AdmissionAdmitted.class,
                InputAdmission.admit(body, snapshot(), resolver()));

        assertEquals(2, admitted.input().customs().size());
        assertEquals(new DesignValue.Text("d1"),
                admitted.input().customs().get(PUBLIC_DEFINITION_ID));
    }

    @Test
    void privateCustomOverrideIsIgnoredAndDefaultKept() {
        var body = ("{\"rootDocument\":{\"name\":\"alpha\"},"
                + "\"customValues\":[{\"definitionId\":\"" + PRIVATE_DEFINITION_ID
                + "\",\"value\":99}]}").getBytes(StandardCharsets.UTF_8);

        var admitted = assertInstanceOf(
                InputAdmission.AdmissionAdmitted.class,
                InputAdmission.admit(body, snapshot(), resolver()));

        assertEquals(new DesignValue.Decimal(new BigDecimal("5")),
                admitted.input().customs().get(PRIVATE_DEFINITION_ID));
    }

    @Test
    void customWinnerTypeMismatchRejects() {
        var body = ("{\"rootDocument\":{\"name\":\"alpha\"},"
                + "\"customValues\":[{\"definitionId\":\"" + PRIVATE_DEFINITION_ID
                + "\",\"value\":5},{\"definitionId\":\"" + PUBLIC_DEFINITION_ID
                + "\",\"value\":42}]}").getBytes(StandardCharsets.UTF_8);

        var rejected = assertInstanceOf(
                InputAdmission.AdmissionRejected.class,
                InputAdmission.admit(body, snapshot(), resolver()));

        assertEquals(EvaluationStage.INPUT_ADMISSION, rejected.problems().get(0).stage());
        assertTrue(rejected.problems().get(0).safeLocation().orElseThrow()
                .startsWith("/customValues/"));
    }

    @Test
    void duplicateCustomTargetsUseLastWinner() {
        var body = ("{\"rootDocument\":{\"name\":\"alpha\"},"
                + "\"customValues\":["
                + "{\"definitionId\":\"" + PUBLIC_DEFINITION_ID + "\",\"value\":\"first\"},"
                + "{\"definitionId\":\"" + PUBLIC_DEFINITION_ID + "\",\"value\":\"second\"}"
                + "]}").getBytes(StandardCharsets.UTF_8);

        var admitted = assertInstanceOf(
                InputAdmission.AdmissionAdmitted.class,
                InputAdmission.admit(body, snapshot(), resolver()));

        assertEquals(new DesignValue.Text("second"),
                admitted.input().customs().get(PUBLIC_DEFINITION_ID));
    }

    @Test
    void envelopeRejectionPropagatesAtRequestAdmissionStage() {
        var rejected = assertInstanceOf(
                InputAdmission.AdmissionRejected.class,
                InputAdmission.admit("{\"nope\":1}".getBytes(StandardCharsets.UTF_8),
                        snapshot(), resolver()));

        assertEquals(EvaluationStage.REQUEST_ADMISSION, rejected.problems().get(0).stage());
    }

    @Test
    void resolverFailureIsAdmissionUnavailable() {
        var result = InputAdmission.admit(
                "{\"rootDocument\":{\"name\":\"alpha\"}}".getBytes(StandardCharsets.UTF_8),
                snapshot(),
                target -> {
                    throw new IllegalStateException("schema store down");
                });

        assertInstanceOf(InputAdmission.AdmissionUnavailable.class, result);
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static TemplateClosureAuthority.TemplateSnapshot snapshot() {
        var canonical = ("{\"dslVersion\":\"renderweave-design/1.0\","
                + "\"expressionProfile\":\"renderweave-expression/1.0\","
                + "\"displayName\":\"R\",\"definitions\":["
                + "{\"definitionId\":\"" + PUBLIC_DEFINITION_ID + "\",\"kind\":\"custom\","
                + "\"displayName\":\"Pub\",\"exposure\":\"PUBLIC\",\"valueType\":\"text\","
                + "\"defaultValue\":\"d1\"},"
                + "{\"definitionId\":\"" + PRIVATE_DEFINITION_ID + "\",\"kind\":\"custom\","
                + "\"displayName\":\"Priv\",\"exposure\":\"PRIVATE\",\"valueType\":\"decimal\","
                + "\"defaultValue\":5}"
                + "],\"designRoot\":{\"nodeId\":\"00000000-0000-4000-8000-000000000001\","
                + "\"kind\":\"canvas\",\"widthMm\":210,\"heightMm\":297,"
                + "\"bindings\":[],\"children\":[]}}").getBytes(StandardCharsets.UTF_8);
        return new TemplateClosureAuthority.TemplateSnapshot(
                new cn.hbads.renderweave.template.api.TemplateApplication.TemplateId(
                        "00000000-0000-4000-8000-0000000000a1"),
                4,
                new TemplateClosureAuthority.OwnerScope("owner-a"),
                ROOT_SCHEMA,
                "renderweave-design/1.0",
                "renderweave-expression/1.0",
                canonical,
                "sha256:" + "a".repeat(64)
        );
    }

    private static cn.hbads.renderweave.validation.ValidationTargetResolver resolver() {
        var rootSchema = new ResolvedSchema(
                new ResolvedSchemaIdentity.StaticIdentity(ROOT_SCHEMA),
                new SchemaDefinition(
                        SchemaDefinition.DSL_VERSION,
                        "TestRoot",
                        Optional.empty(),
                        List.of(
                                field("name", true, new TextValue(emptyText())),
                                field("age", false, new DecimalValue(emptyDecimal())),
                                field("active", false, new BooleanValue(new BooleanConstraints(Optional.empty()))),
                                field("birthday", false, new DateValue(emptyDate())),
                                field("photo", false, new ReferenceValue(PHOTO_SCHEMA)),
                                field("tags", false, new ArrayValue(
                                        new ArrayConstraints(OptionalInt.empty(), OptionalInt.empty(), Optional.empty()),
                                        new TextValue(emptyText())))
                        )
                )
        );
        var photoSchema = new ResolvedSchema(
                new ResolvedSchemaIdentity.StaticIdentity(PHOTO_SCHEMA),
                new SchemaDefinition(
                        SchemaDefinition.DSL_VERSION,
                        "TestPhoto",
                        Optional.empty(),
                        List.of(
                                field("title", true, new TextValue(emptyText())),
                                field("width", false, new DecimalValue(emptyDecimal()))
                        )
                )
        );
        var statics = new LinkedHashMap<StaticSchemaRef, ResolvedSchema>();
        statics.put(ROOT_SCHEMA, rootSchema);
        statics.put(PHOTO_SCHEMA, photoSchema);
        var target = new ResolvedValidationTarget(
                new ResolvedSchemaIdentity.StaticIdentity(ROOT_SCHEMA),
                Map.of(),
                statics
        );
        return ignored -> target;
    }

    private static SchemaField field(String name, boolean required, cn.hbads.renderweave.schema.definition.ValueDescriptor value) {
        return new SchemaField(
                FieldKey.of(name), Optional.empty(), Optional.empty(), required, value);
    }

    private static TextConstraints emptyText() {
        return new TextConstraints(
                OptionalInt.empty(), OptionalInt.empty(), Optional.empty(), List.of(), Optional.empty());
    }

    private static DecimalConstraints emptyDecimal() {
        return new DecimalConstraints(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), List.of(), Optional.empty());
    }

    private static DateConstraints emptyDate() {
        return new DateConstraints(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(), Optional.empty());
    }
}
