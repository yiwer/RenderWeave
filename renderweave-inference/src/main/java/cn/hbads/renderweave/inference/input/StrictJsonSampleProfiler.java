package cn.hbads.renderweave.inference.input;

import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Produces bounded structural context; sample values are deliberately not retained. */
public final class StrictJsonSampleProfiler {
    public static final int MAX_DEPTH = 32;
    private static final ObjectMapper JSON = JsonMapper.builder(
                    JsonFactory.builder()
                            .streamReadConstraints(StreamReadConstraints.builder()
                                    .maxNestingDepth(MAX_DEPTH + 1)
                                    .maxStringLength(InputNormalizer.MAX_JSON_SAMPLE_BYTES)
                                    .maxNumberLength(InputNormalizer.MAX_JSON_SAMPLE_BYTES)
                                    .build())
                            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                            .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public byte[] profile(List<InferenceInput.BinaryInput> samples) {
        try {
            var root = JSON.createObjectNode();
            root.put("profileVersion", "renderweave-json-profile/1.0");
            root.put("sampleCount", samples.size());
            var sampleArray = root.putArray("samples");
            for (var index = 0; index < samples.size(); index++) {
                var parsed = JSON.readTree(samples.get(index).bytes());
                if (parsed == null || !parsed.isObject()) {
                    throw new InvalidInferenceInputException(
                            "INFERENCE_JSON_ROOT_INVALID", "/jsonSamples/" + index,
                            "Each inference JSON sample must have an object root"
                    );
                }
                var sample = sampleArray.addObject();
                sample.put("index", index);
                var nodes = new ArrayList<NodeProfile>();
                collect(parsed, "", 1, nodes);
                var outputNodes = sample.putArray("nodes");
                for (var node : nodes) {
                    var output = outputNodes.addObject();
                    output.put("pointer", node.pointer());
                    output.put("kind", node.kind());
                    if (!node.itemKinds().isEmpty()) {
                        var kinds = output.putArray("itemKinds");
                        node.itemKinds().forEach(kinds::add);
                    }
                }
            }
            return JSON.writeValueAsBytes(root);
        } catch (InvalidInferenceInputException exception) {
            throw exception;
        } catch (Exception exception) {
            var message = exception.getMessage() == null ? "" : exception.getMessage().toLowerCase(Locale.ROOT);
            var code = message.contains("duplicate")
                    ? "INFERENCE_JSON_DUPLICATE_MEMBER"
                    : message.contains("nesting depth")
                    ? "INFERENCE_JSON_DEPTH_EXCEEDED"
                    : "INFERENCE_JSON_INVALID";
            throw new InvalidInferenceInputException(
                    code, "/jsonSamples", java.util.Map.of("maximumDepth", MAX_DEPTH),
                    "Inference samples must use strict bounded JSON", exception
            );
        }
    }

    private static void collect(JsonNode node, String pointer, int depth, List<NodeProfile> output) {
        if (depth > MAX_DEPTH) {
            throw new InvalidInferenceInputException(
                    "INFERENCE_JSON_DEPTH_EXCEEDED", "/jsonSamples",
                    java.util.Map.of("maximumDepth", MAX_DEPTH),
                    "An inference sample exceeds nesting depth 32", null
            );
        }
        if (node.isObject()) {
            output.add(new NodeProfile(pointer, "object", List.of()));
            var fields = new ArrayList<String>();
            fields.addAll(node.propertyNames());
            fields.sort(Comparator.naturalOrder());
            for (var field : fields) {
                collect(node.get(field), pointer + "/" + escape(field), depth + 1, output);
            }
        } else if (node.isArray()) {
            var kinds = new ArrayList<String>();
            for (var item : node) {
                var kind = kind(item);
                if (!kinds.contains(kind)) kinds.add(kind);
            }
            kinds.sort(Comparator.naturalOrder());
            output.add(new NodeProfile(pointer, "array", List.copyOf(kinds)));
            for (var index = 0; index < node.size(); index++) {
                collect(node.get(index), pointer + "/" + index, depth + 1, output);
            }
        } else {
            output.add(new NodeProfile(pointer, kind(node), List.of()));
        }
    }

    private static String kind(JsonNode node) {
        if (node.isObject()) return "object";
        if (node.isArray()) return "array";
        if (node.isTextual()) return "text";
        if (node.isNumber()) return "decimal";
        if (node.isBoolean()) return "boolean";
        if (node.isNull()) return "null";
        return "unknown";
    }

    private static String escape(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private record NodeProfile(String pointer, String kind, List<String> itemKinds) { }
}
