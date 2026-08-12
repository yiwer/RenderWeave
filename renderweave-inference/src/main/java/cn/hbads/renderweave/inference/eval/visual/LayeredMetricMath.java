package cn.hbads.renderweave.inference.eval.visual;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Frozen integer-only metric primitives shared by the Java R1 scorer contract, not its Python implementation. */
public final class LayeredMetricMath {
    public static final String VERSION = "renderweave-layered-metric-math/1.0";

    private LayeredMetricMath() { }

    public static EditCounts characters(String reference, String prediction) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(prediction, "prediction");
        return edit(reference.codePoints().boxed().toList(), prediction.codePoints().boxed().toList());
    }

    public static EditCounts words(String reference, String prediction) {
        return edit(wordsOf(reference), wordsOf(prediction));
    }

    private static List<String> wordsOf(String value) {
        Objects.requireNonNull(value, "value");
        var normalized = value.strip();
        return normalized.isEmpty() ? List.of() : List.of(normalized.split("\\s+"));
    }

    private static <T> EditCounts edit(List<T> reference, List<T> prediction) {
        var rows = reference.size() + 1;
        var columns = prediction.size() + 1;
        var distance = new int[rows][columns];
        for (var row = 0; row < rows; row++) distance[row][0] = row;
        for (var column = 0; column < columns; column++) distance[0][column] = column;
        for (var row = 1; row < rows; row++) {
            for (var column = 1; column < columns; column++) {
                var substitution = distance[row - 1][column - 1]
                        + (Objects.equals(reference.get(row - 1), prediction.get(column - 1)) ? 0 : 1);
                distance[row][column] = Math.min(substitution,
                        Math.min(distance[row][column - 1] + 1, distance[row - 1][column] + 1));
            }
        }
        var substitutions = 0;
        var insertions = 0;
        var deletions = 0;
        var row = reference.size();
        var column = prediction.size();
        while (row > 0 || column > 0) {
            if (row > 0 && column > 0
                    && Objects.equals(reference.get(row - 1), prediction.get(column - 1))
                    && distance[row][column] == distance[row - 1][column - 1]) {
                row--;
                column--;
            } else if (row > 0 && column > 0
                    && distance[row][column] == distance[row - 1][column - 1] + 1) {
                substitutions++;
                row--;
                column--;
            } else if (column > 0 && distance[row][column] == distance[row][column - 1] + 1) {
                insertions++;
                column--;
            } else {
                deletions++;
                row--;
            }
        }
        return new EditCounts(reference.size(), prediction.size(), substitutions, insertions, deletions);
    }

    public static int iouBps(Box expected, Box predicted) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(predicted, "predicted");
        var intersectionWidth = Math.max(0, Math.min(expected.right, predicted.right)
                - Math.max(expected.left, predicted.left));
        var intersectionHeight = Math.max(0, Math.min(expected.bottom, predicted.bottom)
                - Math.max(expected.top, predicted.top));
        var intersection = Math.multiplyExact((long) intersectionWidth, intersectionHeight);
        var union = Math.addExact(expected.area(), predicted.area()) - intersection;
        return union == 0 ? 0 : ratio(intersection, union);
    }

    public static DetectionScore detection(List<Detection> gold, List<Detection> predictions) {
        gold = List.copyOf(Objects.requireNonNull(gold, "gold"));
        predictions = List.copyOf(Objects.requireNonNull(predictions, "predictions"));
        requireDistinct(gold.stream().map(Detection::id).toList(), "DUPLICATE_GOLD_DETECTION");
        requireDistinct(predictions.stream().map(Detection::id).toList(), "DUPLICATE_PREDICTED_DETECTION");
        if (gold.isEmpty() && predictions.isEmpty()) {
            return new DetectionScore(0, 0, java.util.Collections.nCopies(10, 0), 0, 0, 10_000);
        }
        var sorted = predictions.stream().sorted(Comparator.comparingInt(Detection::confidenceBps).reversed()
                .thenComparing(Detection::id)).toList();
        var thresholdMatches = new ArrayList<Integer>();
        long apSum = 0;
        long matchedIouSum = 0;
        var matchedAt50 = 0;
        for (var thresholdIndex = 0; thresholdIndex < 10; thresholdIndex++) {
            var threshold = 5_000 + thresholdIndex * 500;
            var matching = match(gold, sorted, threshold);
            thresholdMatches.add(matching.matched());
            apSum += interpolatedApBps(gold.size(), matching.truePositiveByPrediction());
            if (thresholdIndex == 0) {
                matchedAt50 = matching.matched();
                matchedIouSum = matching.matchedIouBpsSum();
            }
        }
        return new DetectionScore(gold.size(), predictions.size(), thresholdMatches, matchedAt50,
                matchedIouSum, (int) Math.floorDiv(apSum, 10));
    }

    private static Matching match(List<Detection> gold, List<Detection> predictions, int thresholdBps) {
        var used = new boolean[gold.size()];
        var truePositive = new ArrayList<Boolean>();
        var matched = 0;
        long iouSum = 0;
        for (var predicted : predictions) {
            var bestIndex = -1;
            var bestIou = -1;
            for (var index = 0; index < gold.size(); index++) {
                if (used[index] || !gold.get(index).kind().equals(predicted.kind())) continue;
                var iou = iouBps(gold.get(index).box(), predicted.box());
                if (iou >= thresholdBps && (iou > bestIou
                        || iou == bestIou && gold.get(index).id().compareTo(gold.get(bestIndex).id()) < 0)) {
                    bestIndex = index;
                    bestIou = iou;
                }
            }
            if (bestIndex >= 0) {
                used[bestIndex] = true;
                matched++;
                iouSum += bestIou;
                truePositive.add(true);
            } else {
                truePositive.add(false);
            }
        }
        return new Matching(matched, iouSum, truePositive);
    }

    private static int interpolatedApBps(int expected, List<Boolean> truePositive) {
        if (expected == 0) return truePositive.isEmpty() ? 10_000 : 0;
        if (truePositive.isEmpty()) return 0;
        var cumulativeTruePositive = new int[truePositive.size()];
        var total = 0;
        for (var index = 0; index < truePositive.size(); index++) {
            if (truePositive.get(index)) total++;
            cumulativeTruePositive[index] = total;
        }
        long sum = 0;
        for (var recallPercent = 0; recallPercent <= 100; recallPercent++) {
            var bestPrecision = 0;
            for (var index = 0; index < cumulativeTruePositive.length; index++) {
                if ((long) cumulativeTruePositive[index] * 100 >= (long) recallPercent * expected) {
                    bestPrecision = Math.max(bestPrecision, ratio(cumulativeTruePositive[index], index + 1));
                }
            }
            sum += bestPrecision;
        }
        return (int) Math.floorDiv(sum, 101);
    }

    public static SetCounts setCounts(List<String> expected, List<String> predicted) {
        expected = List.copyOf(Objects.requireNonNull(expected, "expected"));
        predicted = List.copyOf(Objects.requireNonNull(predicted, "predicted"));
        requireDistinct(expected, "DUPLICATE_EXPECTED_MEMBER");
        requireDistinct(predicted, "DUPLICATE_PREDICTED_MEMBER");
        var expectedSet = Set.copyOf(expected);
        return new SetCounts(expected.size(), predicted.size(),
                predicted.stream().filter(expectedSet::contains).count());
    }

    public static boolean hasCycle(List<String> edges) {
        edges = List.copyOf(Objects.requireNonNull(edges, "edges"));
        requireDistinct(edges, "DUPLICATE_GRAPH_EDGE");
        var graph = new HashMap<String, List<String>>();
        var indegree = new HashMap<String, Integer>();
        for (var edge : edges) {
            var split = edge.split(">", -1);
            if (split.length != 2 || split[0].isBlank() || split[1].isBlank() || split[0].equals(split[1])) {
                throw new IllegalArgumentException("GRAPH_EDGE_INVALID");
            }
            indegree.putIfAbsent(split[0], 0);
            indegree.merge(split[1], 1, Integer::sum);
            graph.computeIfAbsent(split[0], ignored -> new ArrayList<>()).add(split[1]);
        }
        var queue = new ArrayDeque<String>();
        indegree.forEach((node, degree) -> { if (degree == 0) queue.add(node); });
        var visited = 0;
        while (!queue.isEmpty()) {
            var node = queue.removeFirst();
            visited++;
            for (var child : graph.getOrDefault(node, List.of())) {
                if (indegree.merge(child, -1, Integer::sum) == 0) queue.addLast(child);
            }
        }
        return visited != indegree.size();
    }

    public static RepeatScore repeat(
            List<RepeatMembership> expected,
            List<RepeatMembership> predicted,
            int expectedItemCount,
            int predictedItemCount
    ) {
        if (expectedItemCount < 0 || predictedItemCount < 0) throw new IllegalArgumentException("ITEM_COUNT_INVALID");
        var expectedKeys = expected.stream().map(RepeatMembership::key).toList();
        var predictedKeys = predicted.stream().map(RepeatMembership::key).toList();
        return new RepeatScore(Math.abs(expectedItemCount - predictedItemCount),
                setCounts(expectedKeys, predictedKeys));
    }

    public static int treeEditDistance(List<String> expectedEdges, List<String> predictedEdges) {
        requireDistinct(expectedEdges, "DUPLICATE_EXPECTED_TREE_EDGE");
        requireDistinct(predictedEdges, "DUPLICATE_PREDICTED_TREE_EDGE");
        var expected = Set.copyOf(expectedEdges);
        var predicted = Set.copyOf(predictedEdges);
        return Math.toIntExact(expected.stream().filter(edge -> !predicted.contains(edge)).count()
                + predicted.stream().filter(edge -> !expected.contains(edge)).count());
    }

    public static int expectedCalibrationErrorBps(List<CalibrationBin> bins) {
        bins = List.copyOf(Objects.requireNonNull(bins, "bins"));
        var total = bins.stream().mapToLong(CalibrationBin::count).sum();
        if (total == 0) return 0;
        long weighted = 0;
        for (var bin : bins) {
            if (bin.count() == 0) continue;
            var confidence = (int) Math.floorDiv(bin.confidenceBpsSum(), bin.count());
            var accuracy = ratio(bin.correct(), bin.count());
            weighted = Math.addExact(weighted,
                    Math.multiplyExact((long) bin.count(), Math.abs(confidence - accuracy)));
        }
        return (int) Math.floorDiv(weighted, total);
    }

    public static int brierScoreBps(List<CalibrationBin> bins) {
        bins = List.copyOf(Objects.requireNonNull(bins, "bins"));
        var total = bins.stream().mapToLong(CalibrationBin::count).sum();
        return total == 0 ? 0 : (int) Math.floorDiv(
                bins.stream().mapToLong(CalibrationBin::squaredErrorBpsSum).sum(), total);
    }

    private static int ratio(long numerator, long denominator) {
        if (denominator == 0) return 10_000;
        return (int) Math.floorDiv(Math.multiplyExact(numerator, 10_000), denominator);
    }

    private static void requireDistinct(List<String> values, String code) {
        if (new HashSet<>(values).size() != values.size()) throw new IllegalArgumentException(code);
    }

    public record EditCounts(int referenceUnits, int predictedUnits, int substitutions, int insertions, int deletions) {
        public EditCounts {
            if (referenceUnits < 0 || predictedUnits < 0 || substitutions < 0 || insertions < 0
                    || deletions < 0 || substitutions + deletions > referenceUnits
                    || substitutions + insertions > predictedUnits) {
                throw new IllegalArgumentException("EDIT_COUNTS_INVALID");
            }
        }

        public int errorRateBps() {
            var errors = substitutions + insertions + deletions;
            return referenceUnits == 0 ? errors == 0 ? 0 : 10_000 : ratio(errors, referenceUnits);
        }
    }

    public record Box(int left, int top, int right, int bottom) {
        public Box {
            if (left < 0 || top < 0 || left >= right || top >= bottom) {
                throw new IllegalArgumentException("METRIC_BOX_INVALID");
            }
        }

        long area() { return Math.multiplyExact((long) right - left, (long) bottom - top); }
    }

    public record Detection(String id, String kind, Box box, int confidenceBps) {
        public Detection {
            if (id == null || id.isBlank() || kind == null || kind.isBlank() || confidenceBps < 0
                    || confidenceBps > 10_000) throw new IllegalArgumentException("DETECTION_INVALID");
            Objects.requireNonNull(box, "box");
        }
    }

    public record DetectionScore(
            int expected,
            int predicted,
            List<Integer> matchedByIouThreshold,
            int matchedAtIou50,
            long matchedIouBpsSum,
            int ap5095Bps
    ) {
        public DetectionScore {
            matchedByIouThreshold = List.copyOf(matchedByIouThreshold);
            if (expected < 0 || predicted < 0 || matchedByIouThreshold.size() != 10
                    || matchedAtIou50 < 0 || matchedAtIou50 > expected || matchedAtIou50 > predicted
                    || matchedIouBpsSum < 0 || matchedIouBpsSum > (long) matchedAtIou50 * 10_000
                    || ap5095Bps < 0 || ap5095Bps > 10_000) {
                throw new IllegalArgumentException("DETECTION_SCORE_INVALID");
            }
        }
    }

    public record SetCounts(long expected, long predicted, long matched) {
        public SetCounts {
            if (expected < 0 || predicted < 0 || matched < 0 || matched > expected || matched > predicted) {
                throw new IllegalArgumentException("SET_COUNTS_INVALID");
            }
        }
        public int precisionBps() { return predicted == 0 ? expected == 0 ? 10_000 : 0 : ratio(matched, predicted); }
        public int recallBps() { return expected == 0 ? 10_000 : ratio(matched, expected); }
        public int f1Bps() {
            return expected + predicted == 0 ? 10_000 : ratio(2 * matched, expected + predicted);
        }
    }

    public record RepeatMembership(String groupId, String itemId, String memberId) {
        public RepeatMembership {
            if (groupId == null || groupId.isBlank() || itemId == null || itemId.isBlank()
                    || memberId == null || memberId.isBlank()) throw new IllegalArgumentException("MEMBERSHIP_INVALID");
        }
        String key() { return groupId + ">" + itemId + ">" + memberId; }
    }

    public record RepeatScore(int itemCountAbsoluteError, SetCounts membership) {
        public RepeatScore {
            if (itemCountAbsoluteError < 0) throw new IllegalArgumentException("REPEAT_SCORE_INVALID");
            Objects.requireNonNull(membership, "membership");
        }
    }

    public record CalibrationBin(int count, int correct, long confidenceBpsSum, long squaredErrorBpsSum) {
        public CalibrationBin {
            if (count < 0 || correct < 0 || correct > count || confidenceBpsSum < 0
                    || confidenceBpsSum > (long) count * 10_000 || squaredErrorBpsSum < 0
                    || squaredErrorBpsSum > (long) count * 10_000) {
                throw new IllegalArgumentException("CALIBRATION_BIN_INVALID");
            }
        }
    }

    private record Matching(int matched, long matchedIouBpsSum, List<Boolean> truePositiveByPrediction) { }
}
