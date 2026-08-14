package cn.hbads.renderweave.inference.eval.visual.quality;

import cn.hbads.renderweave.inference.eval.visual.LayeredEvaluationRecord;
import cn.hbads.renderweave.inference.eval.visual.LayeredVisualCorpus;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Frozen zero-provider protocol for deciding whether a quality-repair route may advance. */
public final class OfflineQualityEvaluationProtocol {
    public static final String VERSION = "renderweave-offline-quality-evaluation-protocol/1.0";
    private static final String RESOURCE =
            "visual-eval/quality-repair/offline-evaluation-protocol-v1.json";
    private static final String FINAL_AUTHORITY = "renderweave-visual-stage-corpus/1.0";
    private static final List<String> WINNER_TIE_BREAK = List.of(
            "STRUCTURAL_MARGIN_DESC",
            "DOWNSTREAM_MARGIN_DESC",
            "CRITICAL_HALLUCINATIONS_ASC",
            "FAILURE_RATE_ASC",
            "P95_LATENCY_ASC",
            "PEAK_RAM_ASC",
            "CONFIGURATION_ID_ASC");
    private static final ObjectMapper JSON = JsonMapper.builder(
                    JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .build();

    private final Document document;
    private final String identity;
    private final Assignment r2DevAssignment;
    private final List<String> r2HoldoutCaseIds;
    private final Assignment r3ProbeAssignment;
    private final Assignment r5ProbeAssignment;

    private OfflineQualityEvaluationProtocol(Document document, byte[] source) {
        this.document = Objects.requireNonNull(document, "document");
        if (!VERSION.equals(document.protocolVersion())) {
            throw invalid("QUALITY_REPAIR_PROTOCOL_VERSION_INVALID");
        }
        identity = VERSION + ":" + sha256(normalizeLineEndings(source));

        var corpus = new LayeredVisualCorpus();
        if (!LayeredVisualCorpus.VERSION.equals(document.corpusVersion())
                || !corpus.corpusIdentity().equals(document.corpusIdentity())) {
            throw invalid("QUALITY_REPAIR_CORPUS_IDENTITY_INVALID");
        }
        if (!document.shadowDiagnostic() || document.certificationEligible()
                || !FINAL_AUTHORITY.equals(document.finalAuthorityCorpusVersion())) {
            throw invalid("QUALITY_REPAIR_CORPUS_AUTHORITY_INVALID");
        }

        var dev = corpus.cases().stream()
                .filter(item -> item.partition() == LayeredEvaluationRecord.Partition.DEV)
                .map(LayeredVisualCorpus.Case::caseId)
                .toList();
        r2HoldoutCaseIds = corpus.cases().stream()
                .filter(item -> item.partition() == LayeredEvaluationRecord.Partition.HOLDOUT)
                .map(LayeredVisualCorpus.Case::caseId)
                .toList();
        if (dev.size() != 45 || r2HoldoutCaseIds.size() != 15) {
            throw invalid("QUALITY_REPAIR_CORPUS_PARTITION_INVALID");
        }
        r2DevAssignment = assignment("renderweave-r2-dev-assignment/1.0", dev);
        r3ProbeAssignment = probeAssignment(
                "renderweave-r3-probe-assignment/1.0", document.r3ProbeCaseIds(), corpus);
        r5ProbeAssignment = probeAssignment(
                "renderweave-r5-probe-assignment/1.0", document.r5ProbeCaseIds(), corpus);
        if (!disjoint(r3ProbeAssignment.caseIds(), r5ProbeAssignment.caseIds())) {
            throw invalid("QUALITY_REPAIR_PROBE_GOLD_NOT_ISOLATED");
        }
        validateFrozenDecisionRules(document);
    }

    public static OfflineQualityEvaluationProtocol load() {
        try (var input = OfflineQualityEvaluationProtocol.class.getClassLoader()
                .getResourceAsStream(RESOURCE)) {
            if (input == null) throw invalid("QUALITY_REPAIR_PROTOCOL_RESOURCE_MISSING");
            var bytes = input.readAllBytes();
            return new OfflineQualityEvaluationProtocol(JSON.readValue(bytes, Document.class), bytes);
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof IllegalArgumentException known) throw known;
            throw new IllegalStateException("QUALITY_REPAIR_PROTOCOL_INVALID", failure);
        }
    }

    public String identity() { return identity; }

    public String corpusIdentity() { return document.corpusIdentity(); }

    public boolean shadowDiagnostic() { return document.shadowDiagnostic(); }

    public boolean certificationEligible() { return document.certificationEligible(); }

    public String finalAuthorityCorpusVersion() { return document.finalAuthorityCorpusVersion(); }

    public Assignment r2DevAssignment() { return r2DevAssignment; }

