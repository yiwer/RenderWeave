package cn.hbads.renderweave.inference.eval.visual.quality;

import cn.hbads.renderweave.inference.eval.visual.LayeredVisualCorpus;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class R5OracleProbeEvidenceTest {
    @Test
    void fixedHigherResolutionTransformIsDeterministicAndBounded() {
        var corpus = new LayeredVisualCorpus();
        var transform = new R5OracleHigherResolutionTransform();
        var dense = transform.render(corpus.require("transit-board-v3"));
        var noisy = transform.render(corpus.require("transit-board-v5"));

        assertEquals(2048, dense.width());
        assertEquals(1536, dense.height());
        assertEquals(2400, noisy.width());
        assertEquals(1600, noisy.height());
        assertTrue(dense.identity().matches("renderweave-r5-oracle-higher-resolution/1\\.0:[0-9a-f]{64}"));
        assertEquals(dense.artifactId(), transform.render(corpus.require("transit-board-v3")).artifactId());
        assertFalse(dense.artifactId().equals(corpus.require("transit-board-v3").renderIdentity()
                .substring("render-sha256:".length())));
    }

    @Test
    void completeThreePlusOneDifferentialCanOnlyTriggerStopToSpecR5() {
        var evidence = R5OracleProbeEvidence.decide(identity("evaluation"), improvedCases(), 4);

        assertEquals(R5OracleProbeEvidence.Disposition.TRIGGERED, evidence.disposition());
        assertTrue(evidence.triggered());
        assertEquals("R5_ORACLE_DIFFERENTIAL_CONFIRMED", evidence.reasonCode());
        assertEquals(R5OracleProbeEvidence.PredicateResult.PASS,
                evidence.predicates().get(R5OracleProbeEvidence.Predicate.STATIC_VIEW_UNREADABLE));
        assertEquals(R5OracleProbeEvidence.PredicateResult.PASS,
                evidence.predicates().get(R5OracleProbeEvidence.Predicate.TARGET_SLICE_IMPROVED));
        assertEquals(R5OracleProbeEvidence.PredicateResult.PASS,
                evidence.predicates().get(R5OracleProbeEvidence.Predicate.CRITICAL_HALLUCINATION_NON_INCREASE));
        assertEquals(16, evidence.actualAcquisitions());
        assertEquals(0, evidence.externalProviderUsage().attempts());

        var codec = new R5OracleProbeEvidenceJsonCodec();
        var encoded = codec.write(evidence);
        assertArrayEquals(encoded, codec.write(codec.read(encoded, codec.evidenceIdentity(evidence))));
        var payload = new String(encoded, StandardCharsets.UTF_8).toLowerCase();
        for (var forbidden : new String[]{
                "base64", "data:image", "ocrtext", "ocr_text", "prompttext", "modeloutput",
                "candidatejson", "rootdocument", "boundingbox", "\"bbox\""
        }) {
            assertFalse(payload.contains(forbidden), forbidden);
        }
    }

    @Test
    void absentOracleImprovementIsNotATrigger() {
        var cases = improvedCases();
        var first = cases.getFirst();
        var noGain = new R5OracleProbeEvidence.CaseDifferential(
                first.caseId(), first.caseIdentity(), first.partition(), first.sourceWidth(), first.sourceHeight(),
                first.oracleWidth(), first.oracleHeight(), first.baseline(), first.baseline(), true);
        var evidence = R5OracleProbeEvidence.decide(identity("evaluation-no-gain"),
                List.of(noGain, cases.get(1), cases.get(2), cases.get(3)), 4);

        assertEquals(R5OracleProbeEvidence.Disposition.NOT_TRIGGERED, evidence.disposition());
        assertFalse(evidence.triggered());
        assertEquals("R5_ORACLE_IMPROVEMENT_NOT_PROVEN", evidence.reasonCode());
    }

    private static List<R5OracleProbeEvidence.CaseDifferential> improvedCases() {
        var corpus = new LayeredVisualCorpus();
        var protocol = OfflineQualityEvaluationProtocol.load();
        var transform = new R5OracleHigherResolutionTransform();
        return protocol.r5ProbeAssignment().caseIds().stream().map(caseId -> {
            var item = corpus.require(caseId);
            var rendered = transform.render(item);
            return new R5OracleProbeEvidence.CaseDifferential(
                    caseId, item.caseIdentity(), item.partition(), item.renderCase().width(), item.renderCase().height(),
                    rendered.width(), rendered.height(),
                    metrics(20, 18, 30, 1), metrics(20, 20, 10, 1), true);
        }).toList();
    }

    private static R5OracleProbeEvidence.CaseMetrics metrics(
            long expectedLines,
            long matchedLines,
            long characterErrors,
            long hallucinationCases
    ) {
        return new R5OracleProbeEvidence.CaseMetrics(
                matchedLines, expectedLines, matchedLines, characterErrors, hallucinationCases,
                10, 8, 7, 12, 12);
    }

    private static String identity(String value) {
        return "renderweave-r5-oracle-evaluation/1.0:" + sha256(value);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
