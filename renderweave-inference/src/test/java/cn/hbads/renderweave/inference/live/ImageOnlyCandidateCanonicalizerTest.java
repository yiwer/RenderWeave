package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.candidate.CandidateAssessment;
import cn.hbads.renderweave.inference.candidate.CandidateBundle;
import cn.hbads.renderweave.inference.candidate.CandidateEvidence;
import cn.hbads.renderweave.inference.candidate.CandidateField;
import cn.hbads.renderweave.inference.candidate.CandidateReference;
import cn.hbads.renderweave.inference.candidate.CandidateResolution;
import cn.hbads.renderweave.inference.candidate.CandidateSchema;
import cn.hbads.renderweave.inference.candidate.CandidateSource;
import cn.hbads.renderweave.inference.candidate.CandidateValue;
import cn.hbads.renderweave.inference.candidate.CandidateValueKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageOnlyCandidateCanonicalizerTest {

    private final ImageOnlyCandidateCanonicalizer canonicalizer = new ImageOnlyCandidateCanonicalizer();

    @Test
    void normalizesOnlyTechnicalSchemaIdentityAndRedundantScalarObservations() {
        var schemaId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        var fieldId = UUID.fromString("22222222-2222-4222-8222-222222222222");
        var assessment = assessment();
        var result = canonicalizer.canonicalize(new CandidateBundle(
                CandidateBundle.CONTRACT_VERSION, schemaId,
                List.of(new CandidateSchema(
                        schemaId, "商品卡片", "商品卡片", CandidateSource.AI, assessment,
                        List.of(new CandidateField(
                                fieldId, "title", "标题", false,
                                new CandidateValue(
                                        CandidateValueKind.TEXT, null, null,
                                        List.of("TEXT"), Map.of("minLength", "1")
                                ),
                                CandidateSource.AI, assessment
                        ))
                ))
        ));

        var schema = result.candidate().schemas().getFirst();
        var field = schema.fields().getFirst();
        assertEquals("inferred-11111111111141118111111111111111", schema.proposedSchemaKey());
        assertEquals("title", field.proposedFieldKey());
        assertEquals(CandidateValueKind.TEXT, field.value().kind());
        assertTrue(field.value().observedKinds().isEmpty());
        assertEquals("1", field.value().constraints().get("minLength"));
        assertEquals(
                List.of(
                        "CANDIDATE_SCHEMA_KEY_NORMALIZED",
                        "CANDIDATE_SCALAR_OBSERVED_KINDS_NORMALIZED"
                ),
                result.problems().stream().map(problem -> problem.code()).toList()
        );
    }

    @Test
    void leavesScalarTopologyConflictsBlocked() {
        var schemaId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        var fieldId = UUID.fromString("22222222-2222-4222-8222-222222222222");
        var assessment = assessment();
        var illegalReference = CandidateReference.candidate(
                UUID.fromString("33333333-3333-4333-8333-333333333333")
        );
        var value = new CandidateValue(
                CandidateValueKind.TEXT, null, illegalReference, List.of("TEXT"), Map.of()
        );
        var result = canonicalizer.canonicalize(new CandidateBundle(
                CandidateBundle.CONTRACT_VERSION, schemaId,
                List.of(new CandidateSchema(
                        schemaId, "valid-schema", "结构", CandidateSource.AI, assessment,
                        List.of(new CandidateField(
                                fieldId, "title", "标题", false, value, CandidateSource.AI, assessment
                        ))
                ))
        ));

        assertEquals(value, result.candidate().schemas().getFirst().fields().getFirst().value());
        assertTrue(result.problems().isEmpty());
    }

    @Test
    void convertsAnArtifactWidePixelCoordinateFamilyToNormalizedCoordinates() {
        var schemaId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        var artifactId = "normalized-image";
        var boxes = List.of(
                new cn.hbads.renderweave.inference.candidate.CandidateBoundingBox(200, 200, 1_300, 500),
                new cn.hbads.renderweave.inference.candidate.CandidateBoundingBox(200, 500, 1_300, 700),
                new cn.hbads.renderweave.inference.candidate.CandidateBoundingBox(200, 700, 800, 800),
                new cn.hbads.renderweave.inference.candidate.CandidateBoundingBox(800, 700, 1_300, 800),
                new cn.hbads.renderweave.inference.candidate.CandidateBoundingBox(200, 800, 1_300, 900),
                new cn.hbads.renderweave.inference.candidate.CandidateBoundingBox(200, 900, 1_300, 3_800),
                new cn.hbads.renderweave.inference.candidate.CandidateBoundingBox(200, 3_900, 1_300, 4_096)
        );
        var fields = IntStream.range(0, boxes.size()).mapToObj(index -> new CandidateField(
                UUID.nameUUIDFromBytes(("field-" + index).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "field" + index, "字段 " + index, false,
                CandidateValue.scalar(CandidateValueKind.TEXT), CandidateSource.AI,
                CandidateAssessment.ai(
                        9_000, true, CandidateResolution.NOT_REQUIRED,
                        List.of(CandidateEvidence.image(artifactId, boxes.get(index)))
                )
        )).toList();
        var candidate = new CandidateBundle(
                CandidateBundle.CONTRACT_VERSION, schemaId,
                List.of(new CandidateSchema(
                        schemaId, "route-card", "线路卡", CandidateSource.AI,
                        CandidateAssessment.ai(
                                9_000, true, CandidateResolution.NOT_REQUIRED,
                                List.of(CandidateEvidence.image(artifactId, boxes.getFirst()))
                        ),
                        fields
                ))
        );

        var result = canonicalizer.canonicalize(candidate, Map.of(
                artifactId, new ImageOnlyCandidateCanonicalizer.ImageDimensions(1_510, 4_096)
        ));

        var converted = result.candidate().schemas().getFirst().fields().get(5)
                .assessment().evidence().getFirst().boundingBox();
        assertEquals(
                new cn.hbads.renderweave.inference.candidate.CandidateBoundingBox(
                        1_324, 2_197, 8_610, 9_278
                ),
                converted
        );
        assertEquals(
                8,
                result.problems().stream()
                        .filter(problem -> problem.code().equals("IMAGE_EVIDENCE_PIXEL_COORDINATES_NORMALIZED"))
                        .count()
        );
    }

    @Test
    void leavesBroadOriginTouchingButEndAmbiguousFamiliesUnchanged() {
        var schemaId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        var fieldId = UUID.fromString("22222222-2222-4222-8222-222222222222");
        var artifactId = "ambiguous-image";
        var schemaBox = new cn.hbads.renderweave.inference.candidate.CandidateBoundingBox(
                0, 0, 1_300, 2_000
        );
        var fieldBox = new cn.hbads.renderweave.inference.candidate.CandidateBoundingBox(
                100, 1_800, 1_400, 3_800
        );
        var candidate = new CandidateBundle(
                CandidateBundle.CONTRACT_VERSION, schemaId,
                List.of(new CandidateSchema(
                        schemaId, "route-card", "线路卡", CandidateSource.AI,
                        CandidateAssessment.ai(
                                9_000, true, CandidateResolution.NOT_REQUIRED,
                                List.of(CandidateEvidence.image(artifactId, schemaBox))
                        ),
                        List.of(new CandidateField(
                                fieldId, "stops", "站点", false,
                                CandidateValue.scalar(CandidateValueKind.TEXT), CandidateSource.AI,
                                CandidateAssessment.ai(
                                        9_000, true, CandidateResolution.NOT_REQUIRED,
                                        List.of(CandidateEvidence.image(artifactId, fieldBox))
                                )
                        ))
                ))
        );

        var result = canonicalizer.canonicalize(candidate, Map.of(
                artifactId, new ImageOnlyCandidateCanonicalizer.ImageDimensions(1_510, 4_096)
        ));

        assertEquals(fieldBox, result.candidate().schemas().getFirst().fields().getFirst()
                .assessment().evidence().getFirst().boundingBox());
        assertTrue(result.problems().isEmpty());
    }

    private static CandidateAssessment assessment() {
        return CandidateAssessment.ai(
                9_000, true, CandidateResolution.NOT_REQUIRED,
                List.of(CandidateEvidence.image(
                        "artifact", new cn.hbads.renderweave.inference.candidate.CandidateBoundingBox(
                                100, 100, 900, 900
                        )
                ))
        );
    }
}
