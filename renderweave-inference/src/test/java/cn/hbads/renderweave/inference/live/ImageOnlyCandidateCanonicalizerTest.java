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
