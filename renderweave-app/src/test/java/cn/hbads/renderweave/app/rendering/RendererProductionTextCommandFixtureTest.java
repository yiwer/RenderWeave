package cn.hbads.renderweave.app.rendering;

import cn.hbads.renderweave.rendering.api.Evaluator.OutputSelection;
import cn.hbads.renderweave.rendering.api.Evaluator.RenderRequestId;
import cn.hbads.renderweave.rendering.spi.RenderEngine.RendererCommand;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Java authority for the shared production Text-to-PNG Renderer Command fixture. */
class RendererProductionTextCommandFixtureTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REQUEST_ID = "123e4567-e89b-42d3-a456-426614174000";
    private static final String RESOURCE_ID =
            "rwres_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final long DEADLINE_EPOCH_MILLIS = 2_000_000_000_000L;

    @Test
    void productionSerializerOwnsTheExactCrossLanguageTextCommandFixture() throws Exception {
        byte[] fontBytes = Files.readAllBytes(repoRoot().resolve(
                "renderweave-asset/src/test/resources/asset-fixtures/minimal-ttf.ttf"));
        byte[] documentBytes = JSON.writeValueAsBytes(document(fontBytes));
        String documentDigest = digest(
                "renderweave-render-document/1\0", documentBytes);
        var command = new RendererCommand(
                "renderweave-render-command/1.0",
                new RenderRequestId(REQUEST_ID),
                "renderweave-renderer/1.0",
                DEADLINE_EPOCH_MILLIS,
                documentDigest,
                documentBytes,
                new OutputSelection.Png(96),
                false);

        var encoded = RendererProcessProtocol.encodeCommand(command);
        byte[] storedFixture = Files.readAllBytes(
                repoRoot().resolve("renderer/production-text-command-v1.json"));
        assertEquals((byte) '\n', storedFixture[storedFixture.length - 1]);
        byte[] fixtureBytes = Arrays.copyOf(storedFixture, storedFixture.length - 1);

        assertArrayEquals(fixtureBytes, encoded.canonicalJsonUtf8());
        assertEquals(
                digest("renderweave-render-command/1\0", fixtureBytes),
                encoded.commandDigest());
        assertEquals(1, JSON.readTree(fixtureBytes).path("document").path("resources").size());
        assertEquals("text", JSON.readTree(fixtureBytes)
                .path("document").path("canvas").path("children").get(0).path("kind").asText());
    }

    private static Map<String, Object> document(byte[] fontBytes) throws Exception {
        var run = object(
                "color", "#000000FF",
                "decoration", "NONE",
                "fontResourceId", RESOURCE_ID,
                "fontSizePt", 12,
                "letterSpacingPt", 0,
                "text", "A");
        var text = object(
                "fitMode", "NONE",
                "horizontalAlign", "LEFT",
                "kind", "text",
                "lineBreak", "WORD",
                "lineHeight", object("factor", 1.2, "type", "FACTOR"),
                "occurrenceId", "rwocc_0000000000000001",
                "opacity", 1,
                "overflow", "CLIP",
                "padding", object(
                        "bottomPt", 0, "leftPt", 0, "rightPt", 0, "topPt", 0),
                "placement", object(
                        "heightMode", "FIXED", "heightPt", 24, "type", "ABSOLUTE",
                        "widthMode", "FIXED", "widthPt", 60, "xPt", 6, "yPt", 6),
                "runs", List.of(run),
                "transform", object(
                        "originX", 0.5, "originY", 0.5, "rotationDeg", 0,
                        "scaleX", 1, "scaleY", 1),
                "verticalAlign", "TOP",
                "visible", true,
                "writingMode", "HORIZONTAL_TB");
        var canvas = object(
                "backgroundColor", "#FFFFFFFF",
                "bleed", object(
                        "bottomPt", 0, "leftPt", 0, "rightPt", 0, "topPt", 0),
                "children", List.of(text),
                "heightPt", 36,
                "kind", "canvas",
                "occurrenceId", "rwocc_0000000000000000",
                "widthPt", 72);
        var resource = object(
                "acceptanceProfileId", "renderweave-asset-acceptance/1.0",
                "byteLength", fontBytes.length,
                "expiresAt", DEADLINE_EPOCH_MILLIS,
                "fetchUrl", "https://render.internal.example/internal/render-assets/font",
                "kind", "font",
                "mediaType", "font/ttf",
                "resourceId", RESOURCE_ID,
                "sha256", "sha256:" + sha256(fontBytes),
                "technicalDescriptor", object(
                        "faceIndex", 0,
                        "flavor", "TRUETYPE_GLYF",
                        "kind", "font",
                        "unitsPerEm", 1_000));
        return object(
                "canvas", canvas,
                "dslVersion", "renderweave-render/1.0",
                "layoutProfile", "renderweave-layout/1.0",
                "resources", List.of(resource));
    }

    private static Map<String, Object> object(Object... members) {
        if (members.length % 2 != 0) {
            throw new IllegalArgumentException("object members must be key/value pairs");
        }
        var result = new TreeMap<String, Object>();
        for (int index = 0; index < members.length; index += 2) {
            result.put((String) members[index], members[index + 1]);
        }
        return result;
    }

    private static String digest(String domain, byte[] bytes) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        digest.update(domain.getBytes(StandardCharsets.UTF_8));
        digest.update(bytes);
        return "sha256:" + HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static Path repoRoot() {
        String reactor = System.getProperty("maven.multiModuleProjectDirectory");
        return (reactor == null ? Path.of("..").toAbsolutePath() : Path.of(reactor))
                .toAbsolutePath().normalize();
    }
}
