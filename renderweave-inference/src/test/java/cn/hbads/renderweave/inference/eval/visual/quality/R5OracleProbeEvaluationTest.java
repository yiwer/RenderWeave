package cn.hbads.renderweave.inference.eval.visual.quality;

import cn.hbads.renderweave.inference.eval.visual.RapidOcrShadowEvaluation;
import cn.hbads.renderweave.inference.vision.DocumentObservationIR;
import cn.hbads.renderweave.inference.vision.RapidOcrBaselineContract;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class R5OracleProbeEvaluationTest {
    @Test
    void runsExactlyTwoBaselineAndOraclePassesForTheFixedThreePlusOneAssignment() {
        var calls = new AtomicInteger();
        var result = new R5OracleProbeEvaluation().evaluate(runOrdinal -> {
            var policy = RapidOcrBaselineContract.policy(RapidOcrBaselineContract.DEFAULT_TIMEOUT_MILLIS);
            return RapidOcrShadowEvaluation.RunSession.of(policy, (artifacts, requestedPolicy) -> {
                calls.incrementAndGet();
                var source = artifacts.artifacts().getFirst();
                return DocumentObservationIR.canonical(policy, provenance(), List.of(
                        new DocumentObservationIR.ArtifactObservation(
                                source.artifactId(), source.sourceOrdinal(), source.mediaType(), source.width(),
                                source.height(), source.orientationApplied(), List.of())));
            });
        });

        assertEquals(16, calls.get());
        assertEquals(16, result.evidence().actualAcquisitions());
        assertEquals(4, result.evidence().deterministicCases());
        assertEquals(R5OracleProbeEvidence.Disposition.NOT_TRIGGERED, result.evidence().disposition());
        assertEquals("R5_ORACLE_IMPROVEMENT_NOT_PROVEN", result.evidence().reasonCode());
        var codec = new R5OracleProbeEvidenceJsonCodec();
        assertEquals(result.evidenceIdentity(), codec.evidenceIdentity(
                codec.read(result.encodedEvidence(), result.evidenceIdentity())));
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
