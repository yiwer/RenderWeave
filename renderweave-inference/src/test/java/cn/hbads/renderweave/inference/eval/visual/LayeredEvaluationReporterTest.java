package cn.hbads.renderweave.inference.eval.visual;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LayeredEvaluationReporterTest {
    private final LayeredVisualCorpus corpus = new LayeredVisualCorpus();
    private final LayeredVisualEvaluator evaluator = new LayeredVisualEvaluator();
    private final LayeredEvaluationReporter reporter = new LayeredEvaluationReporter();

    @Test
    void completeSixtyCaseScorecardContainsGlobalPartitionDomainDifficultyAndFailureSlices() {
        var report = report();

        assertTrue(report.complete());
        assertEquals(60, report.expectedCaseCount());
        assertEquals(60, report.observedCaseCount());
        assertEquals(60, report.records().size());
        assertTrue(report.missingCaseIds().isEmpty());
        assertEquals(60, report.global().caseCount());
        assertEquals(45, report.partitions().get("DEV").caseCount());
        assertEquals(15, report.partitions().get("HOLDOUT").caseCount());
        assertEquals(55, report.domains().get("generic").caseCount());
        assertEquals(5, report.domains().get("transit-board").caseCount());
        for (var difficulty : LayeredEvaluationRecord.Difficulty.values()) {
            assertTrue(report.difficulties().containsKey(difficulty.name()));
        }
        for (var slice : LayeredEvaluationRecord.FailureSlice.values()) {
            assertTrue(report.failureSlices().containsKey(slice.name()));
        }
        assertEquals(12, report.difficulties().get("DENSE_TEXT").caseCount());
        assertEquals(12, report.difficulties().get("MULTI_COLUMN").caseCount());
        assertEquals(2, report.failureSlices().get("PROMPT_INJECTION").caseCount());
        assertTrue(report.failureSlices().get("REPEATED_LIST").caseCount() >= 40);
        assertEquals(0, report.global().ocr().cerBps());
        assertEquals(0, report.global().ocr().werBps());
        assertEquals(0, report.global().metricsBps().get("ocr.cer"));
        assertEquals(10_000, report.global().metricsBps().get("layout.SLOT.ap5095"));
        assertEquals(10_000, report.global().metricsBps().get("candidate.topologyPreservation"));
        assertEquals(1_000, report.global().metricsBps().get("calibration.ece"));
        assertEquals(10_000, report.global().order().precedenceEdges().f1Bps());
        assertEquals(10_000, report.global().repeat().memberships().f1Bps());
        assertEquals(10_000, report.global().semantic().bindings().f1Bps());
        assertEquals(60, report.global().candidate().topologyPreservedCases());
        assertEquals(1_000, report.global().calibration().expectedCalibrationErrorBps());
        assertEquals(100, report.global().calibration().brierScoreBps());
        assertEquals(60, report.global().calibration().reviewRequiredReachedCases());
        assertEquals(180, report.global().runtime().scriptedCalls());
        assertEquals(0, report.global().runtime().providerAttempts());
        assertEquals(0, report.global().runtime().providerReservations());
        assertEquals(0, report.global().runtime().externalProviderCostMicrosCny());
        assertEquals(1_000, report.global().runtime().latency().get("ACQUISITION").p50Micros());
        assertEquals(1_000, report.global().runtime().latency().get("ACQUISITION").p95Micros());
    }

    @Test
    void canonicalReportRoundTripsAndContainsNoForbiddenPayload() {
        var report = report();
        var codec = new LayeredEvaluationReportJsonCodec();
        var encoded = codec.write(report);
        var identity = codec.reportIdentity(report);

        assertEquals(report, codec.read(encoded, identity));
        assertArrayEquals(encoded, codec.write(report));
        assertTrue(identity.matches("renderweave-layered-evaluation-report/1\\.0:[0-9a-f]{64}"));
        var json = new String(encoded, StandardCharsets.UTF_8).toLowerCase();
        for (var forbidden : List.of("summer night", "ignore prior instructions", "ocrtext", "prompttext",
                "providerrequest", "providerresponse", "candidatejson", "boundingbox", "rootdocument",
                "data:image", "base64")) {
            assertFalse(json.contains(forbidden), forbidden);
        }

        var changed = new String(encoded, StandardCharsets.UTF_8)
                .replace("\"providerAttempts\":0", "\"providerAttempts\":1");
        assertThrows(IllegalArgumentException.class,
                () -> codec.read(changed.getBytes(StandardCharsets.UTF_8), identity));
        assertThrows(IllegalArgumentException.class,
                () -> codec.read(encoded, "renderweave-layered-evaluation-report/1.0:" + "f".repeat(64)));
    }

    @Test
    void duplicateMissingOrExternalProviderRecordCannotProduceAnR1Report() {
        var records = records();
        var duplicate = new ArrayList<>(records);
        duplicate.set(1, duplicate.getFirst());
        assertThrows(IllegalArgumentException.class,
                () -> reporter.report(corpus, identity(), duplicate));
        assertThrows(IllegalArgumentException.class,
                () -> reporter.report(corpus, identity(), records.subList(0, 59)));

        var first = records.getFirst();
        var runtime = first.runtime();
        var unsafe = new LayeredEvaluationRecord.RuntimeStats(
                runtime.scriptedCalls(), runtime.inputTokens(), runtime.outputTokens(),
                runtime.estimatedCostMicrosCny(), runtime.settledCostMicrosCny(), runtime.latencyMicros(),
                runtime.recoveryCode(), runtime.recoveryCount(), runtime.acceptedStageReplayCount(),
                1, 0, 0);
        var changed = replaceRuntime(first, unsafe);
        var unsafeRecords = new ArrayList<>(records);
        unsafeRecords.set(0, changed);
        assertThrows(IllegalArgumentException.class,
                () -> reporter.report(corpus, identity(), unsafeRecords));
    }

    LayeredEvaluationReport report() {
        return reporter.report(corpus, identity(), records());
    }

    List<LayeredEvaluationRecord> records() {
        return corpus.cases().stream().map(item ->
                evaluator.evaluate(item, LayeredSyntheticReplay.perfect(item))).toList();
    }

    private LayeredEvaluationIdentity identity() {
        var sha = "a".repeat(64);
        return new LayeredEvaluationIdentity(
                corpus.corpusIdentity(), LayeredVisualAnnotation.VERSION, corpus.annotationSetIdentity(),
                corpus.renderContractIdentity(), DocumentObservationSuccessorIdentity.VERSION + ":" + sha,
                "document-observation-ir/1.0", "acquisition-policy/1.0:" + sha,
                "rapidocr-local-process/1.0", "weight-sha256:" + sha,
                "source-pixel-projection/1.0", "top-left-order/1.0",
                "stage-shape-catalog/1.0:" + sha, "scripted-replay/1.0:" + sha,
                "prompt-set-v45:" + sha, "visual-validator/1.0:" + sha,
                "candidate-materializer/1.0:" + sha, LayeredVisualEvaluator.VERSION + ":" + sha,
                "renderweave-zero-provider-budget/1.0:" + sha, "deterministic-json-object/1.0");
    }

    private static LayeredEvaluationRecord replaceRuntime(
            LayeredEvaluationRecord value,
            LayeredEvaluationRecord.RuntimeStats runtime
    ) {
        return new LayeredEvaluationRecord(
                value.recordVersion(), value.caseId(), value.caseIdentity(), value.partition(), value.domain(),
                value.difficulty(), value.failureSlices(), value.outcomeCode(), value.ocr(), value.layout(),
                value.order(), value.repeat(), value.semantic(), value.candidate(), value.calibration(), runtime);
    }
}
