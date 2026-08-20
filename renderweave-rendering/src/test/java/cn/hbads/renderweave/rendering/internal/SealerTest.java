package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.schema.identity.SchemaKey;
import cn.hbads.renderweave.schema.identity.VersionTag;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SealerTest {

    private static final StaticSchemaRef SCHEMA = new StaticSchemaRef(
            SchemaKey.systemProvided("system-empty"), VersionTag.of("v1"));

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
            throw new AssertionError("seal rejected: " + rejected.limitId());
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
                Map.of());
    }
}
