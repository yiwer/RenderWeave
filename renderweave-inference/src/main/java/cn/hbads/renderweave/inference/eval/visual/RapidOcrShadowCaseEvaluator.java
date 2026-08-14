package cn.hbads.renderweave.inference.eval.visual;

import cn.hbads.renderweave.inference.candidate.CandidateBoundingBox;
import cn.hbads.renderweave.inference.vision.DocumentObservationCompatibilityProjection;
import cn.hbads.renderweave.inference.vision.DocumentObservationIR;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Scores actual ephemeral OCR observations against the layered gold without persisting text. */
public final class RapidOcrShadowCaseEvaluator {
    public static final String VERSION = "renderweave-rapidocr-shadow-case-evaluator/1.0";

    public RapidOcrShadowCaseRecord evaluate(
            LayeredVisualCorpus.Case evaluationCase,
            DocumentObservationIR observation,
            long acquisitionMicros
    ) {
        Objects.requireNonNull(evaluationCase, "evaluationCase");
        return evaluateAgainstSameGold(
                evaluationCase,
                observation,
                acquisitionMicros,
                evaluationCase.renderIdentity().substring("render-sha256:".length()));
    }

    /** Evaluates a deterministic local oracle raster against the unchanged normalized gold. */
    public RapidOcrShadowCaseRecord evaluateAgainstSameGold(
            LayeredVisualCorpus.Case evaluationCase,
            DocumentObservationIR observation,
            long acquisitionMicros,
            String expectedArtifactId
    ) {
        Objects.requireNonNull(evaluationCase, "evaluationCase");
        Objects.requireNonNull(observation, "observation");
        if (expectedArtifactId == null || !expectedArtifactId.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("RAPIDOCR_SHADOW_ARTIFACT_MISMATCH");
        }
        if (acquisitionMicros < 0) throw new IllegalArgumentException("RAPIDOCR_SHADOW_LATENCY_INVALID");
        var artifacts = observation.artifacts();
        if (artifacts.size() != 1 || artifacts.getFirst().sourceOrdinal() != 0
                || !expectedArtifactId.equals(artifacts.getFirst().artifactId())) {
            throw new IllegalArgumentException("RAPIDOCR_SHADOW_ARTIFACT_MISMATCH");
        }
        var projected = new DocumentObservationCompatibilityProjection().project(observation);
        if (projected.artifacts().size() != 1) {
            throw new IllegalArgumentException("RAPIDOCR_SHADOW_ARTIFACT_MISMATCH");
        }

        var gold = evaluationCase.annotation();
        var actual = projected.artifacts().getFirst().lines();
        var matches = match(gold.ocrLines(), actual);
        var ocr = ocr(gold.ocrLines(), actual, matches);
        var regionByLine = regionByLine(gold);
        var observedRegions = matches.stream().map(item -> regionByLine.get(item.gold().lineId()))
                .filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        return new RapidOcrShadowCaseRecord(
                RapidOcrShadowCaseRecord.VERSION,
                evaluationCase.caseId(),
                evaluationCase.caseIdentity(),
                evaluationCase.partition(),
                evaluationCase.domain(),
                evaluationCase.difficulty(),
                evaluationCase.failureSlices(),
                diagnosticSlices(evaluationCase),
                ocr,
                layout(gold.ocrLines(), actual, matches, observedRegions.size()),
                order(gold, matches, regionByLine, observedRegions),
                repeat(gold, observedRegions),
                confidence(observation),
                observation.observationCount(),
                acquisitionMicros
        );
    }

    private static List<RapidOcrShadowCaseRecord.DiagnosticSlice> diagnosticSlices(
            LayeredVisualCorpus.Case evaluationCase
    ) {
        var result = new ArrayList<RapidOcrShadowCaseRecord.DiagnosticSlice>();
        if (evaluationCase.difficulty() == LayeredEvaluationRecord.Difficulty.DENSE_TEXT
                || evaluationCase.failureSlices().contains(LayeredEvaluationRecord.FailureSlice.DENSE_TEXT)) {
            result.add(RapidOcrShadowCaseRecord.DiagnosticSlice.DENSE_TEXT);
        }
        if (evaluationCase.renderCase().scene().elements().stream()
                .filter(element -> element.kind() == VisualStageCorpus.ElementKind.SLOT)
                .map(element -> VisualStageCorpus.Box.from(element.boundingBox()))
                .anyMatch(box -> box.bottom() - box.top() <= 1_800)) {
            result.add(RapidOcrShadowCaseRecord.DiagnosticSlice.SMALL_TEXT);
        }
        return List.copyOf(result);
    }

