package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.eval.visual.LayeredR1Evaluation;
import cn.hbads.renderweave.inference.eval.visual.LayeredVisualCorpus;
import cn.hbads.renderweave.inference.eval.visual.N7LiveSemanticEvaluation;
import cn.hbads.renderweave.inference.eval.visual.N7QualificationProtocol;
import cn.hbads.renderweave.inference.profile.InferenceProfileRegistry;
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
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable, payload-free request contract. Human approval remains in the excluded live ledger. */
record N7LiveTicketContract(
        String contractVersion,
        String ticketId,
        String authorizationId,
        String lifecycle,
        String provider,
        String model,
        String profileId,
        String profileSnapshotSha256,
        String pipelineVersion,
        String candidatePromptVersion,
        String elementPromptVersion,
        String hierarchyPromptVersion,
        String bindingPromptVersion,
        String corpusVersion,
        String corpusIdentity,
        String corpusSourceSha256,
        String qualificationProtocolIdentity,
        String assignmentIdentity,
        String evaluatorIdentity,
        List<String> caseIds,
        String inputClassification,
        int maximumProviderAttempts,
        long maximumTotalTokens,
        long maximumCostMicrosCny,
        int maximumCasesPerBatch,
        long maximumAuthorizationWindowSeconds,
        String executionMode,
        boolean holdoutAccess,
        String contractIdentity
) {
    static final String VERSION = "renderweave-n7-live-ticket-contract/1.0";
    static final String PLUS_CANARY_AUTHORIZATION_ID =
            "n7-04-plus-canary-product-v45-20260813c";
    private static final String PLUS_CANARY_RESOURCE =
            "visual-eval/n7/live-contracts/n7-04-plus-canary.json";
    private static final ObjectMapper JSON = JsonMapper.builder(
                    JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .build();

    N7LiveTicketContract {
        caseIds = List.copyOf(Objects.requireNonNull(caseIds, "caseIds"));
    }

    static N7LiveTicketContract plusCanary() {
        try (var input = N7LiveTicketContract.class.getClassLoader()
                .getResourceAsStream(PLUS_CANARY_RESOURCE)) {
            if (input == null) throw invalid("N7_LIVE_CONTRACT_RESOURCE_MISSING");
            var bytes = input.readAllBytes();
            var source = JSON.readValue(bytes, Document.class);
            var value = source.toContract(VERSION + ":" + sha256(normalizeLineEndings(bytes)));
            validatePlusCanary(value);
            return value;
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof IllegalStateException known) throw known;
            throw new IllegalStateException("N7_LIVE_CONTRACT_INVALID", failure);
        }
    }

    static Optional<N7LiveTicketContract> forAuthorization(String authorizationId) {
        if (!PLUS_CANARY_AUTHORIZATION_ID.equals(authorizationId)) return Optional.empty();
        return Optional.of(plusCanary());
    }

    private static void validatePlusCanary(N7LiveTicketContract value) {
        var protocol = N7QualificationProtocol.load();
        var corpus = new LayeredVisualCorpus();
        var profile = new InferenceProfileRegistry().require(value.profileId());
        var actual = profile.profile();
        require(VERSION.equals(value.contractVersion()) && "N7-04".equals(value.ticketId())
                        && PLUS_CANARY_AUTHORIZATION_ID.equals(value.authorizationId())
                        && "PROPOSED_NOT_OPEN".equals(value.lifecycle()),
                "N7_LIVE_CONTRACT_IDENTITY_DRIFT");
        require("DASHSCOPE".equals(value.provider())
                        && protocol.plus().model().equals(value.model())
                        && protocol.plus().profileId().equals(value.profileId()),
                "N7_LIVE_PROVIDER_PROFILE_DRIFT");
        require(protocol.plus().profileSnapshotIdentity()
                        .equals("snapshot-sha256:" + value.profileSnapshotSha256()),
                "N7_LIVE_PROTOCOL_PROFILE_SNAPSHOT_DRIFT");
        require(value.profileSnapshotSha256().equals(sha256(
                        profile.snapshotJson().getBytes(StandardCharsets.UTF_8))),
                "N7_LIVE_REGISTRY_PROFILE_SNAPSHOT_DRIFT");
        require(actual.pipelineVersion().equals(value.pipelineVersion())
                        && actual.promptVersion().equals(value.candidatePromptVersion())
                        && actual.elementPromptVersion().equals(value.elementPromptVersion())
                        && actual.hierarchyPromptVersion().equals(value.hierarchyPromptVersion())
                        && actual.bindingPromptVersion().equals(value.bindingPromptVersion()),
                "N7_LIVE_PIPELINE_PROMPT_DRIFT");
        require(corpus.version().equals(value.corpusVersion())
                        && corpus.corpusIdentity().equals(value.corpusIdentity())
                        && corpus.sourceScenesSha256().equals(value.corpusSourceSha256()),
                "N7_LIVE_CORPUS_DRIFT");
        require(protocol.identity().equals(value.qualificationProtocolIdentity())
                        && protocol.canaryAssignmentIdentity().equals(value.assignmentIdentity())
                        && protocol.canaryCaseIds().equals(value.caseIds()),
                "N7_LIVE_PROTOCOL_ASSIGNMENT_DRIFT");
        require(N7LiveSemanticEvaluation.evaluatorIdentity().equals(value.evaluatorIdentity())
                        && !LayeredR1Evaluation.evaluatorIdentity().equals(value.evaluatorIdentity()),
                "N7_LIVE_EVALUATOR_DRIFT");
        require(VisualEvaluationAuthorization.INPUT_CLASSIFICATION.equals(value.inputClassification())
                        && value.maximumProviderAttempts() == 35
                        && value.maximumTotalTokens() == 500_000L
                        && value.maximumCostMicrosCny() == 5_000_000L
                        && value.maximumCasesPerBatch() == 5
                        && value.maximumAuthorizationWindowSeconds() == 86_400L
                        && "SERIAL".equals(value.executionMode()) && !value.holdoutAccess(),
                "N7_LIVE_SCOPE_BUDGET_DRIFT");
        for (var caseId : value.caseIds()) {
            if (corpus.require(caseId).partition()
                    != cn.hbads.renderweave.inference.eval.visual.LayeredEvaluationRecord.Partition.DEV) {
                throw invalid("N7_LIVE_HOLDOUT_EXPOSED");
            }
        }
    }

    private static String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
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
                throw invalid("N7_LIVE_CONTRACT_LINE_ENDING_INVALID");
            }
            normalized.write('\n');
            index++;
        }
        return normalized.toByteArray();
    }

    private static IllegalStateException invalid(String code) {
        return new IllegalStateException(code);
    }

    private static void require(boolean condition, String code) {
        if (!condition) throw invalid(code);
    }

    private record Document(
            String contractVersion,
            String ticketId,
            String authorizationId,
            String lifecycle,
            String provider,
            String model,
            String profileId,
            String profileSnapshotSha256,
            String pipelineVersion,
            String candidatePromptVersion,
            String elementPromptVersion,
            String hierarchyPromptVersion,
            String bindingPromptVersion,
            String corpusVersion,
            String corpusIdentity,
            String corpusSourceSha256,
            String qualificationProtocolIdentity,
            String assignmentIdentity,
            String evaluatorIdentity,
            List<String> caseIds,
            String inputClassification,
            int maximumProviderAttempts,
            long maximumTotalTokens,
            long maximumCostMicrosCny,
            int maximumCasesPerBatch,
            long maximumAuthorizationWindowSeconds,
            String executionMode,
            boolean holdoutAccess
    ) {
        N7LiveTicketContract toContract(String identity) {
            return new N7LiveTicketContract(
                    contractVersion, ticketId, authorizationId, lifecycle, provider, model, profileId,
                    profileSnapshotSha256, pipelineVersion, candidatePromptVersion,
                    elementPromptVersion, hierarchyPromptVersion, bindingPromptVersion, corpusVersion,
                    corpusIdentity, corpusSourceSha256, qualificationProtocolIdentity,
                    assignmentIdentity, evaluatorIdentity, caseIds, inputClassification,
                    maximumProviderAttempts, maximumTotalTokens, maximumCostMicrosCny,
                    maximumCasesPerBatch, maximumAuthorizationWindowSeconds, executionMode,
                    holdoutAccess, identity);
        }
    }
}
