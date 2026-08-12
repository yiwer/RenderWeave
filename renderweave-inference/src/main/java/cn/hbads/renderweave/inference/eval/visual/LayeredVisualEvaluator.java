package cn.hbads.renderweave.inference.eval.visual;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Converts controlled gold plus ephemeral predictions into payload-safe sufficient statistics. */
public final class LayeredVisualEvaluator {
    public static final String VERSION = "renderweave-layered-evaluator/1.0";

    public LayeredEvaluationRecord evaluate(
            LayeredVisualCorpus.Case evaluationCase,
            LayeredVisualPrediction prediction
    ) {
        Objects.requireNonNull(evaluationCase, "evaluationCase");
        Objects.requireNonNull(prediction, "prediction");
        if (!evaluationCase.caseId().equals(prediction.caseId())) {
            throw new IllegalArgumentException("LAYERED_EVALUATION_CASE_ID_MISMATCH");
        }
        var gold = evaluationCase.annotation();
        var layout = layout(gold, prediction);
        var order = order(gold, prediction);
        var repeat = repeat(gold, prediction);
        var semantic = semantic(gold, prediction);
        var candidate = candidate(gold, prediction);
        var calibration = calibration(gold, prediction);
        return new LayeredEvaluationRecord(
                LayeredEvaluationRecord.VERSION, evaluationCase.caseId(), evaluationCase.caseIdentity(),
                evaluationCase.partition(), evaluationCase.domain(), evaluationCase.difficulty(),
                evaluationCase.failureSlices(), outcome(prediction), ocr(gold, prediction), layout, order,
                repeat, semantic, candidate, calibration, runtime(prediction.runtime()));
    }

