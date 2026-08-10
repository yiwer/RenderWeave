package cn.hbads.renderweave.inference.profile;

import cn.hbads.renderweave.inference.input.InferenceMode;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Objects;

public record InferenceProfile(
        String profileVersion,
        String profileId,
        String provider,
        String model,
        boolean networkAllowed,
        String providerProtocol,
        String providerEndpoint,
        String apiKeyEnvironmentVariable,
        String pipelineVersion,
        String candidateContractVersion,
        String promptVersion,
        String elementPromptVersion,
        String hierarchyPromptVersion,
        String bindingPromptVersion,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String visualHintPackVersion,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String documentVisionCapabilityId,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String documentVisionPromptVersion,
        String responseFormat,
        boolean thinkingEnabled,
        boolean toolsAllowed,
        boolean remoteMediaAllowed,
        String inputClassification,
        List<InferenceMode> supportedModes,
        int lowConfidenceThresholdBps,
        int maximumRepairRounds,
        int maximumTotalCalls,
        int stageTimeoutSeconds,
        int maximumOutputTokens,
        int maximumOutputBytes,
        long maximumEstimatedCostMicrosCny,
        long inputMicrosCnyPerMillionTokens,
        long outputMicrosCnyPerMillionTokens,
        String pricingEffectiveDate,
        String certification
) {
    private static final String DASHSCOPE_ENDPOINT =
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";

    public InferenceProfile {
        profileVersion = requireText(profileVersion, "profileVersion");
        profileId = requireText(profileId, "profileId");
        provider = requireText(provider, "provider");
        model = requireText(model, "model");
        providerProtocol = requireText(providerProtocol, "providerProtocol");
        providerEndpoint = requireText(providerEndpoint, "providerEndpoint");
        apiKeyEnvironmentVariable = requireText(apiKeyEnvironmentVariable, "apiKeyEnvironmentVariable");
        pipelineVersion = requireText(pipelineVersion, "pipelineVersion");
        candidateContractVersion = requireText(candidateContractVersion, "candidateContractVersion");
        promptVersion = requireText(promptVersion, "promptVersion");
        responseFormat = requireText(responseFormat, "responseFormat");
        inputClassification = requireText(inputClassification, "inputClassification");
        supportedModes = List.copyOf(Objects.requireNonNull(supportedModes, "supportedModes"));
        pricingEffectiveDate = requireText(pricingEffectiveDate, "pricingEffectiveDate");
        certification = requireText(certification, "certification");
        if (lowConfidenceThresholdBps < 0 || lowConfidenceThresholdBps > 10_000) {
            throw new IllegalArgumentException("lowConfidenceThresholdBps must be 0..10000");
        }
        if (maximumRepairRounds < 0 || maximumRepairRounds > 2) {
            throw new IllegalArgumentException("maximumRepairRounds must be 0..2");
        }
        if (maximumTotalCalls < 1 || stageTimeoutSeconds < 1
                || maximumOutputTokens < 1 || maximumOutputBytes < 1) {
            throw new IllegalArgumentException("Profile budgets must be positive");
        }
        if (supportedModes.isEmpty()) throw new IllegalArgumentException("supportedModes must not be empty");
        if (maximumOutputBytes > 2 * 1024 * 1024) {
            throw new IllegalArgumentException("maximumOutputBytes must not exceed the Candidate contract limit");
        }
        if (maximumEstimatedCostMicrosCny < 0
                || inputMicrosCnyPerMillionTokens < 0
                || outputMicrosCnyPerMillionTokens < 0) {
            throw new IllegalArgumentException("Pricing and cost budgets must not be negative");
        }
        if (networkAllowed) {
            validateDashScopeLive(
                    profileId, provider, model, providerProtocol, providerEndpoint, apiKeyEnvironmentVariable,
                    pipelineVersion, promptVersion, responseFormat,
                    elementPromptVersion, hierarchyPromptVersion, bindingPromptVersion,
                    visualHintPackVersion, documentVisionCapabilityId, documentVisionPromptVersion,
                    thinkingEnabled, toolsAllowed, remoteMediaAllowed,
                    inputClassification, maximumTotalCalls, stageTimeoutSeconds,
                    maximumEstimatedCostMicrosCny,
                    inputMicrosCnyPerMillionTokens, outputMicrosCnyPerMillionTokens,
                    pricingEffectiveDate, certification
            );
        } else if (maximumEstimatedCostMicrosCny != 0
                || inputMicrosCnyPerMillionTokens != 0
                || outputMicrosCnyPerMillionTokens != 0) {
            throw new IllegalArgumentException("Zero-network profiles cannot carry provider pricing");
        }
    }

    private static void validateDashScopeLive(
            String profileId,
            String provider,
            String model,
            String providerProtocol,
            String providerEndpoint,
            String apiKeyEnvironmentVariable,
            String pipelineVersion,
            String promptVersion,
            String responseFormat,
            String elementPromptVersion,
            String hierarchyPromptVersion,
            String bindingPromptVersion,
            String visualHintPackVersion,
            String documentVisionCapabilityId,
            String documentVisionPromptVersion,
            boolean thinkingEnabled,
            boolean toolsAllowed,
            boolean remoteMediaAllowed,
            String inputClassification,
            int maximumTotalCalls,
            int stageTimeoutSeconds,
            long maximumEstimatedCostMicrosCny,
            long inputMicrosCnyPerMillionTokens,
            long outputMicrosCnyPerMillionTokens,
            String pricingEffectiveDate,
            String certification
    ) {
        if (!"DASHSCOPE".equals(provider)
                || !("qwen3.7-flash".equals(model)
                || "qwen3.7-plus-2026-05-26".equals(model)
                || "qwen3.7-plus".equals(model)
                || "qwen3.7-max-2026-06-08".equals(model)
                || "qwen3.8-max".equals(model))) {
            throw new IllegalArgumentException("Live profiles must use an approved DashScope model");
        }
        if (!"OPENAI_CHAT_COMPLETIONS".equals(providerProtocol)
                || !DASHSCOPE_ENDPOINT.equals(providerEndpoint)) {
            throw new IllegalArgumentException("P5 provider protocol and endpoint are fixed");
        }
        if (!"DASHSCOPE_API_KEY".equals(apiKeyEnvironmentVariable)) {
            throw new IllegalArgumentException("Live API key source is fixed");
        }
        var legacySyntheticPrompt = "SYNTHETIC_ONLY".equals(inputClassification)
                && "renderweave-inference-pipeline/1.0".equals(pipelineVersion)
                && (InferencePromptRegistry.SCHEMA_CANDIDATE_V1.equals(promptVersion)
                || ("qwen3.7-plus-2026-05-26".equals(model)
                && InferencePromptRegistry.SCHEMA_CANDIDATE_V2.equals(promptVersion)))
                || "SYNTHETIC_ONLY".equals(inputClassification)
                && "renderweave-inference-pipeline/2.0".equals(pipelineVersion)
                && "qwen3.7-plus-2026-05-26".equals(model)
                && InferencePromptRegistry.SCHEMA_CANDIDATE_V3.equals(promptVersion);
        var productPromptV1 = InferencePromptRegistry.SCHEMA_CANDIDATE_V3.equals(promptVersion)
                && profileId.endsWith("-product-v1");
        var productPromptV2 = InferencePromptRegistry.SCHEMA_CANDIDATE_V4.equals(promptVersion)
                && profileId.endsWith("-product-v2");
        var productPromptV3 = InferencePromptRegistry.SCHEMA_CANDIDATE_V5.equals(promptVersion)
                && InferencePromptRegistry.VISUAL_ELEMENTS_V1.equals(elementPromptVersion)
                && InferencePromptRegistry.VISUAL_HIERARCHY_V1.equals(hierarchyPromptVersion)
                && InferencePromptRegistry.VISUAL_BINDINGS_V1.equals(bindingPromptVersion)
                && profileId.endsWith("-product-v3");
        var productPromptV4 = InferencePromptRegistry.SCHEMA_CANDIDATE_V5.equals(promptVersion)
                && InferencePromptRegistry.VISUAL_ELEMENTS_V1.equals(elementPromptVersion)
                && InferencePromptRegistry.VISUAL_HIERARCHY_V1.equals(hierarchyPromptVersion)
                && InferencePromptRegistry.VISUAL_BINDINGS_V1.equals(bindingPromptVersion)
                && profileId.endsWith("-product-v4");
        var productPromptV5 = InferencePromptRegistry.SCHEMA_CANDIDATE_V5.equals(promptVersion)
                && InferencePromptRegistry.VISUAL_ELEMENTS_V1.equals(elementPromptVersion)
                && InferencePromptRegistry.VISUAL_HIERARCHY_V1.equals(hierarchyPromptVersion)
                && InferencePromptRegistry.VISUAL_BINDINGS_V1.equals(bindingPromptVersion)
                && profileId.endsWith("-product-v5");
        var productPromptV6 = InferencePromptRegistry.SCHEMA_CANDIDATE_V5.equals(promptVersion)
                && InferencePromptRegistry.VISUAL_ELEMENTS_V2.equals(elementPromptVersion)
                && InferencePromptRegistry.VISUAL_HIERARCHY_V2.equals(hierarchyPromptVersion)
                && InferencePromptRegistry.VISUAL_BINDINGS_V2.equals(bindingPromptVersion)
                && ((InferencePromptRegistry.VISUAL_HINT_GENERIC_V1.equals(visualHintPackVersion)
                && profileId.endsWith("-product-v6-generic"))
                || (InferencePromptRegistry.VISUAL_HINT_TRANSIT_BOARD_V1.equals(visualHintPackVersion)
                && profileId.endsWith("-product-v6-transit-board")));
        var productPromptV7 = InferencePromptRegistry.SCHEMA_CANDIDATE_V5.equals(promptVersion)
                && InferencePromptRegistry.VISUAL_ELEMENTS_V2.equals(elementPromptVersion)
                && InferencePromptRegistry.VISUAL_HIERARCHY_V2.equals(hierarchyPromptVersion)
                && InferencePromptRegistry.VISUAL_BINDINGS_V2.equals(bindingPromptVersion)
                && InferencePromptRegistry.VISUAL_HINT_GENERIC_V1.equals(visualHintPackVersion)
                && InferencePromptRegistry.DOCUMENT_VISION_OBSERVATIONS_V1.equals(
                        documentVisionPromptVersion
                )
                && documentVisionCapabilityId != null
                && documentVisionCapabilityId.matches("[a-z0-9][a-z0-9._:-]{0,190}")
                && profileId.endsWith("-product-v7-hybrid-generic");
        var productPromptV8 = InferencePromptRegistry.SCHEMA_CANDIDATE_V5.equals(promptVersion)
                && InferencePromptRegistry.VISUAL_ELEMENTS_V3.equals(elementPromptVersion)
                && InferencePromptRegistry.VISUAL_HIERARCHY_V2.equals(hierarchyPromptVersion)
                && InferencePromptRegistry.VISUAL_BINDINGS_V2.equals(bindingPromptVersion)
                && InferencePromptRegistry.VISUAL_HINT_GENERIC_V1.equals(visualHintPackVersion)
                && profileId.endsWith("-product-v8-generic");
        var productPromptV9 = InferencePromptRegistry.SCHEMA_CANDIDATE_V5.equals(promptVersion)
                && InferencePromptRegistry.VISUAL_ELEMENTS_V3.equals(elementPromptVersion)
                && InferencePromptRegistry.VISUAL_HIERARCHY_V3.equals(hierarchyPromptVersion)
                && InferencePromptRegistry.VISUAL_BINDINGS_V2.equals(bindingPromptVersion)
                && InferencePromptRegistry.VISUAL_HINT_GENERIC_V1.equals(visualHintPackVersion)
                && profileId.endsWith("-product-v9-generic");
        var productPromptV10 = InferencePromptRegistry.SCHEMA_CANDIDATE_V5.equals(promptVersion)
                && InferencePromptRegistry.VISUAL_ELEMENTS_V4.equals(elementPromptVersion)
                && InferencePromptRegistry.VISUAL_HIERARCHY_V3.equals(hierarchyPromptVersion)
                && InferencePromptRegistry.VISUAL_BINDINGS_V2.equals(bindingPromptVersion)
                && InferencePromptRegistry.VISUAL_HINT_GENERIC_V1.equals(visualHintPackVersion)
                && profileId.endsWith("-product-v10-generic");
        var productPromptV11 = InferencePromptRegistry.SCHEMA_CANDIDATE_V5.equals(promptVersion)
                && InferencePromptRegistry.VISUAL_ELEMENTS_V5.equals(elementPromptVersion)
                && InferencePromptRegistry.VISUAL_HIERARCHY_V4.equals(hierarchyPromptVersion)
                && InferencePromptRegistry.VISUAL_BINDINGS_V3.equals(bindingPromptVersion)
                && InferencePromptRegistry.VISUAL_HINT_GENERIC_V1.equals(visualHintPackVersion)
                && profileId.endsWith("-product-v11-generic");
        var productPromptV12 = InferencePromptRegistry.SCHEMA_CANDIDATE_V5.equals(promptVersion)
                && InferencePromptRegistry.VISUAL_ELEMENTS_V6.equals(elementPromptVersion)
                && InferencePromptRegistry.VISUAL_HIERARCHY_V4.equals(hierarchyPromptVersion)
                && InferencePromptRegistry.VISUAL_BINDINGS_V3.equals(bindingPromptVersion)
                && InferencePromptRegistry.VISUAL_HINT_GENERIC_V1.equals(visualHintPackVersion)
                && profileId.endsWith("-product-v12-generic");
        var serialVisualPipeline = "renderweave-inference-pipeline/3.0".equals(pipelineVersion)
                || "renderweave-inference-pipeline/4.0".equals(pipelineVersion)
                || "renderweave-inference-pipeline/4.1".equals(pipelineVersion)
                || "renderweave-inference-pipeline/4.2".equals(pipelineVersion);
        if (!serialVisualPipeline
                && (elementPromptVersion != null || hierarchyPromptVersion != null || bindingPromptVersion != null)) {
            throw new IllegalArgumentException("Serial visual prompts are exclusive to visual pipelines 3 and 4");
        }
        if (!("renderweave-inference-pipeline/4.1".equals(pipelineVersion)
                || "renderweave-inference-pipeline/4.2".equals(pipelineVersion))
                && visualHintPackVersion != null) {
            throw new IllegalArgumentException("Visual hint packs are exclusive to grounded visual pipelines");
        }
        if ("renderweave-inference-pipeline/4.2".equals(pipelineVersion)
                != (documentVisionCapabilityId != null && documentVisionPromptVersion != null)) {
            throw new IllegalArgumentException("Document vision identity is exclusive and required for pipeline 4.2");
        }
        var productPrompt = "USER_CONFIRMED".equals(inputClassification)
                && (("renderweave-inference-pipeline/2.0".equals(pipelineVersion)
                && (productPromptV1 || productPromptV2))
                || ("renderweave-inference-pipeline/3.0".equals(pipelineVersion)
                && (productPromptV3 || productPromptV4))
                || ("renderweave-inference-pipeline/4.0".equals(pipelineVersion)
                && productPromptV5)
                || ("renderweave-inference-pipeline/4.1".equals(pipelineVersion)
                && (productPromptV6 || productPromptV8 || productPromptV9
                || productPromptV10 || productPromptV11 || productPromptV12))
                || ("renderweave-inference-pipeline/4.2".equals(pipelineVersion)
                && productPromptV7));
        if (!(legacySyntheticPrompt || productPrompt)
                || !"JSON_OBJECT".equals(responseFormat)
                || thinkingEnabled || toolsAllowed || remoteMediaAllowed) {
            throw new IllegalArgumentException("Structured-output and least-capability policy is fixed");
        }
        if (!("SYNTHETIC_ONLY".equals(inputClassification)
                || "USER_CONFIRMED".equals(inputClassification))) {
            throw new IllegalArgumentException("Live input classification is not approved");
        }
        var maximumApprovedCalls = serialVisualPipeline ? 5 : 3;
        if (maximumTotalCalls > maximumApprovedCalls || maximumEstimatedCostMicrosCny <= 0
                || inputMicrosCnyPerMillionTokens <= 0 || outputMicrosCnyPerMillionTokens <= 0) {
            throw new IllegalArgumentException("Live call and cost budgets must be bounded and priced");
        }
        if (productPromptV3 && stageTimeoutSeconds != 90
                || productPromptV4 && stageTimeoutSeconds != 240
                || productPromptV5 && stageTimeoutSeconds != 240
                || productPromptV6 && stageTimeoutSeconds != 240
                || productPromptV7 && stageTimeoutSeconds != 240
                || productPromptV8 && stageTimeoutSeconds != 240) {
            throw new IllegalArgumentException("Product serial profile timeout must match its immutable version");
        }
        if (!pricingEffectiveDate.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw new IllegalArgumentException("pricingEffectiveDate must be an ISO date");
        }
        if (!("EXPERIMENTAL".equals(certification) || "CERTIFIED".equals(certification))) {
            throw new IllegalArgumentException("Live profiles must be experimental or certified");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
