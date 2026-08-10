package cn.hbads.renderweave.inference.eval.visual;

import cn.hbads.renderweave.inference.candidate.CandidateAssessment;
import cn.hbads.renderweave.inference.candidate.CandidateBundle;
import cn.hbads.renderweave.inference.candidate.CandidateField;
import cn.hbads.renderweave.inference.candidate.CandidateReferenceKind;
import cn.hbads.renderweave.inference.candidate.CandidateSchema;
import cn.hbads.renderweave.inference.candidate.CandidateValue;
import cn.hbads.renderweave.inference.candidate.CandidateValueKind;
import cn.hbads.renderweave.inference.eval.LiveCandidateEvaluator;
import cn.hbads.renderweave.inference.eval.LiveEvaluationCase;
import cn.hbads.renderweave.inference.eval.LiveEvaluationPartition;
import cn.hbads.renderweave.inference.input.InferenceMode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Scores every serial visual stage against the same complete semantic scene graph. */
public final class VisualStageEvaluator {
    private static final int IOU_MATCH_THRESHOLD_BPS = 5_000;
    private final LiveCandidateEvaluator finalEvaluator = new LiveCandidateEvaluator();

    public VisualStageEvaluationResult evaluate(
            VisualStageCorpus.EvaluationCase gold,
            VisualStageSnapshot actual
    ) {
        Objects.requireNonNull(gold, "gold");
        Objects.requireNonNull(actual, "actual");
        var elementMatches = matchElements(gold.scene().elements(), actual.elements());
        var slotCounts = elementCounts(gold.scene().elements(), actual.elements(), elementMatches,
                VisualStageCorpus.ElementKind.SLOT);
        var groupCounts = elementCounts(gold.scene().elements(), actual.elements(), elementMatches,
                VisualStageCorpus.ElementKind.GROUP);
        var grounding = grounding(gold.scene().elements(), elementMatches);

        var goldPaths = gold.scene().entityPaths();
        var actualPaths = observedPaths(actual);
        var entityCounts = counts(Set.copyOf(goldPaths.values()), Set.copyOf(actualPaths.values()));
        var relationshipCounts = counts(
                gold.scene().relationships().stream().map(edge -> edgeIdentity(
                        goldPaths.get(edge.parentEntityId()), goldPaths.get(edge.childEntityId()),
                        edge.fieldKey(), edge.cardinality()
                )).collect(java.util.stream.Collectors.toSet()),
                actual.relationships().stream()
                        .filter(edge -> actualPaths.containsKey(edge.parentEntityId())
                                && actualPaths.containsKey(edge.childEntityId()))
                        .map(edge -> edgeIdentity(
                                actualPaths.get(edge.parentEntityId()), actualPaths.get(edge.childEntityId()),
                                edge.fieldKey(), edge.cardinality()
                        )).collect(java.util.stream.Collectors.toSet())
        );
        var correctBindings = correctBindings(gold, actual, elementMatches, actualPaths);
        var bindingCounts = new VisualStageEvaluationResult.StageCounts(
                gold.scene().bindings().size(), actual.bindings().size(), correctBindings.size()
        );

        var candidateGraph = CandidateGraph.from(actual.candidate());
        var candidateSurvival = correctBindings.stream().filter(goldElementId -> {
            var element = gold.scene().elements().stream()
                    .filter(item -> item.elementId().equals(goldElementId)).findFirst().orElseThrow();
            var expectedPath = gold.scene().bindingEntityPaths().get(goldElementId);
            return candidateGraph.fieldShapes().containsKey(fieldIdentity(expectedPath, element.proposedKey()));
        }).count();
        var observedSlots = elementMatches.stream()
                .filter(match -> match.gold().kind() == VisualStageCorpus.ElementKind.SLOT).count();
        var survival = new VisualStageEvaluationResult.SurvivalMetrics(
                slotCounts.expected(), Math.toIntExact(observedSlots), correctBindings.size(),
                Math.toIntExact(candidateSurvival)
        );

        var expectedTokens = expectedTreeTokens(gold);
        var actualTokens = candidateGraph.treeTokens();
        var symmetricDifference = new HashSet<>(expectedTokens);
        symmetricDifference.removeAll(actualTokens);
        var unexpected = new HashSet<>(actualTokens);
        unexpected.removeAll(expectedTokens);
        var treeDistance = Math.addExact(symmetricDifference.size(), unexpected.size());
        var treeDenominator = Math.max(1, Math.addExact(expectedTokens.size(), actualTokens.size()));

        var finalResult = actual.candidate() == null
                ? finalEvaluator.failure(finalGold(gold), "VISUAL_STAGE_CANDIDATE_MISSING")
                : finalEvaluator.evaluate(finalGold(gold), actual.candidate(), actual.candidateProblems());
        return new VisualStageEvaluationResult(
                gold.caseId(), gold.partition(), gold.scene().domainPack(), gold.style(),
                finalResult.outcomeCode(), actual.providerCalls(), actual.repairRounds(),
                slotCounts, groupCounts, grounding, entityCounts, relationshipCounts, bindingCounts,
                survival, treeDistance, treeDenominator,
                calibration(gold, candidateGraph),
                VisualStageEvaluationResult.FinalCandidateMetrics.from(finalResult)
        );
    }

