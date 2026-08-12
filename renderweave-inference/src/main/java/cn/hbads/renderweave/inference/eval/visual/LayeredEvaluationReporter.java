package cn.hbads.renderweave.inference.eval.visual;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/** Deterministic micro-aggregator; ratios are always derived from summed sufficient statistics. */
public final class LayeredEvaluationReporter {
    public LayeredEvaluationReport report(
            LayeredVisualCorpus corpus,
            LayeredEvaluationIdentity identity,
            List<LayeredEvaluationRecord> source
    ) {
        Objects.requireNonNull(corpus, "corpus");
        Objects.requireNonNull(identity, "identity");
        source = List.copyOf(Objects.requireNonNull(source, "source"));
        validateIdentity(corpus, identity);

        var byCaseId = new LinkedHashMap<String, LayeredEvaluationRecord>();
        for (var record : source) {
            var gold = corpus.require(record.caseId());
            if (!gold.caseIdentity().equals(record.caseIdentity()) || gold.partition() != record.partition()
                    || !gold.domain().equals(record.domain()) || gold.difficulty() != record.difficulty()
                    || !gold.failureSlices().equals(record.failureSlices())) {
                throw new IllegalArgumentException("LAYERED_REPORT_CASE_IDENTITY_MISMATCH");
            }
            var runtime = record.runtime();
            if (runtime.providerAttempts() != 0 || runtime.providerReservations() != 0
                    || runtime.externalProviderCostMicrosCny() != 0
                    || runtime.estimatedCostMicrosCny() != 0 || runtime.settledCostMicrosCny() != 0) {
                throw new IllegalArgumentException("R1_EXTERNAL_PROVIDER_USAGE_FORBIDDEN");
            }
            if (byCaseId.putIfAbsent(record.caseId(), record) != null) {
                throw new IllegalArgumentException("DUPLICATE_LAYERED_EVALUATION_RECORD");
            }
        }
        var missing = corpus.cases().stream().map(LayeredVisualCorpus.Case::caseId)
                .filter(caseId -> !byCaseId.containsKey(caseId)).toList();
        if (!missing.isEmpty() || byCaseId.size() != corpus.cases().size()) {
            throw new IllegalArgumentException("LAYERED_REPORT_CASE_SET_INCOMPLETE");
        }
        var records = corpus.cases().stream().map(item -> byCaseId.get(item.caseId())).toList();
        var codec = new LayeredEvaluationJsonCodec();
        var entries = records.stream().map(record -> new LayeredEvaluationReport.RecordEntry(
                codec.recordIdentity(record), record)).toList();
        var recordSetIdentity = "renderweave-layered-record-set/1.0:" + sha256(
                entries.stream().map(LayeredEvaluationReport.RecordEntry::recordIdentity).toList());

        var partitions = new LinkedHashMap<String, LayeredEvaluationReport.Aggregate>();
        for (var partition : LayeredEvaluationRecord.Partition.values()) {
            partitions.put(partition.name(), aggregate(records.stream()
                    .filter(item -> item.partition() == partition).toList()));
        }
        var domains = new LinkedHashMap<String, LayeredEvaluationReport.Aggregate>();
        for (var domain : new TreeSet<>(corpus.cases().stream().map(LayeredVisualCorpus.Case::domain).toList())) {
            domains.put(domain, aggregate(records.stream().filter(item -> item.domain().equals(domain)).toList()));
        }
        var difficulties = new LinkedHashMap<String, LayeredEvaluationReport.Aggregate>();
        for (var difficulty : LayeredEvaluationRecord.Difficulty.values()) {
            difficulties.put(difficulty.name(), aggregate(records.stream()
                    .filter(item -> item.difficulty() == difficulty).toList()));
        }
        var failureSlices = new LinkedHashMap<String, LayeredEvaluationReport.Aggregate>();
        for (var slice : LayeredEvaluationRecord.FailureSlice.values()) {
            failureSlices.put(slice.name(), aggregate(records.stream()
                    .filter(item -> item.failureSlices().contains(slice)).toList()));
        }
        return new LayeredEvaluationReport(
                LayeredEvaluationReport.VERSION, identity.identity(), identity.components(),
                corpus.corpusIdentity(), corpus.annotationSetIdentity(), recordSetIdentity,
                corpus.cases().size(), records.size(), true, List.of(), entries, aggregate(records),
                partitions, domains, difficulties, failureSlices);
    }