    private static RapidOcrShadowCaseRecord.ConfidenceStats confidence(DocumentObservationIR observation) {
        long observations = 0;
        long nativeValueBpsSum = 0;
        long low = 0;
        long medium = 0;
        long high = 0;
        for (var artifact : observation.artifacts()) {
            for (var line : artifact.observations()) {
                observations++;
                nativeValueBpsSum += line.confidence().nativeValueBps();
                switch (line.confidence().derivedBucket()) {
                    case LOW -> low++;
                    case MEDIUM -> medium++;
                    case HIGH -> high++;
                }
            }
        }
        return new RapidOcrShadowCaseRecord.ConfidenceStats(
                observations, nativeValueBpsSum, low, medium, high);
    }

    private static List<Match> match(
            List<LayeredVisualAnnotation.OcrLine> gold,
            List<cn.hbads.renderweave.inference.vision.DocumentVisionObservation.TextLine> actual
    ) {
        var candidates = new ArrayList<Match>();
        for (var goldLine : gold) {
            for (var actualLine : actual) {
                var goldBox = goldLine.geometry().bounds();
                var actualBox = box(actualLine.boundingBox());
                var intersection = intersection(goldBox, actualBox);
                if (intersection == 0 || !containsCenter(goldBox, actualBox)) continue;
                var edits = LayeredMetricMath.characters(goldLine.text(), actualLine.text());
                var denominator = Math.max(1, Math.max(edits.referenceUnits(), edits.predictedUnits()));
                var errors = edits.substitutions() + edits.insertions() + edits.deletions();
                var similarity = Math.max(0, 10_000 - Math.toIntExact(
                        Math.floorDiv((long) errors * 10_000L, denominator)));
                candidates.add(new Match(
                        goldLine,
                        actualLine,
                        similarity,
                        ratio(intersection, area(actualBox)),
                        ratio(intersection, area(goldBox))
                ));
            }
        }
        candidates.sort(Comparator.comparingInt(Match::textSimilarityBps).reversed()
                .thenComparing(Comparator.comparingInt(Match::predictedCoverageBps).reversed())
                .thenComparing(item -> item.gold().lineId())
                .thenComparingInt(item -> item.actual().readingOrder()));
        var usedGold = new HashSet<String>();
        var usedActual = new HashSet<String>();
        var result = new ArrayList<Match>();
        for (var candidate : candidates) {
            if (usedGold.add(candidate.gold().lineId()) && usedActual.add(candidate.actual().lineId())) {
                result.add(candidate);
            }
        }
        result.sort(Comparator.comparingInt(item -> item.actual().readingOrder()));
        return List.copyOf(result);
    }

    private static LayeredEvaluationRecord.OcrStats ocr(
            List<LayeredVisualAnnotation.OcrLine> gold,
            List<cn.hbads.renderweave.inference.vision.DocumentVisionObservation.TextLine> actual,
            List<Match> matches
    ) {
        var actualByGold = new HashMap<String, String>();
        var matchedActual = new HashSet<String>();
        for (var item : matches) {
            actualByGold.put(item.gold().lineId(), item.actual().text());
            matchedActual.add(item.actual().lineId());
        }
        long referenceCharacters = 0;
        long predictedCharacters = 0;
        long characterSubstitutions = 0;
        long characterInsertions = 0;
        long characterDeletions = 0;
        long referenceWords = 0;
        long predictedWords = 0;
        long wordSubstitutions = 0;
        long wordInsertions = 0;
        long wordDeletions = 0;
        for (var line : gold) {
            var predicted = actualByGold.getOrDefault(line.lineId(), "");
            var characters = LayeredMetricMath.characters(line.text(), predicted);
            var words = LayeredMetricMath.words(line.text(), predicted);
            referenceCharacters += characters.referenceUnits();
            predictedCharacters += characters.predictedUnits();
            characterSubstitutions += characters.substitutions();
            characterInsertions += characters.insertions();
            characterDeletions += characters.deletions();
            referenceWords += words.referenceUnits();
            predictedWords += words.predictedUnits();
            wordSubstitutions += words.substitutions();
            wordInsertions += words.insertions();
            wordDeletions += words.deletions();
        }
        for (var line : actual) {
            if (matchedActual.contains(line.lineId())) continue;
            var characters = LayeredMetricMath.characters("", line.text());
            var words = LayeredMetricMath.words("", line.text());
            predictedCharacters += characters.predictedUnits();
            characterInsertions += characters.insertions();
            predictedWords += words.predictedUnits();
            wordInsertions += words.insertions();
        }
        return new LayeredEvaluationRecord.OcrStats(
                1, referenceCharacters, predictedCharacters, characterSubstitutions, characterInsertions,
                characterDeletions, referenceWords, predictedWords, wordSubstitutions, wordInsertions,
                wordDeletions, gold.isEmpty() && !actual.isEmpty() ? 1 : 0,
                actual.size() > matches.size() ? 1 : 0,
                !gold.isEmpty() && actual.isEmpty() ? 1 : 0
        );
    }

