package cn.hbads.renderweave.inference.profile;

import cn.hbads.renderweave.inference.candidate.CandidateValidationContext;
import cn.hbads.renderweave.inference.candidate.CandidateValidator;
import cn.hbads.renderweave.inference.input.InferenceInput;
import cn.hbads.renderweave.inference.input.StrictJsonSampleProfiler;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonStructuralProfilerTest {

    private final StrictJsonSampleProfiler reducer = new StrictJsonSampleProfiler();
    private final JsonStructuralProfiler profiler = new JsonStructuralProfiler();

    @Test
    void normalizationIsValueFreeCompactAndIndependentOfObjectMemberOrder() {
        var left = reducer.profile(List.of(json("{\"name\":\"secret-value\",\"count\":12}")));
        var right = reducer.profile(List.of(json("{\"count\":99,\"name\":\"another-secret\"}")));

        var leftText = new String(left, StandardCharsets.UTF_8);
        assertFalse(leftText.contains("secret-value"));
        assertTrue(leftText.contains("renderweave-json-profile/1.2"));

        var leftProfile = profiler.profile(left);
        var rightProfile = profiler.profile(right);
        assertEquals(leftProfile.nodes().stream().map(node -> List.of(node.pointer(), node.kinds())).toList(),
                rightProfile.nodes().stream().map(node -> List.of(node.pointer(), node.kinds())).toList());
    }

    @Test
    void mergesCrossSampleKindsPresenceAndExactEvidenceLocations() {
        var artifact = reducer.profile(List.of(
                json("{\"value\":12,\"items\":[{\"name\":\"A\"},{\"name\":\"B\"}]}"),
                json("{\"value\":\"twelve\",\"items\":[{\"name\":\"C\"}]}"),
                json("{\"items\":[]}")
        ));

        var profile = profiler.profile(artifact);
        var value = node(profile, "/value");
        assertEquals(java.util.Set.of("decimal", "text"), value.kinds());
        assertEquals(2, value.samplesPresent());
        assertEquals(List.of(
                new JsonEvidenceLocation(0, "/value"),
                new JsonEvidenceLocation(1, "/value")
        ), value.evidence());

        var itemName = node(profile, "/items/*/name");
        assertEquals(2, itemName.samplesPresent());
        assertEquals(3, itemName.occurrences());
        assertEquals("/items/0/name", itemName.evidence().getFirst().jsonPointer());
        assertFalse(profile.nodes().stream().anyMatch(node -> node.pointer().contains("/items/1")));
    }

    @Test
    void literalAsteriskUsesAReversibleStructuralSegmentDistinctFromArrayWildcard() {
        var artifact = reducer.profile(List.of(json(
                "{\"*\":{\"*\":\"root\"},\"items\":[{\"*\":\"row\"}],\"~2\":true}"
        )));

        var profile = profiler.profile(artifact);

        assertEquals("/*", node(profile, "/~2").evidence().getFirst().jsonPointer());
        assertEquals("/*/*", node(profile, "/~2/~2").evidence().getFirst().jsonPointer());
        assertEquals("/items/0/*", node(profile, "/items/*/~2").evidence().getFirst().jsonPointer());
        assertEquals("/~02", node(profile, "/~02").evidence().getFirst().jsonPointer());
    }

    @Test
    void readsLegacyProfilesThatDoNotUseTheLiteralAsteriskEscape() {
        var legacy = """
                {
                  "profileVersion": "renderweave-json-profile/1.1",
                  "sampleCount": 1,
                  "samples": [
                    {
                      "index": 0,
                      "nodes": [
                        {"pointer":"","kinds":["object"],"itemKinds":[],"occurrences":1,"evidencePointer":""},
                        {"pointer":"/a*b","kinds":["text"],"itemKinds":[],"occurrences":1,"evidencePointer":"/a*b"},
                        {"pointer":"/items","kinds":["array"],"itemKinds":["object"],"occurrences":1,"evidencePointer":"/items"},
                        {"pointer":"/items/*","kinds":["object"],"itemKinds":[],"occurrences":1,"evidencePointer":"/items/0"},
                        {"pointer":"/items/*/*","kinds":["decimal"],"itemKinds":[],"occurrences":1,"evidencePointer":"/items/0/*"}
                      ]
                    }
                  ]
                }
                """;

        var profile = profiler.profile(legacy.getBytes(StandardCharsets.UTF_8));
        var candidate = new JsonCandidateProfiler().infer(
                java.util.UUID.fromString("90000000-0000-0000-0000-000000000001"),
                "legacy-json", "Legacy JSON", profile
        ).candidate();
        var problems = new CandidateValidator().validate(
                candidate,
                CandidateValidationContext.trustedReplayOutput(java.util.Set.of(), profile, 8_000)
        );

        assertEquals(java.util.Set.of("text"), node(profile, "/a~2b").kinds());
        assertEquals(java.util.Set.of("object"), node(profile, "/items/*").kinds());
        assertEquals(java.util.Set.of("decimal"), node(profile, "/items/*/~2").kinds());
        assertEquals(List.of("a*b", "items"), candidate.schemas().getFirst().fields().stream()
                .map(field -> field.proposedFieldKey()).toList());
        assertEquals(List.of("*"), candidate.schemas().get(1).fields().stream()
                .map(field -> field.proposedFieldKey()).toList());
        assertTrue(problems.isEmpty(), () -> "Unexpected legacy alignment problems: " + problems);
    }

    private static JsonObservedNode node(JsonStructuralProfile profile, String pointer) {
        return profile.nodes().stream().filter(node -> node.pointer().equals(pointer)).findFirst().orElseThrow();
    }

    private static InferenceInput.BinaryInput json(String value) {
        return new InferenceInput.BinaryInput(
                "sample.json", "application/json", value.getBytes(StandardCharsets.UTF_8)
        );
    }
}
