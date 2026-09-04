package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.candidate.CandidateBoundingBox;
import cn.hbads.renderweave.inference.candidate.CandidateEvidence;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class VisualRegionFallbackClassifierTest {
    private static final String ARTIFACT_ID = "a".repeat(64);
    private static final String ENTRY = "VISUAL_GROUNDING_REGION_ENTRY_INVALID";
    private static final String ID = "VISUAL_GROUNDING_REGION_ID_INVALID";
    private static final String PARENT_ID = "VISUAL_GROUNDING_REGION_PARENT_ID_INVALID";
    private static final String MULTIPLICITY = "VISUAL_GROUNDING_REGION_MULTIPLICITY_INVALID";
    private static final String READING_ORDER = "VISUAL_GROUNDING_REGION_READING_ORDER_INVALID";
    private static final String REPEAT_GROUP_ID =
            "VISUAL_GROUNDING_REGION_REPEAT_GROUP_ID_INVALID";
    private static final String EVIDENCE = "VISUAL_GROUNDING_REGION_EVIDENCE_INVALID";

    @Test
    void classifiesTheFrozenProviderZeroMatrixWithoutPayloadDetails() throws Exception {
        var matrix = matrix();
        assertEquals(matrix.closedDetailCodes(),
                VisualRegionFallbackClassifier.closedDetailCodes());

        for (var fixture : matrix.fixtures()) {
            var actual = classify(fixture, "runtime detail must not escape");
            assertEquals(fixture.expectedDisposition(), actual.disposition().name(),
                    fixture.fixtureId());
            assertEquals(fixture.expectedPrimaryCode(), actual.primaryCode(),
                    fixture.fixtureId());
            assertEquals(fixture.expectedDetailCodes(), actual.detailCodes(),
                    fixture.fixtureId());
            assertEquals(fixture.expectedKnownFieldFamilyCount(),
                    actual.knownFieldFamilyCount(), fixture.fixtureId());
            assertFalse(actual.toString().contains("runtime detail must not escape"),
                    fixture.fixtureId());
        }
    }

    @Test
    void unknownFailureMessagesCannotChangeOrPopulateUnclassifiedDetails() throws Exception {
        var fixture = matrix().fixtures().stream()
                .filter(value -> value.mode().equals("UNCLASSIFIED_VALIDATOR_EXCEPTION"))
                .findFirst().orElseThrow();

        var first = classify(fixture, "first synthetic constructor detail");
        var second = classify(fixture, "different environment-specific detail");

        assertEquals(first, second);
        assertEquals("VISUAL_GROUNDING_REGION_UNCLASSIFIED", first.primaryCode());
        assertEquals(List.of(), first.detailCodes());
        assertEquals(0, first.knownFieldFamilyCount());
    }

    @Test
    void validClassificationHasNoFailureCodeOrDetail() {
        var actual = VisualRegionFallbackClassifier.classify(
                List.of(validInput(List.of(validEvidence()))), this::validateEvidence
        );

        assertEquals(VisualRegionFallbackClassifier.Disposition.VALID, actual.disposition());
        assertNull(actual.primaryCode());
        assertEquals(List.of(), actual.detailCodes());
        assertEquals(0, actual.knownFieldFamilyCount());
    }

    private VisualRegionFallbackClassifier.Classification classify(
            Fixture fixture,
            String unknownFailureMessage
    ) {
        if (fixture.mode().equals("UNCLASSIFIED_COLLECTION")) {
            return VisualRegionFallbackClassifier.classify(null, this::validateEvidence);
        }
        var inputs = new ArrayList<VisualRegionFallbackClassifier.RegionInput>();
        for (var fieldCodes : fixture.regionFailureCodes()) {
            if (fieldCodes.contains(ENTRY)) {
                assertEquals(List.of(ENTRY), fieldCodes, fixture.fixtureId());
                inputs.add(null);
            } else {
                inputs.add(input(fieldCodes));
            }
        }
        VisualRegionFallbackClassifier.EvidenceValidator validator =
                fixture.mode().equals("UNCLASSIFIED_VALIDATOR_EXCEPTION")
                ? ignored -> {
                    throw new IllegalStateException(unknownFailureMessage);
                }
                : this::validateEvidence;
        return VisualRegionFallbackClassifier.classify(inputs, validator);
    }

    private VisualRegionFallbackClassifier.RegionInput input(List<String> failures) {
        var evidence = failures.contains(EVIDENCE)
                ? List.of(new VisualViewEvidence(
                        "fixture-evidence-invalid", new CandidateBoundingBox(0, 0, 10_000, 10_000)
                ))
                : List.of(validEvidence());
        return new VisualRegionFallbackClassifier.RegionInput(
                failures.contains(ID) ? "Invalid" : "region",
                failures.contains(PARENT_ID) ? "Invalid" : null,
                failures.contains(MULTIPLICITY) ? null : VisualMultiplicity.ONE,
                failures.contains(READING_ORDER) ? 128 : 0,
                failures.contains(REPEAT_GROUP_ID) ? "Invalid" : null,
                evidence
        );
    }

    private VisualRegionFallbackClassifier.RegionInput validInput(
            List<VisualViewEvidence> evidence
    ) {
        return new VisualRegionFallbackClassifier.RegionInput(
                "region", null, VisualMultiplicity.ONE, 0, null, evidence
        );
    }

    private List<CandidateEvidence> validateEvidence(List<VisualViewEvidence> evidence) {
        Objects.requireNonNull(evidence, "evidence");
        if (evidence.stream().anyMatch(value ->
                value == null || !"view-00-overview-00".equals(value.viewId()))) {
            throw new IllegalArgumentException("synthetic view is unavailable");
        }
        return List.of(CandidateEvidence.image(
                ARTIFACT_ID, new CandidateBoundingBox(0, 0, 10_000, 10_000)
        ));
    }

    private static VisualViewEvidence validEvidence() {
        return new VisualViewEvidence(
                "view-00-overview-00", new CandidateBoundingBox(0, 0, 10_000, 10_000)
        );
    }

    private static Matrix matrix() throws IOException {
        try (var input = VisualRegionFallbackClassifierTest.class.getResourceAsStream(
                "/image-only/v49-region-fallback-provenance-v1.json"
        )) {
            return JsonMapper.builder().build().readValue(
                    Objects.requireNonNull(input, "matrix"), Matrix.class
            );
        }
    }

    private record Matrix(
            String version,
            List<String> closedDetailCodes,
            List<Fixture> fixtures
    ) {
        private Matrix {
            Objects.requireNonNull(version, "version");
            closedDetailCodes = List.copyOf(closedDetailCodes);
            fixtures = List.copyOf(fixtures);
        }
    }

    private record Fixture(
            String fixtureId,
            String mode,
            List<List<String>> regionFailureCodes,
            String expectedDisposition,
            String expectedPrimaryCode,
            List<String> expectedDetailCodes,
            int expectedKnownFieldFamilyCount
    ) {
        private Fixture {
            Objects.requireNonNull(fixtureId, "fixtureId");
            Objects.requireNonNull(mode, "mode");
            regionFailureCodes = regionFailureCodes.stream().map(List::copyOf).toList();
            Objects.requireNonNull(expectedDisposition, "expectedDisposition");
            expectedDetailCodes = List.copyOf(expectedDetailCodes);
        }
    }
}
