package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.rendering.api.EvaluationStage;
import cn.hbads.renderweave.rendering.api.Evaluator;
import cn.hbads.renderweave.rendering.api.Evaluator.EvaluationCommand;
import cn.hbads.renderweave.rendering.api.Evaluator.EvaluationOutcome;
import cn.hbads.renderweave.rendering.api.Evaluator.OutputSelection;
import cn.hbads.renderweave.rendering.api.Evaluator.OwnerScope;
import cn.hbads.renderweave.rendering.api.Evaluator.RenderRequestId;
import cn.hbads.renderweave.rendering.api.RenderingProblem;
import cn.hbads.renderweave.rendering.spi.RenderingCapabilityRuntime;
import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.schema.identity.SchemaKey;
import cn.hbads.renderweave.schema.identity.VersionTag;
import cn.hbads.renderweave.template.api.DesignDslAuthority;
import cn.hbads.renderweave.template.api.TemplateApplication;
import cn.hbads.renderweave.template.api.TemplateClosureAuthority;
import cn.hbads.renderweave.template.api.TemplateClosureAuthority.ClosureOutcome;
import cn.hbads.renderweave.template.api.TemplateClosureAuthority.ClosureSnapshot;
import cn.hbads.renderweave.template.api.TemplateClosureAuthority.TemplateSnapshot;
import cn.hbads.renderweave.template.internal.TemplateModule;
import cn.hbads.renderweave.validation.ResolvedSchema;
import cn.hbads.renderweave.validation.ResolvedSchemaIdentity;
import cn.hbads.renderweave.validation.ResolvedValidationTarget;
import cn.hbads.renderweave.validation.ValidationTarget;
import cn.hbads.renderweave.validation.ValidationTargetResolver;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluatorContractTest {

    private static final StaticSchemaRef SCHEMA = new StaticSchemaRef(
            SchemaKey.systemProvided("system-empty"), VersionTag.of("v1"));
    private static final String ROOT_ID = "00000000-0000-4000-8000-0000000000a1";

    @Test
    void evaluateSealsDocumentEndToEnd() {
        var evaluator = evaluator(closureWith(canvasWithRect()), resolver());

        var outcome = evaluator.evaluate(command(
                "{\"rootDocument\":{}}"));

        var sealed = assertInstanceOf(EvaluationOutcome.SealedDocument.class, outcome);
        var document = new String(
                sealed.renderDocumentCanonicalUtf8(), StandardCharsets.UTF_8);
        assertTrue(document.contains("\"dslVersion\":\"renderweave-render/1.0\""));
        assertTrue(document.contains("\"layoutProfile\":\"renderweave-layout/1.0\""));
        assertTrue(document.contains("rwocc_0000000000000000"));
        assertTrue(sealed.renderDocumentDigest().startsWith("sha256:"));
        assertTrue(sealed.evaluationResultDigest().startsWith("sha256:"));
        assertEquals(OutputSelection.defaultPng(), sealed.outputSelection());
    }

    @Test
    void malformedEnvelopeRejectsAtRequestAdmission() {
        var evaluator = evaluator(closureWith(canvasWithRect()), resolver());

        var outcome = evaluator.evaluate(command("{\"nope\":1}"));

        var rejected = assertInstanceOf(EvaluationOutcome.Rejected.class, outcome);
        assertEquals(EvaluationStage.REQUEST_ADMISSION, rejected.stage());
    }

    @Test
    void ownerScopeMismatchRejectsAtRequestAdmission() {
        var closure = closureWith(canvasWithRect());
        var evaluator = new cn.hbads.renderweave.rendering.internal.CanonicalEvaluator(
                scriptedClosure(closure),
                TemplateModule.designSemanticAuthority(),
                TemplateModule.designDslAuthority(),
                null,
                scriptedRuntime(),
                resolver(),
                1_000L);

        var outcome = evaluator.evaluate(new EvaluationCommand(
                new RenderRequestId("render-1"),
                new OwnerScope("intruder-scope"),
                new TemplateApplication.TemplateId(ROOT_ID),
                "{\"rootDocument\":{}}".getBytes(StandardCharsets.UTF_8),
                OutputSelection.defaultPng()));

        var rejected = assertInstanceOf(EvaluationOutcome.Rejected.class, outcome);
        assertEquals(EvaluationStage.REQUEST_ADMISSION, rejected.stage());
    }

    @Test
    void unstableClosureRejectsWithFrozenCode() {
        var evaluator = new cn.hbads.renderweave.rendering.internal.CanonicalEvaluator(
                (renderRequestId, rootTemplateId) -> new TemplateClosureAuthority.ClosureUnstable(),
                TemplateModule.designSemanticAuthority(),
                TemplateModule.designDslAuthority(),
                null,
                scriptedRuntime(),
                resolver(),
                1_000L);

        var outcome = evaluator.evaluate(command("{\"rootDocument\":{}}"));

        var rejected = assertInstanceOf(EvaluationOutcome.Rejected.class, outcome);
        assertEquals(EvaluationStage.TEMPLATE_CLOSURE, rejected.stage());
        assertEquals(RenderingProblem.ProblemCode.TEMPLATE_CLOSURE_UNSTABLE,
                rejected.problem().code());
    }

    @Test
    void missingRootDocumentRejectsAtEnvelopeStage() {
        var evaluator = evaluator(closureWith(canvasWithRect()), resolver());

        var outcome = evaluator.evaluate(command("{\"customValues\":[]}"));

        var rejected = assertInstanceOf(EvaluationOutcome.Rejected.class, outcome);
        // envelope 结构拒绝属于 stage 1（REQUEST_ADMISSION）。
        assertEquals(EvaluationStage.REQUEST_ADMISSION, rejected.stage());
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static EvaluationCommand command(String envelope) {
        return new EvaluationCommand(
                new RenderRequestId("render-1"),
                new OwnerScope("owner-a"),
                new TemplateApplication.TemplateId(ROOT_ID),
                envelope.getBytes(StandardCharsets.UTF_8),
                OutputSelection.defaultPng());
    }

    private static cn.hbads.renderweave.rendering.internal.CanonicalEvaluator evaluator(
            ClosureSnapshot closure, ValidationTargetResolver resolver) {
        return new cn.hbads.renderweave.rendering.internal.CanonicalEvaluator(
                scriptedClosure(closure),
                TemplateModule.designSemanticAuthority(),
                TemplateModule.designDslAuthority(),
                null,
                scriptedRuntime(),
                resolver,
                1_000L);
    }

    private static TemplateClosureAuthority scriptedClosure(ClosureSnapshot closure) {
        return (renderRequestId, rootTemplateId) -> new TemplateClosureAuthority.ClosureFrozen(closure);
    }

    private static RenderingCapabilityRuntime scriptedRuntime() {
        return new RenderingCapabilityRuntime() {
            @Override
            public Runtime establish() {
                return (capability, operation, callPosition) -> new ProviderUnavailable();
            }

            @Override
            public String capabilityContracts() {
                return "renderweave-capability-clock/1.0,renderweave-capability-random/1.0";
            }
        };
    }

    private static ClosureSnapshot closureWith(String designDocument) {
        var admission = TemplateModule.designDslAuthority()
                .admit(designDocument.getBytes(StandardCharsets.UTF_8));
        var admitted = (DesignDslAuthority.Admitted) admission;
        var snapshot = new TemplateSnapshot(
                new TemplateApplication.TemplateId(ROOT_ID),
                1,
                new TemplateClosureAuthority.OwnerScope("owner-a"),
                SCHEMA,
                "renderweave-design/1.0",
                "renderweave-expression/1.0",
                admitted.canonicalUtf8(),
                admitted.contentHash());
        return new ClosureSnapshot(
                new TemplateClosureAuthority.OwnerScope("owner-a"),
                snapshot.templateId(),
                1,
                List.of(snapshot),
                List.of());
    }

    private static String canvasWithRect() {
        return "{\"dslVersion\":\"renderweave-design/1.0\","
                + "\"expressionProfile\":\"renderweave-expression/1.0\","
                + "\"displayName\":\"R\",\"definitions\":[],"
                + "\"designRoot\":{\"nodeId\":\"00000000-0000-4000-8000-000000000001\","
                + "\"kind\":\"canvas\",\"widthMm\":210,\"heightMm\":297,\"bindings\":[],"
                + "\"children\":[{\"nodeId\":\"00000000-0000-4000-8000-000000000011\","
                + "\"kind\":\"rect\",\"bindings\":[],\"placement\":{\"type\":\"ABSOLUTE\","
                + "\"xMm\":0,\"yMm\":0,\"widthMode\":\"FIXED\",\"widthMm\":10,"
                + "\"heightMode\":\"FIXED\",\"heightMm\":10},"
                + "\"fill\":{\"color\":\"#FF000000\"}}]}}";
    }

    private static ValidationTargetResolver resolver() {
        var rootSchema = new ResolvedSchema(
                new ResolvedSchemaIdentity.StaticIdentity(SCHEMA),
                new cn.hbads.renderweave.schema.definition.SchemaDefinition(
                        cn.hbads.renderweave.schema.definition.SchemaDefinition.DSL_VERSION,
                        "Empty",
                        java.util.Optional.empty(),
                        List.of()));
        var target = new ResolvedValidationTarget(
                new ResolvedSchemaIdentity.StaticIdentity(SCHEMA),
                Map.of(),
                Map.of(SCHEMA, rootSchema));
        return ignored -> target;
    }
}
