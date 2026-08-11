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
    public static final String VISUAL_ELEMENTS_V2 = "renderweave-visual-elements-prompt/2.0";
    public static final String VISUAL_ELEMENTS_V3 = "renderweave-visual-elements-prompt/3.0";
    public static final String VISUAL_ELEMENTS_V4 = "renderweave-visual-elements-prompt/4.0";
    public static final String VISUAL_ELEMENTS_V5 = "renderweave-visual-elements-prompt/5.0";
    public static final String VISUAL_ELEMENTS_V6 = "renderweave-visual-elements-prompt/6.0";
    public static final String VISUAL_ELEMENTS_V7 = "renderweave-visual-elements-prompt/7.0";
    public static final String VISUAL_ELEMENTS_V8 = "renderweave-visual-elements-prompt/8.0";
    public static final String VISUAL_ELEMENTS_V9 = "renderweave-visual-elements-prompt/9.0";
    public static final String VISUAL_ELEMENTS_V10 = "renderweave-visual-elements-prompt/10.0";
    public static final String VISUAL_HIERARCHY_V2 = "renderweave-visual-hierarchy-prompt/2.0";
    public static final String VISUAL_HIERARCHY_V3 = "renderweave-visual-hierarchy-prompt/3.0";
    public static final String VISUAL_HIERARCHY_V4 = "renderweave-visual-hierarchy-prompt/4.0";
    public static final String VISUAL_HIERARCHY_V5 = "renderweave-visual-hierarchy-prompt/5.0";
    public static final String VISUAL_HIERARCHY_V6 = "renderweave-visual-hierarchy-prompt/6.0";
    public static final String VISUAL_HIERARCHY_V7 = "renderweave-visual-hierarchy-prompt/7.0";
    public static final String VISUAL_BINDINGS_V2 = "renderweave-visual-bindings-prompt/2.0";
    public static final String VISUAL_BINDINGS_V3 = "renderweave-visual-bindings-prompt/3.0";
    public static final String VISUAL_HINT_GENERIC_V1 = "renderweave-visual-hint-pack/generic/1.0";
    public static final String VISUAL_HINT_TRANSIT_BOARD_V1 =
            "renderweave-visual-hint-pack/transit-board/1.0";
    public static final String DOCUMENT_VISION_OBSERVATIONS_V1 =
            "renderweave-document-vision-observations-prompt/1.0";
    private static final Map<String, String> RESOURCES = Map.ofEntries(
            Map.entry(SCHEMA_CANDIDATE_V1, "inference-prompts/schema-candidate-v1.txt"),
            Map.entry(SCHEMA_CANDIDATE_V2, "inference-prompts/schema-candidate-v2.txt"),
            Map.entry(SCHEMA_CANDIDATE_V3, "inference-prompts/schema-candidate-v3.txt"),
            Map.entry(SCHEMA_CANDIDATE_V4, "inference-prompts/schema-candidate-v4.txt"),
            Map.entry(SCHEMA_CANDIDATE_V5, "inference-prompts/schema-candidate-v5.txt"),
            Map.entry(VISUAL_ELEMENTS_V1, "inference-prompts/visual-elements-v1.txt"),
            Map.entry(VISUAL_HIERARCHY_V1, "inference-prompts/visual-hierarchy-v1.txt"),
            Map.entry(VISUAL_BINDINGS_V1, "inference-prompts/visual-bindings-v1.txt"),
            Map.entry(VISUAL_ELEMENTS_V2, "inference-prompts/visual-elements-v2.txt"),
            Map.entry(VISUAL_ELEMENTS_V3, "inference-prompts/visual-elements-v3.txt"),
            Map.entry(VISUAL_ELEMENTS_V4, "inference-prompts/visual-elements-v4.txt"),
            Map.entry(VISUAL_ELEMENTS_V5, "inference-prompts/visual-elements-v5.txt"),
            Map.entry(VISUAL_ELEMENTS_V6, "inference-prompts/visual-elements-v6.txt"),
            Map.entry(VISUAL_ELEMENTS_V7, "inference-prompts/visual-elements-v7.txt"),
            Map.entry(VISUAL_ELEMENTS_V8, "inference-prompts/visual-elements-v8.txt"),
            Map.entry(VISUAL_ELEMENTS_V9, "inference-prompts/visual-elements-v9.txt"),
            Map.entry(VISUAL_ELEMENTS_V10, "inference-prompts/visual-elements-v10.txt"),
            Map.entry(VISUAL_HIERARCHY_V2, "inference-prompts/visual-hierarchy-v2.txt"),
            Map.entry(VISUAL_HIERARCHY_V3, "inference-prompts/visual-hierarchy-v3.txt"),
            Map.entry(VISUAL_HIERARCHY_V4, "inference-prompts/visual-hierarchy-v4.txt"),
            Map.entry(VISUAL_HIERARCHY_V5, "inference-prompts/visual-hierarchy-v5.txt"),
            Map.entry(VISUAL_HIERARCHY_V6, "inference-prompts/visual-hierarchy-v6.txt"),
            Map.entry(VISUAL_HIERARCHY_V7, "inference-prompts/visual-hierarchy-v7.txt"),
            Map.entry(VISUAL_BINDINGS_V2, "inference-prompts/visual-bindings-v2.txt"),
            Map.entry(VISUAL_BINDINGS_V3, "inference-prompts/visual-bindings-v3.txt")
    );
    private static final Map<String, String> HINT_RESOURCES = Map.of(
            VISUAL_HINT_GENERIC_V1, "inference-prompts/visual-hint-generic-v1.txt",
            VISUAL_HINT_TRANSIT_BOARD_V1, "inference-prompts/visual-hint-transit-board-v1.txt"
    );
    private static final Map<String, String> DOCUMENT_VISION_RESOURCES = Map.of(
            DOCUMENT_VISION_OBSERVATIONS_V1,
            "inference-prompts/document-vision-observations-v1.txt"
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
        var prompt = read(path);
        if (prompt.isBlank() || !prompt.contains("JSON") || prompt.contains("DASHSCOPE_API_KEY")) {
            throw new IllegalStateException("Inference prompt violates the safe prompt contract");
        }
        return new PromptResource(promptVersion, prompt);
    }

    public PromptResource requireVisualStage(String promptVersion, String hintPackVersion) {
        if (!java.util.Set.of(
                VISUAL_ELEMENTS_V2,
                VISUAL_ELEMENTS_V3,
                VISUAL_ELEMENTS_V4,
                VISUAL_ELEMENTS_V5,
                VISUAL_ELEMENTS_V6,
                VISUAL_ELEMENTS_V7,
                VISUAL_ELEMENTS_V8,
                VISUAL_ELEMENTS_V9,
                VISUAL_ELEMENTS_V10,
                VISUAL_HIERARCHY_V2,
                VISUAL_HIERARCHY_V3,
                VISUAL_HIERARCHY_V4,
                VISUAL_HIERARCHY_V5,
                VISUAL_HIERARCHY_V6,
                VISUAL_HIERARCHY_V7,
                VISUAL_BINDINGS_V2,
                VISUAL_BINDINGS_V3
        ).contains(promptVersion)) {
            throw new IllegalArgumentException("Visual hint packs require a grounded visual stage prompt");
        }
        var hintPath = HINT_RESOURCES.get(hintPackVersion);
        if (hintPath == null) throw new IllegalArgumentException("Unknown visual hint pack: " + hintPackVersion);
        var core = require(promptVersion).text();
        var hint = read(hintPath);
        if (hint.isBlank() || hint.contains("DASHSCOPE_API_KEY") || hint.contains("```")) {
            throw new IllegalStateException("Visual hint pack violates the safe prompt contract");
        }
        return new PromptResource(promptVersion + "+" + hintPackVersion, core + "\n\n" + hint);
    }

    public PromptResource requireHybridVisualStage(
            String promptVersion,
            String hintPackVersion,
            String documentVisionPromptVersion
    ) {
        var visual = requireVisualStage(promptVersion, hintPackVersion);
        var policyPath = DOCUMENT_VISION_RESOURCES.get(documentVisionPromptVersion);
        if (policyPath == null) {
            throw new IllegalArgumentException(
                    "Unknown document vision prompt: " + documentVisionPromptVersion
            );
        }
        var policy = read(policyPath);
        if (policy.isBlank() || !policy.contains("documentVisionObservation")
                || policy.contains("DASHSCOPE_API_KEY") || policy.contains("```")) {
            throw new IllegalStateException("Document vision prompt violates the safe prompt contract");
        }
        return new PromptResource(
                visual.promptVersion() + "+" + documentVisionPromptVersion,
                visual.text() + "\n\n" + policy
        );
    }

    private String read(String path) {
        try (var input = classLoader.getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("Missing inference prompt resource " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n").replace('\r', '\n');
        } catch (IOException exception) {
            throw new IllegalStateException("Inference prompt cannot be loaded: " + path, exception);
        }
    }

    public record PromptResource(String promptVersion, String text) { }
}
