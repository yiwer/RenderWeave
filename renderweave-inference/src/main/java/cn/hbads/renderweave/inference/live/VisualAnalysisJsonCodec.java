package cn.hbads.renderweave.inference.live;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Set;

final class VisualAnalysisJsonCodec {
    private static final int MAX_BYTES = 256 * 1024;
    private static final ObjectMapper JSON = JsonMapper.builder(
                    JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    VisualElementInventory parseElements(String value, Set<String> imageArtifactIds) {
        try {
            requireBounded(value);
            var result = JSON.readValue(value, VisualElementInventory.class);
            result.requireKnownArtifacts(imageArtifactIds);
            return result;
        } catch (InvalidVisualAnalysisException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid("VISUAL_ELEMENTS_CONTRACT_INVALID", exception);
        }
    }

    VisualHierarchyPlan parseHierarchy(String value, VisualElementInventory inventory) {
        try {
            requireBounded(value);
            var result = JSON.readValue(value, VisualHierarchyPlan.class);
            result.requireConsistentWith(inventory);
            return result;
        } catch (InvalidVisualAnalysisException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid("VISUAL_HIERARCHY_CONTRACT_INVALID", exception);
        }
    }

    VisualElementBindingPlan parseBindings(
            String value,
            VisualElementInventory inventory,
            VisualHierarchyPlan hierarchy
    ) {
        try {
            requireBounded(value);
            var result = JSON.readValue(value, VisualElementBindingPlan.class);
            result.requireConsistentWith(inventory, hierarchy);
            return result;
        } catch (InvalidVisualAnalysisException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid("VISUAL_BINDINGS_CONTRACT_INVALID", exception);
        }
    }

    String write(Object value) {
        try {
            var result = JSON.writeValueAsString(value);
            requireBounded(result);
            return result;
        } catch (Exception exception) {
            throw invalid("VISUAL_ANALYSIS_ENCODING_INVALID", exception);
        }
    }

    private static void requireBounded(String value) {
        if (value == null || value.isBlank()
                || value.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("Visual analysis JSON exceeds its boundary");
        }
    }

    private static InvalidVisualAnalysisException invalid(String code, Throwable cause) {
        return new InvalidVisualAnalysisException(code, "Visual analysis output is invalid", cause);
    }
}
