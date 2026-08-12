package cn.hbads.renderweave.inference.vision;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DocumentObservationCompatibilityProjectionTest {
    private static final String CAPABILITY =
            "rapidocr-3.9.2-openvino-2026.0.0-ppocrv6-small-c05805399d7d10b1";
    private static final String MANIFEST =
            "c05805399d7d10b1d1e32f2f52faf2a9fe6617db50f6b96221cb3b7be47e58a5";
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    @Test
    void lockedOddDimensionAndBoundaryGoldensUseV45FloorCeilProjection() throws Exception {
        var fixture = JSON.readValue(
                getClass().getResourceAsStream("/document-observation/v45-projection-golden.json"),
                GoldenFixture.class
        );
        assertEquals("renderweave-v45-projection-golden/1.0", fixture.fixtureVersion());
        var artifactObservations = new ArrayList<DocumentObservationIR.ArtifactObservation>();
        for (var index = 0; index < fixture.cases().size(); index++) {
            var golden = fixture.cases().get(index);
            artifactObservations.add(new DocumentObservationIR.ArtifactObservation(
                    Integer.toHexString(index + 10).repeat(64), index, golden.mediaType(),
                    golden.width(), golden.height(), true,
                    List.of(new DocumentObservationIR.TextLine(
                            "ocr-%02d-000".formatted(index), 0,
                            box(golden.sourceBox()),
                            new DocumentObservationIR.Confidence(
                                    golden.confidenceBps(), "basis-points/1.0",
                                    DocumentObservationIR.ConfidenceBucket.valueOf(golden.expectedBucket()),
                                    "v45-confidence-buckets/1.0"
                            ),
                            "  sentinel\t%02d  ".formatted(index),
                            DocumentObservationIR.Sensitivity.EPHEMERAL_UNTRUSTED
                    ))
            ));
        }
        var ir = DocumentObservationIR.canonical(policy("v45-source-to-candidate/1.0"),
                provenance("v45-source-to-candidate/1.0"), artifactObservations);

        var projected = new DocumentObservationCompatibilityProjection().project(ir);

        assertEquals(CAPABILITY, projected.capabilityId());
        assertEquals(fixture.cases().size(), projected.lineCount());
        for (var index = 0; index < fixture.cases().size(); index++) {
            var golden = fixture.cases().get(index);
            var line = projected.artifacts().get(index).lines().getFirst();
            assertEquals("ocr-%02d-000".formatted(index), line.lineId());
            assertEquals(ir.artifacts().get(index).observations().getFirst().observationId(), line.lineId());
            assertEquals(0, line.readingOrder());
            assertEquals(golden.expectedBox(), List.of(
                    line.boundingBox().left(), line.boundingBox().top(),
                    line.boundingBox().right(), line.boundingBox().bottom()
            ));
            assertEquals(golden.expectedBucket(), line.confidence().name());
            assertEquals("sentinel %02d".formatted(index), line.text());
        }
    }

    @Test
    void projectionIdentityMismatchFailsWithOnlyAStableCode() {
        var ir = DocumentObservationIR.canonical(
                policy("different-projection/1.0"),
                provenance("different-projection/1.0"),
                List.of(new DocumentObservationIR.ArtifactObservation(
                        "a".repeat(64), 0, "image/png", 10, 10, true,
                        List.of(new DocumentObservationIR.TextLine(
                                "ocr-00-000", 0,
                                new DocumentObservationIR.SourcePixelBox(0, 0, 10, 10),
                                new DocumentObservationIR.Confidence(
                                        9_000, "basis-points/1.0",
                                        DocumentObservationIR.ConfidenceBucket.HIGH,
                                        "v45-confidence-buckets/1.0"
                                ),
                                "not exposed", DocumentObservationIR.Sensitivity.EPHEMERAL_UNTRUSTED
                        ))
                ))
        );

        var failure = assertThrows(DocumentVisionException.class,
                () -> new DocumentObservationCompatibilityProjection().project(ir));

        assertEquals("DOCUMENT_VISION_PROJECTION_IDENTITY_MISMATCH", failure.code());
        assertEquals("DOCUMENT_VISION_PROJECTION_IDENTITY_MISMATCH", failure.getMessage());
    }

    private static DocumentObservationIR.SourcePixelBox box(List<Integer> values) {
        return new DocumentObservationIR.SourcePixelBox(
                values.get(0), values.get(1), values.get(2), values.get(3)
        );
    }

    private static AcquisitionPolicy policy(String projectionIdentity) {
        return new AcquisitionPolicy(
                AcquisitionPolicy.VERSION, DocumentObservationIR.VERSION, CAPABILITY,
                "rapidocr-local-process/1.0", "rapidocr-openvino-ppocrv6-small",
                "rapidocr-3.9.2+openvino-2026.0.0", MANIFEST,
                "explicit-bgr/1.0", "rapidocr-lines/1.0", "source-pixel-top-left/1.0",
                "half-open-box/1.0", projectionIdentity, "top-left-canonical/1.0",
                "unicode-nfc-whitespace-collapse/1.0", "basis-points/1.0",
                "v45-confidence-buckets/1.0", AcquisitionPolicy.TextExposure.EPHEMERAL_STAGE_CONTEXT_ONLY,
                10, 512, 256, 32 * 1024, 512 * 1024, 60_000
        );
    }

    private static DocumentObservationIR.Provenance provenance(String projectionIdentity) {
        return new DocumentObservationIR.Provenance(
                CAPABILITY, "rapidocr-local-process/1.0", "rapidocr-openvino-ppocrv6-small",
                "rapidocr-3.9.2+openvino-2026.0.0", MANIFEST,
                "explicit-bgr/1.0", "rapidocr-lines/1.0", "top-left-canonical/1.0",
                projectionIdentity, "basis-points/1.0", "v45-confidence-buckets/1.0",
                "unicode-nfc-whitespace-collapse/1.0"
        );
    }

    private record GoldenFixture(String fixtureVersion, List<GoldenCase> cases) {
    }

    private record GoldenCase(
            String mediaType,
            int width,
            int height,
            List<Integer> sourceBox,
            List<Integer> expectedBox,
            int confidenceBps,
            String expectedBucket
    ) {
    }
}
