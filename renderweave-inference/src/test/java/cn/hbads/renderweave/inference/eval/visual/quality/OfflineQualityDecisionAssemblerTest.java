package cn.hbads.renderweave.inference.eval.visual.quality;

import cn.hbads.renderweave.inference.eval.visual.LayeredVisualCorpus;
import cn.hbads.renderweave.inference.eval.visual.RapidOcrShadowEvaluation;
import cn.hbads.renderweave.inference.vision.DocumentObservationIR;
import cn.hbads.renderweave.inference.vision.RapidOcrBaselineContract;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfflineQualityDecisionAssemblerTest {
    @Test
    void oneSeamStopsAtR5SpecWhenOnlyTheR5CausalPredicateIsComplete() {
        var report = deterministicOmissionReport();
        var causal = RapidOcrCausalEvidencePack.from(report, true);
        var r3 = R3OrderRepeatProbeEvidence.from(report, true);
        var r5 = R5OracleProbeEvidence.decide(identity("r5-evaluation"), improvedR5Cases(), 4);

        var bundle = new OfflineQualityDecisionAssembler().assemble(
                causal, ChallengerCapabilityAdmission.load(), r3, r5);

        assertEquals(R2R5TriggerDecision.OverallDisposition.STOP_TO_SPEC_R5,
                bundle.decision().overallDisposition());
        assertEquals(R2R5TriggerDecision.RouteDisposition.EVIDENCE_REQUIRED,
                bundle.decision().requireRoute(FrozenQualityEvidencePack.Route.R2).disposition());
        assertEquals(R2R5TriggerDecision.RouteDisposition.EVIDENCE_REQUIRED,
                bundle.decision().requireRoute(FrozenQualityEvidencePack.Route.R3).disposition());
        assertEquals(R2R5TriggerDecision.RouteDisposition.REJECTED_BY_CURRENT_EVIDENCE,
                bundle.decision().requireRoute(FrozenQualityEvidencePack.Route.R4).disposition());
        assertEquals(R2R5TriggerDecision.RouteDisposition.TRIGGERED,
                bundle.decision().requireRoute(FrozenQualityEvidencePack.Route.R5).disposition());
        assertTrue(bundle.decision().requireRoute(FrozenQualityEvidencePack.Route.R5).triggerSatisfied());
        assertFalse(bundle.decision().requireRoute(FrozenQualityEvidencePack.Route.R2).triggerSatisfied());
        assertEquals(0, bundle.evidencePack().externalProviderUsage().attempts());

        var packCodec = new FrozenQualityEvidencePackJsonCodec();
        var decisionCodec = new R2R5TriggerDecisionJsonCodec();
        assertArrayEquals(bundle.encodedEvidencePack(), packCodec.write(
                packCodec.read(bundle.encodedEvidencePack(), bundle.evidencePackIdentity())));
        assertArrayEquals(bundle.encodedDecision(), decisionCodec.write(
                decisionCodec.read(bundle.encodedDecision(), bundle.decisionIdentity())));
    }

    private static List<R5OracleProbeEvidence.CaseDifferential> improvedR5Cases() {
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
            long expectedLines, long matchedLines, long characterErrors, long hallucinations
    ) {
        return new R5OracleProbeEvidence.CaseMetrics(
                matchedLines, expectedLines, matchedLines, characterErrors, hallucinations,
                10, 8, 7, 12, 12);
    }

    private static cn.hbads.renderweave.inference.eval.visual.RapidOcrShadowReport
    deterministicOmissionReport() {
        return new RapidOcrShadowEvaluation().evaluate(runOrdinal -> {
            var policy = RapidOcrBaselineContract.policy(RapidOcrBaselineContract.DEFAULT_TIMEOUT_MILLIS);
            return RapidOcrShadowEvaluation.RunSession.of(policy, (artifacts, requestedPolicy) -> {
                var source = artifacts.artifacts().getFirst();
                return DocumentObservationIR.canonical(policy, provenance(), List.of(
                        new DocumentObservationIR.ArtifactObservation(
                                source.artifactId(), source.sourceOrdinal(), source.mediaType(), source.width(),
                                source.height(), source.orientationApplied(), List.of())));
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

    private static String identity(String value) {
        try {
            return "renderweave-r5-oracle-evaluation/1.0:" + java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