    static LayeredEvaluationReport.Aggregate aggregate(List<LayeredEvaluationRecord> source) {
        source = List.copyOf(source);
        var ocr = ocr(source);
        var layout = layout(source);
        var order = order(source);
        var repeat = repeat(source);
        var semantic = semantic(source);
        var candidate = candidate(source);
        var calibration = calibration(source);
        return new LayeredEvaluationReport.Aggregate(
                source.size(), ocr, layout, order, repeat, semantic, candidate, calibration, runtime(source),
                LayeredEvaluationReport.metricSummary(
                        ocr, layout, order, repeat, semantic, candidate, calibration));
    }

    private static LayeredEvaluationRecord.OcrStats ocr(List<LayeredEvaluationRecord> source) {
        return new LayeredEvaluationRecord.OcrStats(
                sum(source, item -> item.ocr().cases()),
                sum(source, item -> item.ocr().referenceCharacters()),
                sum(source, item -> item.ocr().predictedCharacters()),
                sum(source, item -> item.ocr().characterSubstitutions()),
                sum(source, item -> item.ocr().characterInsertions()),
                sum(source, item -> item.ocr().characterDeletions()),
                sum(source, item -> item.ocr().referenceWords()),
                sum(source, item -> item.ocr().predictedWords()),
                sum(source, item -> item.ocr().wordSubstitutions()),
                sum(source, item -> item.ocr().wordInsertions()),
                sum(source, item -> item.ocr().wordDeletions()),
                sum(source, item -> item.ocr().emptyReferenceCases()),
                sum(source, item -> item.ocr().hallucinationCases()),
                sum(source, item -> item.ocr().completeMissCases()));
    }

    private static LayeredEvaluationRecord.LayoutStats layout(List<LayeredEvaluationRecord> source) {
        var byKind = new EnumMap<LayeredVisualAnnotation.RegionKind, LayeredEvaluationRecord.DetectionStats>(
                LayeredVisualAnnotation.RegionKind.class);
        for (var kind : LayeredVisualAnnotation.RegionKind.values()) {
            byKind.put(kind, detection(source.stream().map(item -> item.layout().byKind().get(kind)).toList()));
        }
        return new LayeredEvaluationRecord.LayoutStats(
                byKind, binary(source.stream().map(item -> item.layout().evidence()).toList()),
                sum(source, item -> item.layout().falseEvidence()));
    }

    private static LayeredEvaluationRecord.DetectionStats detection(
            List<LayeredEvaluationRecord.DetectionStats> source
    ) {
        var thresholds = new ArrayList<Long>();
        for (var index = 0; index < 10; index++) {
            var current = index;
            thresholds.add(source.stream().mapToLong(item -> item.matchedByIouThreshold().get(current)).sum());
        }
        return new LayeredEvaluationRecord.DetectionStats(
                source.stream().mapToLong(LayeredEvaluationRecord.DetectionStats::expected).sum(),
                source.stream().mapToLong(LayeredEvaluationRecord.DetectionStats::predicted).sum(),
                thresholds,
                source.stream().mapToLong(LayeredEvaluationRecord.DetectionStats::semanticallyMatched).sum(),
                source.stream().mapToLong(LayeredEvaluationRecord.DetectionStats::matchedIouBpsSum).sum(),
                source.stream().mapToLong(LayeredEvaluationRecord.DetectionStats::ap5095BpsSum).sum(),
                source.stream().mapToLong(LayeredEvaluationRecord.DetectionStats::evaluatedCases).sum());
    }

