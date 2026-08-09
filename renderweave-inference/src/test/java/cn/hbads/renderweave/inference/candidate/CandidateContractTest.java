package cn.hbads.renderweave.inference.candidate;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

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
    void strictCodecClassifiesDecodeFailuresWithoutRetainingProviderMembersOrValues() {
        var valid = codec.write(simpleBundle());
        var unknown = valid.replaceFirst("\\{", "{\"sensitiveCustomerValue\":\"must-not-survive\",");
        var duplicate = valid.replaceFirst(
                "\"contractVersion\":\"renderweave-candidate/1.0\"",
                "\"contractVersion\":\"renderweave-candidate/1.0\",\"contractVersion\":\"other\""
        );
        var invalidValue = valid.replace("\"source\":\"AI\"", "\"source\":\"ALIEN\"");
        var adversarialUnknown = valid.replaceFirst(
                "\\{", "{\"duplicate trailing token\":\"must-not-steer-taxonomy\","
        );
        var adversarialValue = valid.replace(
                "\"source\":\"AI\"", "\"source\":\"trailing token\""
        );

        assertDecodeDiagnostic(unknown, "CANDIDATE_DECODE_UNKNOWN_MEMBER");
        assertDecodeDiagnostic(adversarialUnknown, "CANDIDATE_DECODE_UNKNOWN_MEMBER");
        assertDecodeDiagnostic(duplicate, "CANDIDATE_DECODE_DUPLICATE_MEMBER");
        assertDecodeDiagnostic(valid + "{}", "CANDIDATE_DECODE_TRAILING_CONTENT");
        assertDecodeDiagnostic("{", "CANDIDATE_DECODE_SYNTAX_INVALID");
        assertDecodeDiagnostic("duplicate", "CANDIDATE_DECODE_SYNTAX_INVALID");
        assertDecodeDiagnostic("[]", "CANDIDATE_DECODE_SHAPE_INVALID");
        assertDecodeDiagnostic("\"trailing token\"", "CANDIDATE_DECODE_SHAPE_INVALID");
        assertDecodeDiagnostic("\"trailing content\"", "CANDIDATE_DECODE_SHAPE_INVALID");
        assertDecodeDiagnostic(invalidValue, "CANDIDATE_DECODE_ENUM_INVALID_SOURCE");
        assertDecodeDiagnostic(adversarialValue, "CANDIDATE_DECODE_ENUM_INVALID_SOURCE");
        assertDecodeDiagnostic(" ", "CANDIDATE_DECODE_REQUIRED");
        assertDecodeDiagnostic(
                " ".repeat(CandidateJsonCodec.MAX_CANDIDATE_BYTES + 1),
                "CANDIDATE_DECODE_TOO_LARGE"
        );

        var diagnostic = assertThrows(
                InvalidCandidateContractException.class, () -> codec.parse(unknown)
        ).diagnosticCode();
        assertFalse(diagnostic.contains("sensitiveCustomerValue"));
        assertFalse(diagnostic.contains("must-not-survive"));
    }

    @Test
    void invalidCandidateEnumUsesABoundedPayloadFreeContractSlot() {
        var providerValue = "SENSITIVE_CUSTOM_SOURCE_VALUE";
        var invalid = codec.write(simpleBundle()).replace(
                "\"source\":\"AI\"", "\"source\":\"" + providerValue + "\""
        );

        var diagnostic = assertThrows(
                InvalidCandidateContractException.class, () -> codec.parse(invalid)
        ).diagnosticCode();

        assertEquals("CANDIDATE_DECODE_ENUM_INVALID_SOURCE", diagnostic);
        assertTrue(diagnostic.matches("[A-Z][A-Z0-9_]{0,127}"));
        assertFalse(diagnostic.contains(providerValue));
    }

    @Test
    void everyCandidateEnumUsesItsFiniteContractSlot() {
        var root = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var child = UUID.fromString("00000000-0000-0000-0000-000000000002");
        var fieldId = UUID.fromString("00000000-0000-0000-0000-000000000011");
        var bundle = new CandidateBundle(CandidateBundle.CONTRACT_VERSION, root, List.of(
                schema(root, "root", "Root", List.of(
                        field(fieldId, "child",
                                CandidateValue.reference(CandidateReference.candidate(child)), "/child")
                ), ""),
                schema(child, "child", "Child", List.of(), "/child")
        ));
        var valid = codec.write(bundle);

        assertDecodeDiagnostic(
                valid.replaceFirst("\"resolution\":\"NOT_REQUIRED\"", "\"resolution\":\"ALIEN\""),
                "CANDIDATE_DECODE_ENUM_INVALID_RESOLUTION"
        );
        assertDecodeDiagnostic(
                valid.replaceFirst("\"kind\":\"JSON\"", "\"kind\":\"ALIEN\""),
                "CANDIDATE_DECODE_ENUM_INVALID_EVIDENCE_KIND"
        );
        assertDecodeDiagnostic(
                valid.replaceFirst("\"kind\":\"REFERENCE\"", "\"kind\":\"ALIEN\""),
                "CANDIDATE_DECODE_ENUM_INVALID_VALUE_KIND"
        );
        assertDecodeDiagnostic(
                valid.replaceFirst("\"kind\":\"CANDIDATE_SCHEMA\"", "\"kind\":\"ALIEN\""),
                "CANDIDATE_DECODE_ENUM_INVALID_REFERENCE_KIND"
        );
    }

    @Test
    void constructorInvariantUsesABoundedBundleMemberSlot() {
        var invalid = """
                {"contractVersion":"renderweave-candidate/1.0",
                 "rootCandidateSchemaId":"00000000-0000-0000-0000-000000000001",
                 "schemas":null}
                """;

        assertDecodeDiagnostic(
                invalid, "CANDIDATE_DECODE_CONSTRUCTOR_INVALID_BUNDLE_SCHEMAS"
        );
    }

    @Test
    void constructorInvariantsUseOnlyFiniteRecordMemberSlots() {
        var simple = codec.write(simpleBundle());
        var root = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var fieldId = UUID.fromString("00000000-0000-0000-0000-000000000011");
        var withField = codec.write(new CandidateBundle(
                CandidateBundle.CONTRACT_VERSION, root, List.of(
                        schema(root, "root", "Root", List.of(
                                field(fieldId, "title",
                                        CandidateValue.scalar(CandidateValueKind.TEXT), "/title")
                        ), "")
                )
        ));

        assertDecodeDiagnostic(
                simple.replace("\"contractVersion\":\"renderweave-candidate/1.0\"",
                        "\"contractVersion\":null"),
                "CANDIDATE_DECODE_CONSTRUCTOR_INVALID_BUNDLE_CONTRACT_VERSION"
        );
        assertDecodeDiagnostic(
                simple.replace("\"rootCandidateSchemaId\":\"00000000-0000-0000-0000-000000000001\"",
                        "\"rootCandidateSchemaId\":null"),
                "CANDIDATE_DECODE_CONSTRUCTOR_INVALID_BUNDLE_ROOT_SCHEMA_ID"
        );
        assertDecodeDiagnostic(
                simple.replace("\"candidateSchemaId\":\"00000000-0000-0000-0000-000000000001\"",
                        "\"candidateSchemaId\":null"),
                "CANDIDATE_DECODE_CONSTRUCTOR_INVALID_SCHEMA_ID"
        );
        assertDecodeDiagnostic(
                simple.replace("\"source\":\"AI\"", "\"source\":null"),
                "CANDIDATE_DECODE_CONSTRUCTOR_INVALID_SCHEMA_SOURCE"
        );
        assertDecodeDiagnostic(
                simple.replace("\"fields\":[]", "\"fields\":null"),
                "CANDIDATE_DECODE_CONSTRUCTOR_INVALID_SCHEMA_FIELDS"
        );
        assertDecodeDiagnostic(
                withField.replace("\"candidateFieldId\":\"00000000-0000-0000-0000-000000000011\"",
                        "\"candidateFieldId\":null"),
                "CANDIDATE_DECODE_CONSTRUCTOR_INVALID_FIELD_ID"
        );
        assertDecodeDiagnostic(
                withField.replace("\"kind\":\"TEXT\"", "\"kind\":null"),
                "CANDIDATE_DECODE_CONSTRUCTOR_INVALID_VALUE_KIND"
        );
        assertDecodeDiagnostic(
                withField.replace("\"observedKinds\":[]", "\"observedKinds\":null"),
                "CANDIDATE_DECODE_CONSTRUCTOR_INVALID_VALUE_OBSERVED_KINDS"
        );
        assertDecodeDiagnostic(
                withField.replace("\"constraints\":{}", "\"constraints\":null"),
                "CANDIDATE_DECODE_CONSTRUCTOR_INVALID_VALUE_CONSTRAINTS"
        );
        assertDecodeDiagnostic(
                simple.replace("\"resolution\":\"NOT_REQUIRED\"", "\"resolution\":null"),
                "CANDIDATE_DECODE_CONSTRUCTOR_INVALID_ASSESSMENT_RESOLUTION"
        );
        assertDecodeDiagnostic(
                simple.replaceFirst("\"evidence\":\\[[^]]*]", "\"evidence\":null"),
                "CANDIDATE_DECODE_CONSTRUCTOR_INVALID_ASSESSMENT_EVIDENCE"
        );
    }

    @Test
    void nonEnumFormatsUseFiniteContractSlotsWithoutProviderValuesOrPaths() {
        var simple = codec.write(simpleBundle());
        var root = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var fieldId = UUID.fromString("00000000-0000-0000-0000-000000000011");
        var withField = codec.write(new CandidateBundle(
                CandidateBundle.CONTRACT_VERSION, root, List.of(
                        schema(root, "root", "Root", List.of(
                                field(fieldId, "title",
                                        CandidateValue.scalar(CandidateValueKind.TEXT), "/title")
                        ), "")
                )
        ));
        var providerValue = "SENSITIVE_FORMAT_VALUE!";

        assertDecodeDiagnostic(
                simple.replaceFirst(
                        "00000000-0000-0000-0000-000000000001", providerValue
                ),
                "CANDIDATE_DECODE_FORMAT_INVALID_ROOT_SCHEMA_ID"
        );
        assertDecodeDiagnostic(
                simple.replace(
                        "\"candidateSchemaId\":\"00000000-0000-0000-0000-000000000001\"",
                        "\"candidateSchemaId\":\"" + providerValue + "\""
                ),
                "CANDIDATE_DECODE_FORMAT_INVALID_SCHEMA_ID"
        );
        assertDecodeDiagnostic(
                withField.replace(
                        "\"candidateFieldId\":\"00000000-0000-0000-0000-000000000011\"",
                        "\"candidateFieldId\":\"" + providerValue + "\""
                ),
                "CANDIDATE_DECODE_FORMAT_INVALID_FIELD_ID"
        );
        assertDecodeDiagnostic(
                simple.replace("\"confidenceBps\":9200",
                        "\"confidenceBps\":\"" + providerValue + "\""),
                "CANDIDATE_DECODE_FORMAT_INVALID_ASSESSMENT_CONFIDENCE"
        );
        assertDecodeDiagnostic(
                simple.replace("\"sampleIndex\":0",
                        "\"sampleIndex\":\"" + providerValue + "\""),
                "CANDIDATE_DECODE_FORMAT_INVALID_EVIDENCE_SAMPLE_INDEX"
        );

        var diagnostic = assertThrows(
                InvalidCandidateContractException.class,
                () -> codec.parse(simple.replaceFirst(
                        "00000000-0000-0000-0000-000000000001", providerValue
                ))
        ).diagnosticCode();
        assertTrue(diagnostic.matches("[A-Z][A-Z0-9_]{0,127}"));
        assertFalse(diagnostic.contains(providerValue));
        assertFalse(diagnostic.contains("/"));
    }

    @Test
    void scalarCoercionsCannotBypassFiniteValueAttribution() {
        var root = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var fieldId = UUID.fromString("00000000-0000-0000-0000-000000000011");
        var valid = codec.write(new CandidateBundle(
                CandidateBundle.CONTRACT_VERSION, root, List.of(
                        schema(root, "root", "Root", List.of(
                                field(fieldId, "title",
                                        CandidateValue.scalar(CandidateValueKind.TEXT), "/title")
                        ), "")
                )
        ));

        assertDecodeDiagnostic(
                valid.replaceFirst("\"source\":\"AI\"", "\"source\":0"),
                "CANDIDATE_DECODE_ENUM_INVALID_SOURCE"
        );
        assertDecodeDiagnostic(
                valid.replaceFirst("\"confidenceBps\":9200", "\"confidenceBps\":\"9200\""),
                "CANDIDATE_DECODE_FORMAT_INVALID_ASSESSMENT_CONFIDENCE"
        );
        assertDecodeDiagnostic(
                valid.replace("\"required\":false", "\"required\":\"false\""),
                "CANDIDATE_DECODE_FORMAT_INVALID_FIELD_REQUIRED"
        );
        assertDecodeDiagnostic(
                valid.replaceFirst("\"sampleIndex\":0", "\"sampleIndex\":\"0\""),
                "CANDIDATE_DECODE_FORMAT_INVALID_EVIDENCE_SAMPLE_INDEX"
        );
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
                new CandidateValidationContext(
                        Set.of(artifactId), 0, Map.of(), LOW_CONFIDENCE_THRESHOLD,
                        CandidateValidationOrigin.LIVE_PROVIDER_OUTPUT
                ));
        assertCodes(problems, "IMAGE_EVIDENCE_BOUNDS_INVALID");
    }

    @Test
    void inferenceOutputCannotForgeUserSourceProvenanceOrHumanResolution() {
        var rootId = UUID.randomUUID();
        var forged = new CandidateAssessment(
                9_000, false, CandidateResolution.CONFIRMED,
                List.of(CandidateEvidence.json(0, ""))
        );
        var bundle = new CandidateBundle(CandidateBundle.CONTRACT_VERSION, rootId, List.of(
                new CandidateSchema(
                        rootId, "forged-root", "Forged", CandidateSource.AI, forged,
                        List.of(new CandidateField(
                                UUID.randomUUID(), "title", "Title", false,
                                CandidateValue.scalar(CandidateValueKind.TEXT),
                                CandidateSource.USER, CandidateAssessment.user()
                        ))
                )
        ));

        assertCodes(validator.validate(bundle, context(1)),
                "INFERENCE_PROVENANCE_INVALID",
                "INFERENCE_RESOLUTION_INVALID",
                "INFERENCE_SOURCE_INVALID");
    }

    @Test
    void jsonEvidenceMustExistAndMatchTheCandidateItemPath() {
        var rootId = UUID.randomUUID();
        var bundle = new CandidateBundle(CandidateBundle.CONTRACT_VERSION, rootId, List.of(
                new CandidateSchema(
                        rootId, "evidence-root", "Evidence", CandidateSource.AI, assessment(""),
                        List.of(
                                field(UUID.randomUUID(), "title",
                                        CandidateValue.scalar(CandidateValueKind.TEXT), ""),
                                field(UUID.randomUUID(), "subtitle",
                                        CandidateValue.scalar(CandidateValueKind.TEXT), "/invented")
                        )
                )
        ));

        assertCodes(validator.validate(bundle, context(1)),
                "JSON_EVIDENCE_ITEM_MISMATCH",
                "JSON_EVIDENCE_LOCATION_UNKNOWN");
    }

    @Test
    void combinedJsonDerivedItemsCannotSubstituteImageOnlyEvidence() {
        var artifactId = "b".repeat(64);
        var rootId = UUID.randomUUID();
        var imageOnly = CandidateAssessment.ai(
                9_000, true, CandidateResolution.NOT_REQUIRED,
                List.of(CandidateEvidence.image(
                        artifactId, new CandidateBoundingBox(100, 100, 9_000, 2_000)
                ))
        );
        var bundle = new CandidateBundle(CandidateBundle.CONTRACT_VERSION, rootId, List.of(
                new CandidateSchema(
                        rootId, "combined-root", "Combined", CandidateSource.AI, imageOnly,
                        List.of(new CandidateField(
                                UUID.randomUUID(), "title", "Title", false,
                                CandidateValue.scalar(CandidateValueKind.TEXT), CandidateSource.AI, imageOnly
                        ))
                )
        ));
        var initial = context(1);
        var combined = new CandidateValidationContext(
                Set.of(artifactId), initial.jsonSampleCount(), initial.jsonEvidenceByNodePointer(),
                initial.lowConfidenceThresholdBps(), CandidateValidationOrigin.LIVE_PROVIDER_OUTPUT
        );

        assertCodes(validator.validate(bundle, combined), "JSON_EVIDENCE_ITEM_MISSING");
    }

    @Test
    void lowConfidenceConcreteAssertionCannotClaimNotRequired() {
        var rootId = UUID.randomUUID();
        var low = CandidateAssessment.ai(
                1, true, CandidateResolution.NOT_REQUIRED,
                List.of(CandidateEvidence.json(0, "/title"))
        );
        var bundle = new CandidateBundle(CandidateBundle.CONTRACT_VERSION, rootId, List.of(
                new CandidateSchema(
                        rootId, "low-confidence", "Low confidence", CandidateSource.AI, assessment(""),
                        List.of(new CandidateField(
                                UUID.randomUUID(), "title", "Title", false,
                                CandidateValue.scalar(CandidateValueKind.TEXT), CandidateSource.AI, low
                        ))
                )
        ));

        assertCodes(validator.validate(bundle, context(1)), "LOW_CONFIDENCE_STATE_INVALID");
    }

    @Test
    void highConfidenceUnresolvedDispositionRemainsAnExplicitReviewBlocker() {
        var rootId = UUID.randomUUID();
        var unresolved = CandidateAssessment.ai(
                9_000, true, CandidateResolution.UNRESOLVED,
                List.of(CandidateEvidence.json(0, "/title"))
        );
        var bundle = new CandidateBundle(CandidateBundle.CONTRACT_VERSION, rootId, List.of(
                new CandidateSchema(
                        rootId, "review-boundary", "Review boundary", CandidateSource.AI, assessment(""),
                        List.of(new CandidateField(
                                UUID.randomUUID(), "title", "Title", false,
                                CandidateValue.scalar(CandidateValueKind.TEXT), CandidateSource.AI, unresolved
                        ))
                )
        ));

        assertCodes(validator.validate(bundle, context(1)), "CANDIDATE_ITEM_UNRESOLVED");
    }

    @Test
    void largeJsonEvidenceCatalogSupportsRepeatedConstantTimeMembershipChecks() {
        var catalog = new java.util.LinkedHashMap<String, Set<CandidateEvidence>>();
        for (var index = 0; index < 4_096; index++) {
            var pointer = "/field-" + index;
            catalog.put(pointer, Set.of(CandidateEvidence.json(0, pointer)));
        }
        var context = new CandidateValidationContext(
                Set.of(), 1, catalog, LOW_CONFIDENCE_THRESHOLD,
                CandidateValidationOrigin.LIVE_PROVIDER_OUTPUT
        );
        var target = CandidateEvidence.json(0, "/field-4095");

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            for (var lookup = 0; lookup < 100_000; lookup++) {
                assertTrue(context.jsonEvidenceKnown(target));
            }
        });
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

    @Test
    void removedFieldRetainsAuditDataWithoutBlockingOnItsUnresolvedTypeOrConfidence() {
        var root = UUID.randomUUID();
        var fieldId = UUID.randomUUID();
        var removed = CandidateAssessment.ai(
                2_100, true, CandidateResolution.REMOVED,
                List.of(CandidateEvidence.json(0, "/ambiguous"))
        );
        var bundle = new CandidateBundle(CandidateBundle.CONTRACT_VERSION, root, List.of(
                new CandidateSchema(root, "removal-review", "移除审核", CandidateSource.AI,
                        assessment(""), List.of(new CandidateField(
                                fieldId, "ambiguous", "歧义字段", false,
                                CandidateValue.unresolved("null", "empty-array"), CandidateSource.AI, removed
                        )))
        ));

        var problems = validator.validate(bundle, reviewContext(1));
        assertFalse(problems.stream().anyMatch(problem -> fieldId.equals(problem.itemId())),
                () -> "Removed field must not retain semantic blockers: " + problems);
    }

    @Test
    void activeReferenceToRemovedSchemaRemainsAMissingTargetBlocker() {
        var root = UUID.randomUUID();
        var removedTarget = UUID.randomUUID();
        var removed = CandidateAssessment.ai(
                9_000, true, CandidateResolution.REMOVED,
                List.of(CandidateEvidence.json(0, "/child"))
        );
        var bundle = new CandidateBundle(CandidateBundle.CONTRACT_VERSION, root, List.of(
                schema(root, "root", "Root", List.of(
                        field(UUID.randomUUID(), "child",
                                CandidateValue.reference(CandidateReference.candidate(removedTarget)), "/child")
                ), ""),
                new CandidateSchema(removedTarget, null, null, CandidateSource.AI, removed, List.of())
        ));

        var problems = validator.validate(bundle, reviewContext(1));
        assertCodes(problems, "CANDIDATE_REFERENCE_TARGET_MISSING");
        assertFalse(problems.stream().anyMatch(problem -> problem.code().equals("CANDIDATE_SCHEMA_KEY_UNRESOLVED")
                && removedTarget.equals(problem.itemId())));
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
        var pointers = List.of(
                "", "/detail", "/detail/name", "/emptyItems", "/child", "/missing",
                "/parent", "/orphan", "/title", "/subtitle", "/ambiguous", "/discarded"
        );
        var catalog = new java.util.LinkedHashMap<String, Set<CandidateEvidence>>();
        for (var pointer : pointers) {
            var locations = new java.util.LinkedHashSet<CandidateEvidence>();
            for (var sampleIndex = 0; sampleIndex < sampleCount; sampleIndex++) {
                locations.add(CandidateEvidence.json(sampleIndex, pointer));
            }
            catalog.put(pointer, Set.copyOf(locations));
        }
        return new CandidateValidationContext(
                Set.of(), sampleCount, catalog, LOW_CONFIDENCE_THRESHOLD,
                CandidateValidationOrigin.LIVE_PROVIDER_OUTPUT
        );
    }

    private static CandidateValidationContext reviewContext(int sampleCount) {
        var initial = context(sampleCount);
        return new CandidateValidationContext(
                initial.imageArtifactIds(), initial.jsonSampleCount(), initial.jsonEvidenceByNodePointer(),
                initial.lowConfidenceThresholdBps(), CandidateValidationOrigin.USER_REVIEW
        );
    }

    private static void assertCodes(List<CandidateProblem> problems, String... expectedCodes) {
        for (var code : expectedCodes) {
            assertTrue(problems.stream().anyMatch(problem -> problem.code().equals(code)),
                    () -> "Expected " + code + ", got " + problems);
        }
    }

    private void assertDecodeDiagnostic(String json, String expectedCode) {
        assertEquals(expectedCode,
                assertThrows(InvalidCandidateContractException.class, () -> codec.parse(json))
                        .diagnosticCode());
    }
}
