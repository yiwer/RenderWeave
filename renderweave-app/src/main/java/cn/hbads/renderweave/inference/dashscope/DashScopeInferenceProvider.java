package cn.hbads.renderweave.inference.dashscope;

import cn.hbads.renderweave.inference.provider.InferenceProvider;
import cn.hbads.renderweave.inference.provider.ProviderCallException;
import cn.hbads.renderweave.inference.provider.ProviderInferenceRequest;
import cn.hbads.renderweave.inference.provider.ProviderInferenceResponse;
import cn.hbads.renderweave.inference.provider.ProviderNotConfiguredException;
import cn.hbads.renderweave.inference.provider.ProviderUsage;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class DashScopeInferenceProvider implements InferenceProvider {
    private static final int MAX_HTTP_RESPONSE_BYTES = 3 * 1024 * 1024;

    private final Optional<DashScopeApiKey> apiKey;
    private final DashScopeHttpTransport transport;
    private final ObjectMapper json;

    DashScopeInferenceProvider(
            Optional<DashScopeApiKey> apiKey,
            DashScopeHttpTransport transport,
            ObjectMapper json
    ) {
        this.apiKey = apiKey == null ? Optional.empty() : apiKey;
        this.transport = java.util.Objects.requireNonNull(transport, "transport");
        this.json = java.util.Objects.requireNonNull(json, "json");
    }

    public static DashScopeInferenceProvider fromConfiguration(
            String directApiKey,
            String apiKeyFile,
            ObjectMapper json
    ) {
        return new DashScopeInferenceProvider(
                DashScopeApiKey.resolve(directApiKey, apiKeyFile),
                new JdkDashScopeHttpTransport(),
                json
        );
    }

    @Override
    public ProviderInferenceResponse complete(ProviderInferenceRequest request) {
        var credential = apiKey.orElseThrow(
                () -> new ProviderNotConfiguredException("DASHSCOPE_NOT_CONFIGURED")
        );
        var profile = request.profile();
        var headers = new LinkedHashMap<String, String>();
        headers.put("Authorization", credential.authorizationHeader());
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        var response = transport.exchange(
                URI.create(profile.providerEndpoint()),
                Map.copyOf(headers),
                encodeRequest(request),
                Duration.ofSeconds(profile.stageTimeoutSeconds())
        );
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw httpFailure(response);
        }
        if (response.body().length > MAX_HTTP_RESPONSE_BYTES) {
            throw new ProviderCallException(
                    "DASHSCOPE_RESPONSE_TOO_LARGE", false, response.statusCode(), Optional.empty(), null
            );
        }
        return decodeResponse(request, response.body());
    }

    @Override
    public boolean configured() {
        return apiKey.isPresent();
    }

    private byte[] encodeRequest(ProviderInferenceRequest request) {
        try {
            var root = json.createObjectNode();
            root.put("model", request.profile().model());
            var messages = root.putArray("messages");
            messages.addObject().put("role", "system").put("content", request.systemPrompt());
            var content = messages.addObject().put("role", "user").putArray("content");
            for (var image : request.images()) {
                var media = content.addObject();
                media.put("type", "image_url");
                media.putObject("image_url").put(
                        "url",
                        "data:" + image.mediaType() + ";base64,"
                                + Base64.getEncoder().encodeToString(image.bytes())
                );
            }
            content.addObject().put("type", "text").put("text", request.taskJson());
            root.putObject("response_format").put("type", "json_object");
            root.put("enable_thinking", false);
            root.put("max_tokens", request.profile().maximumOutputTokens());
            root.put("stream", false);
            return json.writeValueAsBytes(root);
        } catch (JacksonException exception) {
            throw new IllegalStateException("DashScope request could not be encoded", exception);
        }
    }

    private ProviderInferenceResponse decodeResponse(ProviderInferenceRequest request, byte[] body) {
        try {
            var root = json.readTree(body);
            var choice = required(root.path("choices").path(0), "choices[0]");
            var candidateJson = requiredText(choice.path("message").path("content"), "message.content");
            if (candidateJson.getBytes(StandardCharsets.UTF_8).length
                    > request.profile().maximumOutputBytes()) {
                throw new ProviderCallException(
                        "DASHSCOPE_CANDIDATE_TOO_LARGE", false, 200, Optional.empty(), null
                );
            }
            var usage = required(root.path("usage"), "usage");
            return new ProviderInferenceResponse(
                    candidateJson,
                    requiredText(root.path("id"), "id"),
                    requiredText(root.path("model"), "model"),
                    new ProviderUsage(
                            requiredLong(usage.path("prompt_tokens"), "usage.prompt_tokens"),
                            requiredLong(usage.path("completion_tokens"), "usage.completion_tokens")
                    ),
                    requiredText(choice.path("finish_reason"), "finish_reason")
            );
        } catch (ProviderCallException exception) {
            throw exception;
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new ProviderCallException(
                    "DASHSCOPE_RESPONSE_INVALID", false, 200, Optional.empty(), exception
            );
        }
    }

    private static JsonNode required(JsonNode node, String name) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return node;
    }

    private static String requiredText(JsonNode node, String name) {
        required(node, name);
        if (!node.isString() || node.asText().isBlank()) {
            throw new IllegalArgumentException(name + " must be text");
        }
        return node.asText();
    }

    private static long requiredLong(JsonNode node, String name) {
        required(node, name);
        if (!node.isIntegralNumber() || !node.canConvertToLong() || node.asLong() < 0) {
            throw new IllegalArgumentException(name + " must be a non-negative integer");
        }
        return node.asLong();
    }

    private static ProviderCallException httpFailure(DashScopeHttpResponse response) {
        var status = response.statusCode();
        var retryable = status == 408 || status == 429 || status >= 500;
        return new ProviderCallException(
                "DASHSCOPE_HTTP_" + status,
                retryable,
                status,
                retryAfter(response.headers()),
                null
        );
    }

    private static Optional<Duration> retryAfter(Map<String, java.util.List<String>> headers) {
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase("retry-after"))
                .flatMap(entry -> entry.getValue().stream())
                .findFirst()
                .flatMap(value -> {
                    try {
                        var seconds = Long.parseLong(value);
                        if (seconds < 0 || seconds > 300) return Optional.empty();
                        return Optional.of(Duration.ofSeconds(seconds));
                    } catch (NumberFormatException ignored) {
                        return Optional.empty();
                    }
                });
    }
}
