package cn.hbads.renderweave.inference.profile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class InferencePromptRegistry {
    public static final String SCHEMA_CANDIDATE_V1 = "renderweave-schema-candidate-prompt/1.0";
    public static final String SCHEMA_CANDIDATE_V2 = "renderweave-schema-candidate-prompt/2.0";
    public static final String SCHEMA_CANDIDATE_V3 = "renderweave-schema-candidate-prompt/3.0";
    public static final String SCHEMA_CANDIDATE_V4 = "renderweave-schema-candidate-prompt/4.0";
    public static final String SCHEMA_CANDIDATE_V5 = "renderweave-schema-candidate-prompt/5.0";
    public static final String VISUAL_ELEMENTS_V1 = "renderweave-visual-elements-prompt/1.0";
    public static final String VISUAL_HIERARCHY_V1 = "renderweave-visual-hierarchy-prompt/1.0";
    public static final String VISUAL_BINDINGS_V1 = "renderweave-visual-bindings-prompt/1.0";
    private static final Map<String, String> RESOURCES = Map.ofEntries(
            Map.entry(SCHEMA_CANDIDATE_V1, "inference-prompts/schema-candidate-v1.txt"),
            Map.entry(SCHEMA_CANDIDATE_V2, "inference-prompts/schema-candidate-v2.txt"),
            Map.entry(SCHEMA_CANDIDATE_V3, "inference-prompts/schema-candidate-v3.txt"),
            Map.entry(SCHEMA_CANDIDATE_V4, "inference-prompts/schema-candidate-v4.txt"),
            Map.entry(SCHEMA_CANDIDATE_V5, "inference-prompts/schema-candidate-v5.txt"),
            Map.entry(VISUAL_ELEMENTS_V1, "inference-prompts/visual-elements-v1.txt"),
            Map.entry(VISUAL_HIERARCHY_V1, "inference-prompts/visual-hierarchy-v1.txt"),
            Map.entry(VISUAL_BINDINGS_V1, "inference-prompts/visual-bindings-v1.txt")
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
            var prompt = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n").replace('\r', '\n');
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
