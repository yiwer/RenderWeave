package cn.hbads.renderweave.inference.eval;

import cn.hbads.renderweave.inference.candidate.CandidateAssessment;
import cn.hbads.renderweave.inference.candidate.CandidateBoundingBox;
import cn.hbads.renderweave.inference.candidate.CandidateBundle;
import cn.hbads.renderweave.inference.candidate.CandidateEvidence;
import cn.hbads.renderweave.inference.candidate.CandidateField;
import cn.hbads.renderweave.inference.candidate.CandidateProblem;
import cn.hbads.renderweave.inference.candidate.CandidateProblemSeverity;
import cn.hbads.renderweave.inference.candidate.CandidateReference;
import cn.hbads.renderweave.inference.candidate.CandidateResolution;
import cn.hbads.renderweave.inference.candidate.CandidateSchema;
import cn.hbads.renderweave.inference.candidate.CandidateSource;
import cn.hbads.renderweave.inference.candidate.CandidateValue;
import cn.hbads.renderweave.inference.candidate.CandidateValueKind;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveCandidateEvaluatorTest {
    private final LiveEvaluationCorpus corpus = new LiveEvaluationCorpus();
    private final LiveCandidateEvaluator evaluator = new LiveCandidateEvaluator();
    private final LiveEvaluationReporter reporter = new LiveEvaluationReporter();
    private final LiveCertificationPolicy policy = new LiveCertificationPolicy();

    @Test
    void versionTwoGoldCorpusHasSixtyBalancedSyntheticCasesAndCompleteGraphGold() {
        assertEquals(60, corpus.cases().size());
        var fixtures = corpus.cases().stream().map(LiveEvaluationCase::fixtureId).toList();
        assertEquals(60, new java.util.HashSet<>(fixtures).size());
        var modes = corpus.cases().stream().collect(Collectors.groupingBy(
                LiveEvaluationCase::mode, Collectors.counting()
        ));
        assertTrue(modes.values().stream().allMatch(count -> count == 20));
        assertEquals(45, corpus.cases().stream()
                .filter(item -> item.partition() == LiveEvaluationPartition.DEV).count());
        assertEquals(15, corpus.cases().stream()
                .filter(item -> item.partition() == LiveEvaluationPartition.HOLDOUT).count());
        assertTrue(corpus.cases().stream().allMatch(item ->
                item.expectedSchemaCount() == item.expectedSchemas().size()));
        assertEquals(Map.of("city", "TEXT"),
                corpus.require("live-json-02-nested-object").expectedNestedShapes().get("/address"));
        assertEquals(3, corpus.require("live-json-19-deep-objects").expectedSchemas().size());
    }

    @Test
    void exactWholeGraphTypeEvidenceAndDagPassWithoutDependingOnGeneratedIdsOrSchemaKeys() {
        var gold = corpus.require("live-image-06-object-array");
        var result = evaluator.evaluate(gold, exactCandidate(gold), List.of());

        assertTrue(result.passed());
        assertEquals(10_000, result.bundleContractBps());
        assertEquals(10_000, result.schemaEntityF1Bps());
        assertEquals(10_000, result.fieldF1Bps());
        assertEquals(10_000, result.supportedTypeAccuracyBps());
        assertEquals(10_000, result.parentChildEdgeF1Bps());
        assertEquals(10_000, result.evidenceCoverageBps());
        assertEquals(10_000, result.dagValidityBps());
        assertEquals(0, result.criticalHallucinationCount());
    }

    @Test
    void wrongTypeAndUnprovenRequirednessAreIndependentRegressions() {
        var result = evaluator.evaluate(
                corpus.require("live-json-01-scalars"), scalarCandidate(true, CandidateValueKind.TEXT), List.of()
        );

        assertFalse(result.passed());
        assertIterableEquals(List.of("/#score:DECIMAL!=TEXT"), result.typeMismatches());
        assertEquals(6_666, result.supportedTypeAccuracyBps());
        assertEquals(10_000, result.fieldF1Bps());
        assertEquals(1, result.criticalHallucinationCount());
        assertEquals(1, result.supportedTypeMismatchCount());
        assertEquals(1, result.unsupportedAssertionCount());
        assertEquals(0, result.unexpectedFieldCount());
    }

    @Test
    void uncertainScalarGoldCannotBeConcretizedWithoutACriticalHallucination() {
        var gold = corpus.require("live-image-08-low-information");
        var result = evaluator.evaluate(
                gold,
                replaceRootFieldValue(
                        exactCandidate(gold), "value", CandidateValue.scalar(CandidateValueKind.TEXT)
                ),
                List.of()
        );

        assertFalse(result.passed());
        assertIterableEquals(List.of("/#value:UNRESOLVED!=TEXT"), result.typeMismatches());
        assertEquals(0, result.supportedTypeExpectedCount());
        assertEquals(1, result.criticalHallucinationCount());
        assertEquals(1, result.unsupportedAssertionCount());
    }

    @Test
    void resultRejectsCriticalCountBelowStructuralHallucinations() {
        assertThrows(IllegalArgumentException.class, () -> new LiveEvaluationResult(
                "invalid-decomposition", "EVALUATED", false, 10_000,
                0, 1, 0,
                0, 0, 0,
                0, 0,
                0, 0, 0,
                0, 0, 10_000,
                0, 0,
                List.of(), List.of("/#unexpected"), List.of(), List.of(), List.of(), List.of()
        ));
    }

    @Test
    void forgedHumanDispositionIsAContractAndCriticalPolicyFailure() {
        var gold = corpus.require("live-json-01-scalars");
        var forged = replaceRootFieldAssessment(
                exactCandidate(gold), "name",
                new CandidateAssessment(
                        9_000, false, CandidateResolution.CONFIRMED,
                        List.of(CandidateEvidence.json(0, "/name"))
                )
        );
        var result = evaluator.evaluate(gold, forged, List.of());

        assertFalse(result.passed());
        assertEquals(0, result.bundleContractBps());
        assertEquals(1, result.criticalHallucinationCount());
        assertEquals(1, result.unsupportedAssertionCount());

        var results = corpus.cases().stream()
                .map(item -> item.caseId().equals(gold.caseId())
                        ? result
                        : evaluator.evaluate(item, exactCandidate(item), List.of()))
                .toList();
        var decision = policy.decide("dashscope-qwen37-plus-20260526-prompt-v2", corpus, results);
        assertEquals(LiveCertificationStatus.EXPERIMENTAL, decision.status());
        assertTrue(decision.violations().stream().anyMatch(value ->
                value.contains("CRITICAL_HALLUCINATION_NONZERO")));
    }

    @Test
    void invalidJsonEvidenceLocationCannotPassTheCertificationPolicy() {
        var gold = corpus.require("live-json-01-scalars");
        var invalidEvidence = evaluator.evaluate(
                gold, exactCandidate(gold), List.of(new CandidateProblem(
                        "JSON_EVIDENCE_ITEM_MISMATCH", CandidateProblemSeverity.BLOCKER,
                        null, "/schemas/0/fields/0/assessment/evidence/0", Map.of()
                ))
        );

        assertEquals(0, invalidEvidence.bundleContractBps());
        var results = corpus.cases().stream()
                .map(item -> item.caseId().equals(gold.caseId())
                        ? invalidEvidence
                        : evaluator.evaluate(item, exactCandidate(item), List.of()))
                .toList();
        var decision = policy.decide("dashscope-qwen37-plus-20260526-prompt-v2", corpus, results);
        assertEquals(LiveCertificationStatus.EXPERIMENTAL, decision.status());
        assertTrue(decision.violations().stream().anyMatch(value ->
                value.contains("BUNDLE_CONTRACT_BELOW_THRESHOLD")));
    }

    @Test
    void lowConfidenceConcreteAssertionCannotPassTheCertificationPolicy() {
        var gold = corpus.require("live-json-01-scalars");
        var lowConfidence = replaceRootFieldAssessment(
                exactCandidate(gold), "name",
                new CandidateAssessment(
                        1, true, CandidateResolution.NOT_REQUIRED,
                        List.of(CandidateEvidence.json(0, "/name"))
                )
        );
        var result = evaluator.evaluate(
                gold, lowConfidence, List.of(new CandidateProblem(
                        "LOW_CONFIDENCE_STATE_INVALID", CandidateProblemSeverity.BLOCKER,
                        null, "/schemas/0/fields/0/assessment/resolution", Map.of()
                ))
        );

        assertEquals(0, result.bundleContractBps());
        assertEquals(1, result.criticalHallucinationCount());
        assertEquals(1, result.unsupportedAssertionCount());
        var results = corpus.cases().stream()
                .map(item -> item.caseId().equals(gold.caseId())
                        ? result
                        : evaluator.evaluate(item, exactCandidate(item), List.of()))
                .toList();
        assertEquals(
                LiveCertificationStatus.EXPERIMENTAL,
                policy.decide("dashscope-qwen37-plus-20260526-prompt-v2", corpus, results).status()
        );
    }

    @Test
    void combinedJsonItemWithOnlyImageEvidenceCannotPassCertification() {
        var gold = corpus.require("live-combined-01-label-overlay");
        var imageOnly = replaceAllEvidenceWithImage(exactCandidate(gold));
        var result = evaluator.evaluate(
                gold, imageOnly, List.of(new CandidateProblem(
                        "JSON_EVIDENCE_ITEM_MISSING", CandidateProblemSeverity.BLOCKER,
                        null, "/schemas/0/assessment/evidence", Map.of()
                ))
        );

        assertEquals(10_000, result.evidenceCoverageBps());
        assertEquals(0, result.bundleContractBps());
        var results = corpus.cases().stream()
                .map(item -> item.caseId().equals(gold.caseId())
                        ? result
                        : evaluator.evaluate(item, exactCandidate(item), List.of()))
                .toList();
        assertEquals(
                LiveCertificationStatus.EXPERIMENTAL,
                policy.decide("dashscope-qwen37-plus-20260526-prompt-v2", corpus, results).status()
        );
    }

    @Test
    void uncertainArrayItemGoldCannotBeConcretizedOrCertified() {
        var gold = corpus.require("live-combined-15-heterogeneous-array");
        var asserted = evaluator.evaluate(
                gold,
                replaceRootFieldValue(
                        exactCandidate(gold), "values",
                        CandidateValue.array(CandidateValue.scalar(CandidateValueKind.TEXT))
                ),
                List.of()
        );

        assertFalse(asserted.passed());
        assertIterableEquals(
                List.of("/#values:ARRAY:CONFLICT!=ARRAY:TEXT"), asserted.typeMismatches()
        );
        assertEquals(1, asserted.criticalHallucinationCount());

        var results = corpus.cases().stream()
                .map(item -> item.caseId().equals(gold.caseId())
                        ? asserted
                        : evaluator.evaluate(item, exactCandidate(item), List.of()))
                .toList();
        var decision = policy.decide("dashscope-qwen37-flash-v1", corpus, results);
        assertEquals(LiveCertificationStatus.EXPERIMENTAL, decision.status());
        assertTrue(decision.violations().stream().anyMatch(value ->
                value.contains("CRITICAL_HALLUCINATION_NONZERO")));
    }

    @Test
    void mergedRoleSchemasCannotFakeEntityOrParentChildEdgeAccuracy() {
        var gold = corpus.require("live-image-16-two-groups");
        var merged = mergeRootReferenceTargets(exactCandidate(gold), "receiver", "sender");

        var result = evaluator.evaluate(gold, merged, List.of());

        assertFalse(result.passed());
        assertTrue(result.schemaEntityF1Bps() < 10_000);
        assertTrue(result.parentChildEdgeF1Bps() < 10_000);
        assertEquals(10_000, result.dagValidityBps());
    }

    @Test
    void partialReportsAndProviderFailuresCannotBecomeCertificationEvidence() {
        var gold = corpus.require("live-json-01-scalars");
        var one = evaluator.evaluate(gold, exactCandidate(gold), List.of());
        var report = reporter.report("dashscope-qwen37-flash-v1", corpus, List.of(one));

        assertFalse(report.complete());
        assertEquals(1, report.evaluatedCaseCount());
        assertEquals(59, report.missingCaseIds().size());
        assertEquals(LiveCertificationStatus.INCOMPLETE,
                policy.decide("dashscope-qwen37-flash-v1", corpus, List.of(one)).status());

        var failure = evaluator.failure(gold, "DASHSCOPE_NETWORK_ERROR");
        assertEquals(0, failure.bundleContractBps());
        assertEquals(0, failure.fieldRecallBps());
        assertEquals(0, failure.evidenceCoverageBps());
    }

    @Test
    void certificationPolicyAppliesGlobalModeAndHoldoutThresholds() {
        var exact = corpus.cases().stream()
                .map(gold -> evaluator.evaluate(gold, exactCandidate(gold), List.of()))
                .toList();
        var report = reporter.report("dashscope-qwen37-flash-v1", corpus, exact);

        assertTrue(report.complete());
        assertEquals(60, report.global().caseCount());
        assertEquals(20, report.byMode().get("IMAGE_ONLY").caseCount());
        assertEquals(15, report.byPartition().get("HOLDOUT").caseCount());
        assertEquals(LiveCertificationStatus.CERTIFIED,
                policy.decide("dashscope-qwen37-flash-v1", corpus, exact).status());

        var degraded = new ArrayList<>(exact);
        var gold = corpus.cases().getFirst();
        degraded.set(0, evaluator.evaluate(gold, withUnexpectedField(exactCandidate(gold)), List.of()));
        var degradedReport = reporter.report("dashscope-qwen37-flash-v1", corpus, degraded);
        assertEquals(1, degradedReport.global().diagnostics().unexpectedFieldCount());
        assertEquals(0, degradedReport.global().diagnostics().unsupportedAssertionCount());
        assertEquals(1, degradedReport.global().criticalHallucinationCount());
        var decision = policy.decide("dashscope-qwen37-flash-v1", corpus, degraded);
        assertEquals(LiveCertificationStatus.EXPERIMENTAL, decision.status());
        assertTrue(decision.violations().stream().anyMatch(value ->
                value.contains("CRITICAL_HALLUCINATION_NONZERO")));
    }

    private static CandidateBundle exactCandidate(LiveEvaluationCase gold) {
        var schemaIds = new LinkedHashMap<String, UUID>();
        var ordinal = 0;
        for (var path : gold.expectedSchemas().keySet()) {
            schemaIds.put(path, UUID.nameUUIDFromBytes(
                    (gold.caseId() + ":" + path + ":" + ordinal++).getBytes(StandardCharsets.UTF_8)
            ));
        }
        var schemas = new ArrayList<CandidateSchema>();
        ordinal = 0;
        for (var entry : gold.expectedSchemas().entrySet()) {
            var path = entry.getKey();
            var fields = new ArrayList<CandidateField>();
            for (var field : entry.getValue().entrySet()) {
                fields.add(field(
                        field.getKey(), value(field.getValue(), schemaIds.get(
                                LiveEvaluationCase.childPath(path, field.getKey())
                        )), false, assessment()
                ));
            }
            schemas.add(new CandidateSchema(
                    schemaIds.get(path), "generated-schema-" + ordinal++, "Generated",
                    CandidateSource.AI, assessment(), fields
            ));
        }
        return new CandidateBundle(CandidateBundle.CONTRACT_VERSION, schemaIds.get("/"), schemas);
    }

    private static CandidateValue value(String shape, UUID target) {
        if (shape.startsWith("ARRAY:")) return CandidateValue.array(value(shape.substring(6), target));
        return switch (shape) {
            case "TEXT" -> CandidateValue.scalar(CandidateValueKind.TEXT);
            case "DECIMAL" -> CandidateValue.scalar(CandidateValueKind.DECIMAL);
            case "DATE" -> CandidateValue.scalar(CandidateValueKind.DATE);
            case "TIME" -> CandidateValue.scalar(CandidateValueKind.TIME);
            case "BOOLEAN" -> CandidateValue.scalar(CandidateValueKind.BOOLEAN);
            case "REFERENCE" -> CandidateValue.reference(CandidateReference.candidate(target));
            case "UNRESOLVED" -> CandidateValue.unresolved("NULL");
            case "CONFLICT" -> CandidateValue.conflict("TEXT", "DECIMAL");
            default -> throw new IllegalArgumentException("Unsupported test shape " + shape);
        };
    }

    private static CandidateBundle mergeRootReferenceTargets(
            CandidateBundle bundle,
            String first,
            String second
    ) {
        var root = bundle.schemas().stream()
                .filter(schema -> schema.candidateSchemaId().equals(bundle.rootCandidateSchemaId()))
                .findFirst().orElseThrow();
        var target = root.fields().stream()
                .filter(field -> first.equals(field.proposedFieldKey()))
                .map(field -> field.value().reference().candidateSchemaId())
                .findFirst().orElseThrow();
        var fields = root.fields().stream().map(field -> second.equals(field.proposedFieldKey())
                ? new CandidateField(
                        field.candidateFieldId(), field.proposedFieldKey(), field.displayName(), field.required(),
                        CandidateValue.reference(CandidateReference.candidate(target)),
                        field.source(), field.assessment()
                ) : field).toList();
        var schemas = bundle.schemas().stream()
                .filter(schema -> !schema.candidateSchemaId().equals(root.candidateSchemaId())
                        && !schema.candidateSchemaId().equals(root.fields().stream()
                        .filter(field -> second.equals(field.proposedFieldKey()))
                        .map(field -> field.value().reference().candidateSchemaId()).findFirst().orElseThrow()))
                .map(schema -> schema.candidateSchemaId().equals(root.candidateSchemaId())
                        ? new CandidateSchema(
                        root.candidateSchemaId(), root.proposedSchemaKey(), root.displayName(),
                        root.source(), root.assessment(), fields
                ) : schema).toList();
        var withRoot = new ArrayList<CandidateSchema>();
        withRoot.add(new CandidateSchema(
                root.candidateSchemaId(), root.proposedSchemaKey(), root.displayName(),
                root.source(), root.assessment(), fields
        ));
        bundle.schemas().stream()
                .filter(schema -> !schema.candidateSchemaId().equals(root.candidateSchemaId()))
                .filter(schema -> schemas.stream().anyMatch(kept ->
                        kept.candidateSchemaId().equals(schema.candidateSchemaId())))
                .forEach(withRoot::add);
        return new CandidateBundle(bundle.contractVersion(), bundle.rootCandidateSchemaId(), withRoot);
    }

    private static CandidateBundle withUnexpectedField(CandidateBundle bundle) {
        var schemas = bundle.schemas().stream().map(schema -> {
            if (!schema.candidateSchemaId().equals(bundle.rootCandidateSchemaId())) return schema;
            var fields = new ArrayList<>(schema.fields());
            fields.add(field("hallucinated", CandidateValue.scalar(CandidateValueKind.TEXT), false, assessment()));
            return new CandidateSchema(
                    schema.candidateSchemaId(), schema.proposedSchemaKey(), schema.displayName(),
                    schema.source(), schema.assessment(), fields
            );
        }).toList();
        return new CandidateBundle(bundle.contractVersion(), bundle.rootCandidateSchemaId(), schemas);
    }

    private static CandidateBundle replaceRootFieldValue(
            CandidateBundle bundle,
            String fieldKey,
            CandidateValue replacement
    ) {
        var schemas = bundle.schemas().stream().map(schema -> {
            if (!schema.candidateSchemaId().equals(bundle.rootCandidateSchemaId())) return schema;
            var fields = schema.fields().stream().map(field -> fieldKey.equals(field.proposedFieldKey())
                    ? new CandidateField(
                    field.candidateFieldId(), field.proposedFieldKey(), field.displayName(),
                    field.required(), replacement, field.source(), field.assessment()
            ) : field).toList();
            return new CandidateSchema(
                    schema.candidateSchemaId(), schema.proposedSchemaKey(), schema.displayName(),
                    schema.source(), schema.assessment(), fields
            );
        }).toList();
        return new CandidateBundle(bundle.contractVersion(), bundle.rootCandidateSchemaId(), schemas);
    }

    private static CandidateBundle replaceRootFieldAssessment(
            CandidateBundle bundle,
            String fieldKey,
            CandidateAssessment replacement
    ) {
        var schemas = bundle.schemas().stream().map(schema -> {
            if (!schema.candidateSchemaId().equals(bundle.rootCandidateSchemaId())) return schema;
            var fields = schema.fields().stream().map(field -> fieldKey.equals(field.proposedFieldKey())
                    ? new CandidateField(
                    field.candidateFieldId(), field.proposedFieldKey(), field.displayName(),
                    field.required(), field.value(), field.source(), replacement
            ) : field).toList();
            return new CandidateSchema(
                    schema.candidateSchemaId(), schema.proposedSchemaKey(), schema.displayName(),
                    schema.source(), schema.assessment(), fields
            );
        }).toList();
        return new CandidateBundle(bundle.contractVersion(), bundle.rootCandidateSchemaId(), schemas);
    }

    private static CandidateBundle replaceAllEvidenceWithImage(CandidateBundle bundle) {
        var evidence = List.of(CandidateEvidence.image(
                "c".repeat(64), new CandidateBoundingBox(100, 100, 9_000, 2_000)
        ));
        var schemas = bundle.schemas().stream().map(schema -> {
            var schemaAssessment = new CandidateAssessment(
                    schema.assessment().confidenceBps(), schema.assessment().inferred(),
                    schema.assessment().resolution(), evidence
            );
            var fields = schema.fields().stream().map(field -> new CandidateField(
                    field.candidateFieldId(), field.proposedFieldKey(), field.displayName(),
                    field.required(), field.value(), field.source(), new CandidateAssessment(
                    field.assessment().confidenceBps(), field.assessment().inferred(),
                    field.assessment().resolution(), evidence
            ))).toList();
            return new CandidateSchema(
                    schema.candidateSchemaId(), schema.proposedSchemaKey(), schema.displayName(),
                    schema.source(), schemaAssessment, fields
            );
        }).toList();
        return new CandidateBundle(bundle.contractVersion(), bundle.rootCandidateSchemaId(), schemas);
    }

    private static CandidateBundle scalarCandidate(boolean scoreRequired, CandidateValueKind scoreKind) {
        var schemaId = UUID.randomUUID();
        return new CandidateBundle(
                CandidateBundle.CONTRACT_VERSION, schemaId,
                List.of(new CandidateSchema(
                        schemaId, "generated-root", "Generated", CandidateSource.AI, assessment(),
                        List.of(
                                field("active", CandidateValue.scalar(CandidateValueKind.BOOLEAN), false, assessment()),
                                field("name", CandidateValue.scalar(CandidateValueKind.TEXT), false, assessment()),
                                field("score", CandidateValue.scalar(scoreKind), scoreRequired, assessment())
                        )
                ))
        );
    }

    private static CandidateAssessment assessment() {
        return CandidateAssessment.ai(
                9_000, true, CandidateResolution.NOT_REQUIRED,
                List.of(CandidateEvidence.json(0, ""))
        );
    }

    private static CandidateField field(
            String key,
            CandidateValue value,
            boolean required,
            CandidateAssessment assessment
    ) {
        return new CandidateField(
                UUID.randomUUID(), key, key, required, value, CandidateSource.AI, assessment
        );
    }
}
