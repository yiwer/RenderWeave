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

class R5P2PairedProductViewEvaluationTest {
    private static final AcquisitionPolicy POLICY = RapidOcrBaselineContract.policy(
            RapidOcrBaselineContract.DEFAULT_TIMEOUT_MILLIS);

    @Test
    void executesTwoCompleteRunsWithBranchProbeAndArtifactAccountingSeparated() {
        var sessions = new AtomicInteger();
        var branches = new AtomicInteger();
        var artifactViews = new AtomicInteger();
        var evaluation = new R5P2PairedProductViewEvaluation();

        var result = evaluation.evaluate(runOrdinal -> {
            assertEquals(sessions.incrementAndGet(), runOrdinal);
            return R5P2PairedProductViewEvaluation.RunSession.of(
                    POLICY, deterministicAcquisition("stable", branches, artifactViews));
        });

        var report = result.report();
        assertEquals(2, sessions.get());
        assertEquals(48, branches.get());
        assertEquals(48, report.accounting().branchAcquisitionProcesses());
        assertEquals(2, report.accounting().capabilityProbeProcesses());
        assertEquals(artifactViews.get(), report.accounting().artifactViews());
        assertTrue(report.accounting().artifactViews() > report.accounting()
                .branchAcquisitionProcesses());
        assertEquals(24, report.accounting().normalizationExecutions());
        assertEquals(24, report.accounting().actionExecutions());
        assertEquals(2, report.runs().size());
        for (var run : report.runs()) {
            assertEquals(12, run.caseResults().size());
            assertEquals(24, run.accounting().branchAcquisitionProcesses());
            assertEquals(1, run.accounting().capabilityProbeProcesses());
            for (var item : run.caseResults()) {
                assertEquals(item.baseline().plannedViewCount(),
                        item.baseline().acquiredViewCount());
                assertEquals(item.successor().plannedViewCount(),
                        item.successor().acquiredViewCount());
                assertEquals(1, item.baseline().branchAcquisitionProcesses());
                assertEquals(1, item.successor().branchAcquisitionProcesses());
                assertEquals(item.baseline().plannedViewCount(),
                        item.baseline().artifactViews());
                assertEquals(item.successor().plannedViewCount(),
                        item.successor().artifactViews());
                assertTrue(item.baseline().resourceIdentity().matches(
                        "renderweave-r5p2-branch-resources/1\\.0:[0-9a-f]{64}"));
                assertTrue(item.successor().resourceIdentity().matches(
                        "renderweave-r5p2-branch-resources/1\\.0:[0-9a-f]{64}"));
            }
        }
        assertEquals(8, report.diagnosticSummary().caseCount());
        assertEquals(4, report.confirmationSummary().caseCount());
        assertEquals("transit-board-v3", report.transitBoardV3().caseId());
        assertEquals(1, report.holdoutAccess().goldMetricReads());
        assertEquals("SEALED", report.holdoutAccess().status());
        assertTrue(report.determinism().deterministic());
        assertEquals(12, report.determinism().equivalentCases());
        assertEquals(24, report.determinism().equivalentBranches());
        assertEquals(0, report.externalProviderUsage().attempts());
        assertEquals(0, report.externalProviderUsage().reservations());
        assertEquals(0, report.externalProviderUsage().costMicrosCny());
        assertEquals(0, report.apiKeyReads());
        assertFalse(report.finalTerminalClaimed());
        assertEquals("R5P2_PAIRED_PRODUCER_COMPLETE", report.terminalCode());

        var encoded = new String(result.encodedReport(), StandardCharsets.UTF_8).toLowerCase();
        for (var forbidden : List.of(
                "ocrtext", "goldtext", "boundingbox", "sourcepixelbox", "base64",
                "data:image", "rootdocument", "candidatejson", "providerrequest",
                "providerresponse", "prompttext", "modeloutput")) {
            assertFalse(encoded.contains(forbidden), forbidden);
        }
        assertEquals(report, evaluation.readReport(
                result.encodedReport(), result.reportIdentity()));
    }

    @Test
    void rejectsIncompleteBranchBeforePublishingMetrics() {
        var evaluation = new R5P2PairedProductViewEvaluation();
        var failure = assertThrows(IllegalArgumentException.class,
                () -> evaluation.evaluate(runOrdinal ->
                        R5P2PairedProductViewEvaluation.RunSession.of(
                                POLICY, incompleteAcquisition())));

        assertEquals("R5P2_PAIRED_ACQUISITION_COVERAGE_INVALID", failure.getMessage());
    }

    @Test
    void rejectsSecondRunObservationAndReconciledMetricInputDrift() {
        var evaluation = new R5P2PairedProductViewEvaluation();
        var failure = assertThrows(IllegalStateException.class,
                () -> evaluation.evaluate(runOrdinal ->
                        R5P2PairedProductViewEvaluation.RunSession.of(
                                POLICY, deterministicAcquisition(
                                        runOrdinal == 1 ? "first" : "second",
                                        new AtomicInteger(), new AtomicInteger()))));

        assertEquals("R5P2_PAIRED_SECOND_RUN_DRIFT", failure.getMessage());
    }

    @Test
    void strictReportReaderRejectsUnknownDuplicateTrailingAndIdentityTampering() {
        var evaluation = new R5P2PairedProductViewEvaluation();
        var result = evaluation.evaluate(runOrdinal ->
                R5P2PairedProductViewEvaluation.RunSession.of(
                        POLICY, deterministicAcquisition(
                                "strict", new AtomicInteger(), new AtomicInteger())));
        var raw = new String(result.encodedReport(), StandardCharsets.UTF_8);

        assertInvalidReport(evaluation, result, raw.replaceFirst(
                "\\{", "{\"unexpected\":true,"));
        assertInvalidReport(evaluation, result, raw.replaceFirst(
                "\"apiKeyReads\":0", "\"apiKeyReads\":0,\"apiKeyReads\":0"));
        assertInvalidReport(evaluation, result, raw + "{}\n");
        assertThrows(IllegalArgumentException.class, () -> evaluation.readReport(
                result.encodedReport(),
                "renderweave-r5p2-paired-product-view-report/1.0:" + "0".repeat(64)));
    }

    private static void assertInvalidReport(
            R5P2PairedProductViewEvaluation evaluation,
            R5P2PairedProductViewEvaluation.Result result,
            String mutated
    ) {
        var failure = assertThrows(IllegalArgumentException.class, () -> evaluation.readReport(
                mutated.getBytes(StandardCharsets.UTF_8), result.reportIdentity()));
        assertEquals("R5P2_PAIRED_REPORT_INVALID", failure.getMessage());
    }

    private static VisualEvidenceAcquisition deterministicAcquisition(
            String text,
            AtomicInteger calls,
            AtomicInteger artifactViews
    ) {
        return (artifacts, policy) -> {
            calls.incrementAndGet();
            artifactViews.addAndGet(artifacts.artifacts().size());
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
                    ordinal, artifact.mediaType(), artifact.width(), artifact.height(),
                    true, List.of(line)));
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