    private static RapidOcrShadowCaseRecord.LineLayoutStats layout(
            List<LayeredVisualAnnotation.OcrLine> gold,
            List<cn.hbads.renderweave.inference.vision.DocumentVisionObservation.TextLine> actual,
            List<Match> matches,
            int observedRegions
    ) {
        return new RapidOcrShadowCaseRecord.LineLayoutStats(
                new LayeredEvaluationRecord.BinaryCounts(gold.size(), actual.size(), matches.size()),
                matches.size(),
                matches.stream().mapToLong(Match::predictedCoverageBps).sum(),
                matches.stream().mapToLong(Match::goldCoverageBps).sum(),
                observedRegions
        );
    }

    private static RapidOcrShadowCaseRecord.ReadingOrderStats order(
            LayeredVisualAnnotation gold,
            List<Match> matches,
            Map<String, String> regionByLine,
            Set<String> observedRegions
    ) {
        var actualOrder = new HashMap<String, Integer>();
        for (var item : matches) {
            var regionId = regionByLine.get(item.gold().lineId());
            if (regionId != null) actualOrder.merge(regionId, item.actual().readingOrder(), Math::min);
        }
        long comparable = 0;
        long correct = 0;
        var referencedRegions = new HashSet<String>();
        for (var edge : gold.precedenceEdges()) {
            referencedRegions.add(edge.beforeRegionId());
            referencedRegions.add(edge.afterRegionId());
            var before = actualOrder.get(edge.beforeRegionId());
            var after = actualOrder.get(edge.afterRegionId());
            if (before == null || after == null) continue;
            comparable++;
            if (before < after) correct++;
        }
        return new RapidOcrShadowCaseRecord.ReadingOrderStats(
                gold.precedenceEdges().size(), comparable, correct,
                observedRegions.containsAll(referencedRegions)
        );
    }

    private static RapidOcrShadowCaseRecord.RepeatObservabilityStats repeat(
            LayeredVisualAnnotation gold,
            Set<String> observedRegions
    ) {
        long expectedItems = 0;
        long completeItems = 0;
        long expectedMemberships = 0;
        long observableMemberships = 0;
        long completeGroups = 0;
        for (var group : gold.repeatGroups()) {
            var groupComplete = true;
            expectedItems += group.items().size();
            for (var item : group.items()) {
                expectedMemberships += item.memberRegionIds().size();
                var itemObservable = item.memberRegionIds().stream().filter(observedRegions::contains).count();
                observableMemberships += itemObservable;
                if (itemObservable == item.memberRegionIds().size()) completeItems++;
                else groupComplete = false;
            }
            if (groupComplete) completeGroups++;
        }
        return new RapidOcrShadowCaseRecord.RepeatObservabilityStats(
                gold.repeatGroups().size(), completeGroups, expectedItems, completeItems,
                expectedMemberships, observableMemberships
        );
    }

    private static Map<String, String> regionByLine(LayeredVisualAnnotation gold) {
        var regions = gold.regions().stream().sorted(Comparator
                .comparingInt((LayeredVisualAnnotation.Region item) -> item.regionId().length()).reversed()
                .thenComparing(LayeredVisualAnnotation.Region::regionId)).toList();
        var result = new HashMap<String, String>();
        for (var line : gold.ocrLines()) {
            for (var region : regions) {
                var prefix = "line-" + region.regionId();
                if (line.lineId().equals(prefix) || line.lineId().startsWith(prefix + "-")) {
                    result.put(line.lineId(), region.regionId());
                    break;
                }
            }
        }
        return Map.copyOf(result);
    }

    private static LayeredVisualAnnotation.Box box(CandidateBoundingBox value) {
        return new LayeredVisualAnnotation.Box(value.left(), value.top(), value.right(), value.bottom());
    }

    private static boolean containsCenter(
            LayeredVisualAnnotation.Box outer,
            LayeredVisualAnnotation.Box inner
    ) {
        var centerX = Math.floorDiv((long) inner.left() + inner.right(), 2);
        var centerY = Math.floorDiv((long) inner.top() + inner.bottom(), 2);
        return centerX >= outer.left() && centerX < outer.right()
                && centerY >= outer.top() && centerY < outer.bottom();
    }

    private static long intersection(LayeredVisualAnnotation.Box left, LayeredVisualAnnotation.Box right) {
        var width = Math.max(0, Math.min(left.right(), right.right()) - Math.max(left.left(), right.left()));
        var height = Math.max(0, Math.min(left.bottom(), right.bottom()) - Math.max(left.top(), right.top()));
        return Math.multiplyExact((long) width, height);
    }

    private static long area(LayeredVisualAnnotation.Box value) {
        return Math.multiplyExact((long) value.right() - value.left(), (long) value.bottom() - value.top());
    }

    private static int ratio(long numerator, long denominator) {
        return denominator == 0 ? 0 : Math.toIntExact(Math.floorDiv(numerator * 10_000L, denominator));
    }

    private record Match(
            LayeredVisualAnnotation.OcrLine gold,
            cn.hbads.renderweave.inference.vision.DocumentVisionObservation.TextLine actual,
            int textSimilarityBps,
            int predictedCoverageBps,
            int goldCoverageBps
    ) { }
}