    /** Scores every durable stage while preserving a terminal run failure as the case outcome. */
    public VisualStageEvaluationResult evaluateFailure(
            VisualStageCorpus.EvaluationCase gold,
            VisualStageSnapshot actual,
            String outcomeCode
    ) {
        if (outcomeCode == null || !outcomeCode.matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw new IllegalArgumentException("Visual terminal outcome code is invalid");
        }
        var result = evaluate(gold, actual);
        var candidate = result.finalCandidate();
        var failedCandidate = new VisualStageEvaluationResult.FinalCandidateMetrics(
                outcomeCode, false, candidate.bundleContractBps(), candidate.entities(), candidate.fields(),
                candidate.relationships(), candidate.supportedTypeExpected(),
                candidate.supportedTypeMatched(), candidate.evidenceExpected(), candidate.evidencePresent(),
                candidate.dagValidityBps(), candidate.criticalHallucinations(), candidate.blockers()
        );
        return new VisualStageEvaluationResult(
                result.caseId(), result.partition(), result.domainPack(), result.style(), outcomeCode,
                result.providerCalls(), result.repairRounds(), result.slots(), result.groups(),
                result.grounding(), result.entities(), result.relationships(), result.bindings(),
                result.survival(), result.treeEditDistance(), result.treeEditDenominator(),
                result.calibrationBins(), failedCandidate
        );
    }

    private static List<ElementMatch> matchElements(
            List<VisualStageCorpus.Element> gold,
            List<VisualStageSnapshot.ObservedElement> actual
    ) {
        var proposals = new ArrayList<ElementMatch>();
        for (var expected : gold) {
            for (var observed : actual) {
                if (expected.kind() != observed.kind()
                        || !expected.proposedKey().equals(observed.proposedKey())) continue;
                var iou = observed.evidenceBoxes().stream().mapToInt(box -> iou(expected.box(), box)).max()
                        .orElse(0);
                var contractBonus = expected.multiplicity() == observed.multiplicity() ? 20_000 : 0;
                if (expected.kind() == VisualStageCorpus.ElementKind.SLOT
                        && expected.valueHint() == observed.valueHint()) contractBonus += 10_000;
                proposals.add(new ElementMatch(expected, observed, iou, contractBonus + iou));
            }
        }
        proposals.sort(Comparator.comparingInt(ElementMatch::score).reversed()
                .thenComparing(item -> item.gold().elementId())
                .thenComparing(item -> item.actual().elementId()));
        var usedGold = new HashSet<String>();
        var usedActual = new HashSet<String>();
        var result = new ArrayList<ElementMatch>();
        for (var proposal : proposals) {
            if (usedGold.add(proposal.gold().elementId()) && usedActual.add(proposal.actual().elementId())) {
                result.add(proposal);
            }
        }
        return List.copyOf(result);
    }

