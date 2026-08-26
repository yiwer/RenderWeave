package cn.hbads.renderweave.template.internal;

import cn.hbads.renderweave.template.api.DesignInputExpressionCapacityAuthority;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DesignDslCapacityReservationTest {

    @Test
    void parserDerivesAllEightObservationsAtItsProductReservationPoints() throws Exception {
        var recording = new RecordingAuthority(null, null);
        var raw = "{\"name\":\"é\",\"items\":[123]}".getBytes(StandardCharsets.UTF_8);

        new StrictJsonParser(recording).parse(raw);

        assertEquals(
                List.of(
                        observation("designDslParser.rawUtf8Bytes", Integer.toString(raw.length)),
                        observation("designDslParser.totalValuesAndContainers", "1"),
                        observation("designDslParser.jsonDepth", "1"),
                        observation("designDslParser.objectMembers", "1"),
                        observation("designDslParser.memberNameUtf8Bytes", "4"),
                        observation("designDslParser.totalValuesAndContainers", "2"),
                        observation("designDslParser.stringUtf8Bytes", "2"),
                        observation("designDslParser.objectMembers", "2"),
                        observation("designDslParser.memberNameUtf8Bytes", "5"),
                        observation("designDslParser.totalValuesAndContainers", "3"),
                        observation("designDslParser.jsonDepth", "2"),
                        observation("designDslParser.arrayItems", "1"),
                        observation("designDslParser.totalValuesAndContainers", "4"),
                        observation("designDslParser.numberTokenBytes", "3")
                ),
                recording.observations
        );
    }

    @Test
    void parserMapsTheSharedAuthorityRejectionToItsExistingPublicLimit() {
        var recording = new RecordingAuthority("designDslParser.objectMembers", "1");

        var failure = assertThrows(
                DesignDslFailureException.class,
                () -> new StrictJsonParser(recording).parse("{\"a\":1}".getBytes(StandardCharsets.UTF_8))
        );

        assertEquals(
                cn.hbads.renderweave.template.api.DesignDslAuthority.Limit.OBJECT_MEMBERS,
                failure.rejection().limit().orElseThrow()
        );
    }

    @Test
    void canonicalWriterDerivesFinalBytesBeforeAllocationAndUsesTheSameAuthority() {
        var recording = new RecordingAuthority("designDslParser.canonicalBytes", "3");

        assertThrows(
                CanonicalJsonWriter.CanonicalLimitException.class,
                () -> new CanonicalJsonWriter(recording).write(new JsonValue.StringValue("a"))
        );

        assertEquals(
                List.of(
                        observation("designDslParser.canonicalBytes", "3")
                ),
                recording.observations
        );
    }

    private static DesignInputExpressionCapacityAuthority.Observation observation(
            String limitId,
            String observedValue
    ) {
        return new DesignInputExpressionCapacityAuthority.Observation(limitId, observedValue);
    }

    private static final class RecordingAuthority
            implements DesignInputExpressionCapacityAuthority {
        private final String rejectedLimitId;
        private final String rejectedObservedValue;
        private final List<Observation> observations = new ArrayList<>();

        private RecordingAuthority(String rejectedLimitId, String rejectedObservedValue) {
            this.rejectedLimitId = rejectedLimitId;
            this.rejectedObservedValue = rejectedObservedValue;
        }

        @Override
        public Decision evaluate(Observation observation) {
            observations.add(observation);
            if (observation.limitId().equals(rejectedLimitId)
                    && observation.observedValue().equals(rejectedObservedValue)) {
                return new Rejected(new Terminal(
                        "DESIGN_DSL_LIMIT_EXCEEDED",
                        "DESIGN_PARSE",
                        "TEMPLATE_CLOSURE",
                        "ZERO_WRITE_AND_DOWNSTREAM",
                        List.of(
                                "templateWrites=0",
                                "assetWrites=0",
                                "evaluationStarts=0",
                                "renderDocuments=0",
                                "renderOutputs=0"
                        )
                ));
            }
            return new Accepted();
        }
    }
}
