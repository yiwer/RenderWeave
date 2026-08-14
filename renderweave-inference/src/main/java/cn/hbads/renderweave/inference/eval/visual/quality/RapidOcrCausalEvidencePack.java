package cn.hbads.renderweave.inference.eval.visual.quality;

import cn.hbads.renderweave.inference.eval.visual.LayeredVisualCorpus;
import cn.hbads.renderweave.inference.eval.visual.RapidOcrShadowEvaluationIdentity;
import cn.hbads.renderweave.inference.eval.visual.RapidOcrShadowReport;
import cn.hbads.renderweave.inference.vision.RapidOcrBaselineContract;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Stable, payload-safe causal projection of the two-run RapidOCR shadow report. */
public record RapidOcrCausalEvidencePack(
        String contractVersion,
        String evaluationIdentity,
        String protocolIdentity,
        String corpusIdentity,
        String annotationSetIdentity,
        String capabilityIdentity,
        String acquisitionPolicyIdentity,
        Accounting accounting,
        Map<String, ScopedMetrics> metrics,
        RapidOcrShadowReport.EvidenceFacts evidenceFacts,
        Map<AttributionLayer, Attribution> attributions,
        ExternalProviderUsage externalProviderUsage
) {
    public static final String VERSION = "renderweave-rapidocr-causal-evidence-pack/1.0";

    public RapidOcrCausalEvidencePack {
        if (!VERSION.equals(contractVersion)) throw invalid("RAPIDOCR_CAUSAL_VERSION_INVALID");
        requireIdentity(evaluationIdentity, "RAPIDOCR_CAUSAL_EVALUATION_IDENTITY_INVALID");
        requireIdentity(protocolIdentity, "RAPIDOCR_CAUSAL_PROTOCOL_IDENTITY_INVALID");
        requireIdentity(corpusIdentity, "RAPIDOCR_CAUSAL_CORPUS_IDENTITY_INVALID");
        requireIdentity(annotationSetIdentity, "RAPIDOCR_CAUSAL_ANNOTATION_IDENTITY_INVALID");
        if (capabilityIdentity == null || capabilityIdentity.isBlank()) {
            throw invalid("RAPIDOCR_CAUSAL_CAPABILITY_IDENTITY_INVALID");
        }
        requireIdentity(acquisitionPolicyIdentity, "RAPIDOCR_CAUSAL_POLICY_IDENTITY_INVALID");
        Objects.requireNonNull(accounting, "accounting");
        metrics = canonicalMetrics(metrics);
        Objects.requireNonNull(evidenceFacts, "evidenceFacts");
        attributions = canonicalAttributions(attributions);
        externalProviderUsage = Objects.requireNonNull(externalProviderUsage, "externalProviderUsage");
        if (!externalProviderUsage.zeroUsage()) throw invalid("RAPIDOCR_CAUSAL_PROVIDER_USAGE_NONZERO");
    }

    public static RapidOcrCausalEvidencePack from(
            RapidOcrShadowReport report,
            boolean independentReplayPassed
    ) {
        Objects.requireNonNull(report, "report");
        var corpus = new LayeredVisualCorpus();
        var policy = RapidOcrBaselineContract.policy(RapidOcrBaselineContract.DEFAULT_TIMEOUT_MILLIS);
        var expectedEvaluation = RapidOcrShadowEvaluationIdentity.exact(corpus, policy).identity();
        if (!expectedEvaluation.equals(report.evaluationIdentity())
                || !corpus.corpusIdentity().equals(report.corpusIdentity())
                || !corpus.annotationSetIdentity().equals(report.annotationSetIdentity())) {
            throw invalid("RAPIDOCR_CAUSAL_SOURCE_IDENTITY_DRIFT");
        }
        if (!report.determinism().deterministic()
                || report.determinism().metricsEquivalentCases() != 60
                || report.determinism().observationEquivalentCases() != 60) {
            throw invalid("RAPIDOCR_CAUSAL_SOURCE_NONDETERMINISTIC");
        }
        var evidenceFacts = report.evidenceFacts();
        if (evidenceFacts.stableOcrOrLayoutGapCases() <= 0
                || evidenceFacts.stableOcrOrLayoutGapCases()
                != evidenceFacts.stableOcrOrLayoutGapDevCases()
                + evidenceFacts.stableOcrOrLayoutGapHoldoutCases()) {
            throw invalid("RAPIDOCR_CAUSAL_STABLE_GAP_MISSING");
        }
        var first = stableMetrics(report.runs().getFirst());
        var second = stableMetrics(report.runs().getLast());
        if (!first.equals(second)) throw invalid("RAPIDOCR_CAUSAL_METRIC_DRIFT");

        var components = report.evaluationComponents();
        var evidenceReference = report.evaluationIdentity();
        var protocolIdentity = OfflineQualityEvaluationProtocol.load().identity();
        var attributions = new EnumMap<AttributionLayer, Attribution>(AttributionLayer.class);
        attributions.put(AttributionLayer.OBSERVATION, attribution(
                AttributionResult.OBSERVED_CONTRIBUTOR,
                "OBSERVATION_TWO_RUN_STABLE_GAP_CONFIRMED", evidenceReference));
        attributions.put(AttributionLayer.LAYOUT, attribution(
                AttributionResult.OBSERVED_CONTRIBUTOR,
                "LAYOUT_RECALL_GAP_CONFIRMED", evidenceReference));
        attributions.put(AttributionLayer.ORDER_REPEAT, attribution(
                AttributionResult.MISSING,
                "ORDER_REPEAT_CAUSAL_PROBE_REQUIRED", protocolIdentity));
        attributions.put(AttributionLayer.SHAPE_CODEC, attribution(
                AttributionResult.EXCLUDED_BY_CURRENT_EVIDENCE,
                "SHAPE_CODEC_NOT_PRIMARY_BOTTLENECK",
                "sha256:" + FrozenQualityEvidencePack.N7_04_AUDIT_SHA256));
        attributions.put(AttributionLayer.SEMANTIC, attribution(
                AttributionResult.OBSERVED_CONTRIBUTOR,
                "SEMANTIC_TOPOLOGY_GROUNDING_FAILURE_CONFIRMED",
                "sha256:" + FrozenQualityEvidencePack.N7_04_AUDIT_SHA256));
        attributions.put(AttributionLayer.STATIC_VIEW, attribution(
                AttributionResult.MISSING,
                "ORACLE_STATIC_VIEW_DIFFERENTIAL_REQUIRED", protocolIdentity));
        attributions.put(AttributionLayer.MATERIALIZER, attribution(
                AttributionResult.MISSING,
                "MATERIALIZER_CAUSAL_SEPARATION_REQUIRED", protocolIdentity));
        attributions.put(AttributionLayer.SCORER, attribution(
                independentReplayPassed
                        ? AttributionResult.EXCLUDED_BY_CURRENT_EVIDENCE
                        : AttributionResult.MISSING,
                independentReplayPassed
                        ? "SCORER_INDEPENDENT_RECOMPUTE_PASSED"
                        : "SCORER_INDEPENDENT_RECOMPUTE_MISSING",
                evidenceReference));

        return new RapidOcrCausalEvidencePack(
                VERSION,
                report.evaluationIdentity(),
                protocolIdentity,
                report.corpusIdentity(),
                report.annotationSetIdentity(),
                requiredComponent(components, "capabilityIdentity"),
                requiredComponent(components, "acquisitionPolicyIdentity"),
                new Accounting(2, 60, 45, 15, 120,
                        report.determinism().metricsEquivalentCases(),
                        report.determinism().observationEquivalentCases()),
                first,
                evidenceFacts,
                attributions,
                new ExternalProviderUsage(
                        report.externalProvider().attempts(),
                        report.externalProvider().reservations(),
                        report.externalProvider().costMicrosCny()));
    }

    private static Map<String, ScopedMetrics> stableMetrics(RapidOcrShadowReport.RunReport run) {
        var result = new TreeMap<String, ScopedMetrics>();
        put(result, "GLOBAL", run.global());
        add(result, "PARTITION/", run.partitions());
        add(result, "DOMAIN/", run.domains());
        add(result, "DIFFICULTY/", run.difficulties());
        add(result, "DIAGNOSTIC/", run.diagnosticSlices());
        add(result, "FAILURE/", run.failureSlices());
        return Map.copyOf(result);
    }

    private static void add(
            Map<String, ScopedMetrics> target,
            String prefix,
            Map<String, RapidOcrShadowReport.Aggregate> source
    ) {
        source.forEach((key, value) -> put(target, prefix + key, value));
    }

    private static void put(
            Map<String, ScopedMetrics> target,
            String key,
            RapidOcrShadowReport.Aggregate value
    ) {
        if (target.putIfAbsent(key, new ScopedMetrics(value.caseCount(), value.metricsBps())) != null) {
            throw invalid("RAPIDOCR_CAUSAL_METRIC_SCOPE_DUPLICATE");
        }
    }

    private static Attribution attribution(
            AttributionResult result,
            String reasonCode,
            String evidenceReference
    ) {
        return new Attribution(result, reasonCode, evidenceReference);
    }

    private static String requiredComponent(Map<String, String> components, String key) {
        var value = components.get(key);
        if (value == null || value.isBlank()) throw invalid("RAPIDOCR_CAUSAL_COMPONENT_MISSING");
        return value;
    }

    private static Map<String, ScopedMetrics> canonicalMetrics(Map<String, ScopedMetrics> source) {
        var result = new TreeMap<>(Objects.requireNonNull(source, "metrics"));
        if (result.isEmpty() || !result.containsKey("GLOBAL")
                || !result.containsKey("PARTITION/DEV") || !result.containsKey("PARTITION/HOLDOUT")
                || result.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                || !entry.getKey().matches("[A-Z]+(?:/[A-Za-z0-9_-]+)?") || entry.getValue() == null)) {
            throw invalid("RAPIDOCR_CAUSAL_METRICS_INVALID");
        }
        return Map.copyOf(result);
    }

    private static Map<AttributionLayer, Attribution> canonicalAttributions(
            Map<AttributionLayer, Attribution> source
    ) {
        var result = new EnumMap<AttributionLayer, Attribution>(AttributionLayer.class);
        result.putAll(Objects.requireNonNull(source, "attributions"));
        if (result.size() != AttributionLayer.values().length
                || new HashSet<>(result.keySet()).size() != AttributionLayer.values().length
                || result.values().stream().anyMatch(Objects::isNull)) {
            throw invalid("RAPIDOCR_CAUSAL_ATTRIBUTION_SET_INVALID");
        }
        return Map.copyOf(result);
    }

    private static void requireIdentity(String value, String code) {
        if (value == null || !value.matches("[A-Za-z][A-Za-z0-9._/-]{0,127}:[0-9a-f]{64}")) {
            throw invalid(code);
        }
    }

    private static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }

    public enum AttributionLayer {
        OBSERVATION,
        LAYOUT,
        ORDER_REPEAT,
        SHAPE_CODEC,
        SEMANTIC,
        STATIC_VIEW,
        MATERIALIZER,
        SCORER
    }

    public enum AttributionResult { OBSERVED_CONTRIBUTOR, EXCLUDED_BY_CURRENT_EVIDENCE, MISSING }

    public record Accounting(
            int runs,
            int casesPerRun,
            int devPerRun,
            int holdoutPerRun,
            int actualAcquisitions,
            int metricsEquivalentCases,
            int observationEquivalentCases
    ) {
        public Accounting {
            if (runs != 2 || casesPerRun != 60 || devPerRun != 45 || holdoutPerRun != 15
                    || actualAcquisitions != 120 || metricsEquivalentCases != 60
                    || observationEquivalentCases != 60) {
                throw invalid("RAPIDOCR_CAUSAL_ACCOUNTING_INVALID");
            }
        }
    }

    public record ScopedMetrics(long caseCount, Map<String, Integer> metricsBps) {
        public ScopedMetrics {
            if (caseCount < 0) throw invalid("RAPIDOCR_CAUSAL_SCOPE_COUNT_INVALID");
            metricsBps = Map.copyOf(new TreeMap<>(Objects.requireNonNull(metricsBps, "metricsBps")));
            if (metricsBps.isEmpty() || metricsBps.entrySet().stream().anyMatch(entry ->
                    entry.getKey() == null || !entry.getKey().matches("[a-z]+(?:\\.[A-Za-z]+)+")
                            || entry.getValue() == null || entry.getValue() < 0)) {
                throw invalid("RAPIDOCR_CAUSAL_SCOPE_METRICS_INVALID");
            }
        }
    }

    public record Attribution(
            AttributionResult result,
            String reasonCode,
            String evidenceReference
    ) {
        public Attribution {
            Objects.requireNonNull(result, "result");
            if (reasonCode == null || !reasonCode.matches("[A-Z][A-Z0-9_]{0,127}")) {
                throw invalid("RAPIDOCR_CAUSAL_ATTRIBUTION_REASON_INVALID");
            }
            requireIdentity(evidenceReference, "RAPIDOCR_CAUSAL_ATTRIBUTION_EVIDENCE_INVALID");
        }
    }

    public record ExternalProviderUsage(long attempts, long reservations, long costMicrosCny) {
        public ExternalProviderUsage {
            if (attempts < 0 || reservations < 0 || costMicrosCny < 0) {
                throw invalid("RAPIDOCR_CAUSAL_PROVIDER_USAGE_INVALID");
            }
        }

        public boolean zeroUsage() {
            return attempts == 0 && reservations == 0 && costMicrosCny == 0;
        }
    }
}