    private static VisualStageEvaluationResult.StageCounts elementCounts(
            List<VisualStageCorpus.Element> gold,
            List<VisualStageSnapshot.ObservedElement> actual,
            List<ElementMatch> matches,
            VisualStageCorpus.ElementKind kind
    ) {
        return new VisualStageEvaluationResult.StageCounts(
                Math.toIntExact(gold.stream().filter(item -> item.kind() == kind).count()),
                Math.toIntExact(actual.stream().filter(item -> item.kind() == kind).count()),
                Math.toIntExact(matches.stream().filter(item -> item.gold().kind() == kind).count())
        );
    }

    private static VisualStageEvaluationResult.GroundingMetrics grounding(
            List<VisualStageCorpus.Element> gold,
            List<ElementMatch> matches
    ) {
        return new VisualStageEvaluationResult.GroundingMetrics(
                gold.size(), matches.size(),
                Math.toIntExact(matches.stream().filter(item -> item.iouBps() >= IOU_MATCH_THRESHOLD_BPS).count()),
                matches.stream().mapToLong(ElementMatch::iouBps).sum()
        );
    }

    private static Map<String, String> observedPaths(VisualStageSnapshot snapshot) {
        if (snapshot.rootEntityId() == null) return Map.of();
        var entityIds = snapshot.entities().stream().map(VisualStageSnapshot.ObservedEntity::entityId)
                .collect(java.util.stream.Collectors.toSet());
        if (!entityIds.contains(snapshot.rootEntityId())) return Map.of();
        var result = new HashMap<String, String>();
        result.put(snapshot.rootEntityId(), "/");
        var pending = new ArrayDeque<>(snapshot.relationships());
        while (!pending.isEmpty()) {
            var before = pending.size();
            for (var index = 0; index < before; index++) {
                var edge = pending.removeFirst();
                var parent = result.get(edge.parentEntityId());
                if (parent == null) {
                    pending.addLast(edge);
                } else if (entityIds.contains(edge.childEntityId())
                        && !result.containsKey(edge.childEntityId())) {
                    result.put(edge.childEntityId(), childPath(parent, edge.fieldKey()));
                }
            }
            if (pending.size() == before) break;
        }
        return Collections.unmodifiableMap(result);
    }

    private static VisualStageEvaluationResult.StageCounts counts(Set<String> expected, Set<String> actual) {
        var intersection = new HashSet<>(expected);
        intersection.retainAll(actual);
        return new VisualStageEvaluationResult.StageCounts(expected.size(), actual.size(), intersection.size());
    }

    private static Set<String> correctBindings(
            VisualStageCorpus.EvaluationCase gold,
            VisualStageSnapshot actual,
            List<ElementMatch> elementMatches,
            Map<String, String> actualPaths
    ) {
        var bindingByElement = actual.bindings().stream().collect(java.util.stream.Collectors.toMap(
                VisualStageSnapshot.ObservedBinding::elementId,
                VisualStageSnapshot.ObservedBinding::entityId,
                (first, ignored) -> first
        ));
        var result = new HashSet<String>();
        for (var match : elementMatches) {
            if (match.gold().kind() != VisualStageCorpus.ElementKind.SLOT) continue;
            var actualEntityId = bindingByElement.get(match.actual().elementId());
            if (actualEntityId == null) continue;
            var actualPath = actualPaths.get(actualEntityId);
            if (Objects.equals(gold.scene().bindingEntityPaths().get(match.gold().elementId()), actualPath)) {
                result.add(match.gold().elementId());
            }
        }
        return Set.copyOf(result);
    }

    private static Set<String> expectedTreeTokens(VisualStageCorpus.EvaluationCase gold) {
        var result = new HashSet<String>();
        gold.expectedShapes().forEach((path, fields) -> {
            result.add("E:" + path);
            fields.forEach((fieldKey, shape) -> result.add("F:" + fieldIdentity(path, fieldKey) + ":" + shape));
        });
        var paths = gold.scene().entityPaths();
        gold.scene().relationships().forEach(edge -> result.add("R:" + edgeIdentity(
                paths.get(edge.parentEntityId()), paths.get(edge.childEntityId()),
                edge.fieldKey(), edge.cardinality()
        )));
        return Set.copyOf(result);
    }

