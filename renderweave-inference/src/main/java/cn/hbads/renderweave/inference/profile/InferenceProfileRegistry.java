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
            "inference-profiles/dashscope-qwen37-plus-20260526-v1.json",
            "inference-profiles/dashscope-qwen37-plus-20260526-prompt-v2.json",
            "inference-profiles/dashscope-qwen37-plus-20260526-grounded-v1.json",
            "inference-profiles/dashscope-qwen38-max-v1.json",
            "inference-profiles/dashscope-qwen37-flash-product-v1.json",
            "inference-profiles/dashscope-qwen37-plus-product-v1.json",
            "inference-profiles/dashscope-qwen38-max-product-v1.json",
            "inference-profiles/dashscope-qwen37-max-20260608-product-v1.json",
            "inference-profiles/dashscope-qwen37-flash-product-v2.json",
            "inference-profiles/dashscope-qwen37-plus-product-v2.json",
            "inference-profiles/dashscope-qwen38-max-product-v2.json",
            "inference-profiles/dashscope-qwen37-max-20260608-product-v2.json",
            "inference-profiles/dashscope-qwen37-flash-product-v3.json",
            "inference-profiles/dashscope-qwen37-plus-product-v3.json",
            "inference-profiles/dashscope-qwen38-max-product-v3.json",
            "inference-profiles/dashscope-qwen37-max-20260608-product-v3.json",
            "inference-profiles/dashscope-qwen37-flash-product-v4.json",
            "inference-profiles/dashscope-qwen37-plus-product-v4.json",
            "inference-profiles/dashscope-qwen38-max-product-v4.json",
            "inference-profiles/dashscope-qwen37-max-20260608-product-v4.json",
            "inference-profiles/dashscope-qwen37-flash-product-v5.json",
            "inference-profiles/dashscope-qwen37-plus-product-v5.json",
            "inference-profiles/dashscope-qwen38-max-product-v5.json",
            "inference-profiles/dashscope-qwen37-flash-product-v6-generic.json",
            "inference-profiles/dashscope-qwen37-plus-product-v6-generic.json",
            "inference-profiles/dashscope-qwen38-max-product-v6-generic.json",
            "inference-profiles/dashscope-qwen37-flash-product-v6-transit-board.json",
            "inference-profiles/dashscope-qwen37-plus-product-v6-transit-board.json",
            "inference-profiles/dashscope-qwen38-max-product-v6-transit-board.json",
            "inference-profiles/dashscope-qwen37-flash-product-v7-hybrid-generic.json",
            "inference-profiles/dashscope-qwen37-plus-product-v7-hybrid-generic.json",
            "inference-profiles/dashscope-qwen38-max-product-v7-hybrid-generic.json",
            "inference-profiles/dashscope-qwen37-flash-product-v8-generic.json",
            "inference-profiles/dashscope-qwen37-plus-product-v8-generic.json",
            "inference-profiles/dashscope-qwen38-max-product-v8-generic.json",
            "inference-profiles/dashscope-qwen37-flash-product-v9-generic.json",
            "inference-profiles/dashscope-qwen37-plus-product-v9-generic.json",
            "inference-profiles/dashscope-qwen38-max-product-v9-generic.json",
            "inference-profiles/dashscope-qwen37-flash-product-v10-generic.json",
            "inference-profiles/dashscope-qwen37-plus-product-v10-generic.json",
            "inference-profiles/dashscope-qwen38-max-product-v10-generic.json",
            "inference-profiles/dashscope-qwen37-flash-product-v11-generic.json",
            "inference-profiles/dashscope-qwen37-plus-product-v11-generic.json",
            "inference-profiles/dashscope-qwen38-max-product-v11-generic.json",
            "inference-profiles/dashscope-qwen37-flash-product-v12-generic.json",
            "inference-profiles/dashscope-qwen37-plus-product-v12-generic.json",
            "inference-profiles/dashscope-qwen38-max-product-v12-generic.json",
            "inference-profiles/dashscope-qwen37-flash-20260715-product-v13-generic.json",
            "inference-profiles/dashscope-qwen37-flash-20260715-product-v14-generic.json",
            "inference-profiles/dashscope-qwen37-plus-product-v14-generic.json",
            "inference-profiles/dashscope-qwen38-max-product-v14-generic.json",
            "inference-profiles/dashscope-qwen37-flash-20260715-product-v15-generic.json",
            "inference-profiles/dashscope-qwen37-plus-product-v15-generic.json",
            "inference-profiles/dashscope-qwen38-max-product-v15-generic.json",
            "inference-profiles/dashscope-qwen37-flash-20260715-product-v16-generic.json",
            "inference-profiles/dashscope-qwen37-plus-product-v16-generic.json",
            "inference-profiles/dashscope-qwen38-max-product-v16-generic.json",
            "inference-profiles/dashscope-qwen37-flash-20260715-product-v17-generic.json",
            "inference-profiles/dashscope-qwen37-plus-product-v17-generic.json",
            "inference-profiles/dashscope-qwen38-max-product-v17-generic.json",
            "inference-profiles/dashscope-qwen37-flash-20260715-product-v18-generic.json",
            "inference-profiles/dashscope-qwen37-plus-product-v18-generic.json",
            "inference-profiles/dashscope-qwen38-max-product-v18-generic.json",
            "inference-profiles/dashscope-qwen37-flash-20260715-product-v19-generic.json",
            "inference-profiles/dashscope-qwen37-plus-product-v19-generic.json",
            "inference-profiles/dashscope-qwen38-max-product-v19-generic.json",
            "inference-profiles/dashscope-qwen37-flash-20260715-product-v20-generic.json",
            "inference-profiles/dashscope-qwen37-plus-product-v20-generic.json",
            "inference-profiles/dashscope-qwen38-max-product-v20-generic.json",
            "inference-profiles/dashscope-qwen37-flash-20260715-product-v21-generic.json",
            "inference-profiles/dashscope-qwen37-plus-product-v21-generic.json",
            "inference-profiles/dashscope-qwen38-max-product-v21-generic.json",
            "inference-profiles/dashscope-qwen37-flash-20260715-product-v22-generic.json",
            "inference-profiles/dashscope-qwen37-plus-product-v22-generic.json",
            "inference-profiles/dashscope-qwen38-max-product-v22-generic.json",
            "inference-profiles/dashscope-qwen37-flash-20260715-product-v23-hybrid-generic.json",
            "inference-profiles/dashscope-qwen37-plus-product-v23-hybrid-generic.json",
            "inference-profiles/dashscope-qwen38-max-product-v23-hybrid-generic.json",
            "inference-profiles/dashscope-qwen37-flash-20260715-product-v24-hybrid-generic.json",
            "inference-profiles/dashscope-qwen37-plus-product-v24-hybrid-generic.json",
            "inference-profiles/dashscope-qwen38-max-product-v24-hybrid-generic.json"
    );
    private static final java.util.List<String> PRODUCT_LIVE_PROFILE_IDS = java.util.List.of(
            "dashscope-qwen37-flash-product-v4",
            "dashscope-qwen37-plus-product-v4",
            "dashscope-qwen38-max-product-v4",
            "dashscope-qwen37-max-20260608-product-v4"
    );
    private static final java.util.List<String> VISUAL_NEXT_PROFILE_IDS = java.util.List.of(
            "dashscope-qwen37-flash-product-v5",
            "dashscope-qwen37-plus-product-v5",
            "dashscope-qwen38-max-product-v5"
    );
    private static final java.util.List<String> VISUAL_GROUNDING_PROFILE_IDS = java.util.List.of(
            "dashscope-qwen37-flash-product-v6-generic",
            "dashscope-qwen37-plus-product-v6-generic",
            "dashscope-qwen38-max-product-v6-generic",
            "dashscope-qwen37-flash-product-v6-transit-board",
            "dashscope-qwen37-plus-product-v6-transit-board",
            "dashscope-qwen38-max-product-v6-transit-board",
            "dashscope-qwen37-flash-product-v8-generic",
            "dashscope-qwen37-plus-product-v8-generic",
            "dashscope-qwen38-max-product-v8-generic",
            "dashscope-qwen37-flash-product-v9-generic",
            "dashscope-qwen37-plus-product-v9-generic",
            "dashscope-qwen38-max-product-v9-generic",
            "dashscope-qwen37-flash-product-v10-generic",
            "dashscope-qwen37-plus-product-v10-generic",
            "dashscope-qwen38-max-product-v10-generic",
            "dashscope-qwen37-flash-product-v11-generic",
            "dashscope-qwen37-plus-product-v11-generic",
            "dashscope-qwen38-max-product-v11-generic",
            "dashscope-qwen37-flash-product-v12-generic",
            "dashscope-qwen37-plus-product-v12-generic",
            "dashscope-qwen38-max-product-v12-generic",
            "dashscope-qwen37-flash-20260715-product-v13-generic",
            "dashscope-qwen37-flash-20260715-product-v14-generic",
            "dashscope-qwen37-plus-product-v14-generic",
            "dashscope-qwen38-max-product-v14-generic",
            "dashscope-qwen37-flash-20260715-product-v15-generic",
            "dashscope-qwen37-plus-product-v15-generic",
            "dashscope-qwen38-max-product-v15-generic",
            "dashscope-qwen37-flash-20260715-product-v16-generic",
            "dashscope-qwen37-plus-product-v16-generic",
            "dashscope-qwen38-max-product-v16-generic",
            "dashscope-qwen37-flash-20260715-product-v17-generic",
            "dashscope-qwen37-plus-product-v17-generic",
            "dashscope-qwen38-max-product-v17-generic",
            "dashscope-qwen37-flash-20260715-product-v18-generic",
            "dashscope-qwen37-plus-product-v18-generic",
            "dashscope-qwen38-max-product-v18-generic",
            "dashscope-qwen37-flash-20260715-product-v19-generic",
            "dashscope-qwen37-plus-product-v19-generic",
            "dashscope-qwen38-max-product-v19-generic",
            "dashscope-qwen37-flash-20260715-product-v20-generic",
            "dashscope-qwen37-plus-product-v20-generic",
            "dashscope-qwen38-max-product-v20-generic",
            "dashscope-qwen37-flash-20260715-product-v21-generic",
            "dashscope-qwen37-plus-product-v21-generic",
            "dashscope-qwen38-max-product-v21-generic",
            "dashscope-qwen37-flash-20260715-product-v22-generic",
            "dashscope-qwen37-plus-product-v22-generic",
            "dashscope-qwen38-max-product-v22-generic",
            "dashscope-qwen37-flash-20260715-product-v23-hybrid-generic",
            "dashscope-qwen37-plus-product-v23-hybrid-generic",
            "dashscope-qwen38-max-product-v23-hybrid-generic",
            "dashscope-qwen37-flash-20260715-product-v24-hybrid-generic",
            "dashscope-qwen37-plus-product-v24-hybrid-generic",
            "dashscope-qwen38-max-product-v24-hybrid-generic"
    );
    private static final java.util.List<String> VISUAL_HYBRID_PROFILE_IDS = java.util.List.of(
            "dashscope-qwen37-flash-product-v7-hybrid-generic",
            "dashscope-qwen37-plus-product-v7-hybrid-generic",
            "dashscope-qwen38-max-product-v7-hybrid-generic",
            "dashscope-qwen37-flash-20260715-product-v23-hybrid-generic",
            "dashscope-qwen37-plus-product-v23-hybrid-generic",
            "dashscope-qwen38-max-product-v23-hybrid-generic",
            "dashscope-qwen37-flash-20260715-product-v24-hybrid-generic",
            "dashscope-qwen37-plus-product-v24-hybrid-generic",
            "dashscope-qwen38-max-product-v24-hybrid-generic"
    );
    private static final ObjectMapper JSON = JsonMapper.builder(
                    JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private final Map<String, ProfileResource> profiles;
    private final VisualModelCapabilityRegistry visualCapabilities;

    public InferenceProfileRegistry() {
        this(InferenceProfileRegistry.class.getClassLoader());
    }

    InferenceProfileRegistry(ClassLoader classLoader) {
        visualCapabilities = new VisualModelCapabilityRegistry(classLoader);
        var replay = load(classLoader, REPLAY_RESOURCE);
        if (!"REPLAY".equals(replay.profile().provider()) || replay.profile().networkAllowed()) {
            throw new IllegalStateException("Replay profile must make network access impossible by contract");
        }
        var loaded = new LinkedHashMap<String, ProfileResource>();
        add(loaded, replay);
        LIVE_RESOURCES.stream().map(path -> load(classLoader, path)).forEach(resource -> add(loaded, resource));
        profiles = java.util.Collections.unmodifiableMap(loaded);
        for (var profileId : java.util.stream.Stream.of(
                VISUAL_NEXT_PROFILE_IDS, VISUAL_GROUNDING_PROFILE_IDS, VISUAL_HYBRID_PROFILE_IDS
        ).flatMap(java.util.Collection::stream).toList()) {
            var profile = require(profileId).profile();
            visualCapabilities.requireModel(profile.model()).capability().requireCompatible(profile);
        }
    }

    public ProfileResource require(String profileId) {
        var profile = profiles.get(profileId);
        if (profile == null) throw new IllegalArgumentException("Unknown inference profile: " + profileId);
        return profile;
    }

    public java.util.Set<String> profileIds() {
        return profiles.keySet();
    }

    /** Product-visible profiles are deliberately separate from immutable evaluation profiles. */
    public java.util.List<ProfileResource> productLiveProfiles() {
        return PRODUCT_LIVE_PROFILE_IDS.stream().map(this::require).toList();
    }

    public boolean isProductLiveProfile(String profileId) {
        return PRODUCT_LIVE_PROFILE_IDS.contains(profileId);
    }

    /** Experimental pipeline-4 Profiles are withheld from the product selector until quality gates pass. */
    public java.util.List<VisualNextProfileResource> visualNextProfiles() {
        return VISUAL_NEXT_PROFILE_IDS.stream().map(this::require).map(profile ->
                new VisualNextProfileResource(
                        profile,
                        visualCapabilities.requireModel(profile.profile().model())
                )
        ).toList();
    }

    public boolean isVisualNextProfile(String profileId) {
        return VISUAL_NEXT_PROFILE_IDS.contains(profileId);
    }

    /** Grounded visual profiles keep immutable policy and hint identities separate. */
    public java.util.List<VisualNextProfileResource> visualGroundingProfiles() {
        return VISUAL_GROUNDING_PROFILE_IDS.stream().map(this::require).map(profile ->
                new VisualNextProfileResource(
                        profile,
                        visualCapabilities.requireModel(profile.profile().model())
                )
        ).toList();
    }

    public boolean isVisualGroundingProfile(String profileId) {
        return VISUAL_GROUNDING_PROFILE_IDS.contains(profileId);
    }

    /** Hybrid profiles bind one exact local OCR/layout capability to an immutable visual policy. */
    public java.util.List<VisualNextProfileResource> visualHybridProfiles() {
        return VISUAL_HYBRID_PROFILE_IDS.stream().map(this::require).map(profile ->
                new VisualNextProfileResource(
                        profile,
                        visualCapabilities.requireModel(profile.profile().model())
                )
        ).toList();
    }

    public boolean isVisualHybridProfile(String profileId) {
        return VISUAL_HYBRID_PROFILE_IDS.contains(profileId);
    }

    /** Parses the immutable snapshot stored with a run instead of silently substituting the latest registry entry. */
    public InferenceProfile parseSnapshot(String snapshotJson) {
        if (snapshotJson == null || snapshotJson.isBlank()) {
            throw new IllegalArgumentException("Inference profile snapshot is required");
        }
        try {
            return JSON.readValue(snapshotJson, InferenceProfile.class);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Inference profile snapshot is invalid", exception);
        }
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

    public record VisualNextProfileResource(
            ProfileResource profile,
            VisualModelCapabilityRegistry.CapabilityResource capability
    ) { }
}
