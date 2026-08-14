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
import static org.junit.jupiter.api.Assertions.assertTrue;

class R3OrderRepeatProbeEvidenceTest {
    @Test
    void fixedProbeFailsClosedWhenOcrOmissionAndDownstreamConfoundersRemain() {
        var evidence = R3OrderRepeatProbeEvidence.from(deterministicOmissionReport(), true);
        var protocol = OfflineQualityEvaluationProtocol.load();

        assertEquals(protocol.identity(), evidence.protocolIdentity());
        assertEquals(protocol.r3ProbeAssignment().identity(), evidence.assignmentIdentity());
        assertEquals(protocol.r3ProbeAssignment().caseIds(),
                evidence.cases().stream().map(R3OrderRepeatProbeEvidence.CaseEvidence::caseId).toList());
        assertEquals(2, evidence.runs());
        assertEquals(3, evidence.devCases());
        assertEquals(1, evidence.holdoutCases());
        assertEquals(R3OrderRepeatProbeEvidence.Disposition.MISSING, evidence.disposition());
        assertFalse(evidence.triggered());
        assertEquals("R3_OCR_OMISSION_NOT_EXCLUDED", evidence.reasonCode());
        assertEquals(R3OrderRepeatProbeEvidence.PredicateResult.PASS,
                evidence.predicates().get(R3OrderRepeatProbeEvidence.Predicate.EXACT_ASSIGNMENT));
        assertEquals(R3OrderRepeatProbeEvidence.PredicateResult.FAIL,
                evidence.predicates().get(R3OrderRepeatProbeEvidence.Predicate.OCR_OMISSION_EXCLUDED));
        assertEquals(R3OrderRepeatProbeEvidence.PredicateResult.MISSING,
                evidence.predicates().get(R3OrderRepeatProbeEvidence.Predicate.PROMPT_SHAPE_EXCLUDED));
        assertEquals(R3OrderRepeatProbeEvidence.PredicateResult.MISSING,
                evidence.predicates().get(R3OrderRepeatProbeEvidence.Predicate.MATERIALIZER_EXCLUDED));
        assertEquals(R3OrderRepeatProbeEvidence.PredicateResult.PASS,
                evidence.predicates().get(R3OrderRepeatProbeEvidence.Predicate.SCORER_EXCLUDED));
        assertTrue(evidence.cases().stream().noneMatch(R3OrderRepeatProbeEvidence.CaseEvidence::allReferencedRegionsObserved));
        assertEquals(0, evidence.externalProviderUsage().attempts());
        assertEquals(0, evidence.externalProviderUsage().reservations());
        assertEquals(0, evidence.externalProviderUsage().costMicrosCny());

        var codec = new R3OrderRepeatProbeEvidenceJsonCodec();
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

    private static cn.hbads.renderweave.inference.eval.visual.RapidOcrShadowReport
    deterministicOmissionReport() {
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