    private static List<VisualStageEvaluationResult.CalibrationBin> calibration(
            VisualStageCorpus.EvaluationCase gold,
            CandidateGraph actual
    ) {
        var bins = new MutableCalibrationBin[10];
        for (var index = 0; index < bins.length; index++) bins[index] = new MutableCalibrationBin();
        var expectedPaths = Set.copyOf(gold.scene().entityPaths().values());
        actual.schemaPredictions().forEach(prediction -> addPrediction(
                bins, prediction.assessment(), expectedPaths.contains(prediction.path())
        ));
        actual.fieldPredictions().forEach(prediction -> addPrediction(
                bins, prediction.assessment(), Objects.equals(
                        expectedShape(gold.expectedShapes(), prediction.path(), prediction.fieldKey()),
                        prediction.shape()
                )
        ));
        var result = new ArrayList<VisualStageEvaluationResult.CalibrationBin>();
        for (var index = 0; index < bins.length; index++) result.add(bins[index].freeze(index));
        return List.copyOf(result);
    }

    private static void addPrediction(
            MutableCalibrationBin[] bins,
            CandidateAssessment assessment,
            boolean correct
    ) {
        if (assessment == null || assessment.confidenceBps() == null) return;
        var confidence = Math.max(0, Math.min(10_000, assessment.confidenceBps()));
        var bin = Math.min(9, Math.floorDiv(confidence, 1_000));
        var error = confidence - (correct ? 10_000 : 0);
        bins[bin].count++;
        if (correct) bins[bin].correct++;
        bins[bin].confidenceBpsSum = Math.addExact(bins[bin].confidenceBpsSum, confidence);
        bins[bin].squaredErrorBpsSum = Math.addExact(
                bins[bin].squaredErrorBpsSum,
                Math.floorDiv(Math.multiplyExact((long) error, error), 10_000L)
        );
    }

    private static String expectedShape(
            Map<String, Map<String, String>> expected,
            String path,
            String fieldKey
    ) {
        var fields = expected.get(path);
        return fields == null ? null : fields.get(fieldKey);
    }

    private static LiveEvaluationCase finalGold(VisualStageCorpus.EvaluationCase value) {
        var expected = value.expectedShapes();
        var nested = new TreeMap<String, Map<String, String>>(expected);
        var root = nested.remove("/");
        return new LiveEvaluationCase(
                value.caseId(), value.caseId(), InferenceMode.IMAGE_ONLY,
                value.partition() == VisualStageCorpus.Partition.DEV
                        ? LiveEvaluationPartition.DEV : LiveEvaluationPartition.HOLDOUT,
                expected.size(), root, nested
        );
    }

    private static int iou(VisualStageCorpus.Box expected, VisualStageSnapshot.ObservedBox actual) {
        var left = Math.max(expected.left(), actual.left());
        var top = Math.max(expected.top(), actual.top());
        var right = Math.min(expected.right(), actual.right());
        var bottom = Math.min(expected.bottom(), actual.bottom());
        if (left >= right || top >= bottom) return 0;
        var intersection = Math.multiplyExact((long) right - left, (long) bottom - top);
        var expectedArea = Math.multiplyExact((long) expected.right() - expected.left(),
                (long) expected.bottom() - expected.top());
        var actualArea = Math.multiplyExact((long) actual.right() - actual.left(),
                (long) actual.bottom() - actual.top());
        var union = Math.subtractExact(Math.addExact(expectedArea, actualArea), intersection);
        return (int) Math.floorDiv(Math.multiplyExact(intersection, 10_000L), union);
    }

    private static String edgeIdentity(
            String parentPath,
            String childPath,
            String fieldKey,
            VisualStageCorpus.Multiplicity cardinality
    ) {
        return parentPath + "#" + escape(fieldKey) + "->" + childPath + ":" + cardinality.name();
    }

    private static String fieldIdentity(String path, String fieldKey) {
        return path + "#" + escape(fieldKey);
    }

    private static String childPath(String parentPath, String fieldKey) {
        return "/".equals(parentPath) ? "/" + escape(fieldKey) : parentPath + "/" + escape(fieldKey);
    }

