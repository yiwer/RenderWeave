package cn.hbads.renderweave.inference.profile;

import cn.hbads.renderweave.inference.input.InferenceMode;

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
                    provider, model, providerProtocol, providerEndpoint, apiKeyEnvironmentVariable,
                    promptVersion, responseFormat, thinkingEnabled, toolsAllowed, remoteMediaAllowed,
                    inputClassification, maximumTotalCalls, maximumEstimatedCostMicrosCny,
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
            String provider,
            String model,
            String providerProtocol,
            String providerEndpoint,
            String apiKeyEnvironmentVariable,
            String promptVersion,
            String responseFormat,
            boolean thinkingEnabled,
            boolean toolsAllowed,
            boolean remoteMediaAllowed,
            String inputClassification,
            int maximumTotalCalls,
            long maximumEstimatedCostMicrosCny,
            long inputMicrosCnyPerMillionTokens,
            long outputMicrosCnyPerMillionTokens,
            String pricingEffectiveDate,
            String certification
    ) {
        if (!"DASHSCOPE".equals(provider)
                || !("qwen3.7-flash".equals(model)
                || "qwen3.7-plus-2026-05-26".equals(model)
                || "qwen3.8-max".equals(model))) {
            throw new IllegalArgumentException("P5 live profiles must use an approved DashScope model");
        }
        if (!"OPENAI_CHAT_COMPLETIONS".equals(providerProtocol)
                || !DASHSCOPE_ENDPOINT.equals(providerEndpoint)) {
            throw new IllegalArgumentException("P5 provider protocol and endpoint are fixed");
        }
        if (!"DASHSCOPE_API_KEY".equals(apiKeyEnvironmentVariable)) {
            throw new IllegalArgumentException("P5 API key source is fixed");
        }
        var promptAllowed = InferencePromptRegistry.SCHEMA_CANDIDATE_V1.equals(promptVersion)
                || ("qwen3.7-plus-2026-05-26".equals(model)
                && InferencePromptRegistry.SCHEMA_CANDIDATE_V2.equals(promptVersion));
        if (!promptAllowed
                || !"JSON_OBJECT".equals(responseFormat)
                || thinkingEnabled || toolsAllowed || remoteMediaAllowed) {
            throw new IllegalArgumentException("P5 structured-output and least-capability policy is fixed");
        }
        if (!"SYNTHETIC_ONLY".equals(inputClassification)) {
            throw new IllegalArgumentException("Current P5 authorization is synthetic-only");
        }
        if (maximumTotalCalls > 3 || maximumEstimatedCostMicrosCny <= 0
                || inputMicrosCnyPerMillionTokens <= 0 || outputMicrosCnyPerMillionTokens <= 0) {
            throw new IllegalArgumentException("Live call and cost budgets must be bounded and priced");
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
