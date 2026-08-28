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
import java.util.ArrayList;
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
        assertEquals(2, tree.sidecar().size());
    }

    @Test
    void diagnosticSidecarItemBudgetIsRequestTotalAndFailsInsteadOfTruncating() {
        var exactCapacity = new RenderingPipelineCapacityGuard().newRequestTracker();
        assertTrue(exactCapacity.reserve(
                RenderingPipelineCapacityGuard.Limit.DIAGNOSTICS_SIDECAR_ITEMS,
                24_999).isEmpty());

        var exact = materialize(
                canvasWith(""), Map.of(), null, absentCapability(), exactCapacity);
        var tree = assertInstanceOf(Materializer.Materialized.class, exact).tree();
        assertEquals(1, tree.sidecar().size());

        var exceededCapacity = new RenderingPipelineCapacityGuard().newRequestTracker();
        assertTrue(exceededCapacity.reserve(
                RenderingPipelineCapacityGuard.Limit.DIAGNOSTICS_SIDECAR_ITEMS,
                25_000).isEmpty());

        var exceeded = materialize(
                canvasWith(""), Map.of(), null, absentCapability(), exceededCapacity);
        var failed = assertInstanceOf(Materializer.MaterializationFailed.class, exceeded);
        assertEquals(EvaluationStage.MATERIALIZATION, failed.stage());
        assertEquals(RenderingProblem.ProblemCode.RENDER_DIAGNOSTIC_LIMIT_EXCEEDED,
                failed.problem().code());
        assertEquals("diagnostics.sidecarItems",
                failed.problem().limitId().orElseThrow().value());
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
    void repeatGridUsesEffectiveColumnsAndCompactsSurvivingCells() {
        var tree = materializeOk(booleanGridRepeat("[true,false,true]"), Map.of(), null);

        assertEquals(1, tree.root().children().size());
        var instances = tree.root().children().get(0);
        assertEquals("grid", instances.kind());
        assertEquals(1, arrayMemberSize(instances.members(), "rows"));
        assertEquals(2, arrayMemberSize(instances.members(), "columns"));
        assertEquals(2, instances.children().size());
        assertTrue(instances.children().get(0).occurrencePath().contains("[0]"));
        assertTrue(instances.children().get(1).occurrencePath().contains("[2]"));
        assertGridCell(instances.children().get(0), 0, 0);
        assertGridCell(instances.children().get(1), 0, 1);

        for (var item : instances.children()) {
            assertEquals("grid", item.kind());
            assertEquals(1, arrayMemberSize(item.members(), "rows"));
            assertEquals(1, arrayMemberSize(item.members(), "columns"));
            assertEquals(1, item.children().size());
            assertGridCell(item.children().get(0), 0, 0);
        }
        assertEquals(11, countGeneratedGridEntries(instances));
    }

    @Test
    void allPrunedRepeatGeneratesNoContainerTrackOrCell() {
        var tree = materializeOk(booleanGridRepeat("[false,false]"), Map.of(), null);

        assertEquals(0, tree.root().children().size());
    }

    @Test
    void repeatItemReservesLogicalOperationBeforePruning() {
        var capacity = new RenderingPipelineCapacityGuard().newRequestTracker();
        assertTrue(capacity.reserve(
                RenderingPipelineCapacityGuard.Limit.LOGICAL_OPERATIONS,
                1_000_000).isEmpty());

        var outcome = materialize(
                booleanGridRepeat("[false,false]"),
                Map.of(),
                null,
                absentCapability(),
                capacity);

        var failed = assertInstanceOf(Materializer.MaterializationFailed.class, outcome);
        assertEquals(EvaluationStage.MATERIALIZATION, failed.stage());
        assertEquals(RenderingProblem.ProblemCode.EVALUATION_BUDGET_EXCEEDED,
                failed.problem().code());
        assertEquals("closureAndExpansion.logicalOperations",
                failed.problem().limitId().orElseThrow().value());
    }

    @Test
    void emptyRepeatConsumesNoLogicalOperation() {
        var capacity = new RenderingPipelineCapacityGuard().newRequestTracker();
        assertTrue(capacity.reserve(
                RenderingPipelineCapacityGuard.Limit.LOGICAL_OPERATIONS,
                1_000_000).isEmpty());

        var outcome = materialize(
                booleanGridRepeat("[]"),
                Map.of(),
                null,
                absentCapability(),
                capacity);

        assertInstanceOf(Materializer.Materialized.class, outcome);
    }

    @Test
    void logicalOperationBudgetStopsBeforeTheNextItemDemand() {
        var capacity = new RenderingPipelineCapacityGuard().newRequestTracker();
        assertTrue(capacity.reserve(
                RenderingPipelineCapacityGuard.Limit.LOGICAL_OPERATIONS,
                999_999).isEmpty());
        var capability = new CapturingCapability();
        var document = capabilityDocument(
                "{\"kind\":\"loop\",\"loopId\":\"" + LOOP_ID + "\"}",
                repeatWithBoundRect("[\"first\",\"second\"]"));

        var outcome = materialize(
                document, Map.of(), null, capability, capacity);

        var failed = assertInstanceOf(Materializer.MaterializationFailed.class, outcome);
        assertEquals(EvaluationStage.MATERIALIZATION, failed.stage());
        assertEquals(RenderingProblem.ProblemCode.EVALUATION_BUDGET_EXCEEDED,
                failed.problem().code());
        assertEquals("closureAndExpansion.logicalOperations",
                failed.problem().limitId().orElseThrow().value());
        assertEquals(1, capability.positions.size());
        assertEquals(loopPosition(0), capability.position(0));
    }

    private static String booleanGridRepeat(String items) {
        var loopId = capacityUuid(2, 900);
        var definitionId = capacityUuid(5, 900);
        var falseResult = "{\"kind\":\"literal\",\"valueType\":\"boolean\","
                + "\"value\":false}";
        var caseWire = "[false,false]".equals(items)
                ? "{\"operator\":\"IS_PRESENT\",\"then\":" + falseResult + "}"
                : "{\"operator\":\"EQ\","
                + "\"operand\":{\"valueType\":\"decimal\",\"value\":1},"
                + "\"then\":" + falseResult + "}";
        var definition = "{\"definitionId\":\"" + definitionId + "\",\"kind\":\"mapping\","
                + "\"displayName\":\"Survival\",\"domain\":{\"kind\":\"loop\","
                + "\"loopId\":\"" + loopId + "\"},\"output\":\"boolean\","
                + "\"input\":{\"kind\":\"loopIndex\",\"loopId\":\"" + loopId + "\"},"
                + "\"cases\":[" + caseWire + "],\"otherwise\":{\"kind\":\"literal\","
                + "\"valueType\":\"boolean\",\"value\":true}}";
        var conditional = "{\"nodeId\":\"" + capacityUuid(3, 900) + "\","
                + "\"kind\":\"conditional\",\"bindings\":[],"
                + "\"condition\":{\"kind\":\"definition\",\"definitionId\":\""
                + definitionId + "\"},"
                + "\"absentPolicy\":\"ERROR\","
                + "\"placement\":{\"type\":\"PACK\",\"widthMode\":\"HUG_CONTENT\","
                + "\"heightMode\":\"HUG_CONTENT\"},"
                + "\"children\":[" + rect(capacityUuid(4, 900)) + "]}";
        var repeat = "{\"nodeId\":\"" + capacityUuid(1, 900) + "\","
                + "\"kind\":\"repeat\",\"bindings\":[],\"placement\":" + absolute() + ","
                + "\"loopId\":\"" + loopId + "\",\"absentPolicy\":\"ERROR\","
                + "\"items\":{\"kind\":\"literal\","
                + "\"valueType\":{\"type\":\"list\",\"items\":\"boolean\"},"
                + "\"value\":" + items + "},"
                + "\"itemLayout\":{\"kind\":\"GRID\",\"columns\":99},"
                + "\"instanceLayout\":{\"kind\":\"GRID\",\"columns\":99},"
                + "\"children\":[" + conditional + "]}";
        return "{\"dslVersion\":\"renderweave-design/1.0\","
                + "\"expressionProfile\":\"renderweave-expression/1.0\","
                + "\"displayName\":\"R\",\"definitions\":[" + definition + "],"
                + "\"designRoot\":{\"nodeId\":\"00000000-0000-4000-8000-000000000001\","
                + "\"kind\":\"canvas\",\"widthMm\":210,\"heightMm\":297,"
                + "\"bindings\":[],\"children\":[" + repeat + "]}}";
    }

    private static void assertGridCell(
            Materializer.MaterializedNode node, int expectedRow, int expectedColumn) {
        var placement = assertInstanceOf(
                cn.hbads.renderweave.template.api.DesignSemanticAuthority.ObjectNode.class,
                node.members().members().get("placement"));
        assertEquals(Integer.toString(expectedRow), numberMember(placement, "row"));
        assertEquals(Integer.toString(expectedColumn), numberMember(placement, "column"));
    }

    private static int arrayMemberSize(
            cn.hbads.renderweave.template.api.DesignSemanticAuthority.ObjectNode object,
            String member) {
        return assertInstanceOf(
                cn.hbads.renderweave.template.api.DesignSemanticAuthority.ArrayNode.class,
                object.members().get(member)).items().size();
    }

    private static int countGeneratedGridEntries(Materializer.MaterializedNode node) {
        var count = 0;
        if ("grid".equals(node.kind())) {
            count += arrayMemberSize(node.members(), "rows");
            count += arrayMemberSize(node.members(), "columns");
            count += node.children().size();
        }
        for (var child : node.children()) {
            count += countGeneratedGridEntries(child);
        }
        return count;
    }

    @Test
    void materializedStaticNodesBelowLimitAreAccepted() {
        var tree = materializeOk(
                repeatDocument(materializedNodeBoundaryItemLists(994)), Map.of(), null);

        assertEquals(19_999, countNodes(tree.root()));
    }

    @Test
    void materializedStaticNodesAtLimitAreAccepted() {
        var tree = materializeOk(
                repeatDocument(
                        materializedNodeBoundaryItemLists(994),
                        rect(capacityUuid(4, 1))),
                Map.of(), null);

        assertEquals(20_000, countNodes(tree.root()));
    }

    @Test
    void repeatGeneratedContainersAboveStaticNodeLimitAreRejected() {
        var document = repeatDocument(materializedNodeBoundaryItemLists(995));

        var outcome = materialize(document, Map.of(), null);

        var failed = assertInstanceOf(Materializer.MaterializationFailed.class, outcome);
        assertEquals(EvaluationStage.MATERIALIZATION, failed.stage());
        assertEquals(RenderingProblem.ProblemCode.EVALUATION_BUDGET_EXCEEDED,
                failed.problem().code());
        assertEquals("closureAndExpansion.materializedStaticNodes",
                failed.problem().limitId().orElseThrow().value());
    }

    private static List<String> materializedNodeBoundaryItemLists(int finalItemCount) {
        var atCollectionLimit = String.join(",",
                java.util.Collections.nCopies(1_000, "\"x\""));
        var finalCollection = String.join(",",
                java.util.Collections.nCopies(finalItemCount, "\"x\""));
        var itemLists = new ArrayList<String>();
        itemLists.addAll(java.util.Collections.nCopies(9, atCollectionLimit));
        itemLists.add(finalCollection);
        return List.copyOf(itemLists);
    }

    private static String repeatDocument(List<String> itemLists) {
        return repeatDocument(itemLists, null);
    }

    private static String repeatDocument(List<String> itemLists, String trailingNode) {
        var repeats = new ArrayList<String>(itemLists.size());
        for (var index = 0; index < itemLists.size(); index++) {
            repeats.add(repeatNode(index, itemLists.get(index)));
        }
        if (trailingNode != null) {
            repeats.add(trailingNode);
        }
        return "{\"dslVersion\":\"renderweave-design/1.0\","
                + "\"expressionProfile\":\"renderweave-expression/1.0\","
                + "\"displayName\":\"R\",\"definitions\":[],"
                + "\"designRoot\":{\"nodeId\":\"00000000-0000-4000-8000-000000000001\","
                + "\"kind\":\"canvas\",\"widthMm\":210,\"heightMm\":297,\"bindings\":[],"
                + "\"children\":[" + String.join(",", repeats) + "]}}";
    }

    private static int countNodes(Materializer.MaterializedNode node) {
        var count = 1;
        for (var child : node.children()) {
            count += countNodes(child);
        }
        return count;
    }

    private static String repeatNode(int index, String items) {
        return "{\"nodeId\":\"" + capacityUuid(1, index) + "\","
                + "\"kind\":\"repeat\",\"bindings\":[],\"placement\":" + absolute() + ","
                + "\"loopId\":\"" + capacityUuid(2, index) + "\","
                + "\"absentPolicy\":\"ERROR\",\"items\":{\"kind\":\"literal\","
                + "\"valueType\":{\"type\":\"list\",\"items\":\"text\"},"
                + "\"value\":[" + items + "]},"
                + "\"itemLayout\":{\"kind\":\"STACK\",\"direction\":\"ROW\",\"gapMm\":1},"
                + "\"instanceLayout\":{\"kind\":\"STACK\",\"direction\":\"ROW\",\"gapMm\":2},"
                + "\"children\":[" + packRect(capacityUuid(3, index)) + "]}";
    }

    private static String capacityUuid(int namespace, int ordinal) {
        return String.format(java.util.Locale.ROOT,
                "%d0000000-0000-4000-8000-%012x", namespace, ordinal);
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
        var outcome = admitThenMaterialize(
                closure,
                null,
                absentCapability(),
                admitted(Map.of()));
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

    @Test
    void invocationCapabilityMemoIgnoresDownstreamRepeatConsumer() {
        var capability = new CapturingCapability();
        var document = capabilityDocument(
                "\"invocation\"",
                repeatWithBoundRect("[\"a\",\"b\"]"));

        var outcome = materialize(document, Map.of(), null, capability);

        assertInstanceOf(Materializer.Materialized.class, outcome);
        assertEquals(1, capability.positions.size());
        assertEquals(rootPosition(), capability.position(0));
    }

    @Test
    void loopCapabilityUsesOriginalInputIndexPerDeclarationFrame() {
        var capability = new CapturingCapability();
        var document = capabilityDocument(
                "{\"kind\":\"loop\",\"loopId\":\"" + LOOP_ID + "\"}",
                repeatWithBoundRect("[\"duplicate\",\"duplicate\"]"));

        var outcome = materialize(document, Map.of(), null, capability);

        assertInstanceOf(Materializer.Materialized.class, outcome);
        assertEquals(2, capability.positions.size());
        assertEquals(loopPosition(0), capability.position(0));
        assertEquals(loopPosition(1), capability.position(1));
    }

    @Test
    void childInvocationCapabilityIsIsolatedByUseId() {
        var capability = new CapturingCapability();
        var childDocument = capabilityDocument(
                "\"invocation\"",
                boundRect("00000000-0000-4000-8000-000000000091", absolute()));
        var rootDocument = canvasWith(
                templateUse(USE_ONE) + "," + templateUse(USE_TWO));
        var rootSnapshot = snapshot(ROOT_ID, canonical(rootDocument));
        var childSnapshot = snapshot(CHILD_ID, canonical(childDocument));
        var closure = new ClosureSnapshot(
                new cn.hbads.renderweave.template.api.TemplateClosureAuthority.OwnerScope("owner-a"),
                new cn.hbads.renderweave.template.api.TemplateApplication.TemplateId(ROOT_ID),
                1,
                List.of(childSnapshot, rootSnapshot),
                List.of());

        var outcome = admitThenMaterialize(
                closure,
                null,
                capability,
                admitted(Map.of()));

        assertInstanceOf(Materializer.Materialized.class, outcome);
        assertEquals(2, capability.positions.size());
        assertEquals(usePosition(USE_ONE), capability.position(0));
        assertEquals(usePosition(USE_TWO), capability.position(1));
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
    void actualResolveOccurrenceBudgetStopsBeforeTheNextResolverOperation() {
        var document = canvasWith(
                imageNode("00000000-0000-4000-8000-000000000061") + ","
                        + imageNode("00000000-0000-4000-8000-000000000062"));
        var exactCapacity = new RenderingPipelineCapacityGuard().newRequestTracker();
        assertTrue(exactCapacity.reserve(
                RenderingPipelineCapacityGuard.Limit
                        .ASSETS_AND_FETCH_ACTUAL_RESOLVE_OCCURRENCES,
                2_046).isEmpty());
        var exactPort = new ScriptedAssetPort(true);

        var exact = materialize(
                document, Map.of(), exactPort, absentCapability(), exactCapacity);

        var exactTree = assertInstanceOf(Materializer.Materialized.class, exact).tree();
        assertEquals(2, exactPort.resolves);
        assertEquals(2, exactTree.resources().size());

        var exceededCapacity = new RenderingPipelineCapacityGuard().newRequestTracker();
        assertTrue(exceededCapacity.reserve(
                RenderingPipelineCapacityGuard.Limit
                        .ASSETS_AND_FETCH_ACTUAL_RESOLVE_OCCURRENCES,
                2_047).isEmpty());
        var exceededPort = new ScriptedAssetPort(true);

        var exceeded = materialize(
                document, Map.of(), exceededPort, absentCapability(), exceededCapacity);

        var failed = assertInstanceOf(Materializer.MaterializationFailed.class, exceeded);
        assertEquals(EvaluationStage.ASSET_RESOLUTION, failed.stage());
        assertEquals(RenderingProblem.ProblemCode.RESOURCE_BUDGET_EXCEEDED,
                failed.problem().code());
        assertEquals("assetsAndFetch.actualResolveOccurrences",
                failed.problem().limitId().orElseThrow().value());
        assertEquals(1, exceededPort.resolves);
    }

    @Test
    void renderResourceEntryBudgetStopsAfterResolveAndBeforeAppend() {
        var document = canvasWith(
                imageNode("00000000-0000-4000-8000-000000000061") + ","
                        + imageNode("00000000-0000-4000-8000-000000000062"));
        var exactCapacity = new RenderingPipelineCapacityGuard().newRequestTracker();
        assertTrue(exactCapacity.reserve(
                RenderingPipelineCapacityGuard.Limit
                        .ASSETS_AND_FETCH_RENDER_RESOURCE_ENTRIES,
                2_046).isEmpty());
        var exactPort = new ScriptedAssetPort(true);

        var exact = materialize(
                document, Map.of(), exactPort, absentCapability(), exactCapacity);

        var exactTree = assertInstanceOf(Materializer.Materialized.class, exact).tree();
        assertEquals(2, exactPort.resolves);
        assertEquals(2, exactTree.resources().size());

        var exceededCapacity = new RenderingPipelineCapacityGuard().newRequestTracker();
        assertTrue(exceededCapacity.reserve(
                RenderingPipelineCapacityGuard.Limit
                        .ASSETS_AND_FETCH_RENDER_RESOURCE_ENTRIES,
                2_047).isEmpty());
        var exceededPort = new ScriptedAssetPort(true);

        var exceeded = materialize(
                document, Map.of(), exceededPort, absentCapability(), exceededCapacity);

        var failed = assertInstanceOf(Materializer.MaterializationFailed.class, exceeded);
        assertEquals(EvaluationStage.ASSET_RESOLUTION, failed.stage());
        assertEquals(RenderingProblem.ProblemCode.RESOURCE_BUDGET_EXCEEDED,
                failed.problem().code());
        assertEquals("assetsAndFetch.renderResourceEntries",
                failed.problem().limitId().orElseThrow().value());
        assertEquals(2, exceededPort.resolves);
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
        return imageNode("00000000-0000-4000-8000-000000000061");
    }

    private static String imageNode(String nodeId) {
        return "{\"nodeId\":\"" + nodeId + "\",\"kind\":\"image\","
                + "\"bindings\":[],\"placement\":" + absolute() + ","
                + "\"imageRef\":{\"assetId\":\"" + ASSET_ID + "\"}}";
    }

    private static final String DEFINITION_ID = "00000000-0000-4000-8000-0000000000d1";
    private static final String LOOP_ID = "00000000-0000-4000-8000-0000000000b1";
    private static final String USE_ONE = "00000000-0000-4000-8000-0000000000e1";
    private static final String USE_TWO = "00000000-0000-4000-8000-0000000000e2";

    private static String capabilityDocument(String domain, String children) {
        return "{\"dslVersion\":\"renderweave-design/1.0\","
                + "\"expressionProfile\":\"renderweave-expression/1.0\","
                + "\"displayName\":\"Capability\",\"definitions\":["
                + "{\"definitionId\":\"" + DEFINITION_ID + "\",\"kind\":\"expression\","
                + "\"displayName\":\"Draw\",\"domain\":" + domain + ","
                + "\"output\":\"decimal\",\"inputs\":[{\"alias\":\"draw\","
                + "\"source\":{\"kind\":\"capability\",\"capability\":\"RANDOM\","
                + "\"operation\":\"UNIFORM_DECIMAL_0_1\"}}],\"source\":\"input.draw\"}],"
                + "\"designRoot\":{\"nodeId\":\"00000000-0000-4000-8000-000000000001\","
                + "\"kind\":\"canvas\",\"widthMm\":210,\"heightMm\":297,"
                + "\"bindings\":[],\"children\":[" + children + "]}}";
    }

    private static String repeatWithBoundRect(String items) {
        return "{\"nodeId\":\"00000000-0000-4000-8000-000000000092\","
                + "\"kind\":\"repeat\",\"bindings\":[],\"placement\":" + absolute() + ","
                + "\"loopId\":\"" + LOOP_ID + "\",\"absentPolicy\":\"ERROR\","
                + "\"items\":{\"kind\":\"literal\","
                + "\"valueType\":{\"type\":\"list\",\"items\":\"text\"},"
                + "\"value\":" + items + "},"
                + "\"itemLayout\":{\"kind\":\"STACK\",\"direction\":\"ROW\"},"
                + "\"instanceLayout\":{\"kind\":\"STACK\",\"direction\":\"ROW\"},"
                + "\"children\":[" + boundRect(
                        "00000000-0000-4000-8000-000000000093", packFixed()) + "]}";
    }

    private static String boundRect(String nodeId, String placement) {
        return "{\"nodeId\":\"" + nodeId + "\",\"kind\":\"rect\","
                + "\"bindings\":[{\"bindingId\":\"00000000-0000-4000-8000-0000000000f1\","
                + "\"targetPropertyRef\":{\"rootPropertyId\":\"placement\","
                + "\"selectors\":[{\"kind\":\"member\",\"name\":\"widthMm\"}]},"
                + "\"source\":{\"kind\":\"definition\",\"definitionId\":\""
                + DEFINITION_ID + "\"}}],\"placement\":" + placement + ","
                + "\"fill\":{\"color\":\"#FF000000\"}}";
    }

    private static String packFixed() {
        return "{\"type\":\"PACK\",\"widthMode\":\"FIXED\",\"widthMm\":10,"
                + "\"heightMode\":\"FIXED\",\"heightMm\":10}";
    }

    private static String templateUse(String useId) {
        return "{\"nodeId\":\"" + useId + "\",\"kind\":\"templateUse\","
                + "\"bindings\":[],\"useId\":\"" + useId + "\","
                + "\"templateRef\":{\"templateId\":\"" + CHILD_ID + "\"},"
                + "\"contextSelector\":{\"kind\":\"empty\"},\"fills\":[],"
                + "\"placement\":" + absolute() + "}";
    }

    private static String rootPosition() {
        return position("[{\"kind\":\"ROOT\",\"revision\":1,\"templateId\":\""
                + ROOT_ID + "\"}]");
    }

    private static String loopPosition(int inputIndex) {
        return position("[{\"kind\":\"ROOT\",\"revision\":1,\"templateId\":\""
                + ROOT_ID + "\"},{\"inputIndex\":" + inputIndex
                + ",\"kind\":\"REPEAT\",\"loopId\":\"" + LOOP_ID + "\"}]");
    }

    private static String usePosition(String useId) {
        return position("[{\"kind\":\"ROOT\",\"revision\":1,\"templateId\":\""
                + ROOT_ID + "\"},{\"kind\":\"TEMPLATE_USE\",\"revision\":1,"
                + "\"templateId\":\"" + CHILD_ID + "\",\"useId\":\"" + useId + "\"}]");
    }

    private static String position(String path) {
        return "{\"capabilityContractId\":\"renderweave-capability-random/1.0\","
                + "\"definitionId\":\"" + DEFINITION_ID + "\","
                + "\"inputAlias\":\"draw\",\"operation\":\"UNIFORM_DECIMAL_0_1\","
                + "\"path\":" + path + ","
                + "\"positionVersion\":\"renderweave-capability-call-position/1.0\"}";
    }

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
        return new AdmittedRenderInput(
                SCHEMA, new TypedObject(SCHEMA, Map.of()), customs, Map.of());
    }

    private static Materializer.MaterializedTree materializeOk(
            String document, Map<String, DesignValue> customs, AssetResolutionPort port) {
        var outcome = materialize(document, customs, port);
        return assertInstanceOf(
                Materializer.Materialized.class, outcome, () -> "outcome=" + outcome).tree();
    }

    private static Materializer.MaterializationOutcome materialize(
            String document, Map<String, DesignValue> customs, AssetResolutionPort port) {
        return materialize(document, customs, port, absentCapability());
    }

    private static Materializer.MaterializationOutcome materialize(
            String document,
            Map<String, DesignValue> customs,
            AssetResolutionPort port,
            DefinitionEngine.CapabilityProvider capability
    ) {
        return materialize(document, customs, port, capability, null);
    }

    private static Materializer.MaterializationOutcome materialize(
            String document,
            Map<String, DesignValue> customs,
            AssetResolutionPort port,
            DefinitionEngine.CapabilityProvider capability,
            RenderingPipelineCapacityGuard.RequestTracker requestCapacity
    ) {
        var snapshot = snapshot(ROOT_ID, canonical(document));
        var closure = new ClosureSnapshot(
                new cn.hbads.renderweave.template.api.TemplateClosureAuthority.OwnerScope("owner-a"),
                new cn.hbads.renderweave.template.api.TemplateApplication.TemplateId(ROOT_ID),
                1,
                List.of(snapshot),
                List.of());
        return admitThenMaterialize(
                closure,
                port,
                capability,
                admitted(customs),
                requestCapacity);
    }

    private static Materializer.MaterializationOutcome admitThenMaterialize(
            ClosureSnapshot closure,
            AssetResolutionPort port,
            DefinitionEngine.CapabilityProvider capability,
            AdmittedRenderInput input
    ) {
        return admitThenMaterialize(closure, port, capability, input, null);
    }

    private static Materializer.MaterializationOutcome admitThenMaterialize(
            ClosureSnapshot closure,
            AssetResolutionPort port,
            DefinitionEngine.CapabilityProvider capability,
            AdmittedRenderInput input,
            RenderingPipelineCapacityGuard.RequestTracker requestCapacity
    ) {
        var admission = AssetAdmission.admit(
                closure,
                TemplateModule.designSemanticAuthority(),
                port,
                input,
                cn.hbads.renderweave.rendering.api.Evaluator
                        .ExternalAssetReadAuthorization.GRANTED);
        if (admission instanceof AssetAdmission.Rejected rejected) {
            return new Materializer.MaterializationFailed(rejected.stage(), rejected.problem());
        }
        if (requestCapacity == null) {
            return Materializer.materialize(
                    (AssetAdmission.Admitted) admission,
                    closure,
                    TemplateModule.designSemanticAuthority(),
                    DESIGNS,
                    port,
                    capability,
                    input,
                    new RenderRequestId("00000000-0000-4000-8000-000000000001"),
                    new AssetResolutionPort.RendererAudience("test-audience"),
                    1_000L);
        }
        return Materializer.materialize(
                (AssetAdmission.Admitted) admission,
                closure,
                TemplateModule.designSemanticAuthority(),
                DESIGNS,
                port,
                capability,
                input,
                new RenderRequestId("00000000-0000-4000-8000-000000000001"),
                new AssetResolutionPort.RendererAudience("test-audience"),
                1_000L,
                requestCapacity);
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

    static final class CapturingCapability implements DefinitionEngine.CapabilityProvider {
        private final List<byte[]> positions = new ArrayList<>();

        @Override
        public EvalOutcome supply(String capability, String operation, byte[] callPosition) {
            positions.add(callPosition.clone());
            return new EvalValue(new DesignValue.Decimal(new java.math.BigDecimal("5")));
        }

        String position(int index) {
            return new String(positions.get(index), StandardCharsets.UTF_8);
        }
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
