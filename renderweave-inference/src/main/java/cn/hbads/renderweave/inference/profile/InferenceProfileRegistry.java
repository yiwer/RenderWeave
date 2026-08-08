package cn.hbads.renderweave.inference.profile;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Loads immutable, repository-versioned replay and guarded live profiles. */
public final class InferenceProfileRegistry {
    private static final String REPLAY_RESOURCE = "inference-profiles/replay-v1.json";
    private static final java.util.List<String> LIVE_RESOURCES = java.util.List.of(
            "inference-profiles/dashscope-qwen37-flash-v1.json",
            "inference-profiles/dashscope-qwen38-max-v1.json"
    );
    private static final ObjectMapper JSON = JsonMapper.builder(
                    JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private final Map<String, ProfileResource> profiles;

    public InferenceProfileRegistry() {
        this(InferenceProfileRegistry.class.getClassLoader());
    }

    InferenceProfileRegistry(ClassLoader classLoader) {
        var replay = load(classLoader, REPLAY_RESOURCE);
        if (!"REPLAY".equals(replay.profile().provider()) || replay.profile().networkAllowed()) {
            throw new IllegalStateException("Replay profile must make network access impossible by contract");
        }
        var loaded = new LinkedHashMap<String, ProfileResource>();
        add(loaded, replay);
        LIVE_RESOURCES.stream().map(path -> load(classLoader, path)).forEach(resource -> add(loaded, resource));
        profiles = java.util.Collections.unmodifiableMap(loaded);
    }

    public ProfileResource require(String profileId) {
        var profile = profiles.get(profileId);
        if (profile == null) throw new IllegalArgumentException("Unknown inference profile: " + profileId);
        return profile;
    }

    public java.util.Set<String> profileIds() {
        return profiles.keySet();
    }

    private static ProfileResource load(ClassLoader classLoader, String path) {
        try (var input = classLoader.getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("Missing inference profile resource " + path);
            var bytes = input.readAllBytes();
            var profile = JSON.readValue(bytes, InferenceProfile.class);
            return new ProfileResource(profile, JSON.writeValueAsString(profile));
        } catch (IOException exception) {
            throw new IllegalStateException("Inference profile cannot be loaded: " + path, exception);
        }
    }

    private static void add(Map<String, ProfileResource> loaded, ProfileResource resource) {
        if (loaded.putIfAbsent(resource.profile().profileId(), resource) != null) {
            throw new IllegalStateException("Duplicate inference profile id " + resource.profile().profileId());
        }
    }

    public record ProfileResource(InferenceProfile profile, String snapshotJson) { }
}
