package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.candidate.CandidateBoundingBox;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisualGroundingContractTest {
    private static final String IMAGE_ID = "a".repeat(64);
    private final VisualGroundingJsonCodec codec = new VisualGroundingJsonCodec();

    @Test
    void mapsViewEvidenceToOriginalAndAcceptsAThreeLevelSpatialPlan() throws Exception {
        var views = views();
        var observed = codec.parseElements(elementsJson(), views, List.of(IMAGE_ID));
        var hierarchy = codec.parseHierarchy(hierarchyJson(), observed.inventory(), observed.grounding());
        var bindings = codec.parseBindings(
                bindingsJson(), observed.inventory(), hierarchy.hierarchy(), observed.grounding(),
                hierarchy.entityRegions()
        );

        assertEquals(5, observed.grounding().regions().size());
        assertEquals(new CandidateBoundingBox(0, 0, 10_000, 10_000),
                observed.grounding().requireRegion("root").evidence().getFirst().boundingBox());
        assertEquals(List.of("item-a", "item-b"),
                observed.grounding().regionIdsForElement("item-label"));
        assertEquals(VisualHierarchyPlan.VERSION_V2, hierarchy.hierarchy().contractVersion());
        assertEquals(List.of("item-a", "item-b"),
                hierarchy.entityRegions().requireEntity("item").regionIds());
        assertEquals(2, bindings.bindings().size());

        var checkpoint = LiveWorkflowCheckpoint.started()
                .elementsGrounded(observed.inventory(), observed.grounding(), 1)
                .hierarchyGrounded(hierarchy.hierarchy(), hierarchy.entityRegions(), 2)
                .elementsBound(bindings, 3);
        var restored = new LiveWorkflowJsonCodec().parse(new LiveWorkflowJsonCodec().write(checkpoint));
        assertEquals(LiveWorkflowCheckpoint.VERSION, restored.checkpointVersion());
        assertEquals(observed.grounding(), restored.groundingPlan());
        assertEquals(hierarchy.entityRegions(), restored.entityRegionPlan());
    }

    @Test
    void rejectsUnknownViewsSpatialEscapesOverlapCyclesAndRepeatDrift() throws Exception {
        var views = views();
        assertDiagnostic(elementsJson().replace("view-00-overview-00", "unknown-view"), views,
                "VISUAL_GROUNDING_REGION_INVALID");
        assertDiagnostic(elementsJson().replaceFirst("\"left\":0", "\"left\":-1"), views,
                "VISUAL_GROUNDING_JSON_INVALID");
        assertDiagnostic(elementsJson().replace(
                "\"left\":100,\"top\":100,\"right\":3000,\"bottom\":700",
                "\"left\":100,\"top\":2500,\"right\":3000,\"bottom\":2700"
        ), views, "VISUAL_GROUNDING_ELEMENT_OWNERSHIP_INVALID");
        assertDiagnostic(elementsJson().replace(
                "\"left\":0,\"top\":6000,\"right\":10000,\"bottom\":10000",
                "\"left\":0,\"top\":5000,\"right\":10000,\"bottom\":10000"
        ), views, "VISUAL_GROUNDING_REGION_FOREST_INVALID");
        assertDiagnostic(elementsJson().replace(
                "\"regionId\":\"repeat\",\"parentRegionId\":\"root\"",
                "\"regionId\":\"repeat\",\"parentRegionId\":\"item-a\""
        ), views, "VISUAL_GROUNDING_REGION_FOREST_INVALID");
        assertDiagnostic(elementsJson().replace(
                "\"repeatGroupId\":\"rows\",\"evidence\":[{\"viewId\":\"view-00-overview-00\",\"boundingBox\":{\"left\":0,\"top\":6000",
                "\"repeatGroupId\":\"other\",\"evidence\":[{\"viewId\":\"view-00-overview-00\",\"boundingBox\":{\"left\":0,\"top\":6000"
        ), views, "VISUAL_GROUNDING_REGION_FOREST_INVALID");
    }

    @Test
    void rejectsStrictJsonAndSpatiallyInvalidHierarchyOrBinding() throws Exception {
        var views = views();
        var observed = codec.parseElements(elementsJson(), views, List.of(IMAGE_ID));

        assertEquals("VISUAL_GROUNDING_JSON_INVALID", assertThrows(
                InvalidVisualAnalysisException.class, () -> codec.parseElements(
                elementsJson().replaceFirst("\\{", "{\"unexpected\":true,"), views, List.of(IMAGE_ID)
        )).diagnosticCode());
        assertEquals("VISUAL_GROUNDING_JSON_INVALID", assertThrows(
                InvalidVisualAnalysisException.class, () -> codec.parseElements(
                elementsJson().replaceFirst("\"contractVersion\":", "\"contractVersion\":\"bad\",\"contractVersion\":"),
                views, List.of(IMAGE_ID)
        )).diagnosticCode());
        assertEquals("VISUAL_GROUNDING_JSON_INVALID", assertThrows(
                InvalidVisualAnalysisException.class, () -> codec.parseElements(
                elementsJson() + "{}", views, List.of(IMAGE_ID)
        )).diagnosticCode());
        assertEquals("VISUAL_GROUNDING_JSON_INVALID", assertThrows(
                InvalidVisualAnalysisException.class, () -> codec.parseElements(
                elementsJson().replace("\"readingOrder\":0", "\"readingOrder\":\"0\""),
                views, List.of(IMAGE_ID)
        )).diagnosticCode());
        assertDiagnostic(elementsJson().replace(
                "renderweave-visual-grounding/2.0", "renderweave-visual-grounding/9.0"
        ), views, "VISUAL_GROUNDING_VERSION_INVALID");

        assertEquals("VISUAL_HIERARCHY_V2_REGION_OWNERSHIP_INVALID", assertThrows(
                InvalidVisualAnalysisException.class, () -> codec.parseHierarchy(
                hierarchyJson().replace(
                        "\"regionIds\":[\"item-a\",\"item-b\"]",
                        "\"regionIds\":[\"header\"]"
                ), observed.inventory(), observed.grounding()
        )).diagnosticCode());
        var hierarchy = codec.parseHierarchy(hierarchyJson(), observed.inventory(), observed.grounding());
        assertEquals("VISUAL_BINDINGS_V2_REGION_OWNERSHIP_INVALID", assertThrows(
                InvalidVisualAnalysisException.class, () -> codec.parseBindings(
                bindingsJson().replace(
                        "{\"elementId\":\"title\",\"entityId\":\"document\"}",
                        "{\"elementId\":\"title\",\"entityId\":\"item\"}"
                ), observed.inventory(), hierarchy.hierarchy(), observed.grounding(), hierarchy.entityRegions()
        )).diagnosticCode());
    }

    private void assertDiagnostic(String json, VisualViewPlan views, String expectedCode) {
        assertEquals(expectedCode, assertThrows(InvalidVisualAnalysisException.class,
                () -> codec.parseElements(json, views, List.of(IMAGE_ID))).diagnosticCode());
    }

    private static VisualViewPlan views() throws Exception {
        return new MultiScaleVisualViewPlanner().plan(
                List.of(new VisualSourceImage(IMAGE_ID, png(), 1_000, 1_000)), List.of()
        );
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

    private static String elementsJson() {
        return """
                {
                  "contractVersion":"renderweave-visual-grounding/2.0",
                  "regions":[
                    {"regionId":"root","parentRegionId":null,"kind":"ROOT","multiplicity":"ONE","readingOrder":0,"repeatGroupId":null,"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":0,"top":0,"right":10000,"bottom":10000}}]},
                    {"regionId":"header","parentRegionId":"root","kind":"SECTION","multiplicity":"ONE","readingOrder":0,"repeatGroupId":null,"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":0,"top":0,"right":10000,"bottom":2000}}]},
                    {"regionId":"repeat","parentRegionId":"root","kind":"REPEATED_GROUP","multiplicity":"MANY","readingOrder":1,"repeatGroupId":"rows","evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":0,"top":2000,"right":10000,"bottom":10000}}]},
                    {"regionId":"item-a","parentRegionId":"repeat","kind":"ITEM","multiplicity":"ONE","readingOrder":0,"repeatGroupId":"rows","evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":0,"top":2000,"right":10000,"bottom":6000}}]},
                    {"regionId":"item-b","parentRegionId":"repeat","kind":"ITEM","multiplicity":"ONE","readingOrder":1,"repeatGroupId":"rows","evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":0,"top":6000,"right":10000,"bottom":10000}}]}
                  ],
                  "elements":[
                    {"elementId":"title","kind":"SLOT","proposedKey":"title","displayName":"标题","multiplicity":"ONE","valueHint":"TEXT","regionIds":["header"],"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":100,"top":100,"right":3000,"bottom":700}}]},
                    {"elementId":"row-group","kind":"GROUP","proposedKey":"items","displayName":"重复项目","multiplicity":"MANY","valueHint":null,"regionIds":["repeat"],"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":0,"top":2000,"right":10000,"bottom":10000}}]},
                    {"elementId":"item-label","kind":"SLOT","proposedKey":"label","displayName":"项目名称","multiplicity":"ONE","valueHint":"TEXT","regionIds":["item-a","item-b"],"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":100,"top":2300,"right":3000,"bottom":2800}},{"viewId":"view-00-overview-00","boundingBox":{"left":100,"top":6300,"right":3000,"bottom":6800}}]}
                  ]
                }
                """;
    }

    private static String hierarchyJson() {
        return """
                {
                  "contractVersion":"renderweave-visual-hierarchy/2.0",
                  "rootEntityId":"document",
                  "entities":[
                    {"entityId":"document","schemaKey":"document","displayName":"文档","regionIds":["root"],"supportingElementIds":["title"]},
                    {"entityId":"item","schemaKey":"item","displayName":"项目","regionIds":["item-a","item-b"],"supportingElementIds":["row-group"]}
                  ],
                  "relationships":[
                    {"relationshipId":"document-items","parentEntityId":"document","childEntityId":"item","fieldKey":"items","displayName":"项目","cardinality":"MANY","regionId":"repeat","supportingElementIds":["row-group"]}
                  ]
                }
                """;
    }

    private static String bindingsJson() {
        return """
                {
                  "contractVersion":"renderweave-visual-bindings/2.0",
                  "bindings":[
                    {"elementId":"title","entityId":"document"},
                    {"elementId":"item-label","entityId":"item"}
                  ]
                }
                """;
    }
}
