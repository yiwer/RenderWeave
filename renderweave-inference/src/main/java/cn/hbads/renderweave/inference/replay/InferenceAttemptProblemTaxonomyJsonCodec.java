package cn.hbads.renderweave.inference.replay;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Strict bounded codec for the payload-free attempt problem taxonomy. */
public final class InferenceAttemptProblemTaxonomyJsonCodec {
    public static final int MAX_BYTES = 16 * 1024;

    private static final ObjectMapper JSON = JsonMapper.builder(
                    JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    public String write(Map<String, Integer> counts) {
        try {
            var json = JSON.writeValueAsString(InferenceAttemptProblemTaxonomy.normalize(counts));
            requireBounded(json);
            return json;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Attempt problem taxonomy could not be encoded", exception);
        }
    }

    public Map<String, Integer> parse(String json) {
        try {
            requireBounded(json);
            var type = JSON.getTypeFactory().constructMapType(Map.class, String.class, Integer.class);
            return InferenceAttemptProblemTaxonomy.normalize(JSON.readValue(json, type));
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Attempt problem taxonomy is invalid", exception);
        }
    }

    private static void requireBounded(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Attempt problem taxonomy JSON is required");
        }
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("Attempt problem taxonomy JSON is too large");
        }
    }
}
