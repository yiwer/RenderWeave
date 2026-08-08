package cn.hbads.renderweave.inference.provider;

import cn.hbads.renderweave.inference.profile.InferenceProfile;
import cn.hbads.renderweave.inference.run.InferenceStage;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ProviderInferenceRequest(
        UUID runId,
        int attemptOrdinal,
        InferenceStage stage,
        InferenceProfile profile,
        String systemPrompt,
        String taskJson,
        List<ProviderImage> images
) {
    private static final int MAX_PROMPT_BYTES = 64 * 1024;
    private static final int MAX_TASK_BYTES = 2 * 1024 * 1024;

    public ProviderInferenceRequest {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(stage, "stage");
        if (stage != InferenceStage.STRUCTURE && stage != InferenceStage.REPAIR) {
            throw new IllegalArgumentException("Provider calls are limited to STRUCTURE and REPAIR");
        }
        Objects.requireNonNull(profile, "profile");
        if (!profile.networkAllowed() || !"DASHSCOPE".equals(profile.provider())) {
            throw new IllegalArgumentException("Provider request requires an approved live profile");
        }
        if (attemptOrdinal < 0 || attemptOrdinal >= profile.maximumTotalCalls()) {
            throw new IllegalArgumentException("attemptOrdinal exceeds the profile call budget");
        }
        systemPrompt = requireBounded(systemPrompt, "systemPrompt", MAX_PROMPT_BYTES);
        if (!systemPrompt.contains("JSON")) {
            throw new IllegalArgumentException("systemPrompt must explicitly request JSON");
        }
        taskJson = requireBounded(taskJson, "taskJson", MAX_TASK_BYTES);
        images = List.copyOf(Objects.requireNonNull(images, "images"));
        if (images.size() > 10) throw new IllegalArgumentException("At most 10 images are allowed");
    }

    private static String requireBounded(String value, String name, int maximumBytes) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        if (value.getBytes(StandardCharsets.UTF_8).length > maximumBytes) {
            throw new IllegalArgumentException(name + " exceeds its byte budget");
        }
        return value;
    }
}
