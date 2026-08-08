package cn.hbads.renderweave.inference.provider;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** In-memory provider result. Callers persist only the parsed Candidate and safe attempt metadata. */
public record ProviderInferenceResponse(
        String candidateJson,
        String providerRequestId,
        String model,
        ProviderUsage usage,
        String finishReason
) {
    private static final int MAX_OUTPUT_BYTES = 2 * 1024 * 1024;
    private static final String SAFE_ID = "[A-Za-z0-9._/-]{1,200}";

    public ProviderInferenceResponse {
        if (candidateJson == null || candidateJson.isBlank()
                || candidateJson.getBytes(StandardCharsets.UTF_8).length > MAX_OUTPUT_BYTES) {
            throw new IllegalArgumentException("candidateJson must be present and within 2 MiB");
        }
        if (providerRequestId == null || !providerRequestId.matches(SAFE_ID)) {
            throw new IllegalArgumentException("providerRequestId contains unsafe characters");
        }
        if (model == null || !model.matches("[A-Za-z0-9._/-]{1,128}")) {
            throw new IllegalArgumentException("model contains unsafe characters");
        }
        Objects.requireNonNull(usage, "usage");
        if (finishReason == null || !finishReason.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("finishReason contains unsafe characters");
        }
    }
}
