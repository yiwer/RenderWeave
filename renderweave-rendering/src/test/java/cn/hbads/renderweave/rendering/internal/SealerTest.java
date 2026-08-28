package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.rendering.api.EvaluationStage;
import cn.hbads.renderweave.rendering.api.RenderingProblem.ProblemCode;
import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.schema.identity.SchemaKey;
import cn.hbads.renderweave.schema.identity.VersionTag;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.ArrayNode;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.Bool;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.DesignNodeValue;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.NumberToken;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.ObjectNode;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.Text;
import cn.hbads.renderweave.template.api.TemplateClosureAuthority.ClosureSnapshot;
import cn.hbads.renderweave.template.api.TemplateClosureAuthority.TemplateSnapshot;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SealerTest {

    private static final StaticSchemaRef SCHEMA = new StaticSchemaRef(
            SchemaKey.systemProvided("system-empty"), VersionTag.of("v1"));
    private static final String MINIMAL_RENDER_DOCUMENT =
            "{\"canvas\":{\"backgroundColor\":\"#00000000\",\"bleed\":{\"bottomPt\":0,"
                    + "\"leftPt\":0,\"rightPt\":0,\"topPt\":0},\"children\":[],"
                    + "\"heightPt\":841.889764,\"kind\":\"canvas\","
                    + "\"occurrenceId\":\"rwocc_0000000000000000\",\"widthPt\":595.275591},"
                    + "\"dslVersion\":\"renderweave-render/1.0\","
                    + "\"layoutProfile\":\"renderweave-layout/1.0\",\"resources\":[]}";

    @Test
    void sealProducesEnvelopeWithOccurrenceIdsAndPtQuantization() {
        var rect = new Materializer.MaterializedNode(
                "rect",
                new ObjectNode(Map.of(
                        "kind", new Text("rect"),
                        "nodeId", new Text("00000000-0000-4000-8000-000000000011"),
                        "fill", new ObjectNode(Map.of("color", new Text("#FF000000"))),
                        "placement", absoluteFixedPlacement("10", "10"))),
                List.of(),
                "/rect");
        var canvas = new Materializer.MaterializedNode(
                "canvas",
                new ObjectNode(Map.of(
                        "kind", new Text("canvas"),
                        "nodeId", new Text("00000000-0000-4000-8000-000000000001"),
                        "widthMm", new NumberToken("210"),
                        "heightMm", new NumberToken("297"))),
                List.of(rect),
                "");
        var tree = new Materializer.MaterializedTree(canvas, List.of(), List.of());

        var sealOutcome = Sealer.seal(closure(), admitted(), tree, "sha256:" + "c".repeat(64));
        if (sealOutcome instanceof Sealer.SealRejected rejected) {
            throw new AssertionError("seal rejected: "
                    + rejected.problem().limitId().orElseThrow().value());
        }
        var sealed = (Sealer.Sealed) sealOutcome;

        var document = new String(
                sealed.evaluation().renderDocumentCanonicalUtf8(), StandardCharsets.UTF_8);
        assertTrue(document.contains("\"dslVersion\":\"renderweave-render/1.0\""));
        assertTrue(document.contains("\"layoutProfile\":\"renderweave-layout/1.0\""));
        assertTrue(document.contains("\"occurrenceId\":\"rwocc_0000000000000000\""));
        assertTrue(document.contains("\"occurrenceId\":\"rwocc_0000000000000001\""));
        // 10mm × 360/127 → 28.346457 (HALF_EVEN ≤6 位)
        assertTrue(document.contains("\"widthPt\":\"28.346457\"")
                || document.contains("\"widthPt\":28.346457")
                || document.contains("28.346457"));
        // 210mm → 595.275591
        assertTrue(document.contains("595.275591"));
        // authored-only 成员被移除
        assertTrue(!document.contains("nodeId"));
        assertTrue(!document.contains("displayName"));
        assertTrue(!document.contains("bindings"));
        assertTrue(sealed.evaluation().renderDocumentDigest().startsWith("sha256:"));
        assertTrue(sealed.evaluation().evaluationResultDigest().startsWith("sha256:"));
    }

    @Test
    void viewportAssignsSourceCanvasOccurrenceBeforeItsChildren() {
        var leaf = new Materializer.MaterializedNode(
                "rect",
                new ObjectNode(Map.of(
                        "kind", new Text("rect"),
                        "placement", absoluteFixedPlacement("10", "10"),
                        "fill", new ObjectNode(Map.of("color", new Text("#FF000000"))))),
                List.of(),
                "/use/leaf");
        var childCanvas = new Materializer.MaterializedNode(
                "canvas",
                new ObjectNode(Map.of(
                        "kind", new Text("canvas"),
                        "widthMm", new NumberToken("100"),
                        "heightMm", new NumberToken("50"))),
                List.of(leaf),
                "/use");
        var viewport = new Materializer.MaterializedNode(
                "compositionViewport",
                new ObjectNode(Map.of(
                        "kind", new Text("compositionViewport"),
                        "placement", absoluteFixedPlacement("100", "50"))),
                List.of(childCanvas),
                "/use");
        var canvas = new Materializer.MaterializedNode(
                "canvas",
                new ObjectNode(Map.of(
                        "kind", new Text("canvas"),
                        "widthMm", new NumberToken("210"),
                        "heightMm", new NumberToken("297"))),
                List.of(viewport),
                "");
        var tree = new Materializer.MaterializedTree(canvas, List.of(), List.of());

        var sealed = assertInstanceOf(Sealer.Sealed.class, Sealer.seal(
                closure(), admitted(), tree, "sha256:" + "c".repeat(64)));

        var document = new String(
                sealed.evaluation().renderDocumentCanonicalUtf8(), StandardCharsets.UTF_8);
        // canonical member 排序使 children 先于 occurrenceId 输出；byte 序为
        // viewport(01) → leaf(03) → sourceCanvas(02) → 根 canvas(00)。
        var matcher = java.util.regex.Pattern
                .compile("\"occurrenceId\":\"(rwocc_[0-9a-f]{16})\"")
                .matcher(document);
        var sequence = new java.util.ArrayList<String>();
        while (matcher.find()) {
            sequence.add(matcher.group(1));
        }
        assertEquals(List.of(
                "rwocc_0000000000000001",
                "rwocc_0000000000000003",
                "rwocc_0000000000000002",
                "rwocc_0000000000000000"), sequence);
        assertTrue(document.contains("\"sourceCanvas\""));
    }

    @Test
    void digestsAreDeterministicAndSensitive() {
        var canvas = new Materializer.MaterializedNode(
                "canvas",
                new ObjectNode(Map.of(
                        "kind", new Text("canvas"),
                        "widthMm", new NumberToken("210"),
                        "heightMm", new NumberToken("297"))),
                List.of(),
                "");
        var tree = new Materializer.MaterializedTree(canvas, List.of(), List.of());

        var first = assertInstanceOf(Sealer.Sealed.class, Sealer.seal(
                closure(), admitted(), tree, "sha256:" + "c".repeat(64)));
        var second = assertInstanceOf(Sealer.Sealed.class, Sealer.seal(
                closure(), admitted(), tree, "sha256:" + "c".repeat(64)));
        var differentCapability = assertInstanceOf(Sealer.Sealed.class, Sealer.seal(
                closure(), admitted(), tree, "sha256:" + "d".repeat(64)));

        assertEquals(first.evaluation().renderDocumentDigest(),
                second.evaluation().renderDocumentDigest());
        assertEquals(first.evaluation().evaluationResultDigest(),
                second.evaluation().evaluationResultDigest());
        assertEquals(first.evaluation().renderDocumentDigest(),
                differentCapability.evaluation().renderDocumentDigest());
        assertNotEquals(first.evaluation().evaluationResultDigest(),
                differentCapability.evaluation().evaluationResultDigest());
    }

    @Test
    void selectionDigestIsOrderSensitive() {
        var entryA = entry("rwres_" + "a".repeat(64), "asset-a");
        var entryB = entry("rwres_" + "b".repeat(64), "asset-b");
        var digestAb = Sealer.assetSelectionDigest(List.of(entryA, entryB));
        var digestBa = Sealer.assetSelectionDigest(List.of(entryB, entryA));
        assertNotEquals(digestAb, digestBa);
        assertTrue(digestAb.startsWith("sha256:"));
    }

    @Test
    void canonicalWriterCommitsTheFrozenDocumentAtTheInclusiveByteBudget() {
        var expected = MINIMAL_RENDER_DOCUMENT.getBytes(StandardCharsets.UTF_8);
        assertEquals(305, expected.length);
        var capacity = new RenderingPipelineCapacityGuard().newRequestTracker();
        assertTrue(capacity.reserve(
                RenderingPipelineCapacityGuard.Limit.RENDER_DOCUMENT_CANONICAL_BYTES,
                67_108_864L - expected.length).isEmpty());

        var outcome = Sealer.seal(
                closure(), admitted(), minimalTree(), "sha256:" + "c".repeat(64), capacity);

        var sealed = assertInstanceOf(Sealer.Sealed.class, outcome);
        assertArrayEquals(expected, sealed.evaluation().renderDocumentCanonicalUtf8());
    }

    @Test
    void canonicalWriterRejectsAboveTheByteBudgetWithoutASealedDocument() {
        var expectedBytes = MINIMAL_RENDER_DOCUMENT.getBytes(StandardCharsets.UTF_8).length;
        var capacity = new RenderingPipelineCapacityGuard().newRequestTracker();
        assertTrue(capacity.reserve(
                RenderingPipelineCapacityGuard.Limit.RENDER_DOCUMENT_CANONICAL_BYTES,
                67_108_864L - expectedBytes + 1).isEmpty());

        var outcome = Sealer.seal(
                closure(), admitted(), minimalTree(), "sha256:" + "c".repeat(64), capacity);

        var rejected = assertInstanceOf(Sealer.SealRejected.class, outcome);
        assertEquals(EvaluationStage.DOCUMENT_SEAL, rejected.problem().stage());
        assertEquals(ProblemCode.RENDER_DOCUMENT_LIMIT_EXCEEDED, rejected.problem().code());
        assertEquals("renderDocument.canonicalBytes",
                rejected.problem().limitId().orElseThrow().value());
    }

    @Test
    void staticNodeCounterAdmitsTheRootAtAndRejectsItAboveTheInclusiveBudget() {
        var atCapacity = new RenderingPipelineCapacityGuard().newRequestTracker();
        assertTrue(atCapacity.reserve(
                RenderingPipelineCapacityGuard.Limit.RENDER_DOCUMENT_STATIC_NODES,
                19_999).isEmpty());
        assertInstanceOf(Sealer.Sealed.class, Sealer.seal(
                closure(), admitted(), minimalTree(), "sha256:" + "c".repeat(64),
                atCapacity));

        var aboveCapacity = new RenderingPipelineCapacityGuard().newRequestTracker();
        assertTrue(aboveCapacity.reserve(
                RenderingPipelineCapacityGuard.Limit.RENDER_DOCUMENT_STATIC_NODES,
                20_000).isEmpty());
        var rejected = assertInstanceOf(Sealer.SealRejected.class, Sealer.seal(
                closure(), admitted(), minimalTree(), "sha256:" + "c".repeat(64),
                aboveCapacity));
        assertStaticNodeLimit(rejected);
    }

    @Test
    void compositionViewportSourceCanvasConsumesItsOwnStaticNodeUnit() {
        var atCapacity = new RenderingPipelineCapacityGuard().newRequestTracker();
        assertTrue(atCapacity.reserve(
                RenderingPipelineCapacityGuard.Limit.RENDER_DOCUMENT_STATIC_NODES,
                19_997).isEmpty());
        assertInstanceOf(Sealer.Sealed.class, Sealer.seal(
                closure(), admitted(), viewportTree(), "sha256:" + "c".repeat(64),
                atCapacity));

        var aboveCapacity = new RenderingPipelineCapacityGuard().newRequestTracker();
        assertTrue(aboveCapacity.reserve(
                RenderingPipelineCapacityGuard.Limit.RENDER_DOCUMENT_STATIC_NODES,
                19_998).isEmpty());
        var rejected = assertInstanceOf(Sealer.SealRejected.class, Sealer.seal(
                closure(), admitted(), viewportTree(), "sha256:" + "c".repeat(64),
                aboveCapacity));
        assertStaticNodeLimit(rejected);
    }

    @Test
    void childEdgeCounterLeavesTheRootAndEmptyArrayUncharged() {
        var emptyAtCapacity = new RenderingPipelineCapacityGuard().newRequestTracker();
        assertTrue(emptyAtCapacity.reserve(
                RenderingPipelineCapacityGuard.Limit.RENDER_DOCUMENT_CHILD_EDGES,
                19_999).isEmpty());
        assertTrue(emptyAtCapacity.reserve(
                RenderingPipelineCapacityGuard.Limit.RENDER_DOCUMENT_STATIC_NODES,
                19_999).isEmpty());
        assertInstanceOf(Sealer.Sealed.class, Sealer.seal(
                closure(), admitted(), minimalTree(), "sha256:" + "c".repeat(64),
                emptyAtCapacity));

        var atCapacity = new RenderingPipelineCapacityGuard().newRequestTracker();
        assertTrue(atCapacity.reserve(
                RenderingPipelineCapacityGuard.Limit.RENDER_DOCUMENT_CHILD_EDGES,
                19_998).isEmpty());
        assertTrue(atCapacity.reserve(
                RenderingPipelineCapacityGuard.Limit.RENDER_DOCUMENT_STATIC_NODES,
                19_998).isEmpty());
        assertInstanceOf(Sealer.Sealed.class, Sealer.seal(
                closure(), admitted(), oneChildTree(), "sha256:" + "c".repeat(64),
                atCapacity));

        var aboveCapacity = new RenderingPipelineCapacityGuard().newRequestTracker();
        assertTrue(aboveCapacity.reserve(
                RenderingPipelineCapacityGuard.Limit.RENDER_DOCUMENT_CHILD_EDGES,
                19_999).isEmpty());
        assertTrue(aboveCapacity.reserve(
                RenderingPipelineCapacityGuard.Limit.RENDER_DOCUMENT_STATIC_NODES,
                19_999).isEmpty());
        var rejected = assertInstanceOf(Sealer.SealRejected.class, Sealer.seal(
                closure(), admitted(), oneChildTree(), "sha256:" + "c".repeat(64),
                aboveCapacity));
        assertChildEdgeLimit(rejected);
    }

    @Test
    void compositionViewportSourceCanvasConsumesItsOwnChildEdgeUnit() {
        var atCapacity = new RenderingPipelineCapacityGuard().newRequestTracker();
        assertTrue(atCapacity.reserve(
                RenderingPipelineCapacityGuard.Limit.RENDER_DOCUMENT_CHILD_EDGES,
                19_997).isEmpty());
        assertTrue(atCapacity.reserve(
                RenderingPipelineCapacityGuard.Limit.RENDER_DOCUMENT_STATIC_NODES,
                19_997).isEmpty());
        assertInstanceOf(Sealer.Sealed.class, Sealer.seal(
                closure(), admitted(), viewportTree(), "sha256:" + "c".repeat(64),
                atCapacity));

        var aboveCapacity = new RenderingPipelineCapacityGuard().newRequestTracker();
        assertTrue(aboveCapacity.reserve(
                RenderingPipelineCapacityGuard.Limit.RENDER_DOCUMENT_CHILD_EDGES,
                19_998).isEmpty());
        assertTrue(aboveCapacity.reserve(
                RenderingPipelineCapacityGuard.Limit.RENDER_DOCUMENT_STATIC_NODES,
                19_998).isEmpty());
        var rejected = assertInstanceOf(Sealer.SealRejected.class, Sealer.seal(
                closure(), admitted(), viewportTree(), "sha256:" + "c".repeat(64),
                aboveCapacity));
        assertChildEdgeLimit(rejected);
    }

    @Test
    void finalTextRunsUseTheRequestTotalFrozenBoundary() {
        var emptyAtCapacity = new RenderingPipelineCapacityGuard().newRequestTracker();
        assertTrue(emptyAtCapacity.reserve(
                RenderingPipelineCapacityGuard.Limit.RENDER_DOCUMENT_RUNS,
                10_000).isEmpty());
        assertInstanceOf(Sealer.Sealed.class, Sealer.seal(
                closure(), admitted(), minimalTree(), "sha256:" + "c".repeat(64),
                emptyAtCapacity));

        var atCapacity = new RenderingPipelineCapacityGuard().newRequestTracker();
        assertTrue(atCapacity.reserve(
                RenderingPipelineCapacityGuard.Limit.RENDER_DOCUMENT_RUNS,
                9_998).isEmpty());
        var sealed = assertInstanceOf(Sealer.Sealed.class, Sealer.seal(
                closure(), admitted(), twoTextRunsTree(), "sha256:" + "c".repeat(64),
                atCapacity));
        var document = new String(
                sealed.evaluation().renderDocumentCanonicalUtf8(), StandardCharsets.UTF_8);
        assertTrue(document.contains("\"visible\":false"));
        assertTrue(document.contains("\"opacity\":0"));

        var aboveCapacity = new RenderingPipelineCapacityGuard().newRequestTracker();
        assertTrue(aboveCapacity.reserve(
                RenderingPipelineCapacityGuard.Limit.RENDER_DOCUMENT_RUNS,
                9_999).isEmpty());
        var rejected = assertInstanceOf(Sealer.SealRejected.class, Sealer.seal(
                closure(), admitted(), twoTextRunsTree(), "sha256:" + "c".repeat(64),
                aboveCapacity));
        assertRunLimit(rejected);
    }

    @Test
    void finalTextScalarsUseTheRequestTotalFrozenBoundary() {
        var emptyAtCapacity = new RenderingPipelineCapacityGuard().newRequestTracker();
        assertTrue(emptyAtCapacity.reserve(
                RenderingPipelineCapacityGuard.Limit.RENDER_DOCUMENT_TEXT_SCALARS,
                1_000_000).isEmpty());
        assertInstanceOf(Sealer.Sealed.class, Sealer.seal(
                closure(), admitted(), minimalTree(), "sha256:" + "c".repeat(64),
                emptyAtCapacity));

        var atCapacity = new RenderingPipelineCapacityGuard().newRequestTracker();
        assertTrue(atCapacity.reserve(
                RenderingPipelineCapacityGuard.Limit.RENDER_DOCUMENT_TEXT_SCALARS,
                999_996).isEmpty());
        var sealed = assertInstanceOf(Sealer.Sealed.class, Sealer.seal(
                closure(), admitted(), twoTextRunsTree(), "sha256:" + "c".repeat(64),
                atCapacity));
        var document = new String(
                sealed.evaluation().renderDocumentCanonicalUtf8(), StandardCharsets.UTF_8);
        assertTrue(document.contains("\uD83D\uDE00"));
        assertTrue(document.contains("e\u0301\\n"));

        var aboveCapacity = new RenderingPipelineCapacityGuard().newRequestTracker();
        assertTrue(aboveCapacity.reserve(
                RenderingPipelineCapacityGuard.Limit.RENDER_DOCUMENT_TEXT_SCALARS,
                999_997).isEmpty());
        var rejected = assertInstanceOf(Sealer.SealRejected.class, Sealer.seal(
                closure(), admitted(), twoTextRunsTree(), "sha256:" + "c".repeat(64),
                aboveCapacity));
        assertTextScalarLimit(rejected);
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static Materializer.ResourceEntry entry(String resourceId, String assetId) {
        return new Materializer.ResourceEntry(
                resourceId,
                "IMAGE",
                "https://assets.internal/fetch/" + resourceId,
                2_000L,
                "sha256:" + "b".repeat(64),
                "image/png",
                1234,
                "renderweave-asset-acceptance/1.0",
                assetId,
                "content-version-1",
                "/path",
                "imageRef",
                new cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.ImageDescriptor(
                        10, 10,
                        cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.Orientation.IDENTITY,
                        10, 10, 1,
                        cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.ColorEncoding.SRGB_8BIT));
    }

    private static Materializer.MaterializedTree minimalTree() {
        var canvas = new Materializer.MaterializedNode(
                "canvas",
                new ObjectNode(Map.of(
                        "kind", new Text("canvas"),
                        "widthMm", new NumberToken("210"),
                        "heightMm", new NumberToken("297"))),
                List.of(),
                "");
        return new Materializer.MaterializedTree(canvas, List.of(), List.of());
    }

    private static Materializer.MaterializedTree oneChildTree() {
        var child = new Materializer.MaterializedNode(
                "rect",
                new ObjectNode(Map.of(
                        "kind", new Text("rect"),
                        "placement", absoluteFixedPlacement("10", "10"),
                        "fill", new ObjectNode(Map.of(
                                "color", new Text("#FF000000"))))),
                List.of(),
                "/rect");
        var root = new Materializer.MaterializedNode(
                "canvas",
                new ObjectNode(Map.of(
                        "kind", new Text("canvas"),
                        "widthMm", new NumberToken("210"),
                        "heightMm", new NumberToken("297"))),
                List.of(child),
                "");
        return new Materializer.MaterializedTree(root, List.of(), List.of());
    }

    private static Materializer.MaterializedTree viewportTree() {
        var sourceCanvas = new Materializer.MaterializedNode(
                "canvas",
                new ObjectNode(Map.of(
                        "kind", new Text("canvas"),
                        "widthMm", new NumberToken("100"),
                        "heightMm", new NumberToken("50"))),
                List.of(),
                "/use");
        var viewport = new Materializer.MaterializedNode(
                "compositionViewport",
                new ObjectNode(Map.of(
                        "kind", new Text("compositionViewport"),
                        "placement", absoluteFixedPlacement("100", "50"))),
                List.of(sourceCanvas),
                "/use");
        var root = new Materializer.MaterializedNode(
                "canvas",
                new ObjectNode(Map.of(
                        "kind", new Text("canvas"),
                        "widthMm", new NumberToken("210"),
                        "heightMm", new NumberToken("297"))),
                List.of(viewport),
                "");
        return new Materializer.MaterializedTree(root, List.of(), List.of());
    }

    private static Materializer.MaterializedTree twoTextRunsTree() {
        var firstResourceId = "rwres_" + "a".repeat(64);
        var secondResourceId = "rwres_" + "b".repeat(64);
        var first = textNode("\uD83D\uDE00", firstResourceId, "/text-1", false, "1");
        var second = textNode("e\u0301\n", secondResourceId, "/text-2", true, "0");
        var root = new Materializer.MaterializedNode(
                "canvas",
                new ObjectNode(Map.of(
                        "kind", new Text("canvas"),
                        "widthMm", new NumberToken("210"),
                        "heightMm", new NumberToken("297"))),
                List.of(first, second),
                "");
        return new Materializer.MaterializedTree(
                root,
                List.of(
                        fontEntry(firstResourceId, "asset-font-1", "/text-1"),
                        fontEntry(secondResourceId, "asset-font-2", "/text-2")),
                List.of());
    }

    private static Materializer.MaterializedNode textNode(
            String text,
            String resourceId,
            String occurrencePath,
            boolean visible,
            String opacity
    ) {
        var run = new ObjectNode(Map.of(
                "text", new Text(text),
                "fontRef", new ObjectNode(Map.of(
                        "resourceId", new Text(resourceId))),
                "fontSizePt", new NumberToken("12"),
                "color", new Text("#000000FF"),
                "decoration", new Text("NONE"),
                "letterSpacingPt", new NumberToken("0")));
        return new Materializer.MaterializedNode(
                "text",
                new ObjectNode(Map.of(
                        "kind", new Text("text"),
                        "placement", absoluteFixedPlacement("20", "10"),
                        "visible", new Bool(visible),
                        "opacity", new NumberToken(opacity),
                        "runs", new ArrayNode(List.of(run)))),
                List.of(),
                occurrencePath);
    }

    private static Materializer.ResourceEntry fontEntry(
            String resourceId,
            String assetId,
            String occurrencePath
    ) {
        return new Materializer.ResourceEntry(
                resourceId,
                "FONT",
                "https://assets.internal/fetch/" + resourceId,
                2_000L,
                "sha256:" + "b".repeat(64),
                "font/ttf",
                1_234,
                "renderweave-asset-acceptance/1.0",
                assetId,
                "content-version-1",
                occurrencePath,
                "fontRef",
                new cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.FontDescriptor(
                        0,
                        cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.FontFlavor
                                .TRUETYPE_GLYF,
                        1_000));
    }

    private static void assertStaticNodeLimit(Sealer.SealRejected rejected) {
        assertEquals(EvaluationStage.DOCUMENT_SEAL, rejected.problem().stage());
        assertEquals(ProblemCode.RENDER_DOCUMENT_LIMIT_EXCEEDED,
                rejected.problem().code());
        assertEquals("renderDocument.staticNodes",
                rejected.problem().limitId().orElseThrow().value());
    }

    private static void assertChildEdgeLimit(Sealer.SealRejected rejected) {
        assertEquals(EvaluationStage.DOCUMENT_SEAL, rejected.problem().stage());
        assertEquals(ProblemCode.RENDER_DOCUMENT_LIMIT_EXCEEDED,
                rejected.problem().code());
        assertEquals("renderDocument.childEdges",
                rejected.problem().limitId().orElseThrow().value());
    }

    private static void assertRunLimit(Sealer.SealRejected rejected) {
        assertEquals(EvaluationStage.DOCUMENT_SEAL, rejected.problem().stage());
        assertEquals(ProblemCode.RENDER_DOCUMENT_LIMIT_EXCEEDED,
                rejected.problem().code());
        assertEquals("renderDocument.runs",
                rejected.problem().limitId().orElseThrow().value());
    }

    private static void assertTextScalarLimit(Sealer.SealRejected rejected) {
        assertEquals(EvaluationStage.DOCUMENT_SEAL, rejected.problem().stage());
        assertEquals(ProblemCode.RENDER_DOCUMENT_LIMIT_EXCEEDED,
                rejected.problem().code());
        assertEquals("renderDocument.textScalars",
                rejected.problem().limitId().orElseThrow().value());
    }

    private static ClosureSnapshot closure() {
        var snapshot = new TemplateSnapshot(
                new cn.hbads.renderweave.template.api.TemplateApplication.TemplateId(
                        "00000000-0000-4000-8000-0000000000a1"),
                1,
                new cn.hbads.renderweave.template.api.TemplateClosureAuthority.OwnerScope("owner-a"),
                SCHEMA,
                "renderweave-design/1.0",
                "renderweave-expression/1.0",
                "{\"dslVersion\":\"renderweave-design/1.0\"}".getBytes(StandardCharsets.UTF_8),
                "sha256:" + "a".repeat(64));
        return new ClosureSnapshot(
                new cn.hbads.renderweave.template.api.TemplateClosureAuthority.OwnerScope("owner-a"),
                snapshot.templateId(),
                1,
                List.of(snapshot),
                List.of());
    }

    private static ObjectNode absoluteFixedPlacement(String widthMm, String heightMm) {
        return new ObjectNode(Map.of(
                "type", new Text("ABSOLUTE"),
                "xMm", new NumberToken("0"),
                "yMm", new NumberToken("0"),
                "widthMode", new Text("FIXED"),
                "heightMode", new Text("FIXED"),
                "widthMm", new NumberToken(widthMm),
                "heightMm", new NumberToken(heightMm)));
    }

    private static AdmittedRenderInput admitted() {
        return new AdmittedRenderInput(
                SCHEMA,
                new TypedObject(SCHEMA, Map.of("name",
                        java.util.Optional.of(new TypedValue.Text("alpha")))),
                Map.of(),
                Map.of());
    }
}
