package cn.hbads.renderweave.inference.eval.visual;

import cn.hbads.renderweave.inference.profile.InferenceProfileRegistry;
import cn.hbads.renderweave.inference.vision.RapidOcrBaselineContract;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Frozen, machine-executable qualification and selection protocol for the N7 closeout. */
public final class N7QualificationProtocol {
    public static final String VERSION = "renderweave-n7-qualification-protocol/1.0";
    private static final String RESOURCE = "visual-eval/n7/qualification-protocol-v1.json";
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
    private final Map<String, TriggerDisposition> triggerDispositions;
    private final ProfileBinding plus;
    private final ProfileBinding max;
    private final ProfileBinding flash;

    private N7QualificationProtocol(Document document, byte[] source) {
        this.document = Objects.requireNonNull(document, "document");
        if (!VERSION.equals(document.protocolVersion())) {
            throw invalid("N7_PROTOCOL_VERSION_INVALID");
        }
        this.identity = VERSION + ":" + sha256(normalizeLineEndings(source));
        validateEvidenceAnchor(document.evidenceAnchor());
        if (!"CONTINUE_N7_CURRENT_BEHAVIOR".equals(document.continuationCode())) {
            throw invalid("N7_PROTOCOL_CONTINUATION_INVALID");
        }
        this.triggerDispositions = validateTriggers(document.triggerDispositions());
        validateAssignments(document);
        validateThresholds(document.thresholds());
        if (document.nonInferiorityBps() != 200) throw invalid("N7_PROTOCOL_NONINFERIORITY_INVALID");
        validateFinalAuthority(document);
        var profiles = validateProfiles(document.profiles());
        this.plus = profiles.get("PLUS");
        this.max = profiles.get("MAX");
        this.flash = profiles.get("FLASH");
        validateEvidenceMapping(document.evidenceMapping());
        validateArchitecture(document.architecture());
    }

