package cn.hbads.renderweave.inference.live;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class R5P2IndependentReplayProtocolTest {
    @Test
    void startsAtFrozenRawFixturesAndExposesNoProducerDecisionOrReport() throws Exception {
        var encoded = new R5P2IndependentReplayProtocol().build();
        JsonNode document = new JsonMapper().readTree(encoded);

        assertEquals("renderweave-r5p2-independent-replay-input/1.0",
                document.get("protocolVersion").textValue());
        assertEquals(0, document.get("accessBoundary")
                .get("producerReportReadsDuringReplay").intValue());
        assertEquals(0, document.get("accessBoundary")
                .get("producerMetricReadsDuringReplay").intValue());
        assertEquals(0, document.get("accessBoundary")
                .get("producerDecisionReadsDuringReplay").intValue());
        assertEquals("INDEPENDENT_REPLAY",
                document.get("accessBoundary").get("holdoutRole").textValue());
        assertEquals("SEALED",
                document.get("accessBoundary").get("holdoutStatus").textValue());
        assertEquals(1,
                document.get("accessBoundary").get("holdoutGoldMetricReads").intValue());
        assertEquals(2, document.get("runs").size());
        for (var run : document.get("runs")) {
            assertEquals(12, run.get("cases").size());
            for (var evaluationCase : run.get("cases")) {
                assertTrue(evaluationCase.get("rawBytes").isTextual());
                assertTrue(evaluationCase.get("normalization")
                        .get("normalizedBytes").isTextual());
                assertEquals(2, evaluationCase.get("branches").size());
                assertTrue(evaluationCase.get("branches").get(0).get("views").size() >= 1);
                assertTrue(evaluationCase.get("branches").get(1).get("views").size()
                        > evaluationCase.get("branches").get(0).get("views").size());
            }
        }

        var source = Files.readString(Path.of(
                "src/main/java/cn/hbads/renderweave/inference/live/"
                        + "R5P2IndependentReplayProtocol.java"), StandardCharsets.UTF_8);
        assertFalse(source.contains("R5P2PairedProductViewEvaluation"));
        assertFalse(source.contains("paired-product-view-report"));
        assertFalse(source.contains("candidateTerminal"));
    }
}
