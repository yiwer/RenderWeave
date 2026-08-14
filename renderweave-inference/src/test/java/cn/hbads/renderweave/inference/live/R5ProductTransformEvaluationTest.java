package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.eval.visual.RapidOcrShadowEvaluation;
import cn.hbads.renderweave.inference.eval.visual.quality.R5ProductTransformEvidence;
import cn.hbads.renderweave.inference.vision.DocumentObservationIR;
import cn.hbads.renderweave.inference.vision.RapidOcrBaselineContract;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class R5ProductTransformEvaluationTest {
    @Test
    void runsTheFrozenThreePlusOneStaticAndInspectedAssignmentTwice() {
        var acquisitions = new AtomicInteger();
        var result = new R5ProductTransformEvaluation().evaluate(runOrdinal -> {
            var policy = RapidOcrBaselineContract.policy(RapidOcrBaselineContract.DEFAULT_TIMEOUT_MILLIS);
            return RapidOcrShadowEvaluation.RunSession.of(policy, (artifacts, requestedPolicy) -> {
                acquisitions.incrementAndGet();
                return DocumentObservationIR.canonical(policy, provenance(), artifacts.artifacts().stream()
                        .map(source -> new DocumentObservationIR.ArtifactObservation(
                                source.artifactId(), source.sourceOrdinal(), source.mediaType(), source.width(),
                                source.height(), source.orientationApplied(), List.of()))
                        .toList());
            });
        });

        assertEquals(16, acquisitions.get());
        assertEquals(16, result.evidence().actualAcquisitions());
        assertEquals(4, result.evidence().caseCount());
        assertEquals(2, result.evidence().runs().size());
        assertEquals(4, result.evidence().deterministicCases());
        assertEquals(R5ProductTransformEvidence.Disposition.NOT_QUALIFIED,
                result.evidence().disposition());
        assertEquals("R5_PRODUCT_TRANSFORM_NOT_QUALIFIED", result.evidence().reasonCode());
        assertFalse(result.evidence().qualified());
        assertEquals(0, result.evidence().externalProviderUsage().attempts());
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
