package cn.hbads.renderweave.inference.profile;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loads the three exact, immutable visual-next model capability rows. */
public final class VisualModelCapabilityRegistry {
    private static final List<String> RESOURCES = List.of(
            "inference-capabilities/dashscope-qwen37-flash-visual-v1.json",
            "inference-capabilities/dashscope-qwen37-plus-visual-v1.json",
            "inference-capabilities/dashscope-qwen38-max-visual-v1.json"
    );
    private static final ObjectMapper JSON = JsonMapper.builder(
                    JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private final Map<String, CapabilityResource> byModel;

    public VisualModelCapabilityRegistry() {
        this(VisualModelCapabilityRegistry.class.getClassLoader());
    }

    VisualModelCapabilityRegistry(ClassLoader classLoader) {
        var loaded = new LinkedHashMap<String, CapabilityResource>();
        for (var path : RESOURCES) {
            var resource = load(classLoader, path);
            if (loaded.putIfAbsent(resource.capability().model(), resource) != null) {
                throw new IllegalStateException("Duplicate visual capability model "
                        + resource.capability().model());
            }
        }
        if (!loaded.keySet().equals(java.util.Set.of(
                "qwen3.7-flash", "qwen3.7-plus", "qwen3.8-max"
        ))) {
            throw new IllegalStateException("Visual-next capability matrix must bind exactly three models");
        }
        byModel = java.util.Collections.unmodifiableMap(loaded);
    }

    public CapabilityResource requireModel(String model) {
        var result = byModel.get(model);
        if (result == null) throw new IllegalArgumentException("Unknown visual-next model capability: " + model);
        return result;
    }

    public java.util.Set<String> models() {
        return byModel.keySet();
    }

    private static CapabilityResource load(ClassLoader classLoader, String path) {
        try (var input = classLoader.getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("Missing visual capability resource " + path);
            var capability = JSON.readValue(input.readAllBytes(), VisualModelCapability.class);
            return new CapabilityResource(capability, JSON.writeValueAsString(capability));
        } catch (Exception exception) {
            throw new IllegalStateException("Visual capability cannot be loaded: " + path, exception);
        }
    }

    public record CapabilityResource(VisualModelCapability capability, String snapshotJson) { }
}
