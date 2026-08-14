package cn.hbads.renderweave.inference.eval.visual.quality;

import cn.hbads.renderweave.inference.eval.visual.RapidOcrShadowEvaluation;
import cn.hbads.renderweave.inference.vision.DocumentObservationIR;
import cn.hbads.renderweave.inference.vision.RapidOcrBaselineContract;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RapidOcrCausalEvidencePackTest {
    @Test
    void projectsTwoExactRunsIntoStablePayloadSafeLayeredEvidence() {
        var report = deterministicGapReport();
        var pack = RapidOcrCausalEvidencePack.from(report, true);

        assertEquals(2, pack.accounting().runs());
        assertEquals(60, pack.accounting().casesPerRun());
        assertEquals(45, pack.accounting().devPerRun());
        assertEquals(15, pack.accounting().holdoutPerRun());
        assertEquals(120, pack.accounting().actualAcquisitions());
        assertEquals(60, pack.accounting().metricsEquivalentCases());
        assertEquals(60, pack.accounting().observationEquivalentCases());
        assertEquals(60, pack.metrics().get("GLOBAL").caseCount());
        assertEquals(45, pack.metrics().get("PARTITION/DEV").caseCount());
        assertEquals(15, pack.metrics().get("PARTITION/HOLDOUT").caseCount());
        assertTrue(pack.metrics().keySet().stream().anyMatch(key -> key.startsWith("DOMAIN/")));
        assertTrue(pack.metrics().keySet().stream().anyMatch(key -> key.startsWith("DIFFICULTY/")));
        assertTrue(pack.metrics().keySet().stream().anyMatch(key -> key.startsWith("FAILURE/")));

        assertEquals(RapidOcrCausalEvidencePack.AttributionResult.OBSERVED_CONTRIBUTOR,
                pack.attributions().get(RapidOcrCausalEvidencePack.AttributionLayer.OBSERVATION).result());
        assertEquals(RapidOcrCausalEvidencePack.AttributionResult.OBSERVED_CONTRIBUTOR,
                pack.attributions().get(RapidOcrCausalEvidencePack.AttributionLayer.LAYOUT).result());
        assertEquals(RapidOcrCausalEvidencePack.AttributionResult.EXCLUDED_BY_CURRENT_EVIDENCE,
                pack.attributions().get(RapidOcrCausalEvidencePack.AttributionLayer.SHAPE_CODEC).result());
        assertEquals(RapidOcrCausalEvidencePack.AttributionResult.OBSERVED_CONTRIBUTOR,
                pack.attributions().get(RapidOcrCausalEvidencePack.AttributionLayer.SEMANTIC).result());
        assertEquals(RapidOcrCausalEvidencePack.AttributionResult.EXCLUDED_BY_CURRENT_EVIDENCE,
                pack.attributions().get(RapidOcrCausalEvidencePack.AttributionLayer.SCORER).result());
        assertEquals(8, pack.attributions().size());
        assertEquals(0, pack.externalProviderUsage().attempts());
        assertEquals(0, pack.externalProviderUsage().reservations());
        assertEquals(0, pack.externalProviderUsage().costMicrosCny());

        var codec = new RapidOcrCausalEvidencePackJsonCodec();
        var encoded = codec.write(pack);
        assertArrayEquals(encoded, codec.write(codec.read(encoded, codec.evidenceIdentity(pack))));
        assertTrue(codec.evidenceIdentity(pack).matches(
                "renderweave-rapidocr-causal-evidence/1\\.0:[0-9a-f]{64}"));
        var payload = new String(encoded, StandardCharsets.UTF_8).toLowerCase();
        for (var forbidden : new String[]{
                "base64", "data:image", "ocrtext", "ocr_text", "prompttext", "modeloutput",
                "candidatejson", "rootdocument", "boundingbox", "\"bbox\""
        }) {
            assertFalse(payload.contains(forbidden), forbidden);
        }
    }

    @Test
    void missingIndependentReplayDoesNotExcludeTheScorer() {
        var pack = RapidOcrCausalEvidencePack.from(deterministicGapReport(), false);
        assertEquals(RapidOcrCausalEvidencePack.AttributionResult.MISSING,
                pack.attributions().get(RapidOcrCausalEvidencePack.AttributionLayer.SCORER).result());
    }

    @Test
    void refusesToClaimObservationOrLayoutContributionWithoutAStableGap() {
        var report = deterministicGapReport();
        var noGap = new cn.hbads.renderweave.inference.eval.visual.RapidOcrShadowReport(
                report.reportVersion(), report.evaluationIdentity(), report.evaluationComponents(),
                report.corpusIdentity(), report.annotationSetIdentity(), report.shadowDiagnostic(),
                report.certificationEligible(), report.expectedCaseCount(), report.runs(),
                report.determinism(),
                new cn.hbads.renderweave.inference.eval.visual.RapidOcrShadowReport.EvidenceFacts(
                        0, 0, 0,
                        report.evidenceFacts().recalledOrderOrRepeatErrorDevCases(),
                        report.evidenceFacts().recalledOrderOrRepeatErrorHoldoutCases(),
                        report.evidenceFacts().denseOrSmallTextMissDevCases(),
                        report.evidenceFacts().denseOrSmallTextMissHoldoutCases(),
                        report.evidenceFacts().challengerRiskReviews(),
                        report.evidenceFacts().strictShapeProtocolEvidenceCases(),
                        report.evidenceFacts().oracleCropImprovementCases()),
                report.triggers(), report.externalProvider());

        var failure = assertThrows(IllegalArgumentException.class,
                () -> RapidOcrCausalEvidencePack.from(noGap, true));
        assertEquals("RAPIDOCR_CAUSAL_STABLE_GAP_MISSING", failure.getMessage());
    }

    private static cn.hbads.renderweave.inference.eval.visual.RapidOcrShadowReport deterministicGapReport() {
        return new RapidOcrShadowEvaluation().evaluate(runOrdinal -> {
            var policy = RapidOcrBaselineContract.policy(RapidOcrBaselineContract.DEFAULT_TIMEOUT_MILLIS);
            return RapidOcrShadowEvaluation.RunSession.of(policy, (artifacts, requestedPolicy) -> {
                var source = artifacts.artifacts().getFirst();
                return DocumentObservationIR.canonical(policy, provenance(), List.of(
                        new DocumentObservationIR.ArtifactObservation(
                                source.artifactId(), source.sourceOrdinal(), source.mediaType(),
                                source.width(), source.height(), source.orientationApplied(), List.of())));
            });
        }).report();
    }

    private static DocumentObservationIR.Provenance provenance() {
        return new DocumentObservationIR.Provenance(
                RapidOcrBaselineContract.CAPABILITY_IDENTITY,
                RapidOcrBaselineContract.ADAPTER_IDENTITY,
                RapidOcrBaselineContract.ENGINE,
                RapidOcrBaselineContract.ENGINE_VERSION,
                RapidOcrBaselineContract.MODEL_MANIFEST_SHA256,
                RapidOcrBaselineContract.PREPROCESSING_IDENTITY,
                RapidOcrBaselineContract.POSTPROCESSING_IDENTITY,
                RapidOcrBaselineContract.READING_ORDER_IDENTITY,
                RapidOcrBaselineContract.PROJECTION_IDENTITY,
                RapidOcrBaselineContract.CONFIDENCE_SCALE_IDENTITY,
                RapidOcrBaselineContract.CONFIDENCE_BUCKET_IDENTITY,
                RapidOcrBaselineContract.CANONICALIZATION_IDENTITY);
    }
}
