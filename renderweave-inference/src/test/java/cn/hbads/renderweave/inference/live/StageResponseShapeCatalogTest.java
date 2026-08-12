package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.eval.visual.StageResponseShapeCatalog;
import cn.hbads.renderweave.inference.profile.InferenceProfileRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StageResponseShapeCatalogTest {
    private static final String IMAGE_ID = "a".repeat(64);
    private static final String FIXTURE_ROOT = "/response-shapes/1.0/";
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final StageResponseShapeCatalog catalog = new StageResponseShapeCatalog();
    private final VisualGroundingJsonCodec codec = new VisualGroundingJsonCodec();

    @Test
    void catalogDocumentsAndCombinedIdentityAreCanonicalAndByteStable() {
        assertEquals(StageResponseShapeCatalog.VERSION, catalog.catalogVersion());
        assertEquals("645c50f5c2ef2345424659086aec436e9a5de7f67bc2a0c313b117bf9b5f119d",
                catalog.schemaSha256(StageResponseShapeCatalog.Stage.OBSERVE));
        assertEquals("2b594b99fb5435f1868c1ec1bd8d67b5f0b26aff6e367adbd0bdb1829e8ad1ec",
                catalog.schemaSha256(StageResponseShapeCatalog.Stage.HIERARCHY));
        assertEquals("250182a563dc06a5c666fc57f92c22f4e18c06c4fc054cb823913bd9e08ea38d",
                catalog.schemaSha256(StageResponseShapeCatalog.Stage.ELEMENT_BINDING));
        assertEquals("ad46adfbf6dc9e200f4736e693646ee485de5530af35b2f12802f561faa16557",
                catalog.identity());

        var independentlyLoaded = new StageResponseShapeCatalog();
        for (var stage : StageResponseShapeCatalog.Stage.values()) {
            var document = catalog.schemaDocument(stage);
            assertEquals(document, independentlyLoaded.schemaDocument(stage));
            assertFalse(document.contains("\r"));
            assertTrue(document.contains("\"additionalProperties\":false"));
            assertTrue(document.contains("\"required\":"));
            assertTrue(document.contains("\"maxItems\":"));
        }
        assertFalse(catalog.toString().contains("标题"));
    }

    @Test
    void machineReadableFixturesHaveTheSameShapeAcceptanceAsStrictV45Codecs() throws Exception {
        var manifest = JSON.readValue(resource("fixtures-manifest.json"), FixtureManifest.class);
        assertEquals("renderweave-stage-response-shape-fixtures/1.0", manifest.fixtureVersion());
        var context = context();

        for (var fixture : manifest.fixtures()) {
            var stage = StageResponseShapeCatalog.Stage.valueOf(fixture.stage());
            var payload = resource(fixture.resource());
            assertEquals(fixture.shapeAccepted(), catalog.validate(stage, payload).accepted(),
                    fixture.fixtureId() + " catalog");
            assertEquals(fixture.codecAccepted(), codecAccepts(stage, payload, context),
                    fixture.fixtureId() + " codec");
        }

        var oversized = resource("observe-valid.json")
                + " ".repeat(StageResponseShapeCatalog.MAXIMUM_RESPONSE_BYTES);
        assertFalse(catalog.validate(StageResponseShapeCatalog.Stage.OBSERVE, oversized).accepted());
        assertFalse(codecAccepts(StageResponseShapeCatalog.Stage.OBSERVE, oversized, context));
    }

    @Test
    void productV45ProfilesStillRequestJsonObjectWithoutTools() {
        var profiles = new InferenceProfileRegistry();
        for (var profileId : List.of(
                "dashscope-qwen37-flash-product-v45-hybrid-generic",
                "dashscope-qwen37-plus-product-v45-hybrid-generic",
                "dashscope-qwen38-max-product-v45-hybrid-generic"
        )) {
            var profile = profiles.require(profileId).profile();
            assertEquals("JSON_OBJECT", profile.responseFormat());
            assertFalse(profile.toolsAllowed());
            assertEquals("renderweave-inference-pipeline/4.28", profile.pipelineVersion());
        }
    }

    private boolean codecAccepts(
            StageResponseShapeCatalog.Stage stage,
            String payload,
            CodecContext context
    ) {
        try {
            switch (stage) {
                case OBSERVE -> codec.parseElements(payload, context.views(), List.of(IMAGE_ID));
                case HIERARCHY -> codec.parseHierarchy(
                        payload, context.observed().inventory(), context.observed().grounding()
                );
                case ELEMENT_BINDING -> codec.parseBindings(
                        payload, context.observed().inventory(), context.hierarchy().hierarchy(),
                        context.observed().grounding(), context.hierarchy().entityRegions()
                );
            }
            return true;
        } catch (RuntimeException rejected) {
            return false;
        }
    }

    private CodecContext context() throws Exception {
        var views = new MultiScaleVisualViewPlanner().plan(
                List.of(new VisualSourceImage(IMAGE_ID, png(), 1_000, 1_000)), List.of()
        );
        var observed = codec.parseElements(resource("observe-valid.json"), views, List.of(IMAGE_ID));
        var hierarchy = codec.parseHierarchy(
                resource("hierarchy-valid.json"), observed.inventory(), observed.grounding()
        );
        return new CodecContext(views, observed, hierarchy);
    }

    private static byte[] png() throws Exception {
        var image = new BufferedImage(1_000, 1_000, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 1_000, 1_000);
            graphics.setColor(Color.BLACK);
            graphics.fillRect(20, 20, 960, 20);
        } finally {
            graphics.dispose();
        }
        var output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", output));
        return output.toByteArray();
    }

    private static String resource(String name) throws Exception {
        try (InputStream input = StageResponseShapeCatalogTest.class.getResourceAsStream(
                FIXTURE_ROOT + name
        )) {
            if (input == null) throw new IllegalStateException("fixture missing: " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private record FixtureManifest(String fixtureVersion, List<Fixture> fixtures) {
    }

    private record Fixture(
            String fixtureId,
            String stage,
            String resource,
            boolean shapeAccepted,
            boolean codecAccepted
    ) {
    }

    private record CodecContext(
            VisualViewPlan views,
            GroundedElementInventory observed,
            GroundedHierarchyPlan hierarchy
    ) {
    }
}
