package cn.hbads.renderweave.template.internal;

import cn.hbads.renderweave.template.api.DesignDslAuthority;
import cn.hbads.renderweave.template.api.DesignInputExpressionCapacityAuthority;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class GeometryCapacityReservationTest {

    private static final String MIN_POSITIVE_SCALE_64 =
            "0.0000000000000000000000000000000000000000000000000000000000000001";
    private static final String MIN_NEGATIVE_SCALE_64 =
            "-0.0000000000000000000000000000000000000000000000000000000000000001";
    private static final String MAX_BELOW_SCALE_64 =
            "999.9999999999999999999999999999999999999999999999999999999999999999";
    private static final String MAX_ABOVE_SCALE_64 =
            "1000.0000000000000000000000000000000000000000000000000000000000000001";
    private static final String BLEED_MAX_BELOW_SCALE_64 =
            "99.9999999999999999999999999999999999999999999999999999999999999999";
    private static final String BLEED_MAX_ABOVE_SCALE_64 =
            "100.0000000000000000000000000000000000000000000000000000000000000001";

    @Test
    void canvasTrimObservesMinimumThenMaximumForEachAxisAtThePublicAdmissionSeam() {
        var recording = new RecordingAuthority();

        assertInstanceOf(
                DesignDslAuthority.Admitted.class,
                new CanonicalDesignDslAuthority(recording)
                        .admit(canvas("1e-64", "297.00"))
        );

        assertEquals(
                List.of(
                        minObservation(MIN_POSITIVE_SCALE_64),
                        maxObservation(MIN_POSITIVE_SCALE_64),
                        minObservation("297"),
                        maxObservation("297")
                ),
                recording.geometryObservations()
        );
    }

    @Test
    void canvasBleedObservesMinimumThenMaximumForEverySideAtThePublicAdmissionSeam() {
        var recording = new RecordingAuthority();

        assertInstanceOf(
                DesignDslAuthority.Admitted.class,
                new CanonicalDesignDslAuthority(recording).admit(canvasWithBleed(
                        "1e-64",
                        "-0.00",
                        "2.500",
                        "10"
                ))
        );

        assertEquals(
                List.of(
                        bleedMinObservation(MIN_POSITIVE_SCALE_64),
                        bleedMaxObservation(MIN_POSITIVE_SCALE_64),
                        bleedMinObservation("0"),
                        bleedMaxObservation("0"),
                        bleedMinObservation("2.5"),
                        bleedMaxObservation("2.5"),
                        bleedMinObservation("10"),
                        bleedMaxObservation("10")
                ),
                recording.bleedObservations()
        );
    }

    @Test
    void absentCanvasBleedDoesNotReserveBleedCapacity() {
        var recording = new RecordingAuthority();

        assertInstanceOf(
                DesignDslAuthority.Admitted.class,
                new CanonicalDesignDslAuthority(recording).admit(canvas("210", "297"))
        );

        assertEquals(List.of(), recording.bleedObservations());
    }

    @Test
    void defaultAuthorityEnforcesBleedMinimumForEverySide() {
        assertInstanceOf(
                DesignDslAuthority.Admitted.class,
                new CanonicalDesignDslAuthority().admit(canvasWithBleed(
                        "0",
                        "1e-64",
                        "-0.00",
                        "100"
                ))
        );
        assertBleedMinimumRejected(
                canvasWithBleed(MIN_NEGATIVE_SCALE_64, "0", "0", "0"),
                "/designRoot/bleed/topMm"
        );
        assertBleedMinimumRejected(
                canvasWithBleed("0", MIN_NEGATIVE_SCALE_64, "0", "0"),
                "/designRoot/bleed/rightMm"
        );
        assertBleedMinimumRejected(
                canvasWithBleed("0", "0", MIN_NEGATIVE_SCALE_64, "0"),
                "/designRoot/bleed/bottomMm"
        );
        assertBleedMinimumRejected(
                canvasWithBleed("0", "0", "0", MIN_NEGATIVE_SCALE_64),
                "/designRoot/bleed/leftMm"
        );
    }

    @Test
    void defaultAuthorityEnforcesBleedMaximumForEverySide() {
        assertInstanceOf(
                DesignDslAuthority.Admitted.class,
                new CanonicalDesignDslAuthority().admit(canvasWithBleed(
                        BLEED_MAX_BELOW_SCALE_64,
                        "100.000",
                        "0",
                        "1e-64"
                ))
        );
        assertBleedMaximumRejected(
                canvasWithBleed(BLEED_MAX_ABOVE_SCALE_64, "0", "0", "0"),
                "/designRoot/bleed/topMm"
        );
        assertBleedMaximumRejected(
                canvasWithBleed("0", BLEED_MAX_ABOVE_SCALE_64, "0", "0"),
                "/designRoot/bleed/rightMm"
        );
        assertBleedMaximumRejected(
                canvasWithBleed("0", "0", BLEED_MAX_ABOVE_SCALE_64, "0"),
                "/designRoot/bleed/bottomMm"
        );
        assertBleedMaximumRejected(
                canvasWithBleed("0", "0", "0", BLEED_MAX_ABOVE_SCALE_64),
                "/designRoot/bleed/leftMm"
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
    void defaultAuthorityEnforcesMaximumBelowAtAndScale64AboveForBothAxes() {
        assertInstanceOf(
                DesignDslAuthority.Admitted.class,
                new CanonicalDesignDslAuthority()
                        .admit(canvas(MAX_BELOW_SCALE_64, "1000.000"))
        );
        assertGeometryRejected(
                new CanonicalDesignDslAuthority().admit(canvas(MAX_ABOVE_SCALE_64, "297")),
                "/designRoot/widthMm",
                DesignDslAuthority.Limit.GEOMETRY_CANVAS_TRIM_MM_PER_AXIS_MAX
        );
        assertGeometryRejected(
                new CanonicalDesignDslAuthority().admit(canvas("210", MAX_ABOVE_SCALE_64)),
                "/designRoot/heightMm",
                DesignDslAuthority.Limit.GEOMETRY_CANVAS_TRIM_MM_PER_AXIS_MAX
        );
    }

    @Test
    void authorityRejectInvalidAndThrowFailClosedAtTheExactCanvasAxis() {
        for (var mode : FailureMode.values()) {
            var authority = new FailingAuthority(
                    mode,
                    "geometry.canvasTrimMmPerAxisExclusiveMin"
            );

            assertGeometryRejected(
                    new CanonicalDesignDslAuthority(authority)
                            .admit(canvas("210", "297")),
                    "/designRoot/widthMm"
            );
            assertEquals(List.of(minObservation("210")), authority.geometryObservations());
        }
    }

    @Test
    void maximumAuthorityRejectInvalidAndThrowFailClosedAtTheExactCanvasAxis() {
        for (var mode : FailureMode.values()) {
            var authority = new FailingAuthority(
                    mode,
                    "geometry.canvasTrimMmPerAxisMax"
            );

            assertGeometryRejected(
                    new CanonicalDesignDslAuthority(authority)
                            .admit(canvas("210", "297")),
                    "/designRoot/widthMm",
                    DesignDslAuthority.Limit.GEOMETRY_CANVAS_TRIM_MM_PER_AXIS_MAX
            );
            assertEquals(
                    List.of(minObservation("210"), maxObservation("210")),
                    authority.geometryObservations()
            );
        }
    }

    @Test
    void bleedMinimumAuthorityRejectInvalidAndThrowFailClosedAtTheExactSide() {
        for (var mode : FailureMode.values()) {
            var authority = new FailingAuthority(
                    mode,
                    "geometry.bleedMmPerSideMin"
            );

            assertGeometryRejected(
                    new CanonicalDesignDslAuthority(authority).admit(canvasWithBleed(
                            "0",
                            "1",
                            "2",
                            "3"
                    )),
                    "/designRoot/bleed/topMm",
                    DesignDslAuthority.Limit.GEOMETRY_BLEED_MM_PER_SIDE_MIN
            );
            assertEquals(
                    List.of(bleedMinObservation("0")),
                    authority.bleedObservations()
            );
        }
    }

    @Test
    void bleedMaximumAuthorityRejectInvalidAndThrowFailClosedAtTheExactSide() {
        for (var mode : FailureMode.values()) {
            var authority = new FailingAuthority(
                    mode,
                    "geometry.bleedMmPerSideMax"
            );

            assertGeometryRejected(
                    new CanonicalDesignDslAuthority(authority).admit(canvasWithBleed(
                            "100",
                            "1",
                            "2",
                            "3"
                    )),
                    "/designRoot/bleed/topMm",
                    DesignDslAuthority.Limit.GEOMETRY_BLEED_MM_PER_SIDE_MAX
            );
            assertEquals(
                    List.of(bleedMinObservation("100"), bleedMaxObservation("100")),
                    authority.bleedObservations()
            );
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

    @Test
    void canonicalExpansionDominatesBeforeAllocatingAnOversizedBleedObservation() {
        var recording = new RecordingAuthority();

        var rejected = assertInstanceOf(
                DesignDslAuthority.Rejected.class,
                new CanonicalDesignDslAuthority(recording)
                        .admit(canvasWithBleed("1e16777216", "0", "0", "0"))
        );

        assertEquals(DesignDslAuthority.FailureCode.DESIGN_DSL_LIMIT_EXCEEDED, rejected.code());
        assertEquals(DesignDslAuthority.FailureStage.DESIGN_CANONICAL_COUNT, rejected.stage());
        assertEquals(DesignDslAuthority.Limit.CANONICAL_BYTES, rejected.limit().orElseThrow());
        assertEquals(List.of(), recording.bleedObservations());
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

    private static byte[] canvasWithBleed(
            String topMm,
            String rightMm,
            String bottomMm,
            String leftMm
    ) {
        return ("""
                {
                  "dslVersion":"renderweave-design/1.0",
                  "expressionProfile":"renderweave-expression/1.0",
                  "displayName":"Geometry capacity",
                  "definitions":[],
                  "designRoot":{
                    "nodeId":"00000000-0000-4000-8000-000000000001",
                    "kind":"canvas",
                    "widthMm":210,
                    "heightMm":297,
                    "bleed":{
                      "topMm":%s,
                      "rightMm":%s,
                      "bottomMm":%s,
                      "leftMm":%s
                    },
                    "bindings":[],
                    "children":[]
                  }
                }
                """).formatted(topMm, rightMm, bottomMm, leftMm)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static DesignInputExpressionCapacityAuthority.Observation minObservation(
            String observedValue
    ) {
        return new DesignInputExpressionCapacityAuthority.Observation(
                "geometry.canvasTrimMmPerAxisExclusiveMin",
                observedValue
        );
    }

    private static DesignInputExpressionCapacityAuthority.Observation maxObservation(
            String observedValue
    ) {
        return new DesignInputExpressionCapacityAuthority.Observation(
                "geometry.canvasTrimMmPerAxisMax",
                observedValue
        );
    }

    private static DesignInputExpressionCapacityAuthority.Observation bleedMinObservation(
            String observedValue
    ) {
        return new DesignInputExpressionCapacityAuthority.Observation(
                "geometry.bleedMmPerSideMin",
                observedValue
        );
    }

    private static DesignInputExpressionCapacityAuthority.Observation bleedMaxObservation(
            String observedValue
    ) {
        return new DesignInputExpressionCapacityAuthority.Observation(
                "geometry.bleedMmPerSideMax",
                observedValue
        );
    }

    private static void assertGeometryRejected(
            DesignDslAuthority.Admission admission,
            String pointer
    ) {
        assertGeometryRejected(
                admission,
                pointer,
                DesignDslAuthority.Limit.GEOMETRY_CANVAS_TRIM_MM_PER_AXIS_EXCLUSIVE_MIN
        );
    }

    private static void assertGeometryRejected(
            DesignDslAuthority.Admission admission,
            String pointer,
            DesignDslAuthority.Limit limit
    ) {
        var rejected = assertInstanceOf(DesignDslAuthority.Rejected.class, admission);
        assertEquals("DESIGN_PROPERTY_CONSTRAINT_INVALID", rejected.code().name());
        assertEquals("DESIGN_PROPERTY_AND_AGGREGATE_VALIDATION", rejected.stage().name());
        assertEquals(pointer, rejected.pointer());
        assertEquals(limit, rejected.limit().orElse(null));
    }

    private static void assertBleedMinimumRejected(byte[] designDsl, String pointer) {
        assertGeometryRejected(
                new CanonicalDesignDslAuthority().admit(designDsl),
                pointer,
                DesignDslAuthority.Limit.GEOMETRY_BLEED_MM_PER_SIDE_MIN
        );
    }

    private static void assertBleedMaximumRejected(byte[] designDsl, String pointer) {
        assertGeometryRejected(
                new CanonicalDesignDslAuthority().admit(designDsl),
                pointer,
                DesignDslAuthority.Limit.GEOMETRY_BLEED_MM_PER_SIDE_MAX
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

        private List<Observation> bleedObservations() {
            return observations.stream()
                    .filter(observation -> observation.limitId()
                            .startsWith("geometry.bleedMmPerSide"))
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
        private final String failingLimitId;
        private final List<Observation> observations = new ArrayList<>();

        private FailingAuthority(FailureMode mode, String failingLimitId) {
            this.mode = mode;
            this.failingLimitId = failingLimitId;
        }

        @Override
        public Decision evaluate(Observation observation) {
            if (!observation.limitId().startsWith("geometry.")) {
                return CanonicalDesignInputExpressionCapacityAuthority.INSTANCE
                        .evaluate(observation);
            }
            observations.add(observation);
            if (!observation.limitId().equals(failingLimitId)) {
                return CanonicalDesignInputExpressionCapacityAuthority.INSTANCE
                        .evaluate(observation);
            }
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
            return List.copyOf(observations);
        }

        private List<Observation> bleedObservations() {
            return observations.stream()
                    .filter(observation -> observation.limitId()
                            .startsWith("geometry.bleedMmPerSide"))
                    .toList();
        }
    }
}
