package cn.hbads.renderweave.inference.vision;

import cn.hbads.renderweave.inference.candidate.CandidateBoundingBox;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DocumentVisionContractTest {
    private static final String ARTIFACT = "a".repeat(64);
    private static final String CAPABILITY =
            "rapidocr-3.9.2-openvino-2026.0.0-ppocrv6-small-c05805399d7d10b1";

    @Test
    void observationsAreBoundedCanonicalAndPayloadRedacted() {
        var second = new DocumentVisionObservation.TextLine(
                "ocr-00-001", 1, new CandidateBoundingBox(100, 500, 900, 900),
                DocumentVisionObservation.ConfidenceBucket.MEDIUM, "  龙岗\t文化中心  "
        );
        var first = new DocumentVisionObservation.TextLine(
                "ocr-00-000", 0, new CandidateBoundingBox(100, 100, 900, 400),
                DocumentVisionObservation.ConfidenceBucket.HIGH, "Longgang Culture Center"
        );
        var observation = DocumentVisionObservation.canonical(
                CAPABILITY,
                List.of(new DocumentVisionObservation.ArtifactObservation(
                        ARTIFACT, 0, List.of(second, first)
                ))
        );

        assertEquals(
                List.of("ocr-00-000", "ocr-00-001"),
                observation.artifacts().getFirst().lines().stream()
                        .map(DocumentVisionObservation.TextLine::lineId).toList()
        );
        assertEquals("龙岗 文化中心", observation.artifacts().getFirst().lines().get(1).text());
        assertFalse(observation.toString().contains("龙岗"));
        assertFalse(observation.toString().contains("Longgang"));
        assertFalse(observation.artifacts().getFirst().toString().contains("龙岗"));
        assertFalse(observation.artifacts().getFirst().lines().getFirst().toString().contains("Longgang"));
    }

    @Test
    void artifactBytesAreDefensivelyCopiedAndRedacted() {
        var source = new byte[]{1, 2, 3};
        var artifact = new DocumentVisionArtifact(ARTIFACT, 0, "image/png", source, 10, 20);
        source[0] = 9;
        var returned = artifact.bytes();
        returned[1] = 9;

        assertArrayEquals(new byte[]{1, 2, 3}, artifact.bytes());
        assertFalse(artifact.toString().contains("[1, 2, 3]"));
    }

    @Test
    void invalidTextOrderAndAggregateLimitsFailClosed() {
        var invalidText = assertThrows(IllegalArgumentException.class, () -> new DocumentVisionObservation.TextLine(
                "ocr-00-000", 0, new CandidateBoundingBox(0, 0, 100, 100),
                DocumentVisionObservation.ConfidenceBucket.LOW, "x".repeat(257)
        ));
        assertEquals("Document vision text boundary is invalid", invalidText.getMessage());

        var wrongOrder = new DocumentVisionObservation.TextLine(
                "ocr-00-001", 1, new CandidateBoundingBox(0, 0, 100, 100),
                DocumentVisionObservation.ConfidenceBucket.LOW, "value"
        );
        var invalidOrder = assertThrows(IllegalArgumentException.class,
                () -> new DocumentVisionObservation.ArtifactObservation(
                ARTIFACT, 0, List.of(wrongOrder)
        ));
        assertEquals("Document vision line reading order must be contiguous", invalidOrder.getMessage());
    }

    @Test
    void unavailableAdapterFailsWithOnlyStableDiagnostic() {
        var adapter = DocumentVisionPreprocessor.unavailable("DOCUMENT_VISION_DISABLED");

        assertFalse(adapter.capability().available());
        var failure = assertThrows(DocumentVisionException.class, () -> adapter.preprocess(List.of()));
        assertEquals("DOCUMENT_VISION_DISABLED", failure.getMessage());
    }
}