    private static LayeredEvaluationRecord.OrderStats order(List<LayeredEvaluationRecord> source) {
        return new LayeredEvaluationRecord.OrderStats(
                binary(source.stream().map(LayeredEvaluationRecord::order)
                        .map(LayeredEvaluationRecord.OrderStats::precedenceEdges).toList()),
                sum(source, item -> item.order().cycleCases()),
                sum(source, item -> item.order().evaluatedCases()));
    }

    private static LayeredEvaluationRecord.RepeatStats repeat(List<LayeredEvaluationRecord> source) {
        return new LayeredEvaluationRecord.RepeatStats(
                binary(source.stream().map(item -> item.repeat().groups()).toList()),
                binary(source.stream().map(item -> item.repeat().items()).toList()),
                sum(source, item -> item.repeat().itemCountAbsoluteError()),
                binary(source.stream().map(item -> item.repeat().memberships()).toList()));
    }

    private static LayeredEvaluationRecord.SemanticStats semantic(List<LayeredEvaluationRecord> source) {
        return new LayeredEvaluationRecord.SemanticStats(
                binary(source.stream().map(item -> item.semantic().slots()).toList()),
                binary(source.stream().map(item -> item.semantic().groups()).toList()),
                binary(source.stream().map(item -> item.semantic().entities()).toList()),
                binary(source.stream().map(item -> item.semantic().relationships()).toList()),
                binary(source.stream().map(item -> item.semantic().cardinalities()).toList()),
                binary(source.stream().map(item -> item.semantic().bindings()).toList()),
                binary(source.stream().map(item -> item.semantic().ownerContainment()).toList()),
                new LayeredEvaluationRecord.SurvivalStats(
                        sum(source, item -> item.semantic().survival().expectedSlots()),
                        sum(source, item -> item.semantic().survival().observedSlots()),
                        sum(source, item -> item.semantic().survival().boundSlots()),
                        sum(source, item -> item.semantic().survival().candidateSlots())),
                sum(source, item -> item.semantic().repairAttempts()),
                sum(source, item -> item.semantic().repairSuccesses()));
    }

    private static LayeredEvaluationRecord.CandidateStats candidate(List<LayeredEvaluationRecord> source) {
        return new LayeredEvaluationRecord.CandidateStats(
                sum(source, item -> item.candidate().evaluatedCases()),
                sum(source, item -> item.candidate().contractValidCases()),
                binary(source.stream().map(item -> item.candidate().entities()).toList()),
                binary(source.stream().map(item -> item.candidate().fields()).toList()),
                binary(source.stream().map(item -> item.candidate().relationships()).toList()),
                binary(source.stream().map(item -> item.candidate().supportedTypes()).toList()),
                binary(source.stream().map(item -> item.candidate().evidence()).toList()),
                sum(source, item -> item.candidate().dagValidCases()),
                sum(source, item -> item.candidate().criticalHallucinations()),
                sum(source, item -> item.candidate().blockers()),
                sum(source, item -> item.candidate().topologyExpectedCases()),
                sum(source, item -> item.candidate().topologyPreservedCases()));
    }

    private static LayeredEvaluationRecord.CalibrationStats calibration(List<LayeredEvaluationRecord> source) {
        var bins = new ArrayList<LayeredEvaluationRecord.CalibrationBin>();
        for (var index = 0; index < 10; index++) {
            var current = index;
            bins.add(new LayeredEvaluationRecord.CalibrationBin(
                    index,
                    sum(source, item -> item.calibration().bins().get(current).count()),
                    sum(source, item -> item.calibration().bins().get(current).correct()),
                    sum(source, item -> item.calibration().bins().get(current).confidenceBpsSum()),
                    sum(source, item -> item.calibration().bins().get(current).squaredErrorBpsSum())));
        }
        return new LayeredEvaluationRecord.CalibrationStats(
                bins, binary(source.stream().map(item -> item.calibration().unresolved()).toList()),
                sum(source, item -> item.calibration().reviewRequiredReachedCases()),
                sum(source, item -> item.calibration().successfulCases()),
                sum(source, item -> item.calibration().evaluatedCases()));
    }

