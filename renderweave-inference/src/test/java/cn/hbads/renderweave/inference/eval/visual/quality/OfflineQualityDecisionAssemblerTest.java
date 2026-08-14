package cn.hbads.renderweave.inference.eval.visual.quality;

import cn.hbads.renderweave.inference.eval.visual.LayeredVisualCorpus;
import cn.hbads.renderweave.inference.eval.visual.RapidOcrShadowEvaluation;
import cn.hbads.renderweave.inference.vision.DocumentObservationIR;
import cn.hbads.renderweave.inference.vision.RapidOcrBaselineContract;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
                causal, ChallengerCapabilityAdmission.load(), r3, r5,
                componentEvidenceAuthority(causal, r3, r5));

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
        assertTrue(bundle.evidencePack().componentVerifications().stream().allMatch(item ->
                item.result() == FrozenQualityEvidencePack.VerificationResult.PASS));

        var packCodec = new FrozenQualityEvidencePackJsonCodec();
        var decisionCodec = new R2R5TriggerDecisionJsonCodec();
        assertArrayEquals(bundle.encodedEvidencePack(), packCodec.write(
                packCodec.read(bundle.encodedEvidencePack(), bundle.evidencePackIdentity())));
        assertArrayEquals(bundle.encodedDecision(), decisionCodec.write(
                decisionCodec.read(bundle.encodedDecision(), bundle.decisionIdentity())));
    }

    private static OfflineQualityDecisionAssembler.ComponentEvidenceAuthority componentEvidenceAuthority(
            RapidOcrCausalEvidencePack causal,
            R3OrderRepeatProbeEvidence r3,
            R5OracleProbeEvidence r5
    ) {
        var rapidCodec = new RapidOcrCausalEvidencePackJsonCodec();
        var r3Codec = new R3OrderRepeatProbeEvidenceJsonCodec();
        var r5Codec = new R5OracleProbeEvidenceJsonCodec();
        return new OfflineQualityDecisionAssembler.ComponentEvidenceAuthority(
                rapidCodec.write(causal),
                summary(FrozenQualityEvidencePack.Component.RAPIDOCR_CAUSAL,
                        rapidCodec.evidenceIdentity(causal)),
                r3Codec.write(r3),
                summary(FrozenQualityEvidencePack.Component.R3_PROBE, r3Codec.evidenceIdentity(r3)),
                r5Codec.write(r5),
                summary(FrozenQualityEvidencePack.Component.R5_PROBE, r5Codec.evidenceIdentity(r5)),
                "d".repeat(40));
    }

    private static byte[] summary(
            FrozenQualityEvidencePack.Component component,
            String evidenceIdentity
    ) {
        Map<String, Object> value = new HashMap<>();
        value.put("assurance", "A2_CROSS_IMPLEMENTATION_RECOMPUTE");
        value.put("evidenceIdentity", evidenceIdentity);
        value.put("externalProviderCostMicrosCny", 0);
        value.put("providerAttempts", 0);
        value.put("providerReservations", 0);
        value.put("repositoryRevision", "d".repeat(40));
        value.put("result", "PASS");
        value.put("verifierVersion", component.verifierVersion());
        switch (component) {
            case RAPIDOCR_CAUSAL -> {
                value.put("actualAcquisitions", 120);
                value.put("attributionResults", Map.of(
                        "LAYOUT", "OBSERVED_CONTRIBUTOR",
                        "MATERIALIZER", "MISSING",
                        "OBSERVATION", "OBSERVED_CONTRIBUTOR",
                        "ORDER_REPEAT", "MISSING",
                        "SCORER", "EXCLUDED_BY_CURRENT_EVIDENCE",
                        "SEMANTIC", "OBSERVED_CONTRIBUTOR",
                        "SHAPE_CODEC", "EXCLUDED_BY_CURRENT_EVIDENCE",
                        "STATIC_VIEW", "MISSING"));
                value.put("caseCount", 60);
                value.put("evaluationIdentity",
                        "renderweave-rapidocr-shadow-evaluation/1.0:" + "e".repeat(64));
                value.put("metricsEquivalentCases", 60);
                value.put("observationEquivalentCases", 60);
                value.put("protocolIdentity",
                        "renderweave-offline-quality-evaluation-protocol/1.0:" + "f".repeat(64));
                value.put("runCount", 2);
            }
            case R3_PROBE -> {
                value.put("assignmentIdentity",
                        "renderweave-r3-probe-assignment/1.0:" + "e".repeat(64));
                value.put("caseCount", 4);
                value.put("devCases", 3);
                value.put("disposition", "MISSING");
                value.put("holdoutCases", 1);
                value.put("reasonCode", "R3_OCR_OMISSION_NOT_EXCLUDED");
                value.put("runs", 2);
                value.put("triggered", false);
            }
            case R5_PROBE -> {
                value.put("actualAcquisitions", 16);
                value.put("assignmentIdentity",
                        "renderweave-r5-probe-assignment/1.0:" + "e".repeat(64));
                value.put("caseCount", 4);
                value.put("deterministicCases", 4);
                value.put("devCases", 3);
                value.put("disposition", "TRIGGERED");
                value.put("evaluationIdentity",
                        "renderweave-r5-oracle-evaluation/1.0:" + "f".repeat(64));
                value.put("holdoutCases", 1);
                value.put("reasonCode", "R5_ORACLE_DIFFERENTIAL_CONFIRMED");
                value.put("runs", 2);
                value.put("transformIdentity",
                        "renderweave-r5-oracle-higher-resolution/1.0:" + "a".repeat(64));
                value.put("triggered", true);
            }
        }
        return JsonMapper.builder().build().writeValueAsBytes(value);
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
