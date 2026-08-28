package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.schema.identity.SchemaKey;
import cn.hbads.renderweave.schema.identity.VersionTag;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.ArrayNode;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.NumberToken;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.ObjectNode;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.Text;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.DesignNodeValue;
import cn.hbads.renderweave.template.api.TemplateClosureAuthority.ClosureSnapshot;
import cn.hbads.renderweave.template.api.TemplateClosureAuthority.TemplateSnapshot;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderDocumentContractTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final StaticSchemaRef SCHEMA = new StaticSchemaRef(
            SchemaKey.systemProvided("system-empty"), VersionTag.of("v1"));

    @Test
    void sealExpandsCanvasAndPrimitiveDefaultsIntoTheExactRenderWire() throws Exception {
        var rect = node("rect", Map.of(
                "nodeId", text("00000000-0000-4000-8000-000000000011"),
                "placement", absoluteFixed("10", "20"),
                "fill", object(Map.of("color", text("#FF0000FF")))));
        var canvas = canvas(List.of(rect));

        var document = seal(canvas);
        var root = document.get("canvas");
        assertEquals("canvas", root.get("kind").asText());
        assertEquals("#00000000", root.get("backgroundColor").asText());
        assertEquals(0, root.get("bleed").get("topPt").decimalValue().signum());
        assertEquals(0, root.get("bleed").get("rightPt").decimalValue().signum());
        assertEquals(0, root.get("bleed").get("bottomPt").decimalValue().signum());
        assertEquals(0, root.get("bleed").get("leftPt").decimalValue().signum());

        var emitted = root.get("children").get(0);
        assertEquals("rect", emitted.get("kind").asText());
        assertTrue(emitted.get("visible").asBoolean());
        assertEquals(0, emitted.get("opacity").decimalValue().compareTo(java.math.BigDecimal.ONE));
        assertEquals(0, emitted.get("transform").get("rotationDeg").decimalValue().signum());
        assertEquals(0, emitted.get("transform").get("scaleX").decimalValue()
                .compareTo(java.math.BigDecimal.ONE));
        assertEquals(0, emitted.get("transform").get("scaleY").decimalValue()
                .compareTo(java.math.BigDecimal.ONE));
        assertEquals("0.5", emitted.get("transform").get("originX").asText());
        assertEquals("0.5", emitted.get("transform").get("originY").asText());
        assertEquals(0, emitted.get("cornerRadii").get("topLeftPt").decimalValue().signum());
        assertFalse(emitted.get("placement").has("rightInsetPt"));
        assertFalse(emitted.has("render"));
        assertFalse(emitted.has("nodeId"));
    }

    @Test
    void sealResolvesContainerAndPlacementDefaultsWithoutLeavingOmission() throws Exception {
        var child = node("rect", Map.of(
                "nodeId", text("00000000-0000-4000-8000-000000000012"),
                "placement", object(Map.of(
                        "type", text("STACK"),
                        "widthMode", text("FILL"),
                        "heightMode", text("FILL"))),
                "fill", object(Map.of("color", text("#0000FFFF")))));
        var stack = node("stack", Map.of(
                "nodeId", text("00000000-0000-4000-8000-000000000011"),
                "placement", absoluteFixed("40", "50")), List.of(child));

        var document = seal(canvas(List.of(stack)));
        var emittedStack = document.get("canvas").get("children").get(0);
        assertEquals("COLUMN", emittedStack.get("direction").asText());
        assertEquals("START", emittedStack.get("justifyContent").asText());
        assertEquals("START", emittedStack.get("alignItems").asText());
        assertEquals(0, emittedStack.get("gapPt").decimalValue().signum());
        assertFalse(emittedStack.get("clipContent").asBoolean());
        assertEquals(0, emittedStack.get("padding").get("topPt").decimalValue().signum());

        var placement = emittedStack.get("children").get(0).get("placement");
        assertEquals("START", placement.get("alignSelf").asText());
        assertEquals(0, placement.get("fillWeight").decimalValue()
                .compareTo(java.math.BigDecimal.ONE));
        assertEquals(0, placement.get("marginTopPt").decimalValue().signum());
        assertEquals(0, placement.get("marginRightPt").decimalValue().signum());
        assertEquals(0, placement.get("marginBottomPt").decimalValue().signum());
        assertEquals(0, placement.get("marginLeftPt").decimalValue().signum());
    }

    @Test
    void sealLowersAssetAtomsToOpaqueResourceIdsAndExpandsLeafDefaults() throws Exception {
        var image = node("image", Map.of(
                "nodeId", text("00000000-0000-4000-8000-000000000011"),
                "placement", absoluteFixed("10", "10"),
                "imageRef", object(Map.of("resourceId", text("rwres_" + "a".repeat(64))))));
        var run = object(Map.of(
                "text", text("A"),
                "fontRef", object(Map.of("resourceId", text("rwres_" + "b".repeat(64)))),
                "fontSizePt", number("12"),
                "color", text("#000000FF"),
                "decoration", text("NONE"),
                "letterSpacingPt", number("0")));
        var text = node("text", Map.of(
                "nodeId", text("00000000-0000-4000-8000-000000000012"),
                "placement", absoluteFixed("20", "10"),
                "runs", new ArrayNode(List.of(run))));

        var document = seal(canvas(List.of(image, text)));
        var emittedImage = document.get("canvas").get("children").get(0);
        assertEquals("rwres_" + "a".repeat(64), emittedImage.get("imageResourceId").asText());
        assertFalse(emittedImage.has("imageRef"));
        assertEquals("CONTAIN", emittedImage.get("fit").asText());
        assertEquals("LINEAR", emittedImage.get("sampling").asText());

        var emittedText = document.get("canvas").get("children").get(1);
        assertEquals("HORIZONTAL_TB", emittedText.get("writingMode").asText());
        assertEquals("WORD", emittedText.get("lineBreak").asText());
        assertEquals("CLIP", emittedText.get("overflow").asText());
        assertEquals("FACTOR", emittedText.get("lineHeight").get("type").asText());
        assertEquals("1.2", emittedText.get("lineHeight").get("factor").asText());
        var emittedRun = emittedText.get("runs").get(0);
        assertEquals("rwres_" + "b".repeat(64), emittedRun.get("fontResourceId").asText());
        assertFalse(emittedRun.has("fontRef"));
    }

    @Test
    void sealCoversEveryStaticKindInOneExactDocument() throws Exception {
        var fontResourceId = "rwres_" + "a".repeat(64);
        var imageResourceId = "rwres_" + "b".repeat(64);
        var nodes = List.of(
                node("group", Map.of("placement", absoluteFixed("10", "10")), List.of()),
                node("frame", Map.of("placement", absoluteFixed("10", "10")), List.of()),
                node("stack", Map.of("placement", absoluteFixed("10", "10")), List.of()),
                node("grid", Map.of(
                        "placement", absoluteFixed("10", "10"),
                        "rows", new ArrayNode(List.of(object(Map.of("type", text("AUTO"))))),
                        "columns", new ArrayNode(List.of(object(Map.of("type", text("AUTO")))))),
                        List.of()),
                node("text", Map.of(
                        "placement", absoluteFixed("10", "10"),
                        "runs", new ArrayNode(List.of(object(Map.of(
                                "text", text("A"),
                                "fontRef", object(Map.of("resourceId", text(fontResourceId))),
                                "fontSizePt", number("12"),
                                "color", text("#000000FF"),
                                "decoration", text("NONE"),
                                "letterSpacingPt", number("0"))))))),
                node("image", Map.of(
                        "placement", absoluteFixed("10", "10"),
                        "imageRef", object(Map.of("resourceId", text(imageResourceId))))),
                node("rect", Map.of(
                        "placement", absoluteFixed("10", "10"), "fill", fill())),
                node("ellipse", Map.of(
                        "placement", absoluteFixed("10", "10"), "fill", fill())),
                node("line", Map.of(
                        "placement", absoluteFixed("10", "10"),
                        "start", point("0", "0"), "end", point("10", "10"),
                        "stroke", stroke())),
                node("polygon", Map.of(
                        "placement", absoluteFixed("10", "10"),
                        "points", points(point("0", "0"), point("10", "0"), point("0", "10")),
                        "fill", fill())),
                node("polyline", Map.of(
                        "placement", absoluteFixed("10", "10"),
                        "points", points(point("0", "0"), point("10", "10")),
                        "stroke", stroke())),
                node("path", Map.of(
                        "placement", absoluteFixed("10", "10"),
                        "commands", new ArrayNode(List.of(
                                object(Map.of("type", text("MOVE_TO"),
                                        "xMm", number("0"), "yMm", number("0"))),
                                object(Map.of("type", text("LINE_TO"),
                                        "xMm", number("10"), "yMm", number("10"))))),
                        "fill", fill())),
                node("qrCode", Map.of(
                        "placement", absoluteFixed("10", "10"), "content", text("rw"))),
                node("barcode", Map.of(
                        "placement", absoluteFixed("10", "10"),
                        "format", text("CODE_128"), "value", text("123"))),
                node("compositionViewport", Map.of(
                        "placement", absoluteFixed("10", "10")),
                        List.of(canvas(List.of()))));

        var resources = List.of(
                fontEntry(fontResourceId), imageEntry(imageResourceId));
        var outcome = Sealer.seal(closure(), admitted(),
                new Materializer.MaterializedTree(canvas(nodes), resources, List.of()),
                "sha256:" + "c".repeat(64));
        var sealed = assertInstanceOf(Sealer.Sealed.class, outcome);
        var canonical = new String(
                sealed.evaluation().renderDocumentCanonicalUtf8(), StandardCharsets.UTF_8);
        var document = JSON.readTree(canonical);
        var kinds = new java.util.HashSet<String>();
        document.get("canvas").get("children").forEach(child -> kinds.add(child.get("kind").asText()));
        assertEquals(Set.of("group", "frame", "stack", "grid", "text", "image", "rect",
                "ellipse", "line", "polygon", "polyline", "path", "qrCode", "barcode",
                "compositionViewport"), kinds);
        assertEquals(2, document.get("resources").size());

        var frozen = Files.readString(repoFile("renderer/render-document-all-kinds-v1.json"),
                StandardCharsets.UTF_8).stripTrailing();
        assertEquals(frozen, canonical);
        var vectors = JSON.readTree(Files.readAllBytes(
                repoFile("renderer/render-document-vectors-v1.json")));
        assertEquals("renderweave-render-document-vectors/3",
                vectors.get("vectorVersion").asText());
        assertEquals("TYPED_MANIFEST_AND_COMMAND_LEASE_PREFLIGHT_ONLY",
                vectors.get("authorityContext").get("resourceAdmission").asText());
        assertEquals(5_000,
                vectors.get("authorityContext").get("leaseSafetyMarginMillis").asInt());
        assertEquals("sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(Files.readAllBytes(repoFile(vectors.get("authorityContext")
                                .get("catalogPath").asText())))),
                vectors.get("authorityContext").get("catalogSha256").asText());
        var protocolVectors = JSON.readTree(Files.readAllBytes(
                repoFile("renderer/protocol-vectors-v1.json")));
        JsonNode protocolCommand = null;
        for (var candidate : protocolVectors.get("cases")) {
            if ("png-command".equals(candidate.get("id").asText())) {
                protocolCommand = candidate;
                break;
            }
        }
        if (protocolCommand == null) {
            throw new AssertionError("png-command protocol vector is absent");
        }
        var minimal = assertInstanceOf(Sealer.Sealed.class, Sealer.seal(
                closure(), admitted(),
                new Materializer.MaterializedTree(canvas(List.of()), List.of(), List.of()),
                "sha256:" + "c".repeat(64)));
        assertEquals(protocolCommand.get("documentCanonicalJson").asText(),
                new String(minimal.evaluation().renderDocumentCanonicalUtf8(),
                        StandardCharsets.UTF_8));
        assertEquals(vectors.get("positiveCases").get(0).get("renderDocumentDigest").asText(),
                minimal.evaluation().renderDocumentDigest());
        var allKindsCase = vectors.get("positiveCases").get(1);
        assertEquals(canonical.getBytes(StandardCharsets.UTF_8).length,
                allKindsCase.get("canonicalBytes").asInt());
        assertEquals("sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(canonical.getBytes(StandardCharsets.UTF_8))),
                allKindsCase.get("canonicalSha256").asText());
        assertEquals(sealed.evaluation().renderDocumentDigest(),
                allKindsCase.get("renderDocumentDigest").asText());
        assertEquals(12, vectors.get("negativeCases").size());
        assertEquals(42, vectors.get("resourceCases").size());
        assertEquals(19, vectors.get("resourceAggregateCases").size());
        assertEquals(8, vectors.get("resourceLeaseCases").size());
    }

    private static JsonNode seal(Materializer.MaterializedNode canvas) throws Exception {
        var outcome = Sealer.seal(closure(), admitted(),
                new Materializer.MaterializedTree(canvas, List.of(), List.of()),
                "sha256:" + "c".repeat(64));
        var sealed = assertInstanceOf(Sealer.Sealed.class, outcome);
        return JSON.readTree(sealed.evaluation().renderDocumentCanonicalUtf8());
    }

    private static Path repoFile(String relative) {
        var cursor = Path.of("").toAbsolutePath();
        while (cursor != null) {
            var candidate = cursor.resolve(relative);
            if (Files.exists(candidate)) {
                return candidate;
            }
            cursor = cursor.getParent();
        }
        throw new AssertionError("repository file is absent: " + relative);
    }

    private static Materializer.MaterializedNode canvas(List<Materializer.MaterializedNode> children) {
        return node("canvas", Map.of(
                "nodeId", text("00000000-0000-4000-8000-000000000001"),
                "widthMm", number("210"),
                "heightMm", number("297")), children);
    }

    private static Materializer.MaterializedNode node(
            String kind, Map<String, DesignNodeValue> members) {
        return node(kind, members, List.of());
    }

    private static Materializer.MaterializedNode node(
            String kind,
            Map<String, DesignNodeValue> members,
            List<Materializer.MaterializedNode> children) {
        var values = new java.util.LinkedHashMap<String, DesignNodeValue>();
        values.put("kind", text(kind));
        values.putAll(members);
        return new Materializer.MaterializedNode(kind, new ObjectNode(values), children, "/" + kind);
    }

    private static ObjectNode absoluteFixed(String widthMm, String heightMm) {
        return object(Map.of(
                "type", text("ABSOLUTE"),
                "xMm", number("0"),
                "yMm", number("0"),
                "widthMode", text("FIXED"),
                "heightMode", text("FIXED"),
                "widthMm", number(widthMm),
                "heightMm", number(heightMm)));
    }

    private static ObjectNode fill() {
        return object(Map.of("color", text("#000000FF")));
    }

    private static ObjectNode stroke() {
        return object(Map.of(
                "color", text("#000000FF"),
                "widthMm", number("1"),
                "cap", text("BUTT"),
                "join", text("MITER")));
    }

    private static ObjectNode point(String xMm, String yMm) {
        return object(Map.of("xMm", number(xMm), "yMm", number(yMm)));
    }

    private static ArrayNode points(ObjectNode... points) {
        return new ArrayNode(List.of(points));
    }

    private static Materializer.ResourceEntry fontEntry(String resourceId) {
        return new Materializer.ResourceEntry(
                resourceId, "FONT", "https://assets.internal/font", 2_000L,
                "sha256:" + "a".repeat(64), "font/ttf", 256,
                "renderweave-asset-acceptance/1.0", "asset-font", "font-v1",
                "/text", "fontRef",
                new cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.FontDescriptor(
                        0,
                        cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.FontFlavor
                                .TRUETYPE_GLYF,
                        1_000));
    }

    private static Materializer.ResourceEntry imageEntry(String resourceId) {
        return new Materializer.ResourceEntry(
                resourceId, "IMAGE", "https://assets.internal/image", 2_000L,
                "sha256:" + "b".repeat(64), "image/png", 128,
                "renderweave-asset-acceptance/1.0", "asset-image", "image-v1",
                "/image", "imageRef",
                new cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.ImageDescriptor(
                        10, 10,
                        cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.Orientation.IDENTITY,
                        10, 10, 1,
                        cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.ColorEncoding
                                .SRGB_8BIT));
    }

    private static ObjectNode object(Map<String, DesignNodeValue> members) {
        return new ObjectNode(members);
    }

    private static Text text(String value) {
        return new Text(value);
    }

    private static NumberToken number(String value) {
        return new NumberToken(value);
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

    private static AdmittedRenderInput admitted() {
        return new AdmittedRenderInput(
                SCHEMA,
                new TypedObject(SCHEMA, Map.of("name",
                        Optional.of(new TypedValue.Text("alpha")))),
                Map.of(),
                Map.of());
    }
}