    /** Exposes only cardinality until the sole frozen DEV winner is proven. */
    public int r2HoldoutCount() { return r2HoldoutCaseIds.size(); }

    public Assignment r3ProbeAssignment() { return r3ProbeAssignment; }

    public Assignment r5ProbeAssignment() { return r5ProbeAssignment; }

    public Thresholds thresholds() { return document.thresholds(); }

    public List<String> structuralMetrics() { return document.structuralMetrics(); }

    public List<String> downstreamMetrics() { return document.downstreamMetrics(); }

    public List<String> winnerTieBreak() { return document.winnerTieBreak(); }

    public ResourceCeilings resourceCeilings() { return document.resourceCeilings(); }

    public Assignment authorizeR2Holdout(DevWinnerEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        if (!identity.equals(evidence.protocolIdentity())) {
            throw invalid("QUALITY_REPAIR_HOLDOUT_PROTOCOL_IDENTITY_INVALID");
        }
        if (!r2DevAssignment.identity().equals(evidence.devAssignmentIdentity())) {
            throw invalid("QUALITY_REPAIR_HOLDOUT_DEV_ASSIGNMENT_INVALID");
        }
        if (evidence.winnerCount() != 1) {
            throw invalid("QUALITY_REPAIR_HOLDOUT_WINNER_COUNT_INVALID");
        }
        if (!evidence.selectedFromDevOnly()) {
            throw invalid("QUALITY_REPAIR_HOLDOUT_SELECTION_SCOPE_INVALID");
        }
        if (!evidence.thresholdsFrozenBeforeDev()) {
            throw invalid("QUALITY_REPAIR_HOLDOUT_THRESHOLD_FREEZE_MISSING");
        }
        if (evidence.postResultRetuning()) {
            throw invalid("QUALITY_REPAIR_HOLDOUT_POST_RESULT_RETUNING");
        }
        requireIdentity(evidence.configurationIdentity(),
                "renderweave-r2-acquisition-configuration/1.0",
                "QUALITY_REPAIR_HOLDOUT_CONFIGURATION_IDENTITY_INVALID");
        requireIdentity(evidence.selectionReportIdentity(),
                "renderweave-r2-dev-selection/1.0",
                "QUALITY_REPAIR_HOLDOUT_SELECTION_IDENTITY_INVALID");
        return assignment("renderweave-r2-holdout-assignment/1.0", r2HoldoutCaseIds);
    }

    private static void validateFrozenDecisionRules(Document value) {
        var thresholds = Objects.requireNonNull(value.thresholds(), "thresholds");
        if (thresholds.minimumStructuralImprovementBps() != 500
                || thresholds.maximumNonRegressionBps() != 100
                || thresholds.minimumDownstreamMetricsImproved() != 1
                || thresholds.maximumCriticalHallucinationIncrease() != 0) {
            throw invalid("QUALITY_REPAIR_THRESHOLD_INVALID");
        }
        if (!value.structuralMetrics().contains("LAYOUT_RECALL_BPS")
                || !value.downstreamMetrics().contains("CANDIDATE_TOPOLOGY_SIMILARITY_BPS")
                || value.structuralMetrics().isEmpty() || value.downstreamMetrics().isEmpty()
                || !WINNER_TIE_BREAK.equals(value.winnerTieBreak())) {
            throw invalid("QUALITY_REPAIR_SCORING_PROTOCOL_INVALID");
        }
        var ceilings = Objects.requireNonNull(value.resourceCeilings(), "resourceCeilings");
        if (ceilings.maximumStartupMillis() <= 0 || ceilings.maximumCaseP95Millis() <= 0
                || ceilings.maximumPeakRamMiB() <= 0 || ceilings.maximumDiskMiB() <= 0
                || ceilings.maximumGpuVramMiB() < 0) {
            throw invalid("QUALITY_REPAIR_RESOURCE_CEILING_INVALID");
        }
        var isolation = Objects.requireNonNull(value.isolationPolicy(), "isolationPolicy");
        if (!isolation.onlySoleDevWinnerMayOpenR2Holdout()
                || !isolation.r3R5GoldIsolatedFromR2Selection()
                || !isolation.resultBasedRetuningForbidden()) {
            throw invalid("QUALITY_REPAIR_HOLDOUT_POLICY_INVALID");
        }
    }

