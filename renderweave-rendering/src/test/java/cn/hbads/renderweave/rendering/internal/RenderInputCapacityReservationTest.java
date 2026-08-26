package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.rendering.api.EvaluationStage;
import cn.hbads.renderweave.rendering.api.RenderingProblem;
import cn.hbads.renderweave.template.api.DesignInputExpressionCapacityAuthority;
import cn.hbads.renderweave.template.api.DesignInputExpressionCapacityAuthority.Accepted;
import cn.hbads.renderweave.template.api.DesignInputExpressionCapacityAuthority.Invalid;
import cn.hbads.renderweave.template.api.DesignInputExpressionCapacityAuthority.InvalidReason;
import cn.hbads.renderweave.template.api.DesignInputExpressionCapacityAuthority.Observation;
import cn.hbads.renderweave.template.api.DesignInputExpressionCapacityAuthority.Rejected;
import cn.hbads.renderweave.template.api.DesignInputExpressionCapacityAuthority.Terminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderInputCapacityReservationTest {

    private static final String DEFINITION_ID = "00000000-0000-4000-8000-0000000000d1";
    private static final Terminal LIMIT_TERMINAL = new Terminal(
            "RENDER_INPUT_LIMIT_EXCEEDED",
            "RENDER_INPUT_ADMISSION",
            "INPUT_ADMISSION",
            "ZERO_EVALUATION_DOCUMENT_OUTPUT",
            List.of(
                    "capabilityStates=0",
                    "evaluations=0",
                    "renderDocuments=0",
                    "engineCommands=0",
                    "renderOutputs=0"
            )
    );

    @Test
    void derivesAllEightNumericAndCollectionObservationsAtProductReservationPoints() {
        var authority = new RecordingAuthority(null, null, false);
        var body = ("{\"rootDocument\":{\"name\":\"é\",\"values\":[10,20]},"
                + "\"customValues\":[{\"definitionId\":\"" + DEFINITION_ID
                + "\",\"value\":\"x\"}]}").getBytes(StandardCharsets.UTF_8);

        var result = RenderInputEnvelope.parse(body, authority);

        assertInstanceOf(RenderInputEnvelope.EnvelopeAdmitted.class, result);
        assertObserved(authority, "renderInput.utf8Bytes", Integer.toString(body.length));
        assertObserved(authority, "renderInput.jsonDepth", "3");
        assertObserved(authority, "renderInput.objectMembers", "2");
        assertObserved(authority, "renderInput.arrayItems", "2");
        assertObserved(authority, "renderInput.totalValuesAndContainers", "10");
        assertObserved(authority, "renderInput.stringUtf8Bytes", "2");
        assertObserved(authority, "renderInput.numberTokenBytes", "2");
        assertObserved(authority, "renderInput.customValueEntries", "1");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedReservations")
    void authorityRejectionMapsExactLimitAndTerminal(ReservationCase reservation) {
        var authority = new RecordingAuthority(
                reservation.limitId(), reservation.observedValue(), false);

        var result = RenderInputEnvelope.parse(reservation.body(), authority);

        var rejected = assertInstanceOf(RenderInputEnvelope.EnvelopeRejected.class, result);
        var problem = rejected.problems().get(0);
        assertEquals(RenderingProblem.ProblemCode.RENDER_INPUT_LIMIT_EXCEEDED, problem.code());
        assertEquals(EvaluationStage.INPUT_ADMISSION, problem.stage());
        assertEquals(reservation.limitId(), problem.limitId().orElseThrow().value());
        assertObserved(authority, reservation.limitId(), reservation.observedValue());
    }

    @Test
    void invalidAuthorityDecisionFailsClosedWithoutPretendingAProductLimitWasReached() {
        var authority = new RecordingAuthority("renderInput.utf8Bytes", null, true);

        var result = RenderInputEnvelope.parse(bytes("{\"rootDocument\":{}}"), authority);

        var rejected = assertInstanceOf(RenderInputEnvelope.EnvelopeRejected.class, result);
        var problem = rejected.problems().get(0);
        assertEquals(RenderingProblem.ProblemCode.RENDER_INTERNAL_ERROR, problem.code());
        assertEquals(EvaluationStage.INPUT_ADMISSION, problem.stage());
        assertTrue(problem.limitId().isEmpty());
    }

    private static Stream<ReservationCase> rejectedReservations() {
        var customEntries = "{\"rootDocument\":{},\"customValues\":["
                + "{\"definitionId\":\"" + DEFINITION_ID + "\",\"value\":1},"
                + "{\"definitionId\":\"00000000-0000-4000-8000-0000000000d2\",\"value\":2}]}";
        var minimal = bytes("{\"rootDocument\":{}}");
        return Stream.of(
                new ReservationCase("utf8Bytes", minimal,
                        "renderInput.utf8Bytes", Integer.toString(minimal.length)),
                new ReservationCase("jsonDepth", bytes("{\"rootDocument\":{\"a\":[1]}}"),
                        "renderInput.jsonDepth", "3"),
                new ReservationCase("objectMembers", bytes("{\"rootDocument\":{\"a\":1,\"b\":2}}"),
                        "renderInput.objectMembers", "2"),
                new ReservationCase("arrayItems", bytes("{\"rootDocument\":{\"a\":[1,2]}}"),
                        "renderInput.arrayItems", "2"),
                new ReservationCase("totalValuesAndContainers", bytes("{\"rootDocument\":{\"a\":[1]}}"),
                        "renderInput.totalValuesAndContainers", "4"),
                new ReservationCase("stringUtf8Bytes", bytes("{\"rootDocument\":{\"a\":\"é\"}}"),
                        "renderInput.stringUtf8Bytes", "2"),
                new ReservationCase("numberTokenBytes", bytes("{\"rootDocument\":{\"a\":10}}"),
                        "renderInput.numberTokenBytes", "2"),
                new ReservationCase("customValueEntries", bytes(customEntries),
                        "renderInput.customValueEntries", "2")
        );
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void assertObserved(
            RecordingAuthority authority,
            String limitId,
            String observedValue
    ) {
        assertTrue(
                authority.observations.contains(new Observation(limitId, observedValue)),
                () -> "missing observation " + limitId + "=" + observedValue
                        + " in " + authority.observations
        );
    }

    private record ReservationCase(
            String name,
            byte[] body,
            String limitId,
            String observedValue
    ) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static final class RecordingAuthority
            implements DesignInputExpressionCapacityAuthority {
        private final String rejectedLimitId;
        private final String rejectedObservedValue;
        private final boolean invalid;
        private final List<Observation> observations = new ArrayList<>();

        private RecordingAuthority(
                String rejectedLimitId,
                String rejectedObservedValue,
                boolean invalid
        ) {
            this.rejectedLimitId = rejectedLimitId;
            this.rejectedObservedValue = rejectedObservedValue;
            this.invalid = invalid;
        }

        @Override
        public Decision evaluate(Observation observation) {
            observations.add(observation);
            if (observation.limitId().equals(rejectedLimitId)
                    && (rejectedObservedValue == null
                    || observation.observedValue().equals(rejectedObservedValue))) {
                return invalid
                        ? new Invalid(InvalidReason.INVALID_OBSERVED_VALUE)
                        : new Rejected(LIMIT_TERMINAL);
            }
            return new Accepted();
        }
    }
}
