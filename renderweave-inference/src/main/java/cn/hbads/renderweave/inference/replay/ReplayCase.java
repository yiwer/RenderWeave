package cn.hbads.renderweave.inference.replay;

import cn.hbads.renderweave.inference.input.InferenceMode;

import java.util.List;

public record ReplayCase(
        String fixtureId,
        InferenceMode mode,
        String scenario,
        String rootSchemaKey,
        String displayName,
        int imageCount,
        List<String> jsonSamples,
        List<ReplayVisualSchema> visualSchemas,
        int structureFailuresBeforeSuccess,
        int expectedSchemaCount,
        List<String> expectedRootFields,
        List<String> expectedProblemCodes
) {
    public ReplayCase {
        jsonSamples = jsonSamples == null ? List.of() : List.copyOf(jsonSamples);
        visualSchemas = visualSchemas == null ? List.of() : List.copyOf(visualSchemas);
        expectedRootFields = expectedRootFields == null ? List.of() : List.copyOf(expectedRootFields);
        expectedProblemCodes = expectedProblemCodes == null ? List.of() : List.copyOf(expectedProblemCodes);
        if (fixtureId == null || fixtureId.isBlank()) throw new IllegalArgumentException("fixtureId is required");
        if (mode == null) throw new IllegalArgumentException("mode is required");
        if (scenario == null || scenario.isBlank()) throw new IllegalArgumentException("scenario is required");
        if (rootSchemaKey == null || rootSchemaKey.isBlank()) throw new IllegalArgumentException("rootSchemaKey is required");
        if (displayName == null || displayName.isBlank()) throw new IllegalArgumentException("displayName is required");
        if (structureFailuresBeforeSuccess < 0 || structureFailuresBeforeSuccess > 2) {
            throw new IllegalArgumentException("structureFailuresBeforeSuccess must be 0..2");
        }
        if (expectedSchemaCount < 1) throw new IllegalArgumentException("expectedSchemaCount must be positive");
    }
}
