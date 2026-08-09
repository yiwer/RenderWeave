package cn.hbads.renderweave.inference.profile;

import cn.hbads.renderweave.inference.input.StrictJsonSampleProfiler;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/** Merges the value-free normalization artifact into a deterministic cross-sample observation. */
public final class JsonStructuralProfiler {
    private static final ObjectMapper JSON = JsonMapper.builder(
                    JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public JsonStructuralProfile profile(byte[] artifactBytes) {
        Objects.requireNonNull(artifactBytes, "artifactBytes");
        try {
            var stored = JSON.readValue(artifactBytes, StoredProfile.class);
            if (!java.util.Set.of(
                    "renderweave-json-profile/1.1",
                    StrictJsonSampleProfiler.PROFILE_VERSION
            ).contains(stored.profileVersion())) {
                throw new InvalidJsonStructuralProfileException(
                        "Unsupported JSON structural profile version: " + stored.profileVersion(), null
                );
            }
            if (stored.sampleCount() < 1 || stored.sampleCount() > 20
                    || stored.samples() == null || stored.samples().size() != stored.sampleCount()) {
                throw new InvalidJsonStructuralProfileException("Stored sample count is inconsistent", null);
            }

            var merged = new LinkedHashMap<String, MutableNode>();
            for (var expectedIndex = 0; expectedIndex < stored.samples().size(); expectedIndex++) {
                var sample = stored.samples().get(expectedIndex);
                if (sample.index() != expectedIndex || sample.nodes() == null) {
                    throw new InvalidJsonStructuralProfileException("Stored sample ordering is invalid", null);
                }
                var sampleNodes = new LinkedHashMap<String, StoredNode>();
                for (var node : sample.nodes()) {
                    validateNode(node);
                    sampleNodes.putIfAbsent(node.pointer(), node);
                }
                var seenInSample = new java.util.HashSet<String>();
                for (var node : sample.nodes()) {
                    var pointer = "renderweave-json-profile/1.1".equals(stored.profileVersion())
                            ? canonicalizeLegacyPointer(node.pointer(), sampleNodes)
                            : node.pointer();
                    var target = merged.computeIfAbsent(pointer, MutableNode::new);
                    target.kinds.addAll(node.kinds());
                    if (node.itemKinds() != null) target.itemKinds.addAll(node.itemKinds());
                    target.occurrences += node.occurrences();
                    if (seenInSample.add(pointer)) target.samplesPresent++;
                    target.evidence.add(new JsonEvidenceLocation(sample.index(), node.evidencePointer()));
                }
            }
            var nodes = merged.values().stream()
                    .sorted(java.util.Comparator.comparing(node -> node.pointer))
                    .map(MutableNode::freeze)
                    .toList();
            if (nodes.isEmpty() || !nodes.getFirst().pointer().isEmpty()
                    || !nodes.getFirst().kinds().contains("object")) {
                throw new InvalidJsonStructuralProfileException("Root object observation is missing", null);
            }
            return new JsonStructuralProfile(stored.sampleCount(), nodes);
        } catch (InvalidJsonStructuralProfileException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidJsonStructuralProfileException("JSON structural profile is invalid", exception);
        }
    }

    private static void validateNode(StoredNode node) {
        if (node.pointer() == null || node.kinds() == null || node.kinds().isEmpty()
                || node.occurrences() < 1 || node.evidencePointer() == null) {
            throw new InvalidJsonStructuralProfileException("Stored structural node is incomplete", null);
        }
        var allowed = java.util.Set.of("object", "array", "text", "decimal", "boolean", "null");
        if (!allowed.containsAll(node.kinds())
                || node.itemKinds() != null && !allowed.containsAll(node.itemKinds())) {
            throw new InvalidJsonStructuralProfileException("Stored structural node kind is invalid", null);
        }
    }

    private static String canonicalizeLegacyPointer(
            String pointer,
            java.util.Map<String, StoredNode> nodes
    ) {
        if (pointer.isEmpty()) return pointer;
        if (!pointer.startsWith("/")) {
            throw new InvalidJsonStructuralProfileException("Stored structural pointer is invalid", null);
        }
        var canonical = new StringBuilder();
        var rawParent = "";
        for (var segment : pointer.substring(1).split("/", -1)) {
            var parent = nodes.get(rawParent);
            if (parent == null) {
                throw new InvalidJsonStructuralProfileException(
                        "Stored structural pointer parent is missing", null
                );
            }
            var objectParent = parent.kinds().contains("object");
            var arrayParent = parent.kinds().contains("array");
            if ("*".equals(segment)) {
                if (arrayParent == objectParent) {
                    throw new InvalidJsonStructuralProfileException(
                            "Stored structural pointer parent kind is ambiguous", null
                    );
                }
                canonical.append(arrayParent ? "/*" : "/~2");
            } else {
                if (!objectParent) {
                    throw new InvalidJsonStructuralProfileException(
                            "Stored object member pointer is invalid", null
                    );
                }
                canonical.append('/').append(segment.replace("*", "~2"));
            }
            rawParent = rawParent + "/" + segment;
        }
        return canonical.toString();
    }

    private record StoredProfile(
            String profileVersion,
            int sampleCount,
            List<StoredSample> samples
    ) { }

    private record StoredSample(int index, List<StoredNode> nodes) { }

    private record StoredNode(
            String pointer,
            List<String> kinds,
            List<String> itemKinds,
            int occurrences,
            String evidencePointer
    ) { }

    private static final class MutableNode {
        private final String pointer;
        private final TreeSet<String> kinds = new TreeSet<>();
        private final TreeSet<String> itemKinds = new TreeSet<>();
        private final List<JsonEvidenceLocation> evidence = new ArrayList<>();
        private int samplesPresent;
        private int occurrences;

        private MutableNode(String pointer) {
            this.pointer = pointer;
        }

        private JsonObservedNode freeze() {
            return new JsonObservedNode(
                    pointer, kinds, itemKinds, samplesPresent, occurrences, evidence
            );
        }
    }
}
