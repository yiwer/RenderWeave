package cn.hbads.renderweave.inference.eval.visual;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisualStageCorpusTest {
    private final VisualStageCorpus corpus = new VisualStageCorpus();

    @Test
    void loadsBalancedStageGoldWithComplexTransitHierarchy() {
        assertEquals(12, corpus.scenes().size());
        assertEquals(60, corpus.cases().size());
        assertEquals(45, corpus.cases().stream()
                .filter(item -> item.partition() == VisualStageCorpus.Partition.DEV).count());
        assertEquals(15, corpus.cases().stream()
                .filter(item -> item.partition() == VisualStageCorpus.Partition.HOLDOUT).count());
        assertTrue(corpus.sourceSha256().matches("[0-9a-f]{64}"));

        var transit = corpus.require("transit-board-v5");
        assertEquals(VisualStageCorpus.DomainPack.TRANSIT_BOARD, transit.scene().domainPack());
        assertEquals(3, transit.scene().maximumDepth());
        assertEquals("TEXT", transit.expectedShapes().get("/").get("stationName"));
        assertEquals("REFERENCE", transit.expectedShapes().get("/").get("notice"));
        assertEquals("ARRAY:REFERENCE", transit.expectedShapes().get("/").get("routes"));
        assertEquals("ARRAY:REFERENCE", transit.expectedShapes().get("/routes").get("stops"));
        assertEquals("TEXT", transit.expectedShapes().get("/routes/stops").get("stopName"));
        assertEquals("/routes", transit.scene().bindingEntityPaths().get("route-number"));
        assertEquals("/routes/stops", transit.scene().bindingEntityPaths().get("stop-name"));
    }

    @Test
    void everyStyleCoversEverySceneAndEveryGoldSlotIsBound() {
        for (var style : VisualStageCorpus.Style.values()) {
            assertEquals(12, corpus.cases().stream().filter(item -> item.style() == style).count());
        }
        for (var scene : corpus.scenes()) {
            var slots = scene.elements().stream()
                    .filter(item -> item.kind() == VisualStageCorpus.ElementKind.SLOT).count();
            assertEquals(slots, scene.bindings().size(), scene.sceneId());
            assertFalse(scene.expectedShapes().isEmpty(), scene.sceneId());
        }
    }

    @Test
    void bundledFontRasterizationIsDeterministicAndCaseDistinct() {
        var rasterizer = new VisualStageRasterizer();
        var hashes = new HashSet<String>();
        for (var item : corpus.cases()) {
            var first = rasterizer.render(item);
            var second = rasterizer.render(item);
            assertEquals(first.sha256(), second.sha256(), item.caseId());
            assertArrayEquals(first.bytes(), second.bytes(), item.caseId());
            assertEquals(item.width(), first.width());
            assertEquals(item.height(), first.height());
            assertTrue(first.bytes().length > 1_000 && first.bytes().length < 10 * 1024 * 1024,
                    item.caseId());
            assertArrayEquals(new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47},
                    java.util.Arrays.copyOf(first.bytes(), 4), item.caseId());
            assertTrue(hashes.add(first.sha256()), item.caseId());
        }
        assertEquals(60, hashes.size());
    }
}
