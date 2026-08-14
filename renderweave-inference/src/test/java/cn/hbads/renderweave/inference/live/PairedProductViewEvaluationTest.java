package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.vision.AcquisitionPolicy;
import cn.hbads.renderweave.inference.vision.ArtifactSet;
import cn.hbads.renderweave.inference.vision.DocumentObservationIR;
import cn.hbads.renderweave.inference.vision.RapidOcrBaselineContract;
import cn.hbads.renderweave.inference.vision.VisualEvidenceAcquisition;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PairedProductViewEvaluationTest {
    private static final AcquisitionPolicy POLICY = RapidOcrBaselineContract.policy(
            RapidOcrBaselineContract.DEFAULT_TIMEOUT_MILLIS);

    @Test
    void executesEveryCompleteBranchTwiceAndEmitsOnlyPayloadSafeStatistics() {
        var calls = new AtomicInteger();
        var evaluation = new PairedProductViewEvaluation();

        var result = evaluation.evaluate(runOrdinal ->
                PairedProductViewEvaluation.RunSession.of(
                        POLICY, deterministicAcquisition("synthetic", calls)));
        var report = result.report();

        assertEquals(
                "renderweave-r5p-paired-view-assignment/1.0:"
                        + "39266e24b85e0189577573e6e4e56905d41a43f7e0f81a9514fbdbcac954c3e8",
                report.assignmentIdentity());
        assertEquals(
                "renderweave-r5p-paired-view-evaluation/1.0:"
                        + "c8ad69263640ca49cd93ca24c6b558c6f913ff89a40c84052634c7cd79f66b65",
                report.evaluationIdentity());
        assertEquals(2, report.runs().size());
        assertEquals(8, report.caseCount());
        assertEquals(32, report.executedBranchCount());
        assertEquals(32, report.actualAcquisitionCalls());
        assertEquals(32, calls.get());
        assertTrue(report.measurementComplete());
        assertEquals(8, report.determinism().equivalentCases());
        assertEquals(16, report.determinism().equivalentBranches());
        assertTrue(report.determinism().deterministic());
        assertEquals(4, report.seenSummary().caseCount());
        assertEquals(4, report.confirmationSummary().caseCount());
        assertEquals(0, report.externalProviderUsage().attempts());
        assertEquals(0, report.apiKeyReads());
        assertEquals("R5P_PAIRED_EXECUTION_COMPLETE", report.terminalCode());

        for (var run : report.runs()) {
            assertEquals(8, run.caseResults().size());
            assertEquals(16, run.executedBranchCount());
            for (var item : run.caseResults()) {
                assertEquals(item.baseline().plannedViewCount(),
                        item.baseline().acquiredViewCount());
                assertEquals(item.successor().plannedViewCount(),
                        item.successor().acquiredViewCount());
                assertEquals(item.baseline().plannedViewCount(),
                        item.baseline().viewTrace().size());
                assertEquals(item.successor().plannedViewCount(),
                        item.successor().viewTrace().size());
                assertTrue(item.baseline().projectedObservationCount()
                        >= item.baseline().coalescedObservationCount());
                assertTrue(item.successor().projectedObservationCount()
                        >= item.successor().coalescedObservationCount());
            }
        }

        var encoded = new String(result.encodedReport(), StandardCharsets.UTF_8).toLowerCase();
        for (var forbidden : List.of(
                "synthetic", "ocrtext", "boundingbox", "sourcepixelbox", "base64",
                "data:image", "rootdocument", "candidatejson", "providerrequest")) {
            assertFalse(encoded.contains(forbidden), forbidden);
        }
        assertEquals(report, evaluation.readReport(
                result.encodedReport(), result.reportIdentity()));
    }

    @Test
    void rejectsIncompleteAcquisitionBeforeProducingQualityStatistics() {
        var evaluation = new PairedProductViewEvaluation();
        var failure = assertThrows(IllegalArgumentException.class,
                () -> evaluation.evaluate(runOrdinal ->
                        PairedProductViewEvaluation.RunSession.of(POLICY,
                                incompleteAcquisition())));

        assertEquals("R5P_PAIRED_ACQUISITION_COVERAGE_INVALID", failure.getMessage());
    }

    @Test
    void rejectsSecondRunObservationAndProjectedMetricInputDrift() {
        var evaluation = new PairedProductViewEvaluation();
        var failure = assertThrows(IllegalStateException.class,
                () -> evaluation.evaluate(runOrdinal ->
                        PairedProductViewEvaluation.RunSession.of(
                                POLICY,
                                deterministicAcquisition(
                                        runOrdinal == 1 ? "first" : "second",
                                        new AtomicInteger()))));

        assertEquals("R5P_PAIRED_SECOND_RUN_DRIFT", failure.getMessage());
    }

    @Test
    void strictReportReaderRejectsUnknownDuplicateTrailingAndIdentityTampering() {
        var evaluation = new PairedProductViewEvaluation();
        var result = evaluation.evaluate(runOrdinal ->
                PairedProductViewEvaluation.RunSession.of(
                        POLICY, deterministicAcquisition("strict", new AtomicInteger())));
        var raw = new String(result.encodedReport(), StandardCharsets.UTF_8);

        assertInvalidReport(evaluation, result, raw.replaceFirst(
                "\\{", "{\"unexpected\":true,"));
        assertInvalidReport(evaluation, result, raw.replaceFirst(
                "\"apiKeyReads\":0", "\"apiKeyReads\":0,\"apiKeyReads\":0"));
        assertInvalidReport(evaluation, result, raw + "{}\n");
        assertThrows(IllegalArgumentException.class, () -> evaluation.readReport(
                result.encodedReport(),
                "renderweave-r5p-paired-product-view-report/1.0:" + "0".repeat(64)));
    }

    private static void assertInvalidReport(
            PairedProductViewEvaluation evaluation,
            PairedProductViewEvaluation.Result result,
            String mutated
    ) {
        var failure = assertThrows(IllegalArgumentException.class, () -> evaluation.readReport(
                mutated.getBytes(StandardCharsets.UTF_8), result.reportIdentity()));
        assertEquals("R5P_PAIRED_REPORT_INVALID", failure.getMessage());
    }

    private static VisualEvidenceAcquisition deterministicAcquisition(
            String text,
            AtomicInteger calls
    ) {
        return (artifacts, policy) -> {
            calls.incrementAndGet();
            return observation(artifacts, policy, text, false);
        };
    }

    private static VisualEvidenceAcquisition incompleteAcquisition() {
        return (artifacts, policy) -> observation(artifacts, policy, "incomplete", true);
    }

    private static DocumentObservationIR observation(
            ArtifactSet artifacts,
            AcquisitionPolicy policy,
            String text,
            boolean replaceLast
    ) {
        var source = artifacts.artifacts();
        var observed = new ArrayList<DocumentObservationIR.ArtifactObservation>();
        for (var ordinal = 0; ordinal < source.size(); ordinal++) {
            var artifact = source.get(ordinal);
            var box = new DocumentObservationIR.SourcePixelBox(
                    Math.max(0, artifact.width() / 4),
                    Math.max(0, artifact.height() / 4),
                    Math.max(1, artifact.width() * 3 / 4),
                    Math.max(1, artifact.height() * 3 / 4));
            var confidence = new DocumentObservationIR.Confidence(
                    9_000, policy.confidenceScaleIdentity(),
                    DocumentObservationIR.ConfidenceBucket.HIGH,
                    policy.confidenceBucketProjectionIdentity());
            var line = new DocumentObservationIR.TextLine(
                    "ocr-%02d-000".formatted(ordinal), 0, box, confidence,
                    text, DocumentObservationIR.Sensitivity.EPHEMERAL_UNTRUSTED);
            observed.add(new DocumentObservationIR.ArtifactObservation(
                    replaceLast && ordinal == source.size() - 1
                            ? "0".repeat(64) : artifact.artifactId(),
                    ordinal, artifact.mediaType(),
                    artifact.width(), artifact.height(), true, List.of(line)));
        }
        return DocumentObservationIR.canonical(policy, provenance(policy), observed);
    }

    private static DocumentObservationIR.Provenance provenance(AcquisitionPolicy policy) {
        return new DocumentObservationIR.Provenance(
                policy.capabilityIdentity(), policy.adapterIdentity(), policy.engine(),
                policy.engineVersion(), policy.modelManifestSha256(),
                policy.preprocessingIdentity(), policy.postprocessingIdentity(),
                policy.readingOrderDerivationIdentity(), policy.projectionIdentity(),
                policy.confidenceScaleIdentity(),
                policy.confidenceBucketProjectionIdentity(),
                policy.canonicalizationIdentity());
    }
}