    private static String escape(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private record ElementMatch(
            VisualStageCorpus.Element gold,
            VisualStageSnapshot.ObservedElement actual,
            int iouBps,
            int score
    ) { }

    private static final class MutableCalibrationBin {
        private int count;
        private int correct;
        private long confidenceBpsSum;
        private long squaredErrorBpsSum;

        private VisualStageEvaluationResult.CalibrationBin freeze(int index) {
            return new VisualStageEvaluationResult.CalibrationBin(
                    index, count, correct, confidenceBpsSum, squaredErrorBpsSum
            );
        }
    }

    private record SchemaPrediction(String path, CandidateAssessment assessment) { }

    private record FieldPrediction(
            String path,
            String fieldKey,
            String shape,
            CandidateAssessment assessment
    ) { }

    private record CandidateGraph(
            Map<UUID, String> paths,
            Map<String, String> fieldShapes,
            Set<String> treeTokens,
            List<SchemaPrediction> schemaPredictions,
            List<FieldPrediction> fieldPredictions
    ) {
        static CandidateGraph from(CandidateBundle bundle) {
            if (bundle == null) return new CandidateGraph(Map.of(), Map.of(), Set.of(), List.of(), List.of());
            var byId = new LinkedHashMap<UUID, CandidateSchema>();
            bundle.schemas().forEach(schema -> byId.putIfAbsent(schema.candidateSchemaId(), schema));
            if (!byId.containsKey(bundle.rootCandidateSchemaId())) {
                return new CandidateGraph(Map.of(), Map.of(), Set.of(), List.of(), List.of());
            }
            var paths = new LinkedHashMap<UUID, String>();
            paths.put(bundle.rootCandidateSchemaId(), "/");
            var queue = new ArrayDeque<UUID>();
            queue.add(bundle.rootCandidateSchemaId());
            var fields = new TreeMap<String, String>();
            var tokens = new HashSet<String>();
            var schemaPredictions = new ArrayList<SchemaPrediction>();
            var fieldPredictions = new ArrayList<FieldPrediction>();
            while (!queue.isEmpty()) {
                var schemaId = queue.removeFirst();
                var schema = byId.get(schemaId);
                if (schema == null) continue;
                var path = paths.get(schemaId);
                tokens.add("E:" + path);
                schemaPredictions.add(new SchemaPrediction(path, schema.assessment()));
                for (var field : schema.fields()) {
                    if (field.proposedFieldKey() == null) continue;
                    var shape = shape(field.value());
                    var identity = fieldIdentity(path, field.proposedFieldKey());
                    fields.put(identity, shape);
                    tokens.add("F:" + identity + ":" + shape);
                    fieldPredictions.add(new FieldPrediction(
                            path, field.proposedFieldKey(), shape, field.assessment()
                    ));
                    var target = candidateTarget(field.value());
                    if (target == null || !byId.containsKey(target)) continue;
                    var childPath = childPath(path, field.proposedFieldKey());
                    tokens.add("R:" + path + "#" + escape(field.proposedFieldKey()) + "->"
                            + childPath + ":" + (field.value().kind() == CandidateValueKind.ARRAY
                            ? VisualStageCorpus.Multiplicity.MANY : VisualStageCorpus.Multiplicity.ONE));
                    if (!paths.containsKey(target)) {
                        paths.put(target, childPath);
                        queue.addLast(target);
                    }
                }
            }
            return new CandidateGraph(
                    Collections.unmodifiableMap(paths), Collections.unmodifiableMap(fields), Set.copyOf(tokens),
                    List.copyOf(schemaPredictions), List.copyOf(fieldPredictions)
            );
        }

        private static UUID candidateTarget(CandidateValue value) {
            var reference = value.kind() == CandidateValueKind.REFERENCE
                    ? value.reference()
                    : value.kind() == CandidateValueKind.ARRAY && value.items() != null
                    && value.items().kind() == CandidateValueKind.REFERENCE
                    ? value.items().reference() : null;
            return reference != null && reference.kind() == CandidateReferenceKind.CANDIDATE_SCHEMA
                    ? reference.candidateSchemaId() : null;
        }

        private static String shape(CandidateValue value) {
            if (value.kind() == CandidateValueKind.ARRAY) {
                return "ARRAY:" + (value.items() == null ? "UNRESOLVED" : shape(value.items()));
            }
            return value.kind().name();
        }
    }
}
