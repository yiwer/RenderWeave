package cn.hbads.renderweave.inference.replay;

import cn.hbads.renderweave.inference.run.InferenceStage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InferenceRejectionEnvelopeTest {
    private static final String ID = "VISUAL_GROUNDING_REGION_ID_INVALID";
    private static final String PARENT_ID = "VISUAL_GROUNDING_REGION_PARENT_ID_INVALID";
    private static final String ITEM_ZERO = InferenceRejectionEnvelope
            .PARENT_CONTAINMENT_DETAIL_CODES.get(0);
    private static final String NON_ITEM_AMBIGUOUS = InferenceRejectionEnvelope
            .PARENT_CONTAINMENT_DETAIL_CODES.get(3);
    private static final String ATOMIC_ROLLBACK = InferenceRejectionEnvelope
            .PARENT_CONTAINMENT_DETAIL_CODES.get(4);
    private static final String CONTAINMENT_UNCLASSIFIED = InferenceRejectionEnvelope
            .PARENT_CONTAINMENT_DETAIL_CODES.get(5);

    @Test
    void acceptsOnlyCanonicalBoundedMixedOrEmptyUnclassifiedEnvelopes() {
        var mixed = new InferenceRejectionEnvelope(
                InferenceRejectionEnvelope.MIXED_REGION_FIELDS_PRIMARY_CODE,
                InferenceStage.OBSERVE,
                List.of(ID, PARENT_ID)
        );
        var unclassified = new InferenceRejectionEnvelope(
                InferenceRejectionEnvelope.UNCLASSIFIED_REGION_PRIMARY_CODE,
                InferenceStage.OBSERVE,
                List.of()
        );

        assertEquals(2, mixed.detailCodeCount());
        assertEquals(Map.of(ID, 1, PARENT_ID, 1), mixed.detailCodeCounts());
        assertEquals(0, unclassified.detailCodeCount());
        assertEquals(Map.of(), unclassified.detailCodeCounts());
        assertThrows(IllegalArgumentException.class, () -> new InferenceRejectionEnvelope(
                InferenceRejectionEnvelope.MIXED_REGION_FIELDS_PRIMARY_CODE,
                InferenceStage.OBSERVE,
                List.of(PARENT_ID, ID)
        ));
        assertThrows(IllegalArgumentException.class, () -> new InferenceRejectionEnvelope(
                InferenceRejectionEnvelope.MIXED_REGION_FIELDS_PRIMARY_CODE,
                InferenceStage.OBSERVE,
                List.of(ID, ID)
        ));
        assertThrows(IllegalArgumentException.class, () -> new InferenceRejectionEnvelope(
                InferenceRejectionEnvelope.UNCLASSIFIED_REGION_PRIMARY_CODE,
                InferenceStage.OBSERVE,
                List.of(ID)
        ));
        assertThrows(IllegalArgumentException.class, () -> new InferenceRejectionEnvelope(
                InferenceRejectionEnvelope.UNCLASSIFIED_REGION_PRIMARY_CODE,
                InferenceStage.HIERARCHY,
                List.of()
        ));
    }

    @Test
    void strictCodecRoundTripsWithoutAcceptingDrift() {
        var codec = new InferenceRejectionEnvelopeJsonCodec();
        var envelope = new InferenceRejectionEnvelope(
                InferenceRejectionEnvelope.MIXED_REGION_FIELDS_PRIMARY_CODE,
                InferenceStage.OBSERVE,
                List.of(ID, PARENT_ID)
        );
        var json = codec.write(envelope);

        assertEquals(envelope, codec.parse(json));
        assertThrows(IllegalArgumentException.class, () -> codec.parse(json.replace(
                "\"primaryCode\":", "\"unknown\":1,\"primaryCode\":"
        )));
        assertThrows(IllegalArgumentException.class, () -> codec.parse(json.replace(
                "\"detailCodeCount\":2", "\"detailCodeCount\":3"
        )));
        assertThrows(IllegalArgumentException.class, () -> codec.parse(json.replace(
                "\"primaryCode\":", "\"primaryCode\":\"DUPLICATE\",\"primaryCode\":"
        )));
        assertThrows(IllegalArgumentException.class, () -> codec.parse("""
                {"primaryCode":"VISUAL_GROUNDING_REGION_UNCLASSIFIED",
                 "earliestStage":"OBSERVE","detailCodes":[]}
                """));
        assertThrows(IllegalArgumentException.class, () -> codec.parse("""
                {"primaryCode":"VISUAL_GROUNDING_REGION_UNCLASSIFIED",
                 "earliestStage":"OBSERVE","detailCodes":[],"detailCodeCount":null}
                """));
    }

    @Test
    void acceptsOnlyCanonicalBoundedParentContainmentDetails() {
        var classified = new InferenceRejectionEnvelope(
                InferenceRejectionEnvelope.PARENT_CONTAINMENT_PRIMARY_CODE,
                InferenceStage.OBSERVE,
                List.of(ITEM_ZERO, NON_ITEM_AMBIGUOUS)
        );

        assertEquals(2, classified.detailCodeCount());
        assertEquals(Map.of(ITEM_ZERO, 1, NON_ITEM_AMBIGUOUS, 1),
                classified.detailCodeCounts());
        assertEquals(classified, new InferenceRejectionEnvelopeJsonCodec().parse(
                new InferenceRejectionEnvelopeJsonCodec().write(classified)
        ));
        assertThrows(IllegalArgumentException.class, () -> new InferenceRejectionEnvelope(
                InferenceRejectionEnvelope.PARENT_CONTAINMENT_PRIMARY_CODE,
                InferenceStage.OBSERVE,
                List.of(NON_ITEM_AMBIGUOUS, ITEM_ZERO)
        ));
        assertThrows(IllegalArgumentException.class, () -> new InferenceRejectionEnvelope(
                InferenceRejectionEnvelope.PARENT_CONTAINMENT_PRIMARY_CODE,
                InferenceStage.OBSERVE,
                List.of(ITEM_ZERO, ITEM_ZERO)
        ));
        assertThrows(IllegalArgumentException.class, () -> new InferenceRejectionEnvelope(
                InferenceRejectionEnvelope.PARENT_CONTAINMENT_PRIMARY_CODE,
                InferenceStage.OBSERVE,
                List.of(ATOMIC_ROLLBACK, ITEM_ZERO)
        ));
        assertThrows(IllegalArgumentException.class, () -> new InferenceRejectionEnvelope(
                InferenceRejectionEnvelope.PARENT_CONTAINMENT_PRIMARY_CODE,
                InferenceStage.OBSERVE,
                List.of(CONTAINMENT_UNCLASSIFIED, ITEM_ZERO)
        ));
        assertThrows(IllegalArgumentException.class, () -> new InferenceRejectionEnvelope(
                InferenceRejectionEnvelope.PARENT_CONTAINMENT_PRIMARY_CODE,
                InferenceStage.OBSERVE,
                List.of()
        ));
    }

    @Test
    void attemptRequiresEnvelopeAndDetailTelemetryToAgree() {
        var envelope = new InferenceRejectionEnvelope(
                InferenceRejectionEnvelope.MIXED_REGION_FIELDS_PRIMARY_CODE,
                InferenceStage.OBSERVE,
                List.of(ID, PARENT_ID)
        );
        var attempt = new InferenceAttempt(
                UUID.randomUUID(), 0, InferenceStage.OBSERVE,
                InferenceAttemptStatus.REJECTED, "LIVE_VISUAL_ANALYSIS_REJECTED",
                Optional.empty(), Optional.empty(), 0, 0, 0, 0,
                envelope.detailCodeCounts(), Optional.of(envelope), Instant.EPOCH
        );

        assertEquals(Optional.of(envelope), attempt.rejectionEnvelope());
        assertThrows(IllegalArgumentException.class, () -> new InferenceAttempt(
                UUID.randomUUID(), 0, InferenceStage.OBSERVE,
                InferenceAttemptStatus.REJECTED, "LIVE_VISUAL_ANALYSIS_REJECTED",
                Optional.empty(), Optional.empty(), 0, 0, 0, 0,
                Map.of(ID, 2, PARENT_ID, 1), Optional.of(envelope), Instant.EPOCH
        ));
        assertThrows(IllegalArgumentException.class, () -> new InferenceAttempt(
                UUID.randomUUID(), 0, InferenceStage.OBSERVE,
                InferenceAttemptStatus.SUCCEEDED, "LIVE_VISUAL_ANALYSIS_REJECTED",
                Optional.empty(), Optional.empty(), 0, 0, 0, 0,
                envelope.detailCodeCounts(), Optional.of(envelope), Instant.EPOCH
        ));
    }
}