    private Assignment probeAssignment(String version, List<String> caseIds, LayeredVisualCorpus corpus) {
        caseIds = List.copyOf(Objects.requireNonNull(caseIds, "caseIds"));
        if (caseIds.size() != 4 || new HashSet<>(caseIds).size() != 4) {
            throw invalid("QUALITY_REPAIR_PROBE_ASSIGNMENT_INVALID");
        }
        long dev = 0;
        long holdout = 0;
        for (var caseId : caseIds) {
            var partition = corpus.require(caseId).partition();
            if (partition == LayeredEvaluationRecord.Partition.DEV) dev++;
            if (partition == LayeredEvaluationRecord.Partition.HOLDOUT) holdout++;
        }
        if (dev != 3 || holdout != 1) {
            throw invalid("QUALITY_REPAIR_PROBE_PARTITION_INVALID");
        }
        return assignment(version, caseIds);
    }

    private Assignment assignment(String version, List<String> caseIds) {
        caseIds = List.copyOf(caseIds);
        return new Assignment(caseIds, version + ":" + sha256(List.of(identity, String.join("\n", caseIds))));
    }

    private static boolean disjoint(List<String> left, List<String> right) {
        var values = new HashSet<>(left);
        return right.stream().noneMatch(values::contains);
    }

    private static void requireIdentity(String value, String version, String errorCode) {
        if (value == null || !value.matches(java.util.regex.Pattern.quote(version) + ":[0-9a-f]{64}")) {
            throw invalid(errorCode);
        }
    }

    private static String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA_256_UNAVAILABLE", impossible);
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
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA_256_UNAVAILABLE", impossible);
        }
    }

    private static byte[] normalizeLineEndings(byte[] source) {
        var normalized = new java.io.ByteArrayOutputStream(source.length);
        for (var index = 0; index < source.length; index++) {
            if (source[index] != '\r') {
                normalized.write(source[index]);
                continue;
            }
            if (index + 1 >= source.length || source[index + 1] != '\n') {
                throw invalid("QUALITY_REPAIR_PROTOCOL_LINE_ENDING_INVALID");
            }
            normalized.write('\n');
            index++;
        }
        return normalized.toByteArray();
    }

    private static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }

    public record Assignment(List<String> caseIds, String identity) {
        public Assignment {
            caseIds = List.copyOf(Objects.requireNonNull(caseIds, "caseIds"));
            if (caseIds.isEmpty() || new HashSet<>(caseIds).size() != caseIds.size()) {
                throw invalid("QUALITY_REPAIR_ASSIGNMENT_INVALID");
            }
            requireIdentity(identity, identityVersion(identity),
                    "QUALITY_REPAIR_ASSIGNMENT_IDENTITY_INVALID");
        }

        private static String identityVersion(String value) {
            if (value == null) return "invalid";
            var separator = value.lastIndexOf(':');
            return separator < 1 ? "invalid" : value.substring(0, separator);
        }
    }

    public record DevWinnerEvidence(
            String protocolIdentity,
            String devAssignmentIdentity,
            int winnerCount,
            String configurationIdentity,
            String selectionReportIdentity,
            boolean selectedFromDevOnly,
            boolean thresholdsFrozenBeforeDev,
            boolean postResultRetuning
    ) { }

    public record Thresholds(
            int minimumStructuralImprovementBps,
            int maximumNonRegressionBps,
            int minimumDownstreamMetricsImproved,
            int maximumCriticalHallucinationIncrease
    ) { }

    public record ResourceCeilings(
            long maximumStartupMillis,
            long maximumCaseP95Millis,
            long maximumPeakRamMiB,
            long maximumDiskMiB,
            long maximumGpuVramMiB
    ) { }

    private record IsolationPolicy(
            boolean onlySoleDevWinnerMayOpenR2Holdout,
            boolean r3R5GoldIsolatedFromR2Selection,
            boolean resultBasedRetuningForbidden
    ) { }

    private record Document(
            String protocolVersion,
            String corpusVersion,
            String corpusIdentity,
            boolean shadowDiagnostic,
            boolean certificationEligible,
            String finalAuthorityCorpusVersion,
            List<String> r3ProbeCaseIds,
            List<String> r5ProbeCaseIds,
            Thresholds thresholds,
            List<String> structuralMetrics,
            List<String> downstreamMetrics,
            List<String> winnerTieBreak,
            ResourceCeilings resourceCeilings,
            IsolationPolicy isolationPolicy
    ) {
        private Document {
            r3ProbeCaseIds = List.copyOf(Objects.requireNonNull(r3ProbeCaseIds, "r3ProbeCaseIds"));
            r5ProbeCaseIds = List.copyOf(Objects.requireNonNull(r5ProbeCaseIds, "r5ProbeCaseIds"));
            structuralMetrics = List.copyOf(Objects.requireNonNull(structuralMetrics, "structuralMetrics"));
            downstreamMetrics = List.copyOf(Objects.requireNonNull(downstreamMetrics, "downstreamMetrics"));
            winnerTieBreak = List.copyOf(Objects.requireNonNull(winnerTieBreak, "winnerTieBreak"));
        }
    }
}