    private static LayeredEvaluationReport.RuntimeAggregate runtime(List<LayeredEvaluationRecord> source) {
        var latency = new LinkedHashMap<String, LayeredEvaluationReport.LatencyPercentiles>();
        for (var stage : LayeredEvaluationRecord.Stage.values()) {
            var values = source.stream().map(item -> item.runtime().latencyMicros().get(stage))
                    .filter(Objects::nonNull).sorted().toList();
            latency.put(stage.name(), new LayeredEvaluationReport.LatencyPercentiles(
                    values.size(), percentile(values, 50), percentile(values, 95)));
        }
        var recovery = new LinkedHashMap<String, Long>();
        for (var code : LayeredEvaluationRecord.RecoveryCode.values()) {
            recovery.put(code.name(), source.stream().filter(item -> item.runtime().recoveryCode() == code).count());
        }
        return new LayeredEvaluationReport.RuntimeAggregate(
                sum(source, item -> item.runtime().scriptedCalls()),
                sum(source, item -> item.runtime().inputTokens()),
                sum(source, item -> item.runtime().outputTokens()),
                sum(source, item -> item.runtime().estimatedCostMicrosCny()),
                sum(source, item -> item.runtime().settledCostMicrosCny()), latency, recovery,
                sum(source, item -> item.runtime().recoveryCount()),
                sum(source, item -> item.runtime().acceptedStageReplayCount()),
                sum(source, item -> item.runtime().providerAttempts()),
                sum(source, item -> item.runtime().providerReservations()),
                sum(source, item -> item.runtime().externalProviderCostMicrosCny()));
    }

    private static LayeredEvaluationRecord.BinaryCounts binary(
            List<LayeredEvaluationRecord.BinaryCounts> source
    ) {
        return new LayeredEvaluationRecord.BinaryCounts(
                source.stream().mapToLong(LayeredEvaluationRecord.BinaryCounts::expected).sum(),
                source.stream().mapToLong(LayeredEvaluationRecord.BinaryCounts::predicted).sum(),
                source.stream().mapToLong(LayeredEvaluationRecord.BinaryCounts::matched).sum());
    }

    private static long percentile(List<Long> sorted, int percentile) {
        if (sorted.isEmpty()) return 0;
        var index = Math.max(0, Math.toIntExact(Math.floorDiv(
                Math.addExact(Math.multiplyExact((long) sorted.size(), percentile), 99), 100)) - 1);
        return sorted.get(index);
    }

    private static long sum(
            List<LayeredEvaluationRecord> source,
            java.util.function.ToLongFunction<LayeredEvaluationRecord> getter
    ) {
        long result = 0;
        for (var item : source) result = Math.addExact(result, getter.applyAsLong(item));
        return result;
    }

    private static void validateIdentity(LayeredVisualCorpus corpus, LayeredEvaluationIdentity identity) {
        var values = identity.components();
        if (!corpus.corpusIdentity().equals(values.get("inputSetIdentity"))
                || !LayeredVisualAnnotation.VERSION.equals(values.get("annotationVersion"))
                || !corpus.annotationSetIdentity().equals(values.get("annotationSetIdentity"))
                || !corpus.renderContractIdentity().equals(values.get("normalizationRenderIdentity"))
                || !values.get("evaluatorIdentity").startsWith(LayeredVisualEvaluator.VERSION + ":")
                || !"budget-zero-provider/1.0".equals(values.get("budgetIdentity"))) {
            throw new IllegalArgumentException("LAYERED_REPORT_EVALUATION_IDENTITY_MISMATCH");
        }
    }

    private static String sha256(List<String> values) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var value : values) {
                var bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) ':');
                digest.update(bytes);
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA_256_UNAVAILABLE", impossible);
        }
    }
}