    public static N7QualificationProtocol load() {
        try (var input = N7QualificationProtocol.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (input == null) throw invalid("N7_PROTOCOL_RESOURCE_MISSING");
            var bytes = input.readAllBytes();
            return new N7QualificationProtocol(JSON.readValue(bytes, Document.class), bytes);
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof IllegalArgumentException known) throw known;
            throw new IllegalStateException("N7_QUALIFICATION_PROTOCOL_INVALID", failure);
        }
    }

    public String identity() { return identity; }

    public String continuationCode() { return document.continuationCode(); }

    public Map<String, TriggerDisposition> triggerDispositions() { return triggerDispositions; }

    public List<String> canaryCaseIds() { return document.canaryCaseIds(); }

    public String canaryAssignmentIdentity() {
        return assignmentIdentity("renderweave-n7-canary-assignment/1.0", document.canaryCaseIds());
    }

    public List<String> qualificationCaseIds() { return document.qualificationCaseIds(); }

    public String qualificationAssignmentIdentity() {
        return assignmentIdentity(
                "renderweave-n7-qualification-assignment/1.0", document.qualificationCaseIds());
    }

    public List<String> hardNestedCaseIds() { return document.hardNestedCaseIds(); }

    public List<String> finalCaseIds() { return document.finalCaseIds(); }

    public String finalAssignmentIdentity() {
        return assignmentIdentity("renderweave-n7-final-assignment/1.0", document.finalCaseIds());
    }

    public List<String> finalHoldoutCaseIds() { return document.finalHoldoutCaseIds(); }

    public String finalCorpusVersion() { return document.finalCorpusVersion(); }

    public String finalCorpusSourceSha256() { return document.finalCorpusSourceSha256(); }

    public Thresholds thresholds() { return document.thresholds(); }

    public int nonInferiorityBps() { return document.nonInferiorityBps(); }

    public ProfileBinding plus() { return plus; }

    public ProfileBinding max() { return max; }

    public ProfileBinding flash() { return flash; }

    public Map<String, String> evidenceMapping() { return document.evidenceMapping(); }

    public EvidenceAnchor evidenceAnchor() { return document.evidenceAnchor(); }

    public ChallengerRoute route(QualificationEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        if (evidence.algorithmChangeRequired()) return ChallengerRoute.STOP_TO_SPEC;
        if (evidence.global() == null || evidence.hardNested() == null) {
            throw invalid("N7_QUALIFICATION_METRICS_MISSING");
        }
        if (!evidence.integrityPasses(document.thresholds())) return ChallengerRoute.NO_CHALLENGER;
        if (!passes(evidence.global())) return ChallengerRoute.NO_CHALLENGER;
        if (!passes(evidence.hardNested())) return ChallengerRoute.MAX_ELIGIBLE;
        return flash.available() ? ChallengerRoute.FLASH_ELIGIBLE : ChallengerRoute.STOP_TO_SPEC;
    }

    public FinalistDecision selectFinalist(List<QualificationCandidate> source) {
        source = List.copyOf(Objects.requireNonNull(source, "source"));
        var allowed = Set.of(plus.profileId(), max.profileId());
        var candidates = source.stream().filter(QualificationCandidate::qualified).peek(item -> {
            if (!allowed.contains(item.profileId())) throw invalid("N7_FINALIST_PROFILE_NOT_ALLOWED");
        }).toList();
        if (candidates.isEmpty()) return new FinalistDecision("", "NO_FINALIST", "", "");
        var bestMargin = candidates.stream().mapToInt(this::weakestMargin).max().orElseThrow();
        var band = candidates.stream().filter(item ->
                weakestMargin(item) >= bestMargin - document.nonInferiorityBps()).sorted(Comparator
                .comparingLong(QualificationCandidate::estimatedCostMicrosCny)
                .thenComparingLong(QualificationCandidate::p95LatencyMillis)
                .thenComparing(QualificationCandidate::profileId)).toList();
        var selected = band.getFirst();
        return new FinalistDecision(selected.profileId(), "FINALIST_SELECTED",
                selected.reportIdentity(), selected.profileSnapshotIdentity());
    }

    private boolean passes(QualityMetrics value) {
        var thresholds = document.thresholds();
        return value.schemaEntityF1Bps() >= thresholds.schemaEntityF1Bps()
                && value.fieldMicroF1Bps() >= thresholds.fieldMicroF1Bps()
                && value.supportedTypeAccuracyBps() >= thresholds.supportedTypeAccuracyBps()
                && value.parentChildEdgeF1Bps() >= thresholds.parentChildEdgeF1Bps();
    }

    private int weakestMargin(QualificationCandidate value) {
        var thresholds = document.thresholds();
        return Math.min(Math.min(
                        value.metrics().schemaEntityF1Bps() - thresholds.schemaEntityF1Bps(),
                        value.metrics().fieldMicroF1Bps() - thresholds.fieldMicroF1Bps()),
                Math.min(
                        value.metrics().supportedTypeAccuracyBps() - thresholds.supportedTypeAccuracyBps(),
                        value.metrics().parentChildEdgeF1Bps() - thresholds.parentChildEdgeF1Bps()));
    }

    private static void validateEvidenceAnchor(EvidenceAnchor value) {
        Objects.requireNonNull(value, "evidenceAnchor");
        if (!"b50d04e710f3a176b5e95336f912460809939d89".equals(value.baseRevision())
                || !"2f3ad8aff29f53cb79f1b546f323a2966fbf489c".equals(value.implementationRevision())
                || !"renderweave-rapidocr-shadow-evaluation/1.0:91af6d7d79b2c3bc7d8b5446c79d6b4874e16e1cb28b7fffb3fcc3b7a3b25e3a"
                .equals(value.shadowEvaluationIdentity())
                || !"renderweave-rapidocr-shadow-report/1.0:fc2cc3523ba59e9832ba8eb6fa651fd2fac9088751a4b9b72c7fa4bab476f8a5"
                .equals(value.shadowReportIdentity())
                || !"renderweave-rapidocr-shadow-recomputed-aggregates/1.0:8f23fc9ddc7825f5cb4479352bd4dbdf8e013f9f73124a16306b31bb2cb15635"
                .equals(value.shadowAggregateIdentity())) {
            throw invalid("N7_PROTOCOL_EVIDENCE_ANCHOR_INVALID");
        }
    }

    private static Map<String, TriggerDisposition> validateTriggers(Map<String, TriggerDocument> source) {
        source = Map.copyOf(Objects.requireNonNull(source, "triggerDispositions"));
        if (!source.keySet().equals(Set.of("R2", "R3", "R4", "R5"))) {
            throw invalid("N7_PROTOCOL_TRIGGER_SET_INVALID");
        }
        var result = new LinkedHashMap<String, TriggerDisposition>();
        source.forEach((key, item) -> {
            if (item == null || item.status() != TriggerStatus.NOT_TRIGGERED) {
                throw invalid("N7_PROTOCOL_TRIGGER_INVALID");
            }
            result.put(key, new TriggerDisposition(item.status(), requireCode(item.reasonCode()),
                    requireCode(item.factCode())));
        });
        return Map.copyOf(result);
    }

    private static void validateAssignments(Document value) {
        var corpus = new LayeredVisualCorpus();
        var canary = unique(value.canaryCaseIds(), 5, "CANARY");
        var qualification = unique(value.qualificationCaseIds(), 20, "QUALIFICATION");
        var hard = unique(value.hardNestedCaseIds(), 12, "HARD_NESTED");
        if (!qualification.containsAll(canary) || !qualification.containsAll(hard)) {
            throw invalid("N7_PROTOCOL_ASSIGNMENT_SUBSET_INVALID");
        }
        for (var caseId : qualification) {
            if (corpus.require(caseId).partition() != LayeredEvaluationRecord.Partition.DEV) {
                throw invalid("N7_PROTOCOL_HOLDOUT_EXPOSED");
            }
        }
        for (var caseId : hard) {
            var item = corpus.require(caseId);
            if (item.difficulty() != LayeredEvaluationRecord.Difficulty.MULTI_COLUMN
                    && item.difficulty() != LayeredEvaluationRecord.Difficulty.DENSE_TEXT
                    && !item.failureSlices().contains(LayeredEvaluationRecord.FailureSlice.PROMPT_INJECTION)) {
                throw invalid("N7_PROTOCOL_HARD_NESTED_ASSIGNMENT_INVALID");
            }
        }
    }

    private static void validateThresholds(Thresholds value) {
        Objects.requireNonNull(value, "thresholds");
        if (value.terminalReviewRequiredBps() != 10_000 || value.bundleContractBps() != 10_000
                || value.schemaEntityF1Bps() != 9_000 || value.fieldMicroF1Bps() != 9_000
                || value.supportedTypeAccuracyBps() != 9_500
                || value.parentChildEdgeF1Bps() != 9_500 || value.evidenceCoverageBps() != 10_000
                || value.dagValidityBps() != 10_000 || value.maximumCriticalHallucinations() != 0
                || value.maximumPayloadViolations() != 0 || value.maximumIdentityViolations() != 0
                || value.maximumBudgetViolations() != 0) {
            throw invalid("N7_PROTOCOL_THRESHOLD_INVALID");
        }
    }

    private static void validateFinalAuthority(Document value) {
        var corpus = new VisualStageCorpus();
        var expected = corpus.cases().stream().map(VisualStageCorpus.EvaluationCase::caseId).toList();
        var holdout = corpus.cases().stream().filter(item -> item.partition() == VisualStageCorpus.Partition.HOLDOUT)
                .map(VisualStageCorpus.EvaluationCase::caseId).toList();
        if (!VisualStageCorpus.VERSION.equals(value.finalCorpusVersion())
                || !corpus.sourceSha256().equals(value.finalCorpusSourceSha256())
                || !expected.equals(value.finalCaseIds()) || !holdout.equals(value.finalHoldoutCaseIds())) {
            throw invalid("N7_PROTOCOL_FINAL_AUTHORITY_INVALID");
        }
    }

    private static Map<String, ProfileBinding> validateProfiles(Map<String, ProfileDocument> source) {
        source = Map.copyOf(Objects.requireNonNull(source, "profiles"));
        if (!source.keySet().equals(Set.of("PLUS", "MAX", "FLASH"))) {
            throw invalid("N7_PROTOCOL_PROFILE_SET_INVALID");
        }
        var registry = new InferenceProfileRegistry();
        var current = registry.productLiveProfiles();
        var result = new LinkedHashMap<String, ProfileBinding>();
        for (var key : List.of("PLUS", "MAX")) {
            var item = source.get(key);
            if (item == null || !item.available()
                    || !"EXACT_PRODUCT_V45_PROFILE_AVAILABLE".equals(item.reasonCode())) {
                throw invalid("N7_PROTOCOL_PROFILE_BINDING_INVALID");
            }
            var resource = registry.require(item.profileId());
            var profile = resource.profile();
            if (!item.model().equals(profile.model())
                    || !"renderweave-inference-pipeline/4.28".equals(profile.pipelineVersion())
                    || !"renderweave-visual-elements-prompt/12.0".equals(profile.elementPromptVersion())
                    || !"renderweave-visual-hierarchy-prompt/7.0".equals(profile.hierarchyPromptVersion())
                    || !"renderweave-visual-bindings-prompt/4.0".equals(profile.bindingPromptVersion())
                    || !RapidOcrBaselineContract.CAPABILITY_IDENTITY.equals(profile.documentVisionCapabilityId())
                    || !"EXPERIMENTAL".equals(profile.certification())
                    || current.stream().noneMatch(candidate -> candidate.profile().profileId().equals(item.profileId()))) {
                throw invalid("N7_PROTOCOL_PROFILE_IDENTITY_DRIFT");
            }
            result.put(key, new ProfileBinding(item.profileId(), item.model(), true,
                    item.reasonCode(), "snapshot-sha256:" + sha256(
                    resource.snapshotJson().getBytes(StandardCharsets.UTF_8))));
        }
        var item = source.get("FLASH");
        if (item == null || item.available()
                || !"qwen3.7-flash-2026-07-15".equals(item.model())
                || !"PINNED_PRODUCT_V45_PROFILE_ABSENT".equals(item.reasonCode())
                || current.stream().anyMatch(candidate -> item.model().equals(candidate.profile().model()))) {
            throw invalid("N7_PROTOCOL_FLASH_AUTHORITY_INVALID");
        }
        result.put("FLASH", new ProfileBinding(item.profileId(), item.model(), false,
                item.reasonCode(), "NOT_AVAILABLE"));
        return Map.copyOf(result);
    }

    private static void validateEvidenceMapping(Map<String, String> source) {
        source = Map.copyOf(Objects.requireNonNull(source, "evidenceMapping"));
        var expected = new HashSet<String>();
        for (var index = 1; index <= 10; index++) expected.add("AC-VR-%03d".formatted(index));
        expected.add("AC-021");
        if (!source.keySet().equals(expected)
                || source.values().stream().anyMatch(value -> value == null
                || !value.matches("[A-Z][A-Z0-9_]{0,127}"))) {
            throw invalid("N7_PROTOCOL_EVIDENCE_MAPPING_INVALID");
        }
    }

    private static void validateArchitecture(Architecture value) {
        Objects.requireNonNull(value, "architecture");
        if (!"EXISTING_POSTGRESQL_DURABLE_TYPED_STATE_MACHINE".equals(value.orchestration())
                || !"SERIAL".equals(value.semanticStages())
                || !"VALIDATOR_DRIVEN_BOUNDED".equals(value.localRepair())
                || value.openEndedAgent() || value.generalToolExecutor() || value.langGraph() || value.temporal()) {
            throw invalid("N7_PROTOCOL_ARCHITECTURE_INVALID");
        }
    }

    private static List<String> unique(List<String> source, int expectedSize, String name) {
        source = List.copyOf(Objects.requireNonNull(source, name));
        if (source.size() != expectedSize || new HashSet<>(source).size() != expectedSize
                || source.stream().anyMatch(value -> value == null
                || !value.matches("[a-z][a-z0-9-]{0,127}"))) {
            throw invalid("N7_PROTOCOL_" + name + "_ASSIGNMENT_INVALID");
        }
        return source;
    }

    private static String requireCode(String value) {
        if (value == null || !value.matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw invalid("N7_PROTOCOL_CODE_INVALID");
        }
        return value;
    }

    private static String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
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
                throw invalid("N7_PROTOCOL_LINE_ENDING_INVALID");
            }
            normalized.write('\n');
            index++;
        }
        return normalized.toByteArray();
    }

    private static String assignmentIdentity(String version, List<String> caseIds) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var value : caseIds) {
                var bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) ':');
                digest.update(bytes);
                digest.update((byte) '\n');
            }
            return version + ":" + java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA_256_UNAVAILABLE", impossible);
        }
    }

    private static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }

    public enum TriggerStatus { NOT_TRIGGERED, TRIGGERED }

    public enum ChallengerRoute { FLASH_ELIGIBLE, MAX_ELIGIBLE, NO_CHALLENGER, STOP_TO_SPEC }

    public record TriggerDisposition(TriggerStatus status, String reasonCode, String factCode) { }

    public record ProfileBinding(
            String profileId,
            String model,
            boolean available,
            String reasonCode,
            String profileSnapshotIdentity
    ) { }

    public record QualityMetrics(
            int schemaEntityF1Bps,
            int fieldMicroF1Bps,
            int supportedTypeAccuracyBps,
            int parentChildEdgeF1Bps
    ) {
        public QualityMetrics {
            for (var value : new int[]{schemaEntityF1Bps, fieldMicroF1Bps,
                    supportedTypeAccuracyBps, parentChildEdgeF1Bps}) {
                if (value < 0 || value > 10_000) throw invalid("N7_QUALITY_METRIC_INVALID");
            }
        }

        public static QualityMetrics atThresholds() {
            return new QualityMetrics(9_000, 9_000, 9_500, 9_500);
        }
    }

    public record QualificationEvidence(
            boolean assignmentExact,
            boolean identityExact,
            boolean holdoutUntouched,
            int terminalReviewRequiredBps,
            int bundleContractBps,
            int evidenceCoverageBps,
            int dagValidityBps,
            int criticalHallucinations,
            int payloadViolations,
            int identityViolations,
            int budgetViolations,
            QualityMetrics global,
            QualityMetrics hardNested,
            boolean algorithmChangeRequired
    ) {
        public QualificationEvidence {
            for (var value : new int[]{terminalReviewRequiredBps, bundleContractBps,
                    evidenceCoverageBps, dagValidityBps, criticalHallucinations,
                    payloadViolations, identityViolations, budgetViolations}) {
                if (value < 0 || value > 10_000) throw invalid("N7_QUALIFICATION_EVIDENCE_INVALID");
            }
        }

        boolean integrityPasses(Thresholds threshold) {
            return assignmentExact && identityExact && holdoutUntouched
                    && terminalReviewRequiredBps >= threshold.terminalReviewRequiredBps()
                    && bundleContractBps >= threshold.bundleContractBps()
                    && evidenceCoverageBps >= threshold.evidenceCoverageBps()
                    && dagValidityBps >= threshold.dagValidityBps()
                    && criticalHallucinations <= threshold.maximumCriticalHallucinations()
                    && payloadViolations <= threshold.maximumPayloadViolations()
                    && identityViolations <= threshold.maximumIdentityViolations()
                    && budgetViolations <= threshold.maximumBudgetViolations();
        }

        public static QualificationEvidence complete(QualityMetrics global, QualityMetrics hardNested) {
            return new QualificationEvidence(true, true, true, 10_000, 10_000, 10_000, 10_000,
                    0, 0, 0, 0, global, hardNested, false);
        }

        public static QualificationEvidence integrityFailure(QualityMetrics global, QualityMetrics hardNested) {
            return new QualificationEvidence(true, true, true, 10_000, 9_999, 10_000, 10_000,
                    0, 0, 0, 0, global, hardNested, false);
        }

        public static QualificationEvidence requiringAlgorithmChange() {
            return new QualificationEvidence(true, true, true, 10_000, 10_000, 10_000, 10_000,
                    0, 0, 0, 0, QualityMetrics.atThresholds(), QualityMetrics.atThresholds(), true);
        }

        public static QualificationEvidence missingMetrics() {
            return new QualificationEvidence(true, true, true, 10_000, 10_000, 10_000, 10_000,
                    0, 0, 0, 0, null, null, false);
        }
    }

    public record QualificationCandidate(
            String profileId,
            boolean qualified,
            QualityMetrics metrics,
            long estimatedCostMicrosCny,
            long p95LatencyMillis,
            String reportIdentity,
            String profileSnapshotIdentity
    ) {
        public QualificationCandidate {
            if (profileId == null || !profileId.matches("[a-z][a-z0-9-]{0,127}")
                    || estimatedCostMicrosCny < 0 || p95LatencyMillis < 0) {
                throw invalid("N7_QUALIFICATION_CANDIDATE_INVALID");
            }
            Objects.requireNonNull(metrics, "metrics");
            LayeredVisualAnnotation.requireIdentity(reportIdentity, "N7_QUALIFICATION_REPORT_IDENTITY_INVALID");
            LayeredVisualAnnotation.requireIdentity(
                    profileSnapshotIdentity, "N7_PROFILE_SNAPSHOT_IDENTITY_INVALID");
        }
    }

    public record FinalistDecision(
            String profileId,
            String reasonCode,
            String reportIdentity,
            String profileSnapshotIdentity
    ) { }

    public record Thresholds(
            int terminalReviewRequiredBps,
            int bundleContractBps,
            int schemaEntityF1Bps,
            int fieldMicroF1Bps,
            int supportedTypeAccuracyBps,
            int parentChildEdgeF1Bps,
            int evidenceCoverageBps,
            int dagValidityBps,
            int maximumCriticalHallucinations,
            int maximumPayloadViolations,
            int maximumIdentityViolations,
            int maximumBudgetViolations
    ) { }

    public record EvidenceAnchor(
            String baseRevision,
            String implementationRevision,
            String shadowEvaluationIdentity,
            String shadowReportIdentity,
            String shadowAggregateIdentity
    ) { }

    private record TriggerDocument(TriggerStatus status, String reasonCode, String factCode) { }

    private record ProfileDocument(String profileId, String model, boolean available, String reasonCode) { }

    private record Architecture(
            String orchestration,
            String semanticStages,
            String localRepair,
            boolean openEndedAgent,
            boolean generalToolExecutor,
            boolean langGraph,
            boolean temporal
    ) { }

    private record Document(
            String protocolVersion,
            EvidenceAnchor evidenceAnchor,
            String continuationCode,
            Map<String, TriggerDocument> triggerDispositions,
            List<String> canaryCaseIds,
            List<String> qualificationCaseIds,
            List<String> hardNestedCaseIds,
            Thresholds thresholds,
            int nonInferiorityBps,
            String finalCorpusVersion,
            String finalCorpusSourceSha256,
            List<String> finalCaseIds,
            List<String> finalHoldoutCaseIds,
            Map<String, ProfileDocument> profiles,
            Map<String, String> evidenceMapping,
            Architecture architecture
    ) {
        private Document {
            canaryCaseIds = List.copyOf(Objects.requireNonNull(canaryCaseIds, "canaryCaseIds"));
            qualificationCaseIds = List.copyOf(Objects.requireNonNull(
                    qualificationCaseIds, "qualificationCaseIds"));
            hardNestedCaseIds = List.copyOf(Objects.requireNonNull(hardNestedCaseIds, "hardNestedCaseIds"));
            finalCaseIds = List.copyOf(Objects.requireNonNull(finalCaseIds, "finalCaseIds"));
            finalHoldoutCaseIds = List.copyOf(Objects.requireNonNull(
                    finalHoldoutCaseIds, "finalHoldoutCaseIds"));
            evidenceMapping = Map.copyOf(Objects.requireNonNull(evidenceMapping, "evidenceMapping"));
        }
    }
}
