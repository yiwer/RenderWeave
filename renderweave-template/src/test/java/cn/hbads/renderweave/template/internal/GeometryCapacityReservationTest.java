package cn.hbads.renderweave.template.internal;

import cn.hbads.renderweave.template.api.DesignDslAuthority;
import cn.hbads.renderweave.template.api.DesignInputExpressionCapacityAuthority;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeometryCapacityReservationTest {

    private static final String MIN_POSITIVE_SCALE_64 =
            "0.0000000000000000000000000000000000000000000000000000000000000001";

    @Test
    void canvasTrimObservesEachAxisAtThePublicAdmissionSeam() {
        var recording = new RecordingAuthority();

        assertInstanceOf(
                DesignDslAuthority.Admitted.class,
                new CanonicalDesignDslAuthority(recording)
                        .admit(canvas("1e-64", "297.00"))
        );

        assertEquals(
                List.of(
                        observation(MIN_POSITIVE_SCALE_64),
                        observation("297")
                ),
                recording.geometryObservations()
        );
    }

    @Test
    void canvasTrimAtTheBoundaryRejectsWithItsExactPropertyIdentity() {
        assertGeometryRejected(
                new CanonicalDesignDslAuthority().admit(canvas("0", "297")),
                "/designRoot/widthMm"
        );
    }

    @Test
    void defaultAuthorityEnforcesBelowAtAndScale64AboveForBothAxes() {
        assertGeometryRejected(
                new CanonicalDesignDslAuthority().admit(canvas("-1e-64", "297")),
                "/designRoot/widthMm"
        );
        assertGeometryRejected(
                new CanonicalDesignDslAuthority().admit(canvas("210", "0")),
                "/designRoot/heightMm"
        );
        assertInstanceOf(
                DesignDslAuthority.Admitted.class,
                new CanonicalDesignDslAuthority()
                        .admit(canvas("1e-64", "1e-64"))
        );
    }

    @Test
    void authorityRejectInvalidAndThrowFailClosedAtTheExactCanvasAxis() {
        for (var mode : FailureMode.values()) {
            var authority = new FailingAuthority(mode);

            assertGeometryRejected(
                    new CanonicalDesignDslAuthority(authority)
                            .admit(canvas("210", "297")),
                    "/designRoot/widthMm"
            );
            assertEquals(List.of(observation("210")), authority.geometryObservations());
        }
    }

    @Test
    void canonicalExpansionDominatesBeforeAllocatingAnOversizedGeometryObservation() {
        var recording = new RecordingAuthority();

        var rejected = assertInstanceOf(
                DesignDslAuthority.Rejected.class,
                new CanonicalDesignDslAuthority(recording)
                        .admit(canvas("1e16777216", "297"))
        );

        assertEquals(DesignDslAuthority.FailureCode.DESIGN_DSL_LIMIT_EXCEEDED, rejected.code());
        assertEquals(DesignDslAuthority.FailureStage.DESIGN_CANONICAL_COUNT, rejected.stage());
        assertEquals(DesignDslAuthority.Limit.CANONICAL_BYTES, rejected.limit().orElseThrow());
        assertEquals(List.of(), recording.geometryObservations());
    }

    private static byte[] canvas(String widthMm, String heightMm) {
        return ("""
                {
                  "dslVersion":"renderweave-design/1.0",
                  "expressionProfile":"renderweave-expression/1.0",
                  "displayName":"Geometry capacity",
                  "definitions":[],
                  "designRoot":{
                    "nodeId":"00000000-0000-4000-8000-000000000001",
                    "kind":"canvas",
                    "widthMm":%s,
                    "heightMm":%s,
                    "bindings":[],
                    "children":[]
                  }
                }
                """).formatted(widthMm, heightMm).getBytes(StandardCharsets.UTF_8);
    }

    private static DesignInputExpressionCapacityAuthority.Observation observation(
            String observedValue
    ) {
        return new DesignInputExpressionCapacityAuthority.Observation(
                "geometry.canvasTrimMmPerAxisExclusiveMin",
                observedValue
        );
    }

    private static void assertGeometryRejected(
            DesignDslAuthority.Admission admission,
            String pointer
    ) {
        var rejected = assertInstanceOf(DesignDslAuthority.Rejected.class, admission);
        assertEquals("DESIGN_PROPERTY_CONSTRAINT_INVALID", rejected.code().name());
        assertEquals("DESIGN_PROPERTY_AND_AGGREGATE_VALIDATION", rejected.stage().name());
        assertEquals(pointer, rejected.pointer());
        assertEquals(
                "geometry.canvasTrimMmPerAxisExclusiveMin",
                rejected.limit().map(DesignDslAuthority.Limit::id).orElse(null)
        );
    }

    private static final class RecordingAuthority
            implements DesignInputExpressionCapacityAuthority {
        private final List<Observation> observations = new ArrayList<>();

        @Override
        public Decision evaluate(Observation observation) {
            observations.add(observation);
            return CanonicalDesignInputExpressionCapacityAuthority.INSTANCE.evaluate(observation);
        }

        private List<Observation> geometryObservations() {
            return observations.stream()
                    .filter(observation -> observation.limitId().startsWith("geometry."))
                    .toList();
        }
    }

    private enum FailureMode {
        REJECT,
        INVALID,
        THROW
    }

    private static final class FailingAuthority
            implements DesignInputExpressionCapacityAuthority {
        private final FailureMode mode;
        private final List<Observation> observations = new ArrayList<>();

        private FailingAuthority(FailureMode mode) {
            this.mode = mode;
        }

        @Override
        public Decision evaluate(Observation observation) {
            if (!observation.limitId().startsWith("geometry.")) {
                return CanonicalDesignInputExpressionCapacityAuthority.INSTANCE
                        .evaluate(observation);
            }
            observations.add(observation);
            return switch (mode) {
                case REJECT -> new Rejected(new Terminal(
                        "DESIGN_PROPERTY_CONSTRAINT_INVALID",
                        "DESIGN_PROPERTY_AND_AGGREGATE_VALIDATION",
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
                case INVALID -> new Invalid(InvalidReason.INVALID_OBSERVED_VALUE);
                case THROW -> throw new IllegalStateException("capacity unavailable");
            };
        }

        private List<Observation> geometryObservations() {
            assertTrue(observations.size() <= 1, "failure must stop before height reservation");
            return List.copyOf(observations);
        }
    }
}
