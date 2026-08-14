package cn.hbads.renderweave.inference.eval.visual.quality;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Payload-safe cross-implementation result of the R5P independent actual replay. */
public record R5PIndependentReplayEvidence(
        String evidenceVersion,
        String assurance,
        String authorityIdentity,
        String assignmentIdentity,
        String evaluationIdentity,
        String independentEvaluatorIdentity,
        String capabilityIdentity,
        int runCount,
        int caseCount,
        int executedBranchCount,
        int actualAcquisitionCalls,
        int normalizationReplays,
        int actionExecutions,
        Determinism determinism,
        List<CaseDecision> cases,
        CohortSummary seenSummary,
        CohortSummary confirmationSummary,
        boolean measurementValid,
        boolean qualityPass,
        ExternalProviderUsage externalProviderUsage,
        int apiKeyReads,
        int producerDecisionEngineCalls,
        int producerReportReads,
        PayloadBoundary payloadBoundary,
        String terminalCode
) {
    public static final String VERSION = "renderweave-r5p-independent-replay-evidence/1.0";
    public static final String ASSURANCE = "A2_CROSS_IMPLEMENTATION_ACTUAL_REPLAY";
    public static final String AUTHORITY_IDENTITY =
            "renderweave-r5p-authority/1.0:"
                    + "05958659a5ffc302e92f6cc6cda8b1efd868e2ec4fa7f92b0d63f821f843441d";
    public static final String ASSIGNMENT_IDENTITY =
            "renderweave-r5p-paired-view-assignment/1.0:"
                    + "39266e24b85e0189577573e6e4e56905d41a43f7e0f81a9514fbdbcac954c3e8";
    public static final String EVALUATION_IDENTITY =
            "renderweave-r5p-paired-view-evaluation/1.0:"
                    + "c8ad69263640ca49cd93ca24c6b558c6f913ff89a40c84052634c7cd79f66b65";
    public static final String INDEPENDENT_EVALUATOR_IDENTITY =
            "renderweave-r5p-independent-actual-replay/1.0";
    public static final String CAPABILITY_IDENTITY =
            "rapidocr-3.9.2-openvino-2026.0.0-ppocrv6-small-c05805399d7d10b1";
    public static final String TERMINAL_INVALID = "R5P_MEASUREMENT_INVALID";
    public static final String TERMINAL_NOT_QUALIFIED = "R5P_PAIRED_VIEW_NOT_QUALIFIED";
    public static final String TERMINAL_ALLOWED = "R5P_ACTION_IMPLEMENTATION_ALLOWED";
    private static final List<ExpectedCase> EXPECTED_CASES = List.of(
            expected("transit-board-v3", "688daa21a13118b5591d3057b6f1f15cef8a0e4f80a6549a4b80b19d8b043c0e", "SEEN_DIAGNOSTIC"),
            expected("restaurant-menu-v3", "6910f5288cbbde4ac0d813affb19e3e1df9fb8c3d7bab85249e56801e2e8db78", "SEEN_DIAGNOSTIC"),
            expected("hospital-schedule-v3", "749916a935e98fbf48ae59181e1b6bcde0a0b01a347724af04566c22ac3a92f9", "SEEN_DIAGNOSTIC"),
            expected("transit-board-v5", "c8e155a1da4f8d8d93a646b01c4375773b20c14742f9cb233eebaf5673853c4f", "SEEN_DIAGNOSTIC"),
            expected("transit-board-v2", "3976013e6e00f4c93fa874804366ff1d12df066985ca320fdd20e47f7c2ee08d", "SEALED_CONFIRMATION"),
            expected("invoice-lines-v3", "5906265340b4c556196095d90c2b6e34b86ac6c5300dc0fea6fedabe6a18deea", "SEALED_CONFIRMATION"),
            expected("school-timetable-v4", "5c421a2ddace4db33f68e47a39a165474a917d679ae0f33a7f6a4655fdb4a06a", "SEALED_CONFIRMATION"),
            expected("building-directory-v5", "ca5be012237e052ca4adc6726bb8d6c75ed9ce2597b6bee130006b412a7baef9", "SEALED_CONFIRMATION")
    );

    public R5PIndependentReplayEvidence {
        if (!VERSION.equals(evidenceVersion) || !ASSURANCE.equals(assurance)
                || !AUTHORITY_IDENTITY.equals(authorityIdentity)
                || !ASSIGNMENT_IDENTITY.equals(assignmentIdentity)
                || !EVALUATION_IDENTITY.equals(evaluationIdentity)
                || !INDEPENDENT_EVALUATOR_IDENTITY.equals(independentEvaluatorIdentity)
                || !CAPABILITY_IDENTITY.equals(capabilityIdentity)) {
            throw invalid("R5P_A2_AUTHORITY_INVALID");
        }
        Objects.requireNonNull(determinism, "determinism");
        cases = List.copyOf(Objects.requireNonNull(cases, "cases"));
        Objects.requireNonNull(seenSummary, "seenSummary");
        Objects.requireNonNull(confirmationSummary, "confirmationSummary");
        Objects.requireNonNull(externalProviderUsage, "externalProviderUsage");
        Objects.requireNonNull(payloadBoundary, "payloadBoundary");
        if (apiKeyReads != 0 || producerDecisionEngineCalls != 0 || producerReportReads != 0) {
            throw invalid("R5P_A2_FORBIDDEN_USAGE_NONZERO");
        }
        if (seenSummary.thresholdPass() != thresholdPass(seenSummary, false)
                || confirmationSummary.thresholdPass()
                != thresholdPass(confirmationSummary, true)) {
            throw invalid("R5P_A2_COHORT_INVALID");
        }
        if (measurementValid) {
            if (runCount != 2 || caseCount != 8 || cases.size() != 8
                    || executedBranchCount != 32 || actualAcquisitionCalls != 32
                    || normalizationReplays != 16 || actionExecutions != 16
                    || !determinism.deterministic()) {
                throw invalid("R5P_A2_ACCOUNTING_INVALID");
            }
            requireAssignmentClosure(cases, seenSummary, confirmationSummary);
        } else if (runCount < 0 || caseCount < 0 || executedBranchCount < 0
                || actualAcquisitionCalls < 0 || normalizationReplays < 0
                || actionExecutions < 0) {
            throw invalid("R5P_A2_ACCOUNTING_INVALID");
        }
        var expectedQuality = measurementValid
                && seenSummary.thresholdPass() && confirmationSummary.thresholdPass();
        if (qualityPass != expectedQuality) {
            throw invalid("R5P_A2_QUALITY_DECISION_INVALID");
        }
        var expectedTerminal = !measurementValid ? TERMINAL_INVALID
                : qualityPass ? TERMINAL_ALLOWED : TERMINAL_NOT_QUALIFIED;
        if (!expectedTerminal.equals(terminalCode)) {
            throw invalid("R5P_A2_TERMINAL_INVALID");
        }
    }

    private static void requireAssignmentClosure(
            List<CaseDecision> decisions,
            CohortSummary seenSummary,
            CohortSummary confirmationSummary
    ) {
        var ids = new HashSet<String>();
        var seenTarget = 0;
        var seenHallucination = 0;
        var confirmationTarget = 0;
        var confirmationHallucination = 0;
        for (var index = 0; index < EXPECTED_CASES.size(); index++) {
            var expected = EXPECTED_CASES.get(index);
            var actual = decisions.get(index);
            if (!ids.add(actual.caseId()) || !expected.caseId().equals(actual.caseId())
                    || !expected.caseIdentity().equals(actual.caseIdentity())
                    || !expected.cohort().equals(actual.cohort())
                    || !actual.deterministic()) {
                throw invalid("R5P_A2_CASE_CLOSURE_INVALID");
            }
            if ("SEEN_DIAGNOSTIC".equals(actual.cohort())) {
                if (actual.targetImproved()) seenTarget++;
                if (actual.hallucinationNonIncrease()) seenHallucination++;
            } else {
                if (actual.targetImproved()) confirmationTarget++;
                if (actual.hallucinationNonIncrease()) confirmationHallucination++;
            }
        }
        var seen = decisions.stream().filter(item -> "SEEN_DIAGNOSTIC".equals(item.cohort())).toList();
        var confirmation = decisions.stream()
                .filter(item -> "SEALED_CONFIRMATION".equals(item.cohort())).toList();
        if (seen.size() != 4 || confirmation.size() != 4
                || seenTarget != seenSummary.targetImprovementCases()
                || seenHallucination != seenSummary.hallucinationNonIncreaseCases()
                || confirmationTarget != confirmationSummary.targetImprovementCases()
                || confirmationHallucination != confirmationSummary.hallucinationNonIncreaseCases()
                || decisions.stream().filter(item -> "SEEN_DIAGNOSTIC".equals(item.cohort()))
                .mapToLong(CaseDecision::characterErrorReduction).sum()
                != seenSummary.characterErrorReduction()
                || decisions.stream().filter(item -> "SEALED_CONFIRMATION".equals(item.cohort()))
                .mapToLong(CaseDecision::characterErrorReduction).sum()
                != confirmationSummary.characterErrorReduction()
                || decisions.stream().filter(item -> "SEEN_DIAGNOSTIC".equals(item.cohort()))
                .mapToLong(CaseDecision::hallucinationIncrease).sum()
                != seenSummary.successorHallucinations() - seenSummary.baselineHallucinations()
                || decisions.stream().filter(item -> "SEALED_CONFIRMATION".equals(item.cohort()))
                .mapToLong(CaseDecision::hallucinationIncrease).sum()
                != confirmationSummary.successorHallucinations()
                - confirmationSummary.baselineHallucinations()) {
            throw invalid("R5P_A2_CASE_CLOSURE_INVALID");
        }
    }

    public record CaseDecision(
            String caseId,
            String caseIdentity,
            String cohort,
            String normalizationReplayIdentity,
            String baselineExecutionIdentity,
            String successorExecutionIdentity,
            int baselineViewCount,
            int successorViewCount,
            long matchedLineGain,
            int lineRecallGainBps,
            long characterErrorReduction,
            long hallucinationIncrease,
            int orderAccuracyDeltaBps,
            int repeatRecallDeltaBps,
            boolean targetImproved,
            boolean hallucinationNonIncrease,
            boolean deterministic
    ) {
        public CaseDecision {
            if (caseId == null || !caseId.matches("[a-z][a-z0-9-]{0,127}")
                    || !("SEEN_DIAGNOSTIC".equals(cohort)
                    || "SEALED_CONFIRMATION".equals(cohort))) {
                throw invalid("R5P_A2_CASE_INVALID");
            }
            requireIdentity(caseIdentity);
            requireIdentity(normalizationReplayIdentity);
            requireIdentity(baselineExecutionIdentity);
            requireIdentity(successorExecutionIdentity);
            if (baselineViewCount < 1 || baselineViewCount > 10
                    || successorViewCount <= baselineViewCount || successorViewCount > 10
                    || targetImproved != (matchedLineGain > 0 || characterErrorReduction > 0)
                    || hallucinationNonIncrease != (hallucinationIncrease <= 0)) {
                throw invalid("R5P_A2_CASE_INVALID");
            }
        }
    }

    public record CohortSummary(
            int caseCount,
            int targetImprovementCases,
            int hallucinationNonIncreaseCases,
            long baselineMatchedLines,
            long successorMatchedLines,
            int baselineLineRecallBps,
            int successorLineRecallBps,
            int lineRecallGainBps,
            long baselineCharacterErrors,
            long successorCharacterErrors,
            long characterErrorReduction,
            long baselineHallucinations,
            long successorHallucinations,
            int baselineOrderAccuracyBps,
            int successorOrderAccuracyBps,
            int orderAccuracyDeltaBps,
            int baselineRepeatRecallBps,
            int successorRepeatRecallBps,
            int repeatRecallDeltaBps,
            boolean thresholdPass
    ) {
        public CohortSummary {
            if (caseCount != 4 || targetImprovementCases < 0
                    || targetImprovementCases > caseCount
                    || hallucinationNonIncreaseCases < 0
                    || hallucinationNonIncreaseCases > caseCount
                    || baselineMatchedLines < 0 || successorMatchedLines < 0
                    || baselineCharacterErrors < 0 || successorCharacterErrors < 0
                    || baselineHallucinations < 0 || successorHallucinations < 0
                    || !bps(baselineLineRecallBps) || !bps(successorLineRecallBps)
                    || lineRecallGainBps != successorLineRecallBps - baselineLineRecallBps
                    || characterErrorReduction != baselineCharacterErrors - successorCharacterErrors
                    || !bps(baselineOrderAccuracyBps) || !bps(successorOrderAccuracyBps)
                    || orderAccuracyDeltaBps != successorOrderAccuracyBps - baselineOrderAccuracyBps
                    || !bps(baselineRepeatRecallBps) || !bps(successorRepeatRecallBps)
                    || repeatRecallDeltaBps != successorRepeatRecallBps - baselineRepeatRecallBps) {
                throw invalid("R5P_A2_COHORT_INVALID");
            }
        }
    }

    public record Determinism(
            int comparedCases,
            int equivalentCases,
            int comparedBranches,
            int equivalentBranches,
            boolean deterministic,
            String verdictCode
    ) {
        public Determinism {
            if (comparedCases != 8 || equivalentCases < 0 || equivalentCases > comparedCases
                    || comparedBranches != 16 || equivalentBranches < 0
                    || equivalentBranches > comparedBranches
                    || deterministic != (equivalentCases == comparedCases
                    && equivalentBranches == comparedBranches)
                    || !"R5P_A2_TWO_RUN_DETERMINISTIC".equals(verdictCode)) {
                throw invalid("R5P_A2_DETERMINISM_INVALID");
            }
        }
    }

    public record ExternalProviderUsage(long attempts, long reservations, long costMicrosCny) {
        public ExternalProviderUsage {
            if (attempts != 0 || reservations != 0 || costMicrosCny != 0) {
                throw invalid("R5P_A2_PROVIDER_USAGE_NONZERO");
            }
        }
    }

    public record PayloadBoundary(
            boolean imagePersisted,
            boolean encodedImagePayloadPersisted,
            boolean geometryPayloadPersisted,
            boolean ocrTextPersisted,
            boolean goldTextPersisted,
            boolean providerPayloadPersisted
    ) {
        public PayloadBoundary {
            if (imagePersisted || encodedImagePayloadPersisted || geometryPayloadPersisted
                    || ocrTextPersisted || goldTextPersisted || providerPayloadPersisted) {
                throw invalid("R5P_A2_PAYLOAD_PERSISTED");
            }
        }
    }

    public static final class Codec {
        private static final String ENVELOPE_VERSION =
                "renderweave-r5p-independent-replay-envelope/1.0";
        private static final int MAXIMUM_BYTES = 4 * 1024 * 1024;
        private static final tools.jackson.databind.ObjectMapper JSON = JsonMapper.builder(
                        JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .build();

        public byte[] write(R5PIndependentReplayEvidence evidence) {
            Objects.requireNonNull(evidence, "evidence");
            return encode(new Envelope(ENVELOPE_VERSION, identity(evidence), evidence));
        }

        public R5PIndependentReplayEvidence read(byte[] encoded, String expectedIdentity) {
            if (encoded == null || encoded.length == 0 || encoded.length > MAXIMUM_BYTES
                    || expectedIdentity == null) {
                throw invalid("R5P_A2_EVIDENCE_INVALID");
            }
            try {
                var node = JSON.readTree(encoded);
                requirePayloadSafe(node);
                var envelope = JSON.treeToValue(node, Envelope.class);
                if (!ENVELOPE_VERSION.equals(envelope.envelopeVersion())
                        || envelope.evidence() == null
                        || !expectedIdentity.equals(envelope.evidenceIdentity())
                        || !identity(envelope.evidence()).equals(envelope.evidenceIdentity())) {
                    throw invalid("R5P_A2_EVIDENCE_INVALID");
                }
                return envelope.evidence();
            } catch (RuntimeException failure) {
                throw new IllegalArgumentException("R5P_A2_EVIDENCE_INVALID", failure);
            }
        }

        public R5PIndependentReplayEvidence read(byte[] encoded) {
            if (encoded == null || encoded.length == 0 || encoded.length > MAXIMUM_BYTES) {
                throw invalid("R5P_A2_EVIDENCE_INVALID");
            }
            try {
                var node = JSON.readTree(encoded);
                requirePayloadSafe(node);
                var envelope = JSON.treeToValue(node, Envelope.class);
                if (!ENVELOPE_VERSION.equals(envelope.envelopeVersion())
                        || envelope.evidence() == null
                        || !identity(envelope.evidence()).equals(envelope.evidenceIdentity())) {
                    throw invalid("R5P_A2_EVIDENCE_INVALID");
                }
                return envelope.evidence();
            } catch (RuntimeException failure) {
                throw new IllegalArgumentException("R5P_A2_EVIDENCE_INVALID", failure);
            }
        }

        public String identity(R5PIndependentReplayEvidence evidence) {
            return VERSION + ":" + sha256(encode(evidence));
        }

        private static byte[] encode(Object value) {
            try {
                return JSON.writeValueAsBytes(canonical(JSON.valueToTree(value)));
            } catch (RuntimeException failure) {
                throw invalid("R5P_A2_EVIDENCE_INVALID");
            }
        }

        private static JsonNode canonical(JsonNode source) {
            if (source.isObject()) {
                var result = JSON.createObjectNode();
                var properties = new ArrayList<>(source.properties());
                properties.sort(java.util.Map.Entry.comparingByKey());
                properties.forEach(property -> result.set(property.getKey(), canonical(property.getValue())));
                return result;
            }
            if (source.isArray()) {
                var result = JSON.createArrayNode();
                source.forEach(item -> result.add(canonical(item)));
                return result;
            }
            return source;
        }

        private static void requirePayloadSafe(JsonNode node) {
            if (node.isObject()) {
                node.properties().forEach(property -> {
                    var key = property.getKey().toLowerCase(java.util.Locale.ROOT);
                    if (key.contains("base64") || key.equals("text") || key.contains("boundingbox")
                            || key.contains("sourcepixelbox") || key.contains("imagebytes")) {
                        throw invalid("R5P_A2_DECODED_PAYLOAD_FORBIDDEN");
                    }
                    requirePayloadSafe(property.getValue());
                });
            } else if (node.isArray()) {
                node.forEach(Codec::requirePayloadSafe);
            } else if (node.isTextual()) {
                var value = node.textValue().toLowerCase(java.util.Locale.ROOT);
                if (value.contains("data:image") || value.contains("ignore prior instructions")
                        || value.startsWith("bearer ")) {
                    throw invalid("R5P_A2_DECODED_PAYLOAD_FORBIDDEN");
                }
            }
        }

        private record Envelope(
                String envelopeVersion,
                String evidenceIdentity,
                R5PIndependentReplayEvidence evidence
        ) { }
    }

    private static boolean bps(int value) {
        return value >= 0 && value <= 10_000;
    }

    private static boolean thresholdPass(CohortSummary value, boolean confirmation) {
        var perCase = value.targetImprovementCases() == value.caseCount()
                && value.hallucinationNonIncreaseCases() == value.caseCount()
                && value.successorHallucinations() - value.baselineHallucinations() <= 0;
        return perCase && (!confirmation
                || value.lineRecallGainBps() >= 500
                && value.characterErrorReduction() >= 1
                && value.orderAccuracyDeltaBps() >= -100
                && value.repeatRecallDeltaBps() >= -100);
    }

    private static void requireIdentity(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._/-]+:[0-9a-f]{64}")) {
            throw invalid("R5P_A2_IDENTITY_INVALID");
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    private static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }

    private static ExpectedCase expected(String caseId, String sha256, String cohort) {
        return new ExpectedCase(caseId, "renderweave-layered-case/2.0:" + sha256, cohort);
    }

    private record ExpectedCase(String caseId, String caseIdentity, String cohort) { }
}
