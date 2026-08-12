package cn.hbads.renderweave.inference.vision;

import cn.hbads.renderweave.inference.live.LiveInferenceWorker;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisualEvidenceAcquisitionContractTest {
    private static final String FIRST_ARTIFACT = "a".repeat(64);
    private static final String SECOND_ARTIFACT = "b".repeat(64);
    private static final String MODEL_MANIFEST = "c".repeat(64);
    private static final String CAPABILITY =
            "rapidocr-3.9.2-openvino-2026.0.0-ppocrv6-small-c05805399d7d10b1";

    @Test
    void normalizedArtifactsAndObservationsAreCanonicalBoundedAndPayloadRedacted() {
        var firstBytes = new byte[]{1, 2, 3};
        var secondBytes = new byte[]{4, 5, 6};
        var artifacts = ArtifactSet.canonical(List.of(
                new ArtifactSet.Artifact(SECOND_ARTIFACT, 1, "image/jpeg", secondBytes, 101, 51, true),
                new ArtifactSet.Artifact(FIRST_ARTIFACT, 0, "image/png", firstBytes, 99, 49, true)
        ));
        firstBytes[0] = 9;
        secondBytes[0] = 9;

        var policy = policy();
        var ir = DocumentObservationIR.canonical(
                policy,
                provenance(),
                List.of(
                        new DocumentObservationIR.ArtifactObservation(
                                SECOND_ARTIFACT, 1, "image/jpeg", 101, 51, true,
                                List.of(new DocumentObservationIR.TextLine(
                                        "ocr-01-000", 0,
                                        new DocumentObservationIR.SourcePixelBox(1, 2, 100, 50),
                                        new DocumentObservationIR.Confidence(
                                                6_001, "basis-points/1.0",
                                                DocumentObservationIR.ConfidenceBucket.MEDIUM,
                                                "v45-confidence-buckets/1.0"
                                        ),
                                        "  龙岗\t文化中心  ",
                                        DocumentObservationIR.Sensitivity.EPHEMERAL_UNTRUSTED
                                ))
                        ),
                        new DocumentObservationIR.ArtifactObservation(
                                FIRST_ARTIFACT, 0, "image/png", 99, 49, true,
                                List.of(new DocumentObservationIR.TextLine(
                                        "ocr-00-000", 0,
                                        new DocumentObservationIR.SourcePixelBox(0, 0, 99, 49),
                                        new DocumentObservationIR.Confidence(
                                                9_000, "basis-points/1.0",
                                                DocumentObservationIR.ConfidenceBucket.HIGH,
                                                "v45-confidence-buckets/1.0"
                                        ),
                                        "Longgang Culture Center",
                                        DocumentObservationIR.Sensitivity.EPHEMERAL_UNTRUSTED
                                ))
                        )
                )
        );

        assertEquals(List.of(FIRST_ARTIFACT, SECOND_ARTIFACT),
                artifacts.artifacts().stream().map(ArtifactSet.Artifact::artifactId).toList());
        assertArrayEquals(new byte[]{1, 2, 3}, artifacts.artifacts().getFirst().bytes());
        var returnedBytes = artifacts.artifacts().getFirst().bytes();
        returnedBytes[1] = 9;
        assertArrayEquals(new byte[]{1, 2, 3}, artifacts.artifacts().getFirst().bytes());

        assertEquals(DocumentObservationIR.VERSION, ir.contractVersion());
        assertEquals(policy.identity(), ir.acquisitionPolicyIdentity());
        assertEquals(List.of(FIRST_ARTIFACT, SECOND_ARTIFACT),
                ir.artifacts().stream().map(DocumentObservationIR.ArtifactObservation::artifactId).toList());
        assertEquals("龙岗 文化中心", ir.artifacts().get(1).observations().getFirst().text());
        assertEquals(2, ir.observationCount());

        var representations = artifacts + "\n" + artifacts.artifacts().getFirst() + "\n"
                + policy + "\n" + ir + "\n" + ir.artifacts().get(1) + "\n"
                + ir.artifacts().get(1).observations().getFirst();
        assertFalse(representations.contains("Longgang"));
        assertFalse(representations.contains("龙岗"));
        assertFalse(representations.contains("[1, 2, 3]"));
    }

    @Test
    void invalidIdentityGeometryTextAndPolicyBoundsFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> new ArtifactSet.Artifact(
                "not-a-sha", 0, "image/png", new byte[]{1}, 10, 10, true
        ));
        assertThrows(IllegalArgumentException.class, () -> new ArtifactSet.Artifact(
                FIRST_ARTIFACT, 0, "image/png", new byte[]{1}, 10, 10, false
        ));
        assertThrows(IllegalArgumentException.class, () -> new DocumentObservationIR.SourcePixelBox(
                0, 0, 11, 10
        ).requireWithin(10, 10));

        var policy = policy();
        var tooLong = "x".repeat(policy.maximumLineTextBytes() + 1);
        var failure = assertThrows(IllegalArgumentException.class, () -> DocumentObservationIR.canonical(
                policy,
                provenance(),
                List.of(new DocumentObservationIR.ArtifactObservation(
                        FIRST_ARTIFACT, 0, "image/png", 10, 10, true,
                        List.of(new DocumentObservationIR.TextLine(
                                "ocr-00-000", 0,
                                new DocumentObservationIR.SourcePixelBox(0, 0, 10, 10),
                                new DocumentObservationIR.Confidence(
                                        5_000, "basis-points/1.0",
                                        DocumentObservationIR.ConfidenceBucket.LOW,
                                        "v45-confidence-buckets/1.0"
                                ),
                                tooLong,
                                DocumentObservationIR.Sensitivity.EPHEMERAL_UNTRUSTED
                        ))
                ))
        ));
        assertEquals("DOCUMENT_OBSERVATION_TEXT_LIMIT_EXCEEDED", failure.getMessage());
    }

    @Test
    void observationContractCannotCarrySemanticOrCandidateTypes() {
        var forbiddenPackages = List.of(
                "cn.hbads.renderweave.inference.candidate",
                "cn.hbads.renderweave.inference.live"
        );
        var contractTypes = Stream.concat(
                Stream.of(DocumentObservationIR.class, ArtifactSet.class, AcquisitionPolicy.class),
                Arrays.stream(DocumentObservationIR.class.getDeclaredClasses())
        ).toList();

        var referencedTypes = contractTypes.stream()
                .filter(Class::isRecord)
                .flatMap(type -> Arrays.stream(type.getRecordComponents()))
                .map(RecordComponent::getGenericType)
                .map(Object::toString)
                .toList();

        assertTrue(referencedTypes.stream().noneMatch(reference ->
                forbiddenPackages.stream().anyMatch(reference::contains)));
    }

    @Test
    void unavailableAcquisitionFailsWithOnlyAStableCode() {
        var acquisition = VisualEvidenceAcquisition.unavailable("DOCUMENT_OBSERVATION_CAPABILITY_UNAVAILABLE");

        var failure = assertThrows(VisualEvidenceAcquisitionException.class,
                () -> acquisition.acquire(ArtifactSet.canonical(List.of(
                        new ArtifactSet.Artifact(
                                FIRST_ARTIFACT, 0, "image/png", new byte[]{1}, 1, 1, true
                        )
                )), policy()));

        assertEquals("DOCUMENT_OBSERVATION_CAPABILITY_UNAVAILABLE", failure.code());
        assertEquals("DOCUMENT_OBSERVATION_CAPABILITY_UNAVAILABLE", failure.getMessage());
        assertFalse(acquisition.toString().contains(FIRST_ARTIFACT));
    }

    @Test
    void liveWorkerDependsOnTheSuccessorSeamAndNotTheLegacyPreprocessor() {
        assertTrue(Arrays.stream(LiveInferenceWorker.class.getDeclaredFields())
                .anyMatch(field -> field.getType() == VisualEvidenceAcquisition.class));
        assertFalse(Arrays.stream(LiveInferenceWorker.class.getDeclaredFields())
                .anyMatch(field -> field.getType() == DocumentVisionPreprocessor.class));
        assertFalse(Arrays.stream(LiveInferenceWorker.class.getDeclaredConstructors())
                .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes()))
                .anyMatch(type -> type == DocumentVisionPreprocessor.class));
    }

    private static AcquisitionPolicy policy() {
        return new AcquisitionPolicy(
                AcquisitionPolicy.VERSION,
                DocumentObservationIR.VERSION,
                CAPABILITY,
                "rapidocr-local-process/1.0",
                "rapidocr",
                "3.9.2",
                MODEL_MANIFEST,
                "explicit-bgr/1.0",
                "rapidocr-lines/1.0",
                "source-pixel-top-left/1.0",
                "half-open-box/1.0",
                "v45-source-to-candidate/1.0",
                "top-left-canonical/1.0",
                "unicode-nfc-whitespace-collapse/1.0",
                "basis-points/1.0",
                "v45-confidence-buckets/1.0",
                AcquisitionPolicy.TextExposure.EPHEMERAL_STAGE_CONTEXT_ONLY,
                10,
                512,
                256,
                32 * 1024,
                512 * 1024,
                60_000
        );
    }

    private static DocumentObservationIR.Provenance provenance() {
        return new DocumentObservationIR.Provenance(
                CAPABILITY,
                "rapidocr-local-process/1.0",
                "rapidocr",
                "3.9.2",
                MODEL_MANIFEST,
                "explicit-bgr/1.0",
                "rapidocr-lines/1.0",
                "top-left-canonical/1.0",
                "v45-source-to-candidate/1.0",
                "basis-points/1.0",
                "v45-confidence-buckets/1.0",
                "unicode-nfc-whitespace-collapse/1.0"
        );
    }
}
