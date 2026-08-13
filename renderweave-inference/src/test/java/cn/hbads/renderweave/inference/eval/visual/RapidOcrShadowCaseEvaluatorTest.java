package cn.hbads.renderweave.inference.eval.visual;

import cn.hbads.renderweave.inference.vision.ArtifactSet;
import cn.hbads.renderweave.inference.vision.DocumentObservationIR;
import cn.hbads.renderweave.inference.vision.RapidOcrBaselineContract;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RapidOcrShadowCaseEvaluatorTest {
    @Test
    void scoresAnActualObservationIrWithoutPersistingItsEphemeralText() {
        var evaluationCase = new LayeredVisualCorpus().require("transit-board-v3");
        var policy = RapidOcrBaselineContract.policy(RapidOcrBaselineContract.DEFAULT_TIMEOUT_MILLIS);
        var rendered = new VisualStageRasterizer().render(evaluationCase.renderCase());
        var observation = DocumentObservationIR.canonical(
                policy,
                provenance(),
                List.of(new DocumentObservationIR.ArtifactObservation(
                        rendered.sha256(), 0, rendered.mediaType(), rendered.width(), rendered.height(), true,
                        List.of(new DocumentObservationIR.TextLine(
                                "ocr-00-000", 0,
                                pixels(600, 200, 9_400, 650, rendered.width(), rendered.height()),
                                new DocumentObservationIR.Confidence(
                                        9_500,
                                        RapidOcrBaselineContract.CONFIDENCE_SCALE_IDENTITY,
                                        DocumentObservationIR.ConfidenceBucket.HIGH,
                                        RapidOcrBaselineContract.CONFIDENCE_BUCKET_IDENTITY
                                ),
                                "龙岗文化中心公交站牌",
                                DocumentObservationIR.Sensitivity.EPHEMERAL_UNTRUSTED
                        ))
                ))
        );

        var record = new RapidOcrShadowCaseEvaluator().evaluate(evaluationCase, observation, 123L);

        assertEquals("transit-board-v3", record.caseId());
        assertEquals(evaluationCase.annotation().ocrLines().size(), record.layout().lines().expected());
        assertEquals(1, record.layout().lines().predicted());
        assertEquals(1, record.layout().lines().matched());
        assertEquals(1, record.layout().observedRegions());
        assertEquals(0, record.order().comparableEdges());
        assertTrue(record.repeat().expectedGroups() > 0);
        assertEquals(0, record.repeat().completeGroups());
        assertEquals(1, record.confidence().observations());
        assertEquals(9_500, record.confidence().meanNativeValueBps());
        assertEquals(123L, record.acquisitionMicros());
        assertFalse(record.toString().contains("龙岗文化中心公交站牌"));
        assertFalse(record.toString().toLowerCase().contains("ocrtext"));
    }

    private static DocumentObservationIR.SourcePixelBox pixels(
            int left,
            int top,
            int right,
            int bottom,
            int width,
            int height
    ) {
        return new DocumentObservationIR.SourcePixelBox(
                Math.max(0, Math.floorDiv(left * width, 10_000)),
                Math.max(0, Math.floorDiv(top * height, 10_000)),
                Math.max(1, Math.ceilDiv(right * width, 10_000)),
                Math.max(1, Math.ceilDiv(bottom * height, 10_000))
        );
    }

    private static DocumentObservationIR.Provenance provenance() {
        return new DocumentObservationIR.Provenance(
                RapidOcrBaselineContract.CAPABILITY_IDENTITY,
                RapidOcrBaselineContract.ADAPTER_IDENTITY,
                RapidOcrBaselineContract.ENGINE,
                RapidOcrBaselineContract.ENGINE_VERSION,
                RapidOcrBaselineContract.MODEL_MANIFEST_SHA256,
                RapidOcrBaselineContract.PREPROCESSING_IDENTITY,
                RapidOcrBaselineContract.POSTPROCESSING_IDENTITY,
                RapidOcrBaselineContract.READING_ORDER_IDENTITY,
                RapidOcrBaselineContract.PROJECTION_IDENTITY,
                RapidOcrBaselineContract.CONFIDENCE_SCALE_IDENTITY,
                RapidOcrBaselineContract.CONFIDENCE_BUCKET_IDENTITY,
                RapidOcrBaselineContract.CANONICALIZATION_IDENTITY
        );
    }
}
