package cn.hbads.renderweave.inference.eval;

import cn.hbads.renderweave.inference.candidate.CandidateAssessment;
import cn.hbads.renderweave.inference.candidate.CandidateBundle;
import cn.hbads.renderweave.inference.candidate.CandidateEvidence;
import cn.hbads.renderweave.inference.candidate.CandidateField;
import cn.hbads.renderweave.inference.candidate.CandidateResolution;
import cn.hbads.renderweave.inference.candidate.CandidateSchema;
import cn.hbads.renderweave.inference.candidate.CandidateSource;
import cn.hbads.renderweave.inference.candidate.CandidateValue;
import cn.hbads.renderweave.inference.candidate.CandidateValueKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveCandidateEvaluatorTest {
    private final LiveEvaluationCorpus corpus = new LiveEvaluationCorpus();
    private final LiveCandidateEvaluator evaluator = new LiveCandidateEvaluator();
    private final LiveEvaluationReporter reporter = new LiveEvaluationReporter();

    @Test
    void versionedGoldCorpusHasSixtyBalancedSyntheticCasesAndASealedHoldout() {
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
    }

    @Test
    void exactStructureTypeEvidenceAndOptionalityPassWithoutDependingOnGeneratedIds() {
        var result = evaluator.evaluate(
                corpus.require("live-json-01-scalars"), scalarCandidate(false, CandidateValueKind.DECIMAL), List.of()
        );

        assertTrue(result.passed());
        assertEquals(10_000, result.rootFieldPrecisionBps());
        assertEquals(10_000, result.rootFieldRecallBps());
        assertEquals(10_000, result.rootShapeAccuracyBps());
        assertEquals(10_000, result.evidenceCoverageBps());
        assertEquals(10_000, result.optionalitySafetyBps());
    }

    @Test
    void wrongShapeAndUnprovenRequirednessAreReportedAsSeparateRegressions() {
        var result = evaluator.evaluate(
                corpus.require("live-json-01-scalars"), scalarCandidate(true, CandidateValueKind.TEXT), List.of()
        );

        assertFalse(result.passed());
        assertIterableEquals(List.of("score:DECIMAL!=TEXT"), result.shapeMismatches());
        assertEquals(6_666, result.rootShapeAccuracyBps());
        assertEquals(6_666, result.optionalitySafetyBps());
    }

    @Test
    void partialReportsCannotBeMistakenForCompleteCertificationEvidence() {
        var one = evaluator.evaluate(
                corpus.require("live-json-01-scalars"), scalarCandidate(false, CandidateValueKind.DECIMAL), List.of()
        );

        var report = reporter.report("dashscope-qwen37-flash-v1", corpus, List.of(one));

        assertFalse(report.complete());
        assertEquals(1, report.evaluatedCaseCount());
        assertEquals(60, report.corpusCaseCount());
        assertEquals(59, report.missingCaseIds().size());
        assertEquals(10_000, report.global().passRateBps());
        assertEquals(1, report.byMode().get("JSON_ONLY").caseCount());
        assertEquals(1, report.byPartition().get("DEV").caseCount());
    }

    private static CandidateBundle scalarCandidate(boolean scoreRequired, CandidateValueKind scoreKind) {
        var schemaId = UUID.randomUUID();
        var assessment = CandidateAssessment.ai(
                9_000, true, CandidateResolution.NOT_REQUIRED,
                List.of(CandidateEvidence.json(0, ""))
        );
        return new CandidateBundle(
                CandidateBundle.CONTRACT_VERSION, schemaId,
                List.of(new CandidateSchema(
                        schemaId, "generated-root", "Generated", CandidateSource.AI, assessment,
                        List.of(
                                field("active", CandidateValueKind.BOOLEAN, false, assessment),
                                field("name", CandidateValueKind.TEXT, false, assessment),
                                field("score", scoreKind, scoreRequired, assessment)
                        )
                ))
        );
    }

    private static CandidateField field(
            String key,
            CandidateValueKind kind,
            boolean required,
            CandidateAssessment assessment
    ) {
        return new CandidateField(
                UUID.randomUUID(), key, key, required,
                CandidateValue.scalar(kind), CandidateSource.AI, assessment
        );
    }
}