    private static LayeredEvaluationRecord.OcrStats ocr(
            LayeredVisualAnnotation gold,
            LayeredVisualPrediction prediction
    ) {
        var predictedById = index(prediction.ocrLines(), LayeredVisualPrediction.OcrLine::lineId,
                "DUPLICATE_PREDICTED_OCR_LINE");
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
        for (var line : gold.ocrLines()) {
            var predicted = predictedById.remove(line.lineId());
            var text = predicted == null ? "" : predicted.text();
            var characters = LayeredMetricMath.characters(line.text(), text);
            var words = LayeredMetricMath.words(line.text(), text);
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
        for (var extra : predictedById.values()) {
            var characters = LayeredMetricMath.characters("", extra.text());
            var words = LayeredMetricMath.words("", extra.text());
            predictedCharacters += characters.predictedUnits();
            characterInsertions += characters.insertions();
            predictedWords += words.predictedUnits();
            wordInsertions += words.insertions();
        }
        var hallucination = predictedById.isEmpty() ? 0 : 1;
        var completeMiss = !gold.ocrLines().isEmpty() && prediction.ocrLines().isEmpty() ? 1 : 0;
        return new LayeredEvaluationRecord.OcrStats(
                1, referenceCharacters, predictedCharacters, characterSubstitutions, characterInsertions,
                characterDeletions, referenceWords, predictedWords, wordSubstitutions, wordInsertions,
                wordDeletions, hallucination, hallucination, completeMiss);
    }

    private static LayeredEvaluationRecord.LayoutStats layout(
            LayeredVisualAnnotation gold,
            LayeredVisualPrediction prediction
    ) {
        var byKind = new EnumMap<LayeredVisualAnnotation.RegionKind, LayeredEvaluationRecord.DetectionStats>(
                LayeredVisualAnnotation.RegionKind.class);
        for (var kind : LayeredVisualAnnotation.RegionKind.values()) {
            var expected = gold.regions().stream().filter(item -> item.kind() == kind)
                    .map(item -> detection(item.regionId(), kind.name(), item.geometry(), 10_000)).toList();
            var actual = prediction.regions().stream().filter(item -> item.kind() == kind)
                    .map(item -> detection(item.regionId(), kind.name(), item.geometry(), item.confidenceBps()))
                    .toList();
            var score = LayeredMetricMath.detection(expected, actual);
            byKind.put(kind, new LayeredEvaluationRecord.DetectionStats(
                    score.expected(), score.predicted(), score.matchedByIouThreshold().stream()
                    .map(Integer::longValue).toList(), score.matchedAtIou50(), score.matchedIouBpsSum(),
                    score.ap5095Bps(), 1));
        }
        var evidence = evidenceCounts(gold.evidence(), prediction.evidence(), false);
        return new LayeredEvaluationRecord.LayoutStats(byKind, evidence, evidence.predicted() - evidence.matched());
    }

    private static LayeredEvaluationRecord.OrderStats order(
            LayeredVisualAnnotation gold,
            LayeredVisualPrediction prediction
    ) {
        var expected = gold.precedenceEdges().stream().map(LayeredVisualEvaluator::edgeKey).toList();
        var actual = prediction.precedenceEdges().stream().map(LayeredVisualEvaluator::edgeKey).toList();
        var counts = counts(LayeredMetricMath.setCounts(expected, actual));
        return new LayeredEvaluationRecord.OrderStats(
                counts, LayeredMetricMath.hasCycle(actual) ? 1 : 0, 1);
    }

    private static LayeredEvaluationRecord.RepeatStats repeat(
            LayeredVisualAnnotation gold,
            LayeredVisualPrediction prediction
    ) {
        var expectedGroups = gold.repeatGroups().stream().map(LayeredVisualAnnotation.RepeatGroup::groupRegionId)
                .toList();
        var actualGroups = prediction.repeatGroups().stream()
                .map(LayeredVisualAnnotation.RepeatGroup::groupRegionId).toList();
        var expectedItems = repeatItems(gold.repeatGroups());
        var actualItems = repeatItems(prediction.repeatGroups());
        var expectedMemberships = repeatMemberships(gold.repeatGroups());
        var actualMemberships = repeatMemberships(prediction.repeatGroups());
        return new LayeredEvaluationRecord.RepeatStats(
                counts(LayeredMetricMath.setCounts(expectedGroups, actualGroups)),
                counts(LayeredMetricMath.setCounts(expectedItems, actualItems)),
                Math.abs(expectedItems.size() - actualItems.size()),
                counts(LayeredMetricMath.setCounts(expectedMemberships, actualMemberships)));
    }

    private static LayeredEvaluationRecord.SemanticStats semantic(
            LayeredVisualAnnotation gold,
            LayeredVisualPrediction prediction
    ) {
        var slots = setCounts(
                gold.regions().stream().filter(item -> item.kind() == LayeredVisualAnnotation.RegionKind.SLOT)
                        .map(LayeredVisualAnnotation.Region::regionId).toList(),
                prediction.regions().stream().filter(item -> item.kind() == LayeredVisualAnnotation.RegionKind.SLOT)
                        .map(LayeredVisualPrediction.Region::regionId).toList());
        var groups = setCounts(
                gold.regions().stream().filter(item -> item.kind() == LayeredVisualAnnotation.RegionKind.GROUP
                                || item.kind() == LayeredVisualAnnotation.RegionKind.REPEATED_GROUP)
                        .map(LayeredVisualAnnotation.Region::regionId).toList(),
                prediction.regions().stream().filter(item -> item.kind() == LayeredVisualAnnotation.RegionKind.GROUP
                                || item.kind() == LayeredVisualAnnotation.RegionKind.REPEATED_GROUP)
                        .map(LayeredVisualPrediction.Region::regionId).toList());
        var entities = setCounts(gold.entities().stream().map(LayeredVisualEvaluator::entityKey).toList(),
                prediction.entities().stream().map(LayeredVisualEvaluator::entityKey).toList());
        var relationships = setCounts(
                gold.relationships().stream().map(LayeredVisualEvaluator::relationshipKey).toList(),
                prediction.relationships().stream().map(LayeredVisualEvaluator::relationshipKey).toList());
        var cardinalities = setCounts(
                gold.relationships().stream().map(LayeredVisualEvaluator::cardinalityKey).toList(),
                prediction.relationships().stream().map(LayeredVisualEvaluator::cardinalityKey).toList());
        var bindings = setCounts(gold.bindings().stream().map(LayeredVisualEvaluator::bindingKey).toList(),
                prediction.bindings().stream().map(LayeredVisualEvaluator::bindingKey).toList());
        var ownerContainment = evidenceCounts(gold.evidence(), prediction.evidence(), true);

        var matchedSlotIds = intersection(
                gold.regions().stream().filter(item -> item.kind() == LayeredVisualAnnotation.RegionKind.SLOT)
                        .map(LayeredVisualAnnotation.Region::regionId).toList(),
                prediction.regions().stream().filter(item -> item.kind() == LayeredVisualAnnotation.RegionKind.SLOT)
                        .map(LayeredVisualPrediction.Region::regionId).toList());
        var predictedBindingKeys = prediction.bindings().stream().map(LayeredVisualEvaluator::bindingKey)
                .collect(java.util.stream.Collectors.toSet());
        var boundSlotIds = gold.bindings().stream().filter(item -> matchedSlotIds.contains(item.regionId())
                        && predictedBindingKeys.contains(bindingKey(item)))
                .map(LayeredVisualAnnotation.Binding::regionId).collect(java.util.stream.Collectors.toSet());
        var candidateFieldKeys = prediction.candidate() == null ? Set.<String>of()
                : prediction.candidate().fields().stream().map(LayeredVisualEvaluator::candidateFieldKey)
                .collect(java.util.stream.Collectors.toSet());
        var bindingById = gold.bindings().stream().collect(java.util.stream.Collectors.toMap(
                LayeredVisualAnnotation.Binding::bindingId, Function.identity()));
        var candidateSlots = gold.candidate().fields().stream()
                .filter(item -> candidateFieldKeys.contains(candidateFieldKey(item)))
                .map(item -> bindingById.get(item.bindingId()))
                .filter(Objects::nonNull).map(LayeredVisualAnnotation.Binding::regionId)
                .filter(boundSlotIds::contains).distinct().count();
        var repairAttempts = prediction.runtime().recoveryCode() == LayeredVisualPrediction.RecoveryCode.FIXED_RETRY
                ? prediction.runtime().recoveryCount() : 0;
        var repairSuccesses = repairAttempts > 0 && prediction.candidate() != null
                && "REVIEW_REQUIRED".equals(prediction.candidate().outcomeCode()) ? 1 : 0;
        return new LayeredEvaluationRecord.SemanticStats(
                slots, groups, entities, relationships, cardinalities, bindings, ownerContainment,
                new LayeredEvaluationRecord.SurvivalStats(slots.expected(), slots.matched(),
                        boundSlotIds.size(), candidateSlots), repairAttempts, repairSuccesses);
    }

    private static LayeredEvaluationRecord.CandidateStats candidate(
            LayeredVisualAnnotation gold,
            LayeredVisualPrediction prediction
    ) {
        var actual = prediction.candidate();
        var entities = setCounts(gold.entities().stream().map(LayeredVisualAnnotation.Entity::entityId).toList(),
                prediction.entities().stream().map(LayeredVisualAnnotation.Entity::entityId).toList());
        var fields = setCounts(gold.candidate().fields().stream().map(LayeredVisualEvaluator::candidateFieldKey)
                        .toList(), actual == null ? List.of()
                        : actual.fields().stream().map(LayeredVisualEvaluator::candidateFieldKey).toList());
        var relationships = setCounts(gold.candidate().relationshipIds(),
                actual == null ? List.of() : actual.relationshipIds());
        var supportedTypes = setCounts(
                gold.candidate().fields().stream().map(LayeredVisualEvaluator::candidateTypeKey).toList(),
                actual == null ? List.of()
                        : actual.fields().stream().map(LayeredVisualEvaluator::candidateTypeKey).toList());
        var expectedEvidence = gold.candidate().fields().stream().map(LayeredVisualAnnotation.CandidateField::fieldId)
                .toList();
        var actualEvidence = prediction.evidence().stream()
                .filter(item -> item.ownerKind() == LayeredVisualAnnotation.OwnerKind.CANDIDATE_FIELD)
                .map(LayeredVisualPrediction.Evidence::ownerId).distinct().toList();
        var evidence = setCounts(expectedEvidence, actualEvidence);
        var topologyExpected = gold.candidate().topologyRequired() ? 1 : 0;
        var topologyPreserved = actual != null && actual.topologyPreserved()
                && gold.candidate().rootEntityId().equals(actual.rootEntityId())
                && Set.copyOf(gold.candidate().relationshipIds()).equals(Set.copyOf(actual.relationshipIds()))
                ? topologyExpected : 0;
        return new LayeredEvaluationRecord.CandidateStats(
                1, actual != null && actual.contractValid() ? 1 : 0, entities, fields, relationships,
                supportedTypes, evidence, actual != null && actual.dagValid() ? 1 : 0,
                actual == null ? 0 : actual.criticalHallucinations(), actual == null ? 0 : actual.blockers(),
                topologyExpected, topologyPreserved);
    }

    private static LayeredEvaluationRecord.CalibrationStats calibration(
            LayeredVisualAnnotation gold,
            LayeredVisualPrediction prediction
    ) {
        var correctOwnerIds = new HashSet<String>();
        correctOwnerIds.addAll(intersection(
                gold.regions().stream().map(LayeredVisualAnnotation.Region::regionId).toList(),
                prediction.regions().stream().map(LayeredVisualPrediction.Region::regionId).toList()));
        correctOwnerIds.addAll(intersection(
                gold.entities().stream().map(LayeredVisualAnnotation.Entity::entityId).toList(),
                prediction.entities().stream().map(LayeredVisualAnnotation.Entity::entityId).toList()));
        correctOwnerIds.addAll(intersection(
                gold.relationships().stream().map(LayeredVisualAnnotation.Relationship::relationshipId).toList(),
                prediction.relationships().stream().map(LayeredVisualAnnotation.Relationship::relationshipId)
                        .toList()));
        correctOwnerIds.addAll(intersection(
                gold.bindings().stream().map(LayeredVisualAnnotation.Binding::bindingId).toList(),
                prediction.bindings().stream().map(LayeredVisualAnnotation.Binding::bindingId).toList()));
        var mutable = new long[10][4];
        for (var item : prediction.confidence()) {
            var index = Math.min(9, item.confidenceBps() / 1_000);
            var correct = correctOwnerIds.contains(item.ownerId()) ? 1 : 0;
            var error = item.confidenceBps() - correct * 10_000;
            mutable[index][0]++;
            mutable[index][1] += correct;
            mutable[index][2] += item.confidenceBps();
            mutable[index][3] += Math.floorDiv((long) error * error, 10_000);
        }
        var bins = new ArrayList<LayeredEvaluationRecord.CalibrationBin>();
        for (var index = 0; index < 10; index++) {
            bins.add(new LayeredEvaluationRecord.CalibrationBin(index, mutable[index][0], mutable[index][1],
                    mutable[index][2], mutable[index][3]));
        }
        var expectedUnresolved = gold.abstention().expectedUnresolvedOwnerIds();
        var actualUnresolved = prediction.candidate() == null ? List.<String>of()
                : prediction.candidate().fields().stream()
                .filter(item -> item.valueKind() == LayeredVisualAnnotation.ValueKind.UNRESOLVED)
                .map(LayeredVisualAnnotation.CandidateField::fieldId).toList();
        var reviewReached = prediction.candidate() != null
                && "REVIEW_REQUIRED".equals(prediction.candidate().outcomeCode()) ? 1 : 0;
        var successful = prediction.candidate() != null && prediction.candidate().contractValid() ? 1 : 0;
        return new LayeredEvaluationRecord.CalibrationStats(
                bins, setCounts(expectedUnresolved, actualUnresolved), reviewReached, successful, 1);
    }

    private static LayeredEvaluationRecord.RuntimeStats runtime(LayeredVisualPrediction.Runtime value) {
        var latency = new EnumMap<LayeredEvaluationRecord.Stage, Long>(LayeredEvaluationRecord.Stage.class);
        value.latencyMicros().forEach((stage, micros) ->
                latency.put(LayeredEvaluationRecord.Stage.valueOf(stage.name()), micros));
        return new LayeredEvaluationRecord.RuntimeStats(
                value.scriptedCalls(), value.inputTokens(), value.outputTokens(),
                value.estimatedCostMicrosCny(), value.settledCostMicrosCny(), latency,
                LayeredEvaluationRecord.RecoveryCode.valueOf(value.recoveryCode().name()),
                value.recoveryCount(), value.acceptedStageReplayCount(), value.providerAttempts(),
                value.providerReservations(), value.externalProviderCostMicrosCny());
    }

    private static LayeredEvaluationRecord.BinaryCounts evidenceCounts(
            List<LayeredVisualAnnotation.Evidence> expected,
            List<LayeredVisualPrediction.Evidence> actual,
            boolean semanticOnly
    ) {
        var allowed = Set.of(
                LayeredVisualAnnotation.OwnerKind.ENTITY,
                LayeredVisualAnnotation.OwnerKind.RELATIONSHIP,
                LayeredVisualAnnotation.OwnerKind.BINDING,
                LayeredVisualAnnotation.OwnerKind.CANDIDATE_FIELD);
        var filteredExpected = expected.stream().filter(item -> !semanticOnly || allowed.contains(item.ownerKind()))
                .toList();
        var filteredActual = actual.stream().filter(item -> !semanticOnly || allowed.contains(item.ownerKind()))
                .toList();
        var used = new boolean[filteredActual.size()];
        var matched = 0;
        for (var gold : filteredExpected) {
            for (var index = 0; index < filteredActual.size(); index++) {
                if (used[index]) continue;
                var predicted = filteredActual.get(index);
                if (gold.ownerKind() != predicted.ownerKind() || !gold.ownerId().equals(predicted.ownerId())) {
                    continue;
                }
                var goldBox = gold.geometry().bounds();
                var predictedBox = predicted.geometry().bounds();
                var geometryMatches = semanticOnly
                        ? goldBox.contains(predictedBox)
                        : LayeredMetricMath.iouBps(box(goldBox), box(predictedBox)) >= 5_000;
                if (geometryMatches) {
                    used[index] = true;
                    matched++;
                    break;
                }
            }
        }
        return new LayeredEvaluationRecord.BinaryCounts(filteredExpected.size(), filteredActual.size(), matched);
    }

    private static LayeredMetricMath.Detection detection(
            String id,
            String kind,
            LayeredVisualAnnotation.Geometry geometry,
            int confidence
    ) {
        return new LayeredMetricMath.Detection(id, kind, box(geometry.bounds()), confidence);
    }

    private static LayeredMetricMath.Box box(LayeredVisualAnnotation.Box value) {
        return new LayeredMetricMath.Box(value.left(), value.top(), value.right(), value.bottom());
    }

    private static LayeredEvaluationRecord.BinaryCounts setCounts(List<String> expected, List<String> actual) {
        return counts(LayeredMetricMath.setCounts(expected, actual));
    }

    private static LayeredEvaluationRecord.BinaryCounts counts(LayeredMetricMath.SetCounts value) {
        return new LayeredEvaluationRecord.BinaryCounts(value.expected(), value.predicted(), value.matched());
    }

    private static Set<String> intersection(List<String> expected, List<String> actual) {
        var expectedSet = Set.copyOf(expected);
        return actual.stream().filter(expectedSet::contains).collect(java.util.stream.Collectors.toSet());
    }

    private static List<String> repeatItems(List<LayeredVisualAnnotation.RepeatGroup> values) {
        return values.stream().flatMap(group -> group.items().stream()
                .map(item -> group.groupRegionId() + ">" + item.itemRegionId())).toList();
    }

    private static List<String> repeatMemberships(List<LayeredVisualAnnotation.RepeatGroup> values) {
        return values.stream().flatMap(group -> group.items().stream().flatMap(item ->
                item.memberRegionIds().stream().map(member ->
                        group.groupRegionId() + ">" + item.itemRegionId() + ">" + member))).toList();
    }

    private static String edgeKey(LayeredVisualAnnotation.PrecedenceEdge value) {
        return value.beforeRegionId() + ">" + value.afterRegionId();
    }

    private static String entityKey(LayeredVisualAnnotation.Entity value) {
        return value.entityId() + ">" + value.schemaKey();
    }

    private static String relationshipKey(LayeredVisualAnnotation.Relationship value) {
        return value.relationshipId() + ">" + value.parentEntityId() + ">" + value.childEntityId()
                + ">" + value.fieldKey();
    }

    private static String cardinalityKey(LayeredVisualAnnotation.Relationship value) {
        return value.relationshipId() + ">" + value.cardinality().name();
    }

    private static String bindingKey(LayeredVisualAnnotation.Binding value) {
        return value.bindingId() + ">" + value.regionId() + ">" + value.entityId() + ">" + value.fieldKey();
    }

    private static String candidateFieldKey(LayeredVisualAnnotation.CandidateField value) {
        return value.fieldId() + ">" + value.entityId() + ">" + value.fieldKey() + ">" + value.bindingId();
    }

    private static String candidateTypeKey(LayeredVisualAnnotation.CandidateField value) {
        return candidateFieldKey(value) + ">" + value.valueKind().name();
    }

    private static String outcome(LayeredVisualPrediction value) {
        return value.candidate() == null ? "NO_CANDIDATE" : value.candidate().outcomeCode();
    }

    private static <T> Map<String, T> index(List<T> values, Function<T, String> identity, String code) {
        var result = new HashMap<String, T>();
        for (var value : values) if (result.putIfAbsent(identity.apply(value), value) != null) {
            throw new IllegalArgumentException(code);
        }
        return result;
    }
}
