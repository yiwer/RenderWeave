package cn.hbads.renderweave.inference.replay;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InferenceAttemptProblemTaxonomyTest {

    @Test
    void countsAreSortedImmutableAndMergeWithoutPayloadFields() {
        var first = InferenceAttemptProblemTaxonomy.count(List.of(
                "CANDIDATE_DECODE_UNKNOWN_MEMBER",
                "AI_REQUIRED_UNCONFIRMED",
                "CANDIDATE_DECODE_UNKNOWN_MEMBER"
        ));
        var merged = InferenceAttemptProblemTaxonomy.merge(List.of(
                first, Map.of("AI_REQUIRED_UNCONFIRMED", 2)
        ));

        assertEquals(List.of("AI_REQUIRED_UNCONFIRMED", "CANDIDATE_DECODE_UNKNOWN_MEMBER"),
                List.copyOf(merged.keySet()));
        assertEquals(Map.of(
                "AI_REQUIRED_UNCONFIRMED", 3,
                "CANDIDATE_DECODE_UNKNOWN_MEMBER", 2
        ), merged);
        assertThrows(UnsupportedOperationException.class,
                () -> merged.put("CANDIDATE_JSON_INVALID", 1));
    }

    @Test
    void excessiveCardinalityAndCountsAreSaturatedWithAStableMarker() {
        var codes = new ArrayList<String>();
        codes.addAll(java.util.Collections.nCopies(10_100, "REPEATED_DIAGNOSTIC"));
        for (var index = 0; index < 80; index++) {
            codes.add("SYNTHETIC_DIAGNOSTIC_" + index);
        }

        var counts = InferenceAttemptProblemTaxonomy.count(codes);

        assertEquals(InferenceAttemptProblemTaxonomy.MAX_DISTINCT_CODES, counts.size());
        assertEquals(InferenceAttemptProblemTaxonomy.MAX_COUNT_PER_CODE,
                counts.get("REPEATED_DIAGNOSTIC"));
        assertEquals(1, counts.get(InferenceAttemptProblemTaxonomy.TRUNCATED_CODE));
    }

    @Test
    void attemptRejectsUnboundedOrUnstableExternallyConstructedTaxonomy() {
        assertThrows(IllegalArgumentException.class, () -> attempt(Map.of("raw field name", 1)));
        assertThrows(IllegalArgumentException.class, () -> attempt(Map.of("VALID_CODE", 0)));
        assertThrows(IllegalArgumentException.class,
                () -> attempt(Map.of("VALID_CODE", InferenceAttemptProblemTaxonomy.MAX_COUNT_PER_CODE + 1)));
    }

    @Test
    void strictJsonCodecRoundTripsOnlyTheBoundedTaxonomy() {
        var codec = new InferenceAttemptProblemTaxonomyJsonCodec();
        var counts = Map.of("CANDIDATE_DECODE_UNKNOWN_MEMBER", 2);

        assertEquals(counts, codec.parse(codec.write(counts)));
        assertThrows(IllegalArgumentException.class,
                () -> codec.parse("{\"VALID_CODE\":1,\"VALID_CODE\":2}"));
        assertThrows(IllegalArgumentException.class,
                () -> codec.parse("{\"raw field name\":1}"));
    }

    private static InferenceAttempt attempt(Map<String, Integer> counts) {
        return new InferenceAttempt(
                UUID.randomUUID(), 0, cn.hbads.renderweave.inference.run.InferenceStage.STRUCTURE,
                InferenceAttemptStatus.REJECTED, "LIVE_OUTPUT_REJECTED",
                java.util.Optional.empty(), java.util.Optional.empty(),
                0, 0, 0, 0, counts, Instant.EPOCH
        );
    }
}
