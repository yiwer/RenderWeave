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
        String pipelineVersion,
        String candidateContractVersion,
        List<InferenceMode> supportedModes,
        int lowConfidenceThresholdBps,
        int maximumRepairRounds,
        int maximumTotalCalls,
        int stageTimeoutSeconds,
        int maximumOutputBytes,
        String certification
) {
    public InferenceProfile {
        profileVersion = requireText(profileVersion, "profileVersion");
        profileId = requireText(profileId, "profileId");
        provider = requireText(provider, "provider");
        model = requireText(model, "model");
        pipelineVersion = requireText(pipelineVersion, "pipelineVersion");
        candidateContractVersion = requireText(candidateContractVersion, "candidateContractVersion");
        supportedModes = List.copyOf(Objects.requireNonNull(supportedModes, "supportedModes"));
        certification = requireText(certification, "certification");
        if (lowConfidenceThresholdBps < 0 || lowConfidenceThresholdBps > 10_000) {
            throw new IllegalArgumentException("lowConfidenceThresholdBps must be 0..10000");
        }
        if (maximumRepairRounds < 0 || maximumRepairRounds > 2) {
            throw new IllegalArgumentException("maximumRepairRounds must be 0..2");
        }
        if (maximumTotalCalls < 1 || stageTimeoutSeconds < 1 || maximumOutputBytes < 1) {
            throw new IllegalArgumentException("Profile budgets must be positive");
        }
        if (supportedModes.isEmpty()) throw new IllegalArgumentException("supportedModes must not be empty");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
