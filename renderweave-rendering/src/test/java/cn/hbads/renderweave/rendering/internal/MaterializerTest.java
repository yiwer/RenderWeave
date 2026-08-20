package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.AssetKind;
import cn.hbads.renderweave.asset.api.AssetApplication.AssetId;
import cn.hbads.renderweave.asset.api.AssetApplication.OwnerScope;
import cn.hbads.renderweave.rendering.api.EvaluationStage;
import cn.hbads.renderweave.rendering.api.Evaluator.RenderRequestId;
import cn.hbads.renderweave.rendering.api.RenderingProblem;
import cn.hbads.renderweave.rendering.internal.ExpressionEvaluator.EvalOutcome;
import cn.hbads.renderweave.rendering.internal.ExpressionEvaluator.EvalValue;
import cn.hbads.renderweave.rendering.spi.AssetResolutionPort;
import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.schema.identity.SchemaKey;
import cn.hbads.renderweave.schema.identity.VersionTag;
import cn.hbads.renderweave.template.api.DesignDslAuthority;
import cn.hbads.renderweave.template.internal.TemplateModule;
import cn.hbads.renderweave.template.api.TemplateClosureAuthority.ClosureSnapshot;
import cn.hbads.renderweave.template.api.TemplateClosureAuthority.TemplateSnapshot;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaterializerTest {

    private static final DesignDslAuthority DESIGNS = TemplateModule.designDslAuthority();
    private static final StaticSchemaRef SCHEMA = new StaticSchemaRef(
            SchemaKey.systemProvided("system-empty"), VersionTag.of("v1"));
    private static final String ROOT_ID = "00000000-0000-4000-8000-0000000000a1";
    private static final String CHILD_ID = "00000000-0000-4000-8000-0000000000c1";
    private static final String ASSET_ID = "00000000-0000-4000-8000-0000000000aa";

    // ------------------------------------------------------------------
    // expansion
    // ------------------------------------------------------------------

    @Test
    void renderFalseSubtreeIsPruned() {
        var document = canvasWith(
                rect("00000000-0000-4000-8000-000000000011") + ","
                        + "{\"nodeId\":\"00000000-0000-4000-8000-000000000012\",\"kind\":\"rect\","
                        + "\"render\":false,\"bindings\":[],\"placement\":" + absolute() + ","
                        + "\"fill\":{\"color\":\"#FF000000\"}}");
        var tree = materializeOk(document, Map.of(), null);
        var root = tree.root();
        assertEquals("canvas", root.kind());
        assertEquals(1, root.children().size());
    }

    @Test
    void conditionalFalsePrunesAndTrueExpandsFrame() {
        var falseDocument = canvasWith(conditional(false));
        var falseTree = materializeOk(falseDocument, Map.of(), null);
        assertEquals(0, falseTree.root().children().size());

        var trueDocument = canvasWith(conditional(true));
        var trueTree = materializeOk(trueDocument, Map.of(), null);
        assertEquals(1, trueTree.root().children().size());
        assertEquals("frame", trueTree.root().children().get(0).kind());
    }

    @Test
    void repeatLowersPackingIntoInstanceAndItemContainers() {
        var document = "{\"dslVersion\":\"renderweave-design/1.0\","
                + "\"expressionProfile\":\"renderweave-expression/1.0\","
                + "\"displayName\":\"R\",\"definitions\":[],"
                + "\"designRoot\":{\"nodeId\":\"00000000-0000-4000-8000-000000000001\","
                + "\"kind\":\"canvas\",\"widthMm\":210,\"heightMm\":297,\"bindings\":[],"
                + "\"children\":[{\"nodeId\":\"00000000-0000-4000-8000-000000000021\","
                + "\"kind\":\"repeat\",\"bindings\":[],\"placement\":" + absolute() + ","
                + "\"loopId\":\"00000000-0000-4000-8000-0000000000b1\","
                + "\"absentPolicy\":\"ERROR\","
                + "\"items\":{\"kind\":\"literal\","
                + "\"valueType\":{\"type\":\"list\",\"items\":\"text\"},"
                + "\"value\":[\"a\",\"b\",\"c\"]},"
                + "\"itemLayout\":{\"kind\":\"STACK\",\"direction\":\"ROW\",\"gapMm\":1},"
                + "\"instanceLayout\":{\"kind\":\"STACK\",\"direction\":\"ROW\",\"gapMm\":2},"
                + "\"children\":[" + packRect("00000000-0000-4000-8000-000000000031") + "]}]}}";
        var tree = materializeOk(document, Map.of(), null);
        assertEquals(1, tree.root().children().size());
        var instances = tree.root().children().get(0);
        assertEquals("stack", instances.kind());
        assertEquals("ROW", textMember(instances.members(), "direction"));
        assertEquals("2", numberMember(instances.members(), "gapMm"));
        assertEquals("ABSOLUTE", placementType(instances));
        assertEquals(3, instances.children().size());

        var secondItem = instances.children().get(1);
        assertEquals("stack", secondItem.kind());
        assertEquals("ROW", textMember(secondItem.members(), "direction"));
        assertEquals("1", numberMember(secondItem.members(), "gapMm"));
        assertEquals("STACK", placementType(secondItem));
        assertTrue(secondItem.occurrencePath().contains("[1]"));
        assertEquals("STACK", placementType(secondItem.children().get(0)));
    }

    @Test
    void repeatGeneratedContainersCountTowardStaticNodeLimit() {
        var items = String.join(",", java.util.Collections.nCopies(10_000, "\"x\""));
        var document = repeatDocument(items);

        var outcome = materialize(document, Map.of(), null);

        var failed = assertInstanceOf(Materializer.MaterializationFailed.class, outcome);
        assertEquals(RenderingProblem.ProblemCode.RENDER_DOCUMENT_LIMIT_EXCEEDED,
                failed.problem().code());
        assertEquals("closureAndExpansion.materializedStaticNodes",
                failed.problem().limitId().orElseThrow().value());
    }

    private static String repeatDocument(String items) {
        return "{\"dslVersion\":\"renderweave-design/1.0\","
                + "\"expressionProfile\":\"renderweave-expression/1.0\","
                + "\"displayName\":\"R\",\"definitions\":[],"
                + "\"designRoot\":{\"nodeId\":\"00000000-0000-4000-8000-000000000001\","
                + "\"kind\":\"canvas\",\"widthMm\":210,\"heightMm\":297,\"bindings\":[],"
                + "\"children\":[{\"nodeId\":\"00000000-0000-4000-8000-000000000021\","
                + "\"kind\":\"repeat\",\"bindings\":[],\"placement\":" + absolute() + ","
                + "\"loopId\":\"00000000-0000-4000-8000-0000000000b1\","
                + "\"absentPolicy\":\"ERROR\",\"items\":{\"kind\":\"literal\","
                + "\"valueType\":{\"type\":\"list\",\"items\":\"text\"},"
                + "\"value\":[" + items + "]},"
                + "\"itemLayout\":{\"kind\":\"STACK\",\"direction\":\"ROW\",\"gapMm\":1},"
                + "\"instanceLayout\":{\"kind\":\"STACK\",\"direction\":\"ROW\",\"gapMm\":2},"
                + "\"children\":[" + packRect("00000000-0000-4000-8000-000000000031")
                + "]}]}}";
    }

    private static String packRect(String nodeId) {
        return "{\"nodeId\":\"" + nodeId + "\",\"kind\":\"rect\",\"bindings\":[],"
                + "\"placement\":{\"type\":\"PACK\",\"widthMode\":\"HUG_CONTENT\","
                + "\"heightMode\":\"HUG_CONTENT\"},\"fill\":{\"color\":\"#FF000000\"}}";
    }

    @Test
    void templateUseExpandsCompositionViewport() {
        var childDocument = canvasWith(rect("00000000-0000-4000-8000-000000000041"));
        var rootDocument = canvasWith(
                "{\"nodeId\":\"00000000-0000-4000-8000-000000000051\","
                        + "\"kind\":\"templateUse\",\"bindings\":[],"
                        + "\"useId\":\"00000000-0000-4000-8000-0000000000b2\","
                        + "\"templateRef\":{\"templateId\":\"" + CHILD_ID + "\"},"
                        + "\"contextSelector\":{\"kind\":\"empty\"},\"fills\":[],"
                        + "\"visible\":false,\"opacity\":0.25,"
                        + "\"transform\":{\"rotationDeg\":15,\"scaleX\":1,\"scaleY\":1,"
                        + "\"originX\":0.5,\"originY\":0.5},"
                        + "\"placement\":" + absolute() + "}");
        var rootSnapshot = snapshot(ROOT_ID, canonical(rootDocument));
        var childSnapshot = snapshot(CHILD_ID, canonical(childDocument));
        var closure = new ClosureSnapshot(
                new cn.hbads.renderweave.template.api.TemplateClosureAuthority.OwnerScope("owner-a"),
                new cn.hbads.renderweave.template.api.TemplateApplication.TemplateId(ROOT_ID),
                1,
                List.of(childSnapshot, rootSnapshot),
                List.of());
        var outcome = Materializer.materialize(
                closure,
                TemplateModule.designSemanticAuthority(),
                DESIGNS,
                null,
                absentCapability(),
                admitted(Map.of()),
                new RenderRequestId("00000000-0000-4000-8000-000000000001"),
                new AssetResolutionPort.RendererAudience("test-audience"),
                1_000L);
        var materialized = assertInstanceOf(Materializer.Materialized.class, outcome);
        var viewport = materialized.tree().root().children().get(0);
        assertEquals("compositionViewport", viewport.kind());
        assertEquals(1, viewport.children().size());
        assertEquals("canvas", viewport.children().get(0).kind());
        assertEquals(false, assertInstanceOf(
                cn.hbads.renderweave.template.api.DesignSemanticAuthority.Bool.class,
                viewport.members().members().get("visible")).value());
        assertEquals("0.25", assertInstanceOf(
                cn.hbads.renderweave.template.api.DesignSemanticAuthority.NumberToken.class,
                viewport.members().members().get("opacity")).rawToken());
        var transform = assertInstanceOf(
                cn.hbads.renderweave.template.api.DesignSemanticAuthority.ObjectNode.class,
                viewport.members().members().get("transform"));
        assertEquals("15", assertInstanceOf(
                cn.hbads.renderweave.template.api.DesignSemanticAuthority.NumberToken.class,
                transform.members().get("rotationDeg")).rawToken());
    }

    // ------------------------------------------------------------------
    // bindings + re-admission
    // ------------------------------------------------------------------

    @Test
    void bindingOverlayAppliesAndReadmits() {
        var document = textDocumentWithDefinitionBinding("text", "\"CENTER\"");
        var tree = materializeOk(document,
                Map.of(DEFINITION_ID, new DesignValue.Text("CENTER")),
                new ScriptedAssetPort(true));
        var text = tree.root().children().get(0);
        assertEquals("text", text.kind());
        var align = (cn.hbads.renderweave.template.api.DesignSemanticAuthority.Text)
                text.members().members().get("horizontalAlign");
        assertEquals("CENTER", align.value());
    }

    @Test
    void typeMismatchedBindingFailsMaterialization() {
        var document = textDocumentWithDefinitionBinding("decimal", "42");
        var outcome = materialize(document,
                Map.of(DEFINITION_ID, new DesignValue.Decimal(new java.math.BigDecimal("42"))),
                new ScriptedAssetPort(true));
        var failed = assertInstanceOf(Materializer.MaterializationFailed.class, outcome);
        assertEquals(EvaluationStage.MATERIALIZATION, failed.stage());
    }

    // ------------------------------------------------------------------
    // asset resolution
    // ------------------------------------------------------------------

    @Test
    void assetResolutionSubstitutesResourceIdAndRecordsEntry() {
        var document = canvasWith(imageNode());
        var scripted = new ScriptedAssetPort(true);
        var tree = materializeOk(document, Map.of(), scripted);
        var image = tree.root().children().get(0);
        var imageRef = (cn.hbads.renderweave.template.api.DesignSemanticAuthority.ObjectNode)
                image.members().members().get("imageRef");
        assertTrue(imageRef.members().containsKey("resourceId"));
        assertEquals(1, tree.resources().size());
        assertEquals("IMAGE", tree.resources().get(0).kind());
        assertTrue(scripted.resolves >= 1);
    }

    @Test
    void missingAssetPortFailsClosedAtAssetAdmission() {
        var document = canvasWith(imageNode());
        var outcome = materialize(document, Map.of(), null);
        var failed = assertInstanceOf(Materializer.MaterializationFailed.class, outcome);
        assertEquals(EvaluationStage.ASSET_ADMISSION, failed.stage());
    }

    @Test
    void rejectedAssetFailsAtAssetAdmission() {
        var document = canvasWith(imageNode());
        var outcome = materialize(document, Map.of(), new ScriptedAssetPort(false));
        var failed = assertInstanceOf(Materializer.MaterializationFailed.class, outcome);
        assertEquals(EvaluationStage.ASSET_ADMISSION, failed.stage());
    }

    @Test
    void assetResolutionFailuresKeepTheirFrozenProblemCodes() {
        assertResolutionProblem(
                new AssetResolutionPort.ResolveOutcome.ResolveRejected(
                        AssetResolutionPort.AdmissionRejection.NOT_FOUND),
                RenderingProblem.ProblemCode.ASSET_RESOLVE_NOT_FOUND);
        assertResolutionProblem(
                new AssetResolutionPort.ResolveOutcome.ResolveRejected(
                        AssetResolutionPort.AdmissionRejection.SCOPE_MISMATCH),
                RenderingProblem.ProblemCode.ASSET_RESOLVE_NOT_FOUND);
        assertResolutionProblem(
                new AssetResolutionPort.ResolveOutcome.ResolveRejected(
                        AssetResolutionPort.AdmissionRejection.NOT_ACTIVE),
                RenderingProblem.ProblemCode.ASSET_RESOLVE_DELETED);
        assertResolutionProblem(
                new AssetResolutionPort.ResolveOutcome.ResolveRejected(
                        AssetResolutionPort.AdmissionRejection.KIND_MISMATCH),
                RenderingProblem.ProblemCode.ASSET_RESOLVE_KIND_MISMATCH);
        assertResolutionProblem(
                new AssetResolutionPort.ResolveOutcome.ResolveConflict(),
                RenderingProblem.ProblemCode.RENDER_REQUEST_CONFLICT);
        assertResolutionProblem(
                new AssetResolutionPort.ResolveOutcome.ResolveTimedOut(),
                RenderingProblem.ProblemCode.ASSET_RESOLVE_TIMEOUT);
        assertResolutionProblem(
                new AssetResolutionPort.ResolveOutcome.ResolveUnavailable(),
                RenderingProblem.ProblemCode.ASSET_RESOLVE_UNAVAILABLE);
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static String canvasWith(String children) {
        return "{\"dslVersion\":\"renderweave-design/1.0\","
                + "\"expressionProfile\":\"renderweave-expression/1.0\","
                + "\"displayName\":\"R\",\"definitions\":[],"
                + "\"designRoot\":{\"nodeId\":\"00000000-0000-4000-8000-000000000001\","
                + "\"kind\":\"canvas\",\"widthMm\":210,\"heightMm\":297,"
                + "\"bindings\":[],\"children\":[" + children + "]}}";
    }

    private static String rect(String nodeId) {
        return "{\"nodeId\":\"" + nodeId + "\",\"kind\":\"rect\",\"bindings\":[],"
                + "\"placement\":" + absolute() + ",\"fill\":{\"color\":\"#FF000000\"}}";
    }

    private static String imageNode() {
        return "{\"nodeId\":\"00000000-0000-4000-8000-000000000061\",\"kind\":\"image\","
                + "\"bindings\":[],\"placement\":" + absolute() + ","
                + "\"imageRef\":{\"assetId\":\"" + ASSET_ID + "\"}}";
    }

    private static final String DEFINITION_ID = "00000000-0000-4000-8000-0000000000d1";

    /** binding source 只允许 context/loopIndex/definition（literal 应写成静态值）。 */
    private static String textDocumentWithDefinitionBinding(String valueType, String defaultJson) {
        return "{\"dslVersion\":\"renderweave-design/1.0\","
                + "\"expressionProfile\":\"renderweave-expression/1.0\","
                + "\"displayName\":\"R\",\"definitions\":["
                + "{\"definitionId\":\"" + DEFINITION_ID + "\",\"kind\":\"custom\","
                + "\"displayName\":\"Align\",\"exposure\":\"PRIVATE\","
                + "\"valueType\":\"" + valueType + "\",\"defaultValue\":" + defaultJson + "}"
                + "],\"designRoot\":{\"nodeId\":\"00000000-0000-4000-8000-000000000001\","
                + "\"kind\":\"canvas\",\"widthMm\":210,\"heightMm\":297,\"bindings\":[],"
                + "\"children\":[{\"nodeId\":\"00000000-0000-4000-8000-000000000071\","
                + "\"kind\":\"text\","
                + "\"bindings\":[{\"bindingId\":\"00000000-0000-4000-8000-0000000000e1\","
                + "\"targetPropertyRef\":{\"rootPropertyId\":\"horizontalAlign\",\"selectors\":[]},"
                + "\"source\":{\"kind\":\"definition\",\"definitionId\":\"" + DEFINITION_ID + "\"}}],"
                + "\"placement\":" + absolute() + ","
                + "\"horizontalAlign\":\"LEFT\","
                + "\"runs\":[{\"text\":\"Hi\","
                + "\"fontRef\":{\"assetId\":\"" + ASSET_ID + "\"},"
                + "\"fontSizePt\":12,\"color\":\"#FF000000\",\"decoration\":\"NONE\","
                + "\"letterSpacingPt\":0}]}]}}";
    }

    private static String conditional(boolean flag) {
        return "{\"nodeId\":\"00000000-0000-4000-8000-000000000081\",\"kind\":\"conditional\","
                + "\"bindings\":[],\"condition\":{\"kind\":\"literal\","
                + "\"valueType\":\"boolean\",\"value\":" + flag + "},"
                + "\"absentPolicy\":\"FALSE\",\"placement\":" + absolute() + ","
                + "\"children\":[" + rect("00000000-0000-4000-8000-000000000082") + "]}";
    }

    private static String absolute() {
        return "{\"type\":\"ABSOLUTE\",\"xMm\":0,\"yMm\":0,\"widthMode\":\"FIXED\","
                + "\"widthMm\":10,\"heightMode\":\"FIXED\",\"heightMm\":10}";
    }

    private static String placementType(Materializer.MaterializedNode node) {
        var placement = assertInstanceOf(
                cn.hbads.renderweave.template.api.DesignSemanticAuthority.ObjectNode.class,
                node.members().members().get("placement"));
        return textMember(placement, "type");
    }

    private static String textMember(
            cn.hbads.renderweave.template.api.DesignSemanticAuthority.ObjectNode object,
            String member) {
        return assertInstanceOf(
                cn.hbads.renderweave.template.api.DesignSemanticAuthority.Text.class,
                object.members().get(member)).value();
    }

    private static String numberMember(
            cn.hbads.renderweave.template.api.DesignSemanticAuthority.ObjectNode object,
            String member) {
        return assertInstanceOf(
                cn.hbads.renderweave.template.api.DesignSemanticAuthority.NumberToken.class,
                object.members().get(member)).rawToken();
    }

    private static byte[] canonical(String document) {
        var admission = DESIGNS.admit(document.getBytes(StandardCharsets.UTF_8));
        if (admission instanceof DesignDslAuthority.Rejected rejected) {
            throw new AssertionError(
                    "admission rejected: " + rejected.code() + " @ " + rejected.pointer());
        }
        return ((DesignDslAuthority.Admitted) admission).canonicalUtf8();
    }

    private static TemplateSnapshot snapshot(String templateId, byte[] canonicalBytes) {
        var admission = DESIGNS.admit(canonicalBytes);
        return new TemplateSnapshot(
                new cn.hbads.renderweave.template.api.TemplateApplication.TemplateId(templateId),
                1,
                new cn.hbads.renderweave.template.api.TemplateClosureAuthority.OwnerScope("owner-a"),
                SCHEMA,
                "renderweave-design/1.0",
                "renderweave-expression/1.0",
                canonicalBytes,
                ((DesignDslAuthority.Admitted) admission).contentHash());
    }

    private static AdmittedRenderInput admitted(Map<String, DesignValue> customs) {
        return new AdmittedRenderInput(SCHEMA, new TypedObject(SCHEMA, Map.of()), customs);
    }

    private static Materializer.MaterializedTree materializeOk(
            String document, Map<String, DesignValue> customs, AssetResolutionPort port) {
        var outcome = materialize(document, customs, port);
        return ((Materializer.Materialized) outcome).tree();
    }

    private static Materializer.MaterializationOutcome materialize(
            String document, Map<String, DesignValue> customs, AssetResolutionPort port) {
        var snapshot = snapshot(ROOT_ID, canonical(document));
        var closure = new ClosureSnapshot(
                new cn.hbads.renderweave.template.api.TemplateClosureAuthority.OwnerScope("owner-a"),
                new cn.hbads.renderweave.template.api.TemplateApplication.TemplateId(ROOT_ID),
                1,
                List.of(snapshot),
                List.of());
        return Materializer.materialize(
                closure,
                TemplateModule.designSemanticAuthority(),
                DESIGNS,
                port,
                absentCapability(),
                admitted(customs),
                new RenderRequestId("00000000-0000-4000-8000-000000000001"),
                new AssetResolutionPort.RendererAudience("test-audience"),
                1_000L);
    }

    private static void assertResolutionProblem(
            AssetResolutionPort.ResolveOutcome resolveOutcome,
            RenderingProblem.ProblemCode expectedCode
    ) {
        var port = new AssetResolutionPort() {
            @Override
            public PrecheckOutcome precheckAdmission(
                    OwnerScope ownerScope, AssetId assetId, AssetKind expectedKind) {
                return new PrecheckOutcome.PrecheckPassed();
            }

            @Override
            public ResolveOutcome resolve(ResolveRequest request) {
                return resolveOutcome;
            }
        };
        var outcome = materialize(canvasWith(imageNode()), Map.of(), port);
        var failed = assertInstanceOf(Materializer.MaterializationFailed.class, outcome);
        assertEquals(EvaluationStage.ASSET_RESOLUTION, failed.stage());
        assertEquals(expectedCode, failed.problem().code());
    }

    private static DefinitionEngine.CapabilityProvider absentCapability() {
        return (capability, operation, callPosition) -> new ExpressionEvaluator.EvalError(
                new ExpressionEvaluator.RuntimeFailure(
                        ExpressionEvaluator.RuntimeFailureKind.TYPE_FAULT, null));
    }

    static final class ScriptedAssetPort implements AssetResolutionPort {
        private final boolean pass;
        int prechecks;
        int resolves;

        ScriptedAssetPort(boolean pass) {
            this.pass = pass;
        }

        @Override
        public PrecheckOutcome precheckAdmission(
                OwnerScope ownerScope, AssetId assetId, AssetKind expectedKind) {
            prechecks++;
            return pass
                    ? new AssetResolutionPort.PrecheckOutcome.PrecheckPassed()
                    : new AssetResolutionPort.PrecheckOutcome.PrecheckRejected(
                    AssetResolutionPort.AdmissionRejection.NOT_FOUND);
        }

        @Override
        public ResolveOutcome resolve(ResolveRequest request) {
            resolves++;
            if (!pass) {
                return new AssetResolutionPort.ResolveOutcome.ResolveRejected(
                        AssetResolutionPort.AdmissionRejection.NOT_FOUND);
            }
            return new AssetResolutionPort.ResolveOutcome.Resolved(new AssetResolutionPort.ResolvedAssetFact(
                    "content-version-1",
                    "sha256:" + "b".repeat(64),
                    "image/png",
                    1234,
                    "renderweave-asset-acceptance/1.0",
                    new cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.ImageDescriptor(
                            10, 10,
                            cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.Orientation.IDENTITY,
                            10, 10, 1,
                            cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.ColorEncoding.SRGB_8BIT),
                    "https://assets.internal/fetch/" + request.resourceId().value(),
                    2_000L));
        }
    }
}
