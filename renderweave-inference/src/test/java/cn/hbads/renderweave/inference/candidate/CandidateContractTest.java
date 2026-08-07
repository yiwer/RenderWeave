package cn.hbads.renderweave.inference.candidate;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CandidateContractTest {
    private static final int LOW_CONFIDENCE_THRESHOLD = 8_000;

    private final CandidateValidator validator = new CandidateValidator();
    private final CandidateJsonCodec codec = new CandidateJsonCodec();

    @Test
    void validNestedCandidateRoundTripsThroughTheStrictCandidateOnlyContract() {
        var childId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        var rootId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var bundle = new CandidateBundle(
                CandidateBundle.CONTRACT_VERSION,
                rootId,
                List.of(
                        schema(rootId, "product-card", "商品卡片", List.of(
                                field("00000000-0000-0000-0000-000000000011", "detail",
                                        CandidateValue.reference(CandidateReference.candidate(childId)), "/detail")
                        ), ""),
                        schema(childId, "product-card-detail", "商品详情", List.of(
                                field("00000000-0000-0000-0000-000000000012", "name",
                                        CandidateValue.scalar(CandidateValueKind.TEXT), "/detail/name")
                        ), "/detail")
                )
        );

        var problems = validator.validate(bundle, context(2));
        assertTrue(problems.isEmpty(), () -> "Unexpected problems: " + problems);

        var encoded = codec.write(bundle);
        assertEquals(bundle, codec.parse(encoded));
        assertFalse(encoded.contains("fieldId"));
        assertTrue(encoded.contains("candidateFieldId"));
        assertTrue(encoded.contains("UNRESOLVED") == false);
    }

    @Test
    void strictCodecRejectsUnknownDuplicateAndOversizedCandidateJson() {
        var valid = codec.write(simpleBundle());
        var unknown = valid.replaceFirst("\\{", "{\"formalDsl\":{},");
        assertEquals("CANDIDATE_JSON_INVALID",
                assertThrows(InvalidCandidateContractException.class, () -> codec.parse(unknown)).code());

        var duplicate = valid.replaceFirst(
                "\"contractVersion\":\"renderweave-candidate/1.0\"",
                "\"contractVersion\":\"renderweave-candidate/1.0\",\"contractVersion\":\"other\""
        );
        assertEquals("CANDIDATE_JSON_INVALID",
                assertThrows(InvalidCandidateContractException.class, () -> codec.parse(duplicate)).code());

        var oversized = " ".repeat(CandidateJsonCodec.MAX_CANDIDATE_BYTES + 1);
        assertEquals("CANDIDATE_TOO_LARGE",
                assertThrows(InvalidCandidateContractException.class, () -> codec.parse(oversized)).code());
    }

    @Test
    void unresolvedAndLowConfidenceItemsRemainReviewBlockers() {
        var rootId = UUID.randomUUID();
        var fieldId = UUID.randomUUID();
        var low = new CandidateAssessment(
                6_500, true, CandidateResolution.UNRESOLVED,
                List.of(CandidateEvidence.json(0, "/emptyItems"))
        );
        var bundle = new CandidateBundle(CandidateBundle.CONTRACT_VERSION, rootId, List.of(
                new CandidateSchema(
                        rootId, "empty-array", "空数组", CandidateSource.AI,
                        assessment(""),
                        List.of(new CandidateField(
                                fieldId, "items", "项目", false,
                                CandidateValue.array(CandidateValue.unresolved("empty-array")),
                                CandidateSource.AI, low
                        ))
                )
        ));

        var problems = validator.validate(bundle, context(1));
        assertCodes(problems, "LOW_CONFIDENCE_UNRESOLVED", "CANDIDATE_TYPE_UNRESOLVED");
        assertTrue(problems.stream().allMatch(problem -> problem.severity() == CandidateProblemSeverity.BLOCKER));
    }

    @Test
    void malformedEvidenceIsBlockedWithoutClampingOrGuessing() {
        var artifactId = "a".repeat(64);
        var rootId = UUID.randomUUID();
        var assessment = CandidateAssessment.ai(
                9_000, true, CandidateResolution.NOT_REQUIRED,
                List.of(CandidateEvidence.image(
                        artifactId, new CandidateBoundingBox(9000, -1, 8000, 10001)
                ))
        );
        var bundle = new CandidateBundle(CandidateBundle.CONTRACT_VERSION, rootId, List.of(
                new CandidateSchema(rootId, "visual-card", "视觉卡片", CandidateSource.AI,
                        assessment, List.of())
        ));

        var problems = validator.validate(bundle,
                new CandidateValidationContext(Set.of(artifactId), 0, LOW_CONFIDENCE_THRESHOLD));
        assertCodes(problems, "IMAGE_EVIDENCE_BOUNDS_INVALID");
    }

    @Test
    void orphanCycleAndMissingTargetsAreDeterministicGraphBlockers() {
        var root = UUID.randomUUID();
        var child = UUID.randomUUID();
        var orphan = UUID.randomUUID();
        var missing = UUID.randomUUID();
        var bundle = new CandidateBundle(CandidateBundle.CONTRACT_VERSION, root, List.of(
                schema(root, "root", "Root", List.of(
                        field(UUID.randomUUID().toString(), "child",
                                CandidateValue.reference(CandidateReference.candidate(child)), "/child"),
                        field(UUID.randomUUID().toString(), "missing",
                                CandidateValue.reference(CandidateReference.candidate(missing)), "/missing")
                ), ""),
                schema(child, "child", "Child", List.of(
                        field(UUID.randomUUID().toString(), "parent",
                                CandidateValue.reference(CandidateReference.candidate(root)), "/parent")
                ), "/child"),
                schema(orphan, "orphan", "Orphan", List.of(), "/orphan")
        ));

        var problems = validator.validate(bundle, context(1));
        assertCodes(problems,
                "CANDIDATE_REFERENCE_TARGET_MISSING",
                "CANDIDATE_SCHEMA_ORPHAN",
                "CANDIDATE_REFERENCE_CYCLE"
        );
    }

    @Test
    void aiRequiredAndConstraintSuggestionsNeedIndividualUserResolution() {
        var root = UUID.randomUUID();
        var fieldId = UUID.randomUUID();
        var value = new CandidateValue(
                CandidateValueKind.TEXT, null, null, List.of(), Map.of("maxLength", "80")
        );
        var bundle = new CandidateBundle(CandidateBundle.CONTRACT_VERSION, root, List.of(
                new CandidateSchema(root, "confirmation-card", "确认卡", CandidateSource.AI,
                        assessment(""), List.of(new CandidateField(
                                fieldId, "title", "标题", true, value, CandidateSource.AI,
                                assessment("/title")
                        )))
        ));

        assertCodes(validator.validate(bundle, context(1)),
                "AI_REQUIRED_UNCONFIRMED", "AI_CONSTRAINT_UNCONFIRMED");
    }

    private static CandidateBundle simpleBundle() {
        var root = UUID.fromString("00000000-0000-0000-0000-000000000001");
        return new CandidateBundle(CandidateBundle.CONTRACT_VERSION, root, List.of(
                schema(root, "simple", "Simple", List.of(), "")
        ));
    }

    private static CandidateSchema schema(
            UUID id,
            String key,
            String displayName,
            List<CandidateField> fields,
            String evidencePointer
    ) {
        return new CandidateSchema(id, key, displayName, CandidateSource.AI,
                assessment(evidencePointer), fields);
    }

    private static CandidateField field(
            String id,
            String key,
            CandidateValue value,
            String evidencePointer
    ) {
        return field(UUID.fromString(id), key, value, evidencePointer);
    }

    private static CandidateField field(
            UUID id,
            String key,
            CandidateValue value,
            String evidencePointer
    ) {
        return new CandidateField(id, key, key, false, value, CandidateSource.AI,
                assessment(evidencePointer));
    }

    private static CandidateAssessment assessment(String pointer) {
        return CandidateAssessment.ai(
                9_200, true, CandidateResolution.NOT_REQUIRED,
                List.of(CandidateEvidence.json(0, pointer))
        );
    }

    private static CandidateValidationContext context(int sampleCount) {
        return new CandidateValidationContext(Set.of(), sampleCount, LOW_CONFIDENCE_THRESHOLD);
    }

    private static void assertCodes(List<CandidateProblem> problems, String... expectedCodes) {
        for (var code : expectedCodes) {
            assertTrue(problems.stream().anyMatch(problem -> problem.code().equals(code)),
                    () -> "Expected " + code + ", got " + problems);
        }
    }
}
