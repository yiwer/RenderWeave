package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.rendering.api.EvaluationStage;
import cn.hbads.renderweave.rendering.api.Evaluator;
import cn.hbads.renderweave.rendering.api.Evaluator.EvaluationCommand;
import cn.hbads.renderweave.rendering.api.Evaluator.EvaluationOutcome;
import cn.hbads.renderweave.rendering.api.Evaluator.OutputSelection;
import cn.hbads.renderweave.rendering.api.Evaluator.OwnerScope;
import cn.hbads.renderweave.rendering.api.Evaluator.RenderRequestId;
import cn.hbads.renderweave.rendering.api.RenderingProblem;
import cn.hbads.renderweave.rendering.spi.AssetResolutionPort;
import cn.hbads.renderweave.rendering.spi.CapabilityStateStore;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluatorContractTest {

    private static final StaticSchemaRef SCHEMA = new StaticSchemaRef(
            SchemaKey.systemProvided("system-empty"), VersionTag.of("v1"));
    private static final String ROOT_ID = "00000000-0000-4000-8000-0000000000a1";
    private static final String AUTH_DIGEST = "sha256:" + "5".repeat(64);

    @Test
    void sameRequestAndFingerprintRestoresCommittedCapabilityStateWithoutResampling() {
        var stateStore = new RecordingCapabilityStateStore();
        var runtime = new RecordingCapabilityRuntime();
        var evaluator = evaluator(closureWith(canvasWithUnusedRandom()), resolver(), stateStore, runtime);

        assertInstanceOf(EvaluationOutcome.SealedDocument.class,
                evaluator.evaluate(command("{\"rootDocument\":{}}")));
        assertInstanceOf(EvaluationOutcome.SealedDocument.class,
                evaluator.evaluate(command("{\"rootDocument\":{}}")));

        assertEquals(1, runtime.establishCalls);
        assertEquals(1, runtime.restoreCalls);
        assertEquals(1, stateStore.saveCalls);
        assertEquals(2, stateStore.loadCalls);
    }

    @Test
    void sameRequestWithDifferentAuthorizationFingerprintRejectsAtCapabilityState() {
        var stateStore = new RecordingCapabilityStateStore();
        var evaluator = evaluator(
                closureWith(canvasWithUnusedRandom()), resolver(), stateStore,
                new RecordingCapabilityRuntime());
        assertInstanceOf(EvaluationOutcome.SealedDocument.class,
                evaluator.evaluate(command("{\"rootDocument\":{}}")));

        var changed = command("{\"rootDocument\":{}}", 61_000L, "sha256:" + "6".repeat(64));
        var rejected = assertInstanceOf(EvaluationOutcome.Rejected.class,
                evaluator.evaluate(changed));

        assertEquals(EvaluationStage.CAPABILITY_STATE, rejected.stage());
        assertEquals(RenderingProblem.ProblemCode.CAPABILITY_STATE_CONFLICT,
                rejected.problem().code());
    }

    @Test
    void capabilityStateDependencyUnavailableFailsBeforeMaterialization() {
        var unavailable = new RecordingCapabilityStateStore();
        unavailable.unavailable = true;
        var evaluator = evaluator(
                closureWith(canvasWithUnusedRandom()), resolver(), unavailable,
                new RecordingCapabilityRuntime());

        var rejected = assertInstanceOf(EvaluationOutcome.Rejected.class,
                evaluator.evaluate(command("{\"rootDocument\":{}}")));

        assertEquals(EvaluationStage.CAPABILITY_STATE, rejected.stage());
        assertEquals(RenderingProblem.ProblemCode.CAPABILITY_STATE_UNAVAILABLE,
                rejected.problem().code());
    }

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
                new RecordingCapabilityStateStore(),
                "{}",
                resolver(),
                Clock.fixed(Instant.ofEpochMilli(1_000L), ZoneOffset.UTC));

        var outcome = evaluator.evaluate(new EvaluationCommand(
                new RenderRequestId("00000000-0000-4000-8000-000000000001"),
                new OwnerScope("intruder-scope"),
                AUTH_DIGEST,
                new TemplateApplication.TemplateId(ROOT_ID),
                "{\"rootDocument\":{}}".getBytes(StandardCharsets.UTF_8),
                OutputSelection.defaultPng(),
                "renderweave-renderer/1.0",
                61_000L));

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
                new RecordingCapabilityStateStore(),
                "{}",
                resolver(),
                Clock.fixed(Instant.ofEpochMilli(1_000L), ZoneOffset.UTC));

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

    @Test
    void evaluationUsesTheApplicationFrozenAbsoluteRenderDeadline() {
        var now = Instant.parse("2026-08-20T08:00:00Z");
        var assets = new CapturingAssetPort();
        var evaluator = new CanonicalEvaluator(
                scriptedClosure(closureWith(canvasWithImage())),
                TemplateModule.designSemanticAuthority(),
                TemplateModule.designDslAuthority(),
                assets,
                scriptedRuntime(),
                new RecordingCapabilityStateStore(),
                "{}",
                resolver(),
                Clock.fixed(now, ZoneOffset.UTC));

        var deadline = now.plusSeconds(60).toEpochMilli();
        var outcome = evaluator.evaluate(command("{\"rootDocument\":{}}", deadline));

        assertInstanceOf(EvaluationOutcome.SealedDocument.class, outcome);
        assertEquals(deadline, assets.lastRequest.deadlineEpochMilli());
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static EvaluationCommand command(String envelope) {
        return command(envelope, 61_000L);
    }

    private static EvaluationCommand command(String envelope, long deadlineAtEpochMilli) {
        return command(envelope, deadlineAtEpochMilli, AUTH_DIGEST);
    }

    private static EvaluationCommand command(
            String envelope, long deadlineAtEpochMilli, String authorizationDigest) {
        return new EvaluationCommand(
                new RenderRequestId("00000000-0000-4000-8000-000000000001"),
                new OwnerScope("owner-a"),
                authorizationDigest,
                new TemplateApplication.TemplateId(ROOT_ID),
                envelope.getBytes(StandardCharsets.UTF_8),
                OutputSelection.defaultPng(),
                "renderweave-renderer/1.0",
                deadlineAtEpochMilli);
    }

    private static cn.hbads.renderweave.rendering.internal.CanonicalEvaluator evaluator(
            ClosureSnapshot closure, ValidationTargetResolver resolver) {
        return evaluator(closure, resolver, new RecordingCapabilityStateStore(), scriptedRuntime());
    }

    private static cn.hbads.renderweave.rendering.internal.CanonicalEvaluator evaluator(
            ClosureSnapshot closure,
            ValidationTargetResolver resolver,
            CapabilityStateStore stateStore,
            RenderingCapabilityRuntime runtime) {
        return new cn.hbads.renderweave.rendering.internal.CanonicalEvaluator(
                scriptedClosure(closure),
                TemplateModule.designSemanticAuthority(),
                TemplateModule.designDslAuthority(),
                null,
                runtime,
                stateStore,
                "{\"capabilityRuntime\":{\"initializationAttempts\":3}}",
                resolver,
                Clock.fixed(Instant.ofEpochMilli(1_000L), ZoneOffset.UTC));
    }

    private static final class RecordingCapabilityRuntime implements RenderingCapabilityRuntime {
        private int establishCalls;
        private int restoreCalls;

        @Override
        public Established establish() {
            establishCalls++;
            return new Established(scriptedProvider(), new byte[]{1, 2, 3});
        }

        @Override
        public Runtime restore(byte[] sealedState) {
            restoreCalls++;
            return scriptedProvider();
        }

        @Override
        public String capabilityContracts() {
            return "renderweave-capability-clock/1.0,renderweave-capability-random/1.0";
        }

        private static Runtime scriptedProvider() {
            return (capability, operation, callPosition) -> new ProviderUnavailable();
        }
    }

    private static final class RecordingCapabilityStateStore implements CapabilityStateStore {
        private SaveRequest committed;
        private boolean unavailable;
        private int saveCalls;
        private int loadCalls;

        @Override
        public SaveOutcome save(SaveRequest request) {
            saveCalls++;
            if (unavailable) {
                return new SaveOutcome.SaveUnavailable();
            }
            committed = request;
            return new SaveOutcome.Stored(new CapabilityStateId("state-1"));
        }

        @Override
        public LoadOutcome load(RenderRequestId requestId, String evaluationFingerprint) {
            loadCalls++;
            if (unavailable) {
                return new LoadOutcome.LoadUnavailable();
            }
            if (committed == null) {
                return new LoadOutcome.Missing();
            }
            if (!committed.evaluationFingerprint().equals(evaluationFingerprint)) {
                return new LoadOutcome.LoadFingerprintConflict();
            }
            return new LoadOutcome.Loaded(committed.sealedState(), committed.expiresAtEpochSecond());
        }
    }

    private static TemplateClosureAuthority scriptedClosure(ClosureSnapshot closure) {
        return (renderRequestId, rootTemplateId) -> new TemplateClosureAuthority.ClosureFrozen(closure);
    }

    private static RenderingCapabilityRuntime scriptedRuntime() {
        return new RenderingCapabilityRuntime() {
            @Override
            public Established establish() {
                return new Established(provider(), new byte[]{1});
            }

            @Override
            public Runtime restore(byte[] sealedState) {
                return provider();
            }

            @Override
            public String capabilityContracts() {
                return "renderweave-capability-clock/1.0,renderweave-capability-random/1.0";
            }

            private Runtime provider() {
                return (capability, operation, callPosition) -> new ProviderUnavailable();
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

    private static String canvasWithImage() {
        return "{\"dslVersion\":\"renderweave-design/1.0\","
                + "\"expressionProfile\":\"renderweave-expression/1.0\","
                + "\"displayName\":\"R\",\"definitions\":[],"
                + "\"designRoot\":{\"nodeId\":\"00000000-0000-4000-8000-000000000001\","
                + "\"kind\":\"canvas\",\"widthMm\":210,\"heightMm\":297,\"bindings\":[],"
                + "\"children\":[{\"nodeId\":\"00000000-0000-4000-8000-000000000011\","
                + "\"kind\":\"image\",\"bindings\":[],\"placement\":{\"type\":\"ABSOLUTE\","
                + "\"xMm\":0,\"yMm\":0,\"widthMode\":\"FIXED\",\"widthMm\":10,"
                + "\"heightMode\":\"FIXED\",\"heightMm\":10},"
                + "\"imageRef\":{\"assetId\":\"00000000-0000-4000-8000-0000000000aa\"}}]}}";
    }

    private static String canvasWithUnusedRandom() {
        return canvasWithRect().replace("\"definitions\":[]", "\"definitions\":[{"
                + "\"definitionId\":\"00000000-0000-4000-8000-0000000000e1\","
                + "\"kind\":\"expression\",\"displayName\":\"Draw\","
                + "\"domain\":\"invocation\",\"output\":\"text\","
                + "\"inputs\":[{\"alias\":\"draw\",\"source\":{\"kind\":\"capability\","
                + "\"capability\":\"RANDOM\",\"operation\":\"UNIFORM_DECIMAL_0_1\"}}],"
                + "\"source\":\"if(input.draw < 0.5, 'A', 'B')\"}]");
    }

    private static final class CapturingAssetPort implements AssetResolutionPort {
        private ResolveRequest lastRequest;

        @Override
        public PrecheckOutcome precheckAdmission(
                cn.hbads.renderweave.asset.api.AssetApplication.OwnerScope ownerScope,
                cn.hbads.renderweave.asset.api.AssetApplication.AssetId assetId,
                cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.AssetKind expectedKind
        ) {
            return new PrecheckOutcome.PrecheckPassed();
        }

        @Override
        public ResolveOutcome resolve(ResolveRequest request) {
            lastRequest = request;
            return new ResolveOutcome.Resolved(new ResolvedAssetFact(
                    "0",
                    "b".repeat(64),
                    "image/png",
                    128,
                    "renderweave-asset-acceptance/1.0",
                    new cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.ImageDescriptor(
                            1, 1,
                            cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.Orientation.IDENTITY,
                            1, 1, 1,
                            cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.ColorEncoding.SRGB_8BIT),
                    "https://assets.internal/fetch/lease",
                    request.deadlineEpochMilli() / 1_000));
        }
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
