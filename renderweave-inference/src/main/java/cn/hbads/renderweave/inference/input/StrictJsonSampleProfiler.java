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
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Produces bounded structural context; sample values are deliberately not retained. */
public final class StrictJsonSampleProfiler {
    public static final int MAX_DEPTH = 32;
    public static final String PROFILE_VERSION = "renderweave-json-profile/1.2";
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
            root.put("profileVersion", PROFILE_VERSION);
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
                var nodes = new TreeMap<String, NodeProfile>();
                collect(parsed, "", "", 1, nodes);
                var outputNodes = sample.putArray("nodes");
                for (var node : nodes.values()) {
                    var output = outputNodes.addObject();
                    output.put("pointer", node.pointer());
                    var kinds = output.putArray("kinds");
                    node.kinds().forEach(kinds::add);
                    if (!node.itemKinds().isEmpty()) {
                        var itemKinds = output.putArray("itemKinds");
                        node.itemKinds().forEach(itemKinds::add);
                    }
                    output.put("occurrences", node.occurrences());
                    output.put("evidencePointer", node.evidencePointer());
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

    private static void collect(
            JsonNode node,
            String normalizedPointer,
            String evidencePointer,
            int depth,
            Map<String, NodeProfile> output
    ) {
        if (depth > MAX_DEPTH) {
            throw new InvalidInferenceInputException(
                    "INFERENCE_JSON_DEPTH_EXCEEDED", "/jsonSamples",
                    java.util.Map.of("maximumDepth", MAX_DEPTH),
                    "An inference sample exceeds nesting depth 32", null
            );
        }
        if (node.isObject()) {
            record(output, normalizedPointer, "object", List.of(), evidencePointer);
            var fields = new ArrayList<>(node.properties());
            fields.sort(Map.Entry.comparingByKey());
            for (var field : fields) {
                var structuralSegment = JsonStructuralPointer.objectSegment(field.getKey());
                var evidenceSegment = escapeEvidenceSegment(field.getKey());
                collect(field.getValue(), normalizedPointer + "/" + structuralSegment,
                        evidencePointer + "/" + evidenceSegment, depth + 1, output);
            }
        } else if (node.isArray()) {
            var itemKinds = new TreeSet<String>();
            node.values().forEach(item -> itemKinds.add(kind(item)));
            record(output, normalizedPointer, "array", List.copyOf(itemKinds), evidencePointer);
            var index = 0;
            for (var item : node.values()) {
                collect(item, normalizedPointer + "/*", evidencePointer + "/" + index,
                        depth + 1, output);
                index++;
            }
        } else {
            record(output, normalizedPointer, kind(node), List.of(), evidencePointer);
        }
    }

    private static void record(
            Map<String, NodeProfile> output,
            String pointer,
            String kind,
            List<String> itemKinds,
            String evidencePointer
    ) {
        var existing = output.get(pointer);
        if (existing == null) {
            output.put(pointer, new NodeProfile(
                    pointer, new TreeSet<>(List.of(kind)), new TreeSet<>(itemKinds), 1, evidencePointer
            ));
            return;
        }
        existing.kinds().add(kind);
        existing.itemKinds().addAll(itemKinds);
        output.put(pointer, new NodeProfile(
                pointer, existing.kinds(), existing.itemKinds(), existing.occurrences() + 1,
                existing.evidencePointer()
        ));
    }

    private static String kind(JsonNode node) {
        if (node.isObject()) return "object";
        if (node.isArray()) return "array";
        if (node.isString()) return "text";
        if (node.isNumber()) return "decimal";
        if (node.isBoolean()) return "boolean";
        if (node.isNull()) return "null";
        return "unknown";
    }

    private static String escapeEvidenceSegment(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private record NodeProfile(
            String pointer,
            TreeSet<String> kinds,
            TreeSet<String> itemKinds,
            int occurrences,
            String evidencePointer
    ) { }
}
