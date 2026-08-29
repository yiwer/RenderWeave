package cn.hbads.renderweave.template.internal;

import cn.hbads.renderweave.template.api.DesignDslAuthority;
import cn.hbads.renderweave.template.api.DesignInputExpressionCapacityAuthority;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class GeometryCapacityReservationTest {

    private static final String RESOURCE =
            "/cn/hbads/renderweave/template/canonical-kernel-v1/vectors.json";
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
    private static final String AUTHORED_MM_MAX_BELOW_SCALE_64 =
            "9999.9999999999999999999999999999999999999999999999999999999999999999";
    private static final String AUTHORED_MM_MAX_ABOVE_SCALE_64 =
            "10000.0000000000000000000000000000000000000000000000000000000000000001";
    private static final String FONT_SIZE_MAX_BELOW_SCALE_64 =
            "4095.9999999999999999999999999999999999999999999999999999999999999999";
    private static final String FONT_SIZE_MAX_ABOVE_SCALE_64 =
            "4096.0000000000000000000000000000000000000000000000000000000000000001";

    @Test
    void textRunFontSizesObserveCanonicalValuesAtThePublicAdmissionSeam() throws Exception {
        var recording = new RecordingAuthority();

        assertInstanceOf(
                DesignDslAuthority.Admitted.class,
                new CanonicalDesignDslAuthority(recording)
                        .admit(canonicalVector("admit-text-with-runs"))
        );

        assertEquals(
                List.of(
                        fontSizeMinimumObservation("12"),
                        fontSizeMaximumObservation("12"),
                        fontSizeMinimumObservation("12"),
                        fontSizeMaximumObservation("12")
                ),
                recording.fontSizeObservations()
        );
    }

    @Test
    void defaultAuthorityEnforcesFontSizeBelowAtAndScale64AboveAtExactRunPointers() {
        assertInstanceOf(
                DesignDslAuthority.Admitted.class,
                new CanonicalDesignDslAuthority().admit(textWithFontSizes(
                        MIN_POSITIVE_SCALE_64,
                        "12.000"
                ))
        );
        assertFontSizeMinimumRejected(
                textWithFontSizes(MIN_NEGATIVE_SCALE_64, "12"),
                "/designRoot/children/0/runs/0/fontSizePt"
        );
        assertFontSizeMinimumRejected(
                textWithFontSizes("12", "-0.00"),
                "/designRoot/children/0/runs/1/fontSizePt"
        );
    }

    @Test
    void defaultAuthorityEnforcesFontSizeMaximumBelowAtAndScale64AboveAtExactRunPointers() {
        assertInstanceOf(
                DesignDslAuthority.Admitted.class,
                new CanonicalDesignDslAuthority().admit(textWithFontSizes(
                        FONT_SIZE_MAX_BELOW_SCALE_64,
                        "4096.000"
                ))
        );
        assertFontSizeMaximumRejected(
                textWithFontSizes(FONT_SIZE_MAX_ABOVE_SCALE_64, "12"),
                "/designRoot/children/0/runs/0/fontSizePt"
        );
        assertFontSizeMaximumRejected(
                textWithFontSizes("12", FONT_SIZE_MAX_ABOVE_SCALE_64),
                "/designRoot/children/0/runs/1/fontSizePt"
        );
    }

    @Test
    void fontSizeCanonicalizationPreservesRunOrderAndNormalizesZeroAndTrailingZeros() {
        var accepted = new RecordingAuthority();
        assertInstanceOf(
                DesignDslAuthority.Admitted.class,
                new CanonicalDesignDslAuthority(accepted).admit(textWithFontSizes(
                        "12.000",
                        MIN_POSITIVE_SCALE_64
                ))
        );
        assertEquals(
                List.of(
                        fontSizeMinimumObservation("12"),
                        fontSizeMaximumObservation("12"),
                        fontSizeMinimumObservation(MIN_POSITIVE_SCALE_64),
                        fontSizeMaximumObservation(MIN_POSITIVE_SCALE_64)
                ),
                accepted.fontSizeObservations()
        );

        var at = new RecordingAuthority();
        assertFontSizeMinimumRejected(
                new CanonicalDesignDslAuthority(at).admit(textWithFontSizes("-0.000", "12")),
                "/designRoot/children/0/runs/0/fontSizePt"
        );
        assertEquals(
                List.of(fontSizeMinimumObservation("0")),
                at.fontSizeObservations()
        );
    }

    @Test
    void fontSizeAuthorityRejectInvalidAndThrowFailClosedAtTheExactRun() {
        for (var mode : FailureMode.values()) {
            var authority = new FailingAuthority(mode, "geometry.fontSizePtExclusiveMin");

            assertFontSizeMinimumRejected(
                    new CanonicalDesignDslAuthority(authority)
                            .admit(textWithFontSizes("12.00", "13")),
                    "/designRoot/children/0/runs/0/fontSizePt"
            );
            assertEquals(
                    List.of(fontSizeMinimumObservation("12")),
                    authority.fontSizeObservations()
            );
        }
    }

    @Test
    void fontSizeMaximumAuthorityRejectInvalidAndThrowFailClosedAtTheExactRun() {
        for (var mode : FailureMode.values()) {
            var authority = new FailingAuthority(mode, "geometry.fontSizePtMax");

            assertFontSizeMaximumRejected(
                    new CanonicalDesignDslAuthority(authority)
                            .admit(textWithFontSizes("4096.00", "13")),
                    "/designRoot/children/0/runs/0/fontSizePt"
            );
            assertEquals(
                    List.of(
                            fontSizeMinimumObservation("4096"),
                            fontSizeMaximumObservation("4096")
                    ),
                    authority.fontSizeObservations()
            );
        }
    }

    @Test
    void permissiveCapacityCannotBypassTheExistingPositiveFontSizeContract() {
        var observations = new ArrayList<DesignInputExpressionCapacityAuthority.Observation>();
        DesignInputExpressionCapacityAuthority permissive = observation -> {
            observations.add(observation);
            return new DesignInputExpressionCapacityAuthority.Accepted();
        };

        var rejected = assertInstanceOf(
                DesignDslAuthority.Rejected.class,
                new CanonicalDesignDslAuthority(permissive)
                        .admit(textWithFontSizes("0", "12"))
        );

        assertEquals(DesignDslAuthority.FailureCode.DESIGN_VALUE_INVALID, rejected.code());
        assertEquals("/designRoot/children/0/runs/0/fontSizePt", rejected.pointer());
        assertEquals(
                List.of(
                        fontSizeMinimumObservation("0"),
                        fontSizeMaximumObservation("0")
                ),
                observations.stream()
                        .filter(observation -> observation.limitId().startsWith(
                                "geometry.fontSizePt"))
                        .toList()
        );
    }

    @Test
    void fontSizeTypeValidationAndCanonicalExpansionPrecedeCapacityObservation() {
        var malformed = new RecordingAuthority();
        var malformedResult = assertInstanceOf(
                DesignDslAuthority.Rejected.class,
                new CanonicalDesignDslAuthority(malformed)
                        .admit(textWithFontSizes("\"12\"", "12"))
        );
        assertEquals(DesignDslAuthority.FailureCode.DESIGN_STRUCTURE_INVALID,
                malformedResult.code());
        assertEquals("/designRoot/children/0/runs/0/fontSizePt", malformedResult.pointer());
        assertEquals(List.of(), malformed.fontSizeObservations());

        var oversized = new RecordingAuthority();
        var oversizedResult = assertInstanceOf(
                DesignDslAuthority.Rejected.class,
                new CanonicalDesignDslAuthority(oversized)
                        .admit(textWithFontSizes("1e16777216", "12"))
        );
        assertEquals(DesignDslAuthority.FailureCode.DESIGN_DSL_LIMIT_EXCEEDED,
                oversizedResult.code());
        assertEquals(DesignDslAuthority.FailureStage.DESIGN_CANONICAL_COUNT,
                oversizedResult.stage());
        assertEquals(DesignDslAuthority.Limit.CANONICAL_BYTES,
                oversizedResult.limit().orElseThrow());
        assertEquals(List.of(), oversized.fontSizeObservations());
    }

    @Test
    void absolutePlacementMmLeavesObserveCanonicalMagnitudeAtThePublicAdmissionSeam()
            throws Exception {
        var recording = new RecordingAuthority();

        assertInstanceOf(
                DesignDslAuthority.Admitted.class,
                new CanonicalDesignDslAuthority(recording)
                        .admit(canonicalVector("admit-transform-and-composites"))
        );

        assertEquals(
                List.of(
                        authoredMmObservation("2.5"),
                        authoredMmObservation("0"),
                        authoredMmObservation("30"),
                        authoredMmObservation("40"),
                        authoredMmObservation("10"),
                        authoredMmObservation("100"),
                        authoredMmObservation("10"),
                        authoredMmObservation("100")
                ),
                recording.authoredMmObservations().stream().limit(8).toList()
        );
    }

    @Test
    void boxStrokeRadiiAndPaddingMmLeavesReserveAfterPlacement() throws Exception {
        var recording = new RecordingAuthority();

        assertInstanceOf(
                DesignDslAuthority.Admitted.class,
                new CanonicalDesignDslAuthority(recording)
                        .admit(canonicalVector("admit-transform-and-composites"))
        );

        assertEquals(
                List.of(
                        "2.5", "0", "30", "40", "10", "100", "10", "100",
                        "1", "2", "3", "4", "5", "1", "2", "3", "4"
                ),
                recording.authoredMmObservedValues()
        );
    }

    @Test
    void stackGridAndRepeatMmLengthsReserveInAuthoredTraversalOrder() throws Exception {
        assertMmObservedValues(
                "admit-stack-with-children",
                List.of("0", "0", "5", "0", "4")
        );
        assertMmObservedValues(
                "admit-grid-with-tracks",
                List.of("0", "0", "120", "80", "2", "3", "10")
        );
        assertMmObservedValues(
                "admit-repeat-with-packed-children",
                List.of("0", "0", "120", "4", "2", "50")
        );
        assertMmObservedValues(
                "admit-repeat-packing-spec-grid-item-layout",
                List.of("0", "0", "100", "100", "1", "2", "0", "5")
        );
    }

    @Test
    void pointAndPathCoordinatesReserveAfterVectorPlacement() throws Exception {
        assertMmObservedValues(
                "admit-line",
                List.of("0", "0", "50", "10", "0", "0", "50", "5", "1")
        );
        assertMmObservedValues(
                "admit-polygon",
                List.of("0", "0", "40", "40", "0", "0", "40", "0", "40", "40", "0", "40")
        );
        assertMmObservedValues(
                "admit-path",
                List.of(
                        "0", "0", "40", "40",
                        "0", "0", "40", "0", "5", "5",
                        "10", "0", "20", "0", "25", "5"
                )
        );
    }

    @Test
    void defaultAuthorityEnforcesPositiveAndNegativeAbsoluteMmBoundaries() throws Exception {
        for (var accepted : List.of(
                AUTHORED_MM_MAX_BELOW_SCALE_64,
                "10000.000",
                "-" + AUTHORED_MM_MAX_BELOW_SCALE_64,
                "-10000.000"
        )) {
            assertInstanceOf(
                    DesignDslAuthority.Admitted.class,
                    new CanonicalDesignDslAuthority().admit(frameWithX(accepted)),
                    accepted
            );
        }

        assertAuthoredMmRejected(
                frameWithX(AUTHORED_MM_MAX_ABOVE_SCALE_64),
                "/designRoot/children/0/placement/xMm"
        );
        assertAuthoredMmRejected(
                frameWithX("-" + AUTHORED_MM_MAX_ABOVE_SCALE_64),
                "/designRoot/children/0/placement/xMm"
        );

        var recording = new RecordingAuthority();
        assertInstanceOf(
                DesignDslAuthority.Admitted.class,
                new CanonicalDesignDslAuthority(recording)
                        .admit(frameWithX("-" + AUTHORED_MM_MAX_BELOW_SCALE_64))
        );
        assertEquals(
                authoredMmObservation(AUTHORED_MM_MAX_BELOW_SCALE_64),
                recording.authoredMmObservations().getFirst()
        );
    }

    @Test
    void localSignValidationPrecedesMmCapacityAndAggregateValidationFollowsIt()
            throws Exception {
        var negativeGapRecording = new RecordingAuthority();
        var negativeGap = assertInstanceOf(
                DesignDslAuthority.Rejected.class,
                new CanonicalDesignDslAuthority(negativeGapRecording).admit(
                        canonicalVectorReplacing(
                                "admit-stack-with-children",
                                "\"gapMm\":4",
                                "\"gapMm\":-1"
                        )
                )
        );
        assertEquals(DesignDslAuthority.FailureCode.DESIGN_VALUE_INVALID, negativeGap.code());
        assertEquals("/designRoot/children/0/gapMm", negativeGap.pointer());
        assertEquals(List.of("0", "0", "5", "0"),
                negativeGapRecording.authoredMmObservedValues());

        var aggregateRecording = new RecordingAuthority();
        var aggregate = assertInstanceOf(
                DesignDslAuthority.Rejected.class,
                new CanonicalDesignDslAuthority(aggregateRecording).admit(
                        canonicalVectorReplacing(
                                "admit-transform-and-composites",
                                "\"widthMm\":30",
                                "\"widthMm\":200"
                        )
                )
        );
        assertEquals(DesignDslAuthority.FailureCode.DESIGN_VALUE_INVALID, aggregate.code());
        assertEquals("/designRoot/children/0/placement/widthMm", aggregate.pointer());
        assertEquals(
                List.of("2.5", "0", "200", "40", "10", "100"),
                aggregateRecording.authoredMmObservedValues()
        );
    }

    @Test
    void canvasTrimAndBleedKeepTheirStricterAxesWithoutDoubleReservation() {
        var recording = new RecordingAuthority();

        assertInstanceOf(
                DesignDslAuthority.Admitted.class,
                new CanonicalDesignDslAuthority(recording).admit(canvasWithBleed(
                        "1", "2", "3", "4"
                ))
        );

        assertEquals(List.of(), recording.authoredMmObservations());
    }

    @Test
    void authoredMmAuthorityRejectInvalidAndThrowFailClosedAtTheExactLeaf() throws Exception {
        for (var mode : FailureMode.values()) {
            var authority = new FailingAuthority(
                    mode,
                    "geometry.authoredCoordinateOrLengthMmAbsoluteMax"
            );

            assertGeometryRejected(
                    new CanonicalDesignDslAuthority(authority).admit(frameWithX("-10.00")),
                    "/designRoot/children/0/placement/xMm",
                    DesignDslAuthority.Limit
                            .GEOMETRY_AUTHORED_COORDINATE_OR_LENGTH_MM_ABSOLUTE_MAX
            );
            assertEquals(
                    List.of(authoredMmObservation("10")),
                    authority.authoredMmObservations()
            );
        }
    }

    @Test
    void canonicalExpansionDominatesBeforeAllocatingAnOversizedAuthoredMmObservation()
            throws Exception {
        var recording = new RecordingAuthority();

        var rejected = assertInstanceOf(
                DesignDslAuthority.Rejected.class,
                new CanonicalDesignDslAuthority(recording).admit(frameWithX("-1e16777216"))
        );

        assertEquals(DesignDslAuthority.FailureCode.DESIGN_DSL_LIMIT_EXCEEDED, rejected.code());
        assertEquals(DesignDslAuthority.FailureStage.DESIGN_CANONICAL_COUNT, rejected.stage());
        assertEquals(DesignDslAuthority.Limit.CANONICAL_BYTES, rejected.limit().orElseThrow());
        assertEquals(List.of(), recording.authoredMmObservations());
    }

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

    private static byte[] textWithFontSizes(String first, String second) {
        return ("""
                {
                  "dslVersion":"renderweave-design/1.0",
                  "expressionProfile":"renderweave-expression/1.0",
                  "displayName":"Font size capacity",
                  "definitions":[],
                  "designRoot":{
                    "nodeId":"00000000-0000-4000-8000-000000000001",
                    "kind":"canvas",
                    "widthMm":210,
                    "heightMm":297,
                    "bindings":[],
                    "children":[{
                      "nodeId":"00000000-0000-4000-8000-000000000011",
                      "kind":"text",
                      "bindings":[],
                      "placement":{
                        "type":"ABSOLUTE",
                        "xMm":0,
                        "yMm":0,
                        "widthMode":"FIXED",
                        "widthMm":80,
                        "heightMode":"HUG_CONTENT"
                      },
                      "runs":[
                        {
                          "text":"A",
                          "fontRef":{"assetId":"00000000-0000-4000-8000-0000000000a1"},
                          "fontSizePt":%s,
                          "color":"#FF000000",
                          "decoration":"NONE",
                          "letterSpacingPt":0
                        },
                        {
                          "text":"B",
                          "fontRef":{"assetId":"00000000-0000-4000-8000-0000000000a1"},
                          "fontSizePt":%s,
                          "color":"#FF000000",
                          "decoration":"NONE",
                          "letterSpacingPt":0
                        }
                      ]
                    }]
                  }
                }
                """).formatted(first, second).getBytes(StandardCharsets.UTF_8);
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

    private byte[] canonicalVector(String id) throws IOException {
        try (var input = GeometryCapacityReservationTest.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IOException("Missing vector resource " + RESOURCE);
            }
            var manifest = new ObjectMapper().readTree(input);
            for (var vector : manifest.required("cases")) {
                if (id.equals(vector.required("id").asString())) {
                    return vector.required("expected").required("canonicalUtf8")
                            .asString().getBytes(StandardCharsets.UTF_8);
                }
            }
            throw new IOException("Missing canonical vector " + id);
        }
    }

    private byte[] canonicalVectorReplacing(String id, String target, String replacement)
            throws IOException {
        var canonical = new String(canonicalVector(id), StandardCharsets.UTF_8);
        if (!canonical.contains(target)) {
            throw new IOException("Missing replacement target " + target + " in " + id);
        }
        return canonical.replace(target, replacement).getBytes(StandardCharsets.UTF_8);
    }

    private byte[] frameWithX(String xMm) throws IOException {
        return canonicalVectorReplacing(
                "admit-frame-in-canvas",
                "\"xMm\":10",
                "\"xMm\":" + xMm
        );
    }

    private void assertMmObservedValues(String vectorId, List<String> expected) throws Exception {
        var recording = new RecordingAuthority();
        assertInstanceOf(
                DesignDslAuthority.Admitted.class,
                new CanonicalDesignDslAuthority(recording).admit(canonicalVector(vectorId)),
                vectorId
        );
        assertEquals(expected, recording.authoredMmObservedValues(), vectorId);
    }

    private static DesignInputExpressionCapacityAuthority.Observation authoredMmObservation(
            String observedValue
    ) {
        return new DesignInputExpressionCapacityAuthority.Observation(
                "geometry.authoredCoordinateOrLengthMmAbsoluteMax",
                observedValue
        );
    }

    private static DesignInputExpressionCapacityAuthority.Observation fontSizeMinimumObservation(
            String observedValue
    ) {
        return new DesignInputExpressionCapacityAuthority.Observation(
                "geometry.fontSizePtExclusiveMin",
                observedValue
        );
    }

    private static DesignInputExpressionCapacityAuthority.Observation fontSizeMaximumObservation(
            String observedValue
    ) {
        return new DesignInputExpressionCapacityAuthority.Observation(
                "geometry.fontSizePtMax",
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

    private static void assertAuthoredMmRejected(byte[] designDsl, String pointer) {
        assertGeometryRejected(
                new CanonicalDesignDslAuthority().admit(designDsl),
                pointer,
                DesignDslAuthority.Limit
                        .GEOMETRY_AUTHORED_COORDINATE_OR_LENGTH_MM_ABSOLUTE_MAX
        );
    }

    private static void assertFontSizeMinimumRejected(byte[] designDsl, String pointer) {
        assertFontSizeMinimumRejected(new CanonicalDesignDslAuthority().admit(designDsl), pointer);
    }

    private static void assertFontSizeMinimumRejected(
            DesignDslAuthority.Admission admission,
            String pointer
    ) {
        assertGeometryRejected(
                admission,
                pointer,
                DesignDslAuthority.Limit.GEOMETRY_FONT_SIZE_PT_EXCLUSIVE_MIN
        );
    }

    private static void assertFontSizeMaximumRejected(byte[] designDsl, String pointer) {
        assertFontSizeMaximumRejected(new CanonicalDesignDslAuthority().admit(designDsl), pointer);
    }

    private static void assertFontSizeMaximumRejected(
            DesignDslAuthority.Admission admission,
            String pointer
    ) {
        assertGeometryRejected(
                admission,
                pointer,
                DesignDslAuthority.Limit.GEOMETRY_FONT_SIZE_PT_MAX
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

        private List<Observation> authoredMmObservations() {
            return observations.stream()
                    .filter(observation -> observation.limitId().equals(
                            "geometry.authoredCoordinateOrLengthMmAbsoluteMax"))
                    .toList();
        }

        private List<String> authoredMmObservedValues() {
            return authoredMmObservations().stream()
                    .map(Observation::observedValue)
                    .toList();
        }

        private List<Observation> fontSizeObservations() {
            return observations.stream()
                    .filter(observation -> observation.limitId().startsWith(
                            "geometry.fontSizePt"))
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

        private List<Observation> authoredMmObservations() {
            return observations.stream()
                    .filter(observation -> observation.limitId().equals(
                            "geometry.authoredCoordinateOrLengthMmAbsoluteMax"))
                    .toList();
        }

        private List<Observation> fontSizeObservations() {
            return observations.stream()
                    .filter(observation -> observation.limitId().startsWith(
                            "geometry.fontSizePt"))
                    .toList();
        }
    }
}
