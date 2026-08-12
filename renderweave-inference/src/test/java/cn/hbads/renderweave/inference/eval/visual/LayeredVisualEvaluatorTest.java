package cn.hbads.renderweave.inference.eval.visual;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LayeredVisualEvaluatorTest {
    private final LayeredVisualCorpus corpus = new LayeredVisualCorpus();
    private final LayeredVisualEvaluator evaluator = new LayeredVisualEvaluator();

    @Test
    void perfectEphemeralReplayProducesEveryRequiredLayerWithoutExternalProviderUse() {
        var gold = corpus.require("transit-board-v1");
        var prediction = LayeredSyntheticReplay.perfect(gold);
        var result = evaluator.evaluate(gold, prediction);

        assertEquals(0, result.ocr().cerBps());
        assertEquals(0, result.ocr().werBps());
        assertEquals(0, result.ocr().hallucinationCases());
        assertEquals(0, result.ocr().completeMissCases());
        result.layout().byKind().values().forEach(item -> assertEquals(10_000, item.meanAp5095Bps()));
        assertEquals(10_000, result.layout().evidence().f1Bps());
        assertEquals(10_000, result.order().precedenceEdges().f1Bps());
        assertEquals(0, result.order().cycleCases());
        assertEquals(10_000, result.repeat().groups().f1Bps());
        assertEquals(10_000, result.repeat().items().f1Bps());
        assertEquals(10_000, result.repeat().memberships().f1Bps());
        assertEquals(10_000, result.semantic().slots().f1Bps());
        assertEquals(10_000, result.semantic().groups().f1Bps());
        assertEquals(10_000, result.semantic().entities().f1Bps());
        assertEquals(10_000, result.semantic().relationships().f1Bps());
        assertEquals(10_000, result.semantic().cardinalities().f1Bps());
        assertEquals(10_000, result.semantic().bindings().f1Bps());
        assertEquals(10_000, result.semantic().ownerContainment().f1Bps());
        assertEquals(result.semantic().survival().expectedSlots(),
                result.semantic().survival().candidateSlots());
        assertEquals(1, result.candidate().contractValidCases());
        assertEquals(10_000, result.candidate().fields().f1Bps());
        assertEquals(1, result.candidate().dagValidCases());
        assertEquals(1, result.candidate().topologyPreservedCases());
        assertEquals(1_000, result.calibration().expectedCalibrationErrorBps());
        assertEquals(100, result.calibration().brierScoreBps());
        assertEquals(1, result.calibration().reviewRequiredReachedCases());
        assertEquals(3, result.runtime().scriptedCalls());
        assertEquals(0, result.runtime().providerAttempts());
        assertEquals(0, result.runtime().providerReservations());
        assertEquals(0, result.runtime().externalProviderCostMicrosCny());
    }

    @Test
    void emptyAndCyclicPredictionExposesLayerSpecificLossInsteadOfHidingItInOneAverage() {
        var gold = corpus.require("transit-board-v1");
        var regions = gold.annotation().regions();
        var prediction = new LayeredVisualPrediction(
                gold.caseId(), List.of(), List.of(), List.of(),
                List.of(new LayeredVisualAnnotation.PrecedenceEdge(
                                regions.get(0).regionId(), regions.get(1).regionId()),
                        new LayeredVisualAnnotation.PrecedenceEdge(
                                regions.get(1).regionId(), regions.get(0).regionId())),
                List.of(), List.of(), List.of(), List.of(), null, List.of(),
                LayeredVisualPrediction.Runtime.empty());

        var result = evaluator.evaluate(gold, prediction);

        assertEquals(1, result.ocr().completeMissCases());
        assertTrue(result.ocr().cerBps() > 0);
        assertEquals(0, result.semantic().slots().recallBps());
        assertEquals(0, result.semantic().entities().recallBps());
        assertEquals(1, result.order().cycleCases());
        assertTrue(result.repeat().itemCountAbsoluteError() > 0);
        assertEquals(0, result.candidate().contractValidCases());
        assertEquals(0, result.candidate().topologyPreservedCases());
        assertEquals(0, result.calibration().reviewRequiredReachedCases());
    }

    @Test
    void extraLineAndEmptyGoldInsertionRemainDistinctOcrFailureMetrics() {
        var original = corpus.require("transit-board-v1");
        var extraLine = new LayeredVisualPrediction.OcrLine("unexpected-line", "ephemeral-extra");
        var nonEmptyGold = withOcrLines(LayeredSyntheticReplay.perfect(original),
                append(LayeredSyntheticReplay.perfect(original).ocrLines(), extraLine));

        var hallucination = evaluator.evaluate(original, nonEmptyGold).ocr();

        assertEquals(1, hallucination.hallucinationCases());
        assertEquals(0, hallucination.emptyReferenceCases());

        var annotation = original.annotation();
        var nonOcrEvidence = annotation.evidence().stream()
                .filter(item -> item.ownerKind() != LayeredVisualAnnotation.OwnerKind.OCR_LINE
                        && item.ownerKind() != LayeredVisualAnnotation.OwnerKind.OCR_TOKEN)
                .toList();
        var emptyAnnotation = new LayeredVisualAnnotation(
                annotation.annotationVersion(), annotation.caseId(), annotation.renderIdentity(),
                annotation.sourceLicense(), List.of(), List.of(), annotation.regions(), nonOcrEvidence,
                annotation.precedenceEdges(), annotation.repeatGroups(), annotation.entities(),
                annotation.relationships(), annotation.bindings(), annotation.candidate(),
                annotation.abstention());
        var emptyGold = new LayeredVisualCorpus.Case(
                original.caseId(), original.partition(), original.domain(), original.difficulty(),
                original.failureSlices(), original.renderCase(), original.renderIdentity(), emptyAnnotation,
                original.annotationIdentity(), original.caseIdentity());
        var emptyGoldInsertion = withOcrLines(LayeredSyntheticReplay.perfect(emptyGold), List.of(extraLine));

        var insertion = evaluator.evaluate(emptyGold, emptyGoldInsertion).ocr();

        assertEquals(1, insertion.hallucinationCases());
        assertEquals(1, insertion.emptyReferenceCases());
    }

    @Test
    void caseIdentityMismatchFailsClosed() {
        var gold = corpus.require("transit-board-v1");
        var prediction = LayeredSyntheticReplay.perfect(corpus.require("transit-board-v2"));
        assertThrows(IllegalArgumentException.class, () -> evaluator.evaluate(gold, prediction));
    }

    private static LayeredVisualPrediction withOcrLines(
            LayeredVisualPrediction source,
            List<LayeredVisualPrediction.OcrLine> ocrLines
    ) {
        return new LayeredVisualPrediction(
                source.caseId(), ocrLines, source.regions(), source.evidence(), source.precedenceEdges(),
                source.repeatGroups(), source.entities(), source.relationships(), source.bindings(),
                source.candidate(), source.confidence(), source.runtime());
    }

    private static <T> List<T> append(List<T> source, T item) {
        var result = new ArrayList<>(source);
        result.add(item);
        return List.copyOf(result);
    }
}
