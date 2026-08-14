package cn.hbads.renderweave.inference.eval.visual;

import cn.hbads.renderweave.inference.vision.DocumentObservationIR;
import cn.hbads.renderweave.inference.vision.RapidOcrBaselineContract;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RapidOcrShadowEvaluationTest {
    @Test
    void runsEveryCorpusCaseTwiceAndEmitsOnlyPayloadSafeSufficientStatistics() {
        var sessions = new AtomicInteger();
        var acquisitions = new AtomicInteger();
        var result = new RapidOcrShadowEvaluation().evaluate(runOrdinal -> {
            sessions.incrementAndGet();
            var policy = RapidOcrBaselineContract.policy(RapidOcrBaselineContract.DEFAULT_TIMEOUT_MILLIS);
            return RapidOcrShadowEvaluation.RunSession.of(policy, (artifacts, requestedPolicy) -> {
                acquisitions.incrementAndGet();
                assertEquals(policy, requestedPolicy);
                var source = artifacts.artifacts().getFirst();
                return DocumentObservationIR.canonical(
                        policy,
                        provenance(),
                        List.of(new DocumentObservationIR.ArtifactObservation(
                                source.artifactId(), source.sourceOrdinal(), source.mediaType(),
                                source.width(), source.height(), source.orientationApplied(), List.of()
                        ))
                );
            });
        });

        assertEquals(2, sessions.get());
        assertEquals(120, acquisitions.get());
        assertEquals(2, result.report().runs().size());
        assertEquals(60, result.report().expectedCaseCount());
        assertEquals(45, result.report().runs().getFirst().partitions().get("DEV").caseCount());
        assertEquals(15, result.report().runs().getFirst().partitions().get("HOLDOUT").caseCount());
        assertEquals(0, result.report().runs().getFirst().global().confidence().observations());
        assertEquals(60, result.report().determinism().metricsEquivalentCases());
        assertEquals(60, result.report().determinism().observationEquivalentCases());
        assertTrue(result.report().determinism().deterministic());
        assertTrue(result.report().shadowDiagnostic());
        assertFalse(result.report().certificationEligible());
        assertEquals(0, result.report().externalProvider().attempts());
        assertEquals(0, result.report().externalProvider().reservations());
        assertEquals(0, result.report().externalProvider().costMicrosCny());
        assertTrue(result.report().triggers().values().stream()
                .allMatch(item -> !item.requiredEvidencePresent()
                        && !item.triggered()
                        && "NOT_TRIGGERED_EVIDENCE_ABSENT".equals(item.reasonCode())));

        var codec = new RapidOcrShadowReportJsonCodec();
        assertArrayEquals(result.encodedReport(), codec.write(result.report()));
        assertEquals(result.report(), codec.read(result.encodedReport(), result.reportIdentity()));
        assertEquals(result.report(), codec.read(result.encodedReport()));
        var payload = new String(result.encodedReport(), StandardCharsets.UTF_8).toLowerCase();
        for (var forbidden : new String[]{
                "base64", "data:image", "ocrtext", "ocr_text", "promptpayload", "providerresponse",
                "providerrequest", "candidatepayload", "rootdocument", "boundingbox", "\"bbox\""
        }) {
            assertFalse(payload.contains(forbidden), forbidden);
        }
    }

    @Test
    void failsClosedWhenTheSecondActualRunDrifts() {
        var failure = assertThrows(IllegalStateException.class,
                () -> new RapidOcrShadowEvaluation().evaluate(runOrdinal -> {
                    var policy = RapidOcrBaselineContract.policy(RapidOcrBaselineContract.DEFAULT_TIMEOUT_MILLIS);
                    return RapidOcrShadowEvaluation.RunSession.of(policy, (artifacts, requestedPolicy) -> {
                        var source = artifacts.artifacts().getFirst();
                        var lines = runOrdinal == 1 ? List.<DocumentObservationIR.TextLine>of()
                                : List.of(new DocumentObservationIR.TextLine(
                                "ocr-00-000", 0,
                                new DocumentObservationIR.SourcePixelBox(0, 0, 1, 1),
                                new DocumentObservationIR.Confidence(
                                        9_000,
                                        RapidOcrBaselineContract.CONFIDENCE_SCALE_IDENTITY,
                                        DocumentObservationIR.ConfidenceBucket.HIGH,
                                        RapidOcrBaselineContract.CONFIDENCE_BUCKET_IDENTITY),
                                "drift", DocumentObservationIR.Sensitivity.EPHEMERAL_UNTRUSTED));
                        return DocumentObservationIR.canonical(policy, provenance(), List.of(
                                new DocumentObservationIR.ArtifactObservation(
                                        source.artifactId(), 0, source.mediaType(), source.width(), source.height(),
                                        true, lines)));
                    });
                }));
        assertEquals("RAPIDOCR_SHADOW_SECOND_RUN_DRIFT", failure.getMessage());
    }

    @Test
    void rejectsAnyAcquisitionPolicyIdentityDriftBeforeAcquisition() {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> new RapidOcrShadowEvaluation().evaluate(runOrdinal ->
                        RapidOcrShadowEvaluation.RunSession.of(
                                RapidOcrBaselineContract.policy(29_999), (artifacts, policy) -> null)));
        assertEquals("RAPIDOCR_SHADOW_ACQUISITION_POLICY_DRIFT", failure.getMessage());
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
                RapidOcrBaselineContract.CANONICALIZATION_IDENTITY
        );
    }
}
