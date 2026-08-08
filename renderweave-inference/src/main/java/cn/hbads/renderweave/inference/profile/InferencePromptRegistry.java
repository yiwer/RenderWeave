package cn.hbads.renderweave.inference.profile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class InferencePromptRegistry {
    public static final String SCHEMA_CANDIDATE_V1 = "renderweave-schema-candidate-prompt/1.0";
    private static final Map<String, String> RESOURCES = Map.of(
            SCHEMA_CANDIDATE_V1, "inference-prompts/schema-candidate-v1.txt"
    );

    private final ClassLoader classLoader;

    public InferencePromptRegistry() {
        this(InferencePromptRegistry.class.getClassLoader());
    }

    InferencePromptRegistry(ClassLoader classLoader) {
        this.classLoader = java.util.Objects.requireNonNull(classLoader, "classLoader");
    }

    public PromptResource require(String promptVersion) {
        var path = RESOURCES.get(promptVersion);
        if (path == null) throw new IllegalArgumentException("Unknown inference prompt: " + promptVersion);
        try (var input = classLoader.getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("Missing inference prompt resource " + path);
            var prompt = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            if (prompt.isBlank() || !prompt.contains("JSON") || prompt.contains("DASHSCOPE_API_KEY")) {
                throw new IllegalStateException("Inference prompt violates the safe prompt contract");
            }
            return new PromptResource(promptVersion, prompt);
        } catch (IOException exception) {
            throw new IllegalStateException("Inference prompt cannot be loaded: " + path, exception);
        }
    }

    public record PromptResource(String promptVersion, String text) { }
}
