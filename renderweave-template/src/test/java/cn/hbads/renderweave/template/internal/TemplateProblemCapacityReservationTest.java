package cn.hbads.renderweave.template.internal;

import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.schema.identity.SchemaKey;
import cn.hbads.renderweave.schema.identity.VersionTag;
import cn.hbads.renderweave.template.api.DesignDslAuthority;
import cn.hbads.renderweave.template.api.DesignInputExpressionCapacityAuthority;
import cn.hbads.renderweave.template.api.TemplateApplication;
import cn.hbads.renderweave.template.api.TemplateDependencyProjection;
import cn.hbads.renderweave.template.spi.DependencyResolution;
import cn.hbads.renderweave.template.spi.OwnerScopeAuthority;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateProblemCapacityReservationTest {
    private static final OwnerScopeAuthority.OwnerScope OWNER =
            new OwnerScopeAuthority.OwnerScope("owner-a");
    private static final StaticSchemaRef SCHEMA = new StaticSchemaRef(
            SchemaKey.systemProvided("system-empty"), VersionTag.of("v1"));
    private static final String SELF = "00000000-0000-4000-8000-000000000001";

    @Test
    void derivesMarkerItemAndByteObservationsBeforeAppendingARealDependencyProblem() {
        var recording = new RecordingAuthority();
        var resolution = new MissingResolution();
        var projection = new TemplateDependencyProjection(
                List.of(new TemplateDependencyProjection.AssetRefAtom(
                        "missing", "imageRef", "/designRoot/children/0/imageRef")),
                List.of()
        );

        var evaluated = evaluate(resolution, recording, projection);

        assertEquals(TemplateDependencyEvaluator.Classification.DEPENDENCY_ERROR,
                evaluated.classification());
        assertEquals(1, resolution.assetResolutions);
        var problemBytes = TemplateProblemBudget.canonicalSize(
                evaluated.report().problems().getFirst());
        assertEquals(List.of(
                observation("problems.limitMarkerReservedBytes", "1024"),
                observation("problems.canonicalBytesPerItem", Integer.toString(problemBytes)),
                observation("problems.canonicalBytesTotal", Integer.toString(1024 + problemBytes)),
                observation("problems.itemsIncludingLimitMarker", "1")
        ), recording.observations);
    }

    @Test
    void perItemRejectionStopsBeforeResolvingTheNextDependency() {
        var recording = new RecordingAuthority("problems.canonicalBytesPerItem");
        var resolution = new MissingResolution();
        var projection = new TemplateDependencyProjection(
                List.of(
                        new TemplateDependencyProjection.AssetRefAtom(
                                "missing-a", "imageRef", "/designRoot/children/0/imageRef"),
                        new TemplateDependencyProjection.AssetRefAtom(
                                "missing-b", "imageRef", "/designRoot/children/1/imageRef")
                ),
                List.of()
        );

        var evaluated = evaluate(resolution, recording, projection);

        assertEquals(TemplateDependencyEvaluator.Classification.HARD_ERROR,
                evaluated.classification());
        assertTrue(evaluated.report().truncated());
        assertFalse(evaluated.report().confirmable());
        assertEquals(List.of("BYTES"), evaluated.report().problems().getFirst().messageArgs());
        assertEquals(1, resolution.assetResolutions);
    }

    @Test
    void semanticValidationWritesIntoTheSameCollectorAndStopsAfterItsFirstRejection() {
        var recording = new RecordingAuthority("problems.canonicalBytesPerItem");
        var budget = new TemplateProblemBudget(recording);
        var rejectingDesigns = new RejectingDesignAuthority();
        var validator = new TemplateSemanticDependencyValidator(
                TemplateTestData::resolvedEmpty,
                rejectingDesigns
        );
        var firstId = "00000000-0000-4000-8000-000000000006";
        var secondId = "00000000-0000-4000-8000-000000000007";

        var validation = validator.validate(
                twoTemplateUseCanonical(firstId, secondId),
                SCHEMA,
                Map.of(
                        firstId, childState(firstId),
                        secondId, childState(secondId)
                ),
                budget
        );

        assertTrue(validation.hard());
        assertEquals(1, rejectingDesigns.admissions);
        assertTrue(budget.report().truncated());
        assertEquals("PROBLEM_LIMIT_REACHED", budget.report().problems().getFirst().code());
    }

    @Test
    void markerReservationRejectionStopsBeforeAnyDependencyResolution() {
        var recording = new RecordingAuthority("problems.limitMarkerReservedBytes");
        var resolution = new MissingResolution();

        var evaluated = evaluate(
                resolution,
                recording,
                new TemplateDependencyProjection(missingAtoms(2), List.of())
        );

        assertEquals(TemplateDependencyEvaluator.Classification.HARD_ERROR,
                evaluated.classification());
        assertEquals(0, resolution.assetResolutions);
        assertTrue(evaluated.report().truncated());
        assertEquals(List.of("BYTES"), evaluated.report().problems().getFirst().messageArgs());
    }

    @Test
    void totalByteRejectionStopsAtTheSamePreAppendReservationPoint() {
        var recording = new RecordingAuthority("problems.canonicalBytesTotal");
        var resolution = new MissingResolution();

        var evaluated = evaluate(
                resolution,
                recording,
                new TemplateDependencyProjection(missingAtoms(2), List.of())
        );

        assertEquals(1, resolution.assetResolutions);
        assertTrue(evaluated.report().truncated());
        assertEquals(List.of("BYTES"), evaluated.report().problems().getFirst().messageArgs());
    }

    @Test
    void itemOverflowObservesCandidate201ThenExact199AndStopsBefore202() {
        var recording = new RecordingAuthority();
        var resolution = new MissingResolution();

        var evaluated = evaluate(
                resolution,
                recording,
                new TemplateDependencyProjection(missingAtoms(500), List.of())
        );

        assertEquals(201, resolution.assetResolutions);
        assertEquals(200, evaluated.report().problems().size());
        assertEquals("PROBLEM_LIMIT_REACHED", evaluated.report().problems().getLast().code());
        assertEquals(List.of("ITEMS"), evaluated.report().problems().getLast().messageArgs());
        var rejectedCandidate = recording.observations.indexOf(
                observation("problems.itemsIncludingLimitMarker", "201"));
        assertTrue(rejectedCandidate >= 0);
        assertEquals(
                observation("problems.ordinaryItemsWhenTruncated", "199"),
                recording.observations.get(rejectedCandidate + 1)
        );
        assertEquals(
                observation("problems.itemsIncludingLimitMarker", "200"),
                recording.observations.get(rejectedCandidate + 2)
        );
    }

    @Test
    void naturalCompletionKeepsAllTwoHundredOrdinaryProblemsWithoutAMarker() {
        var recording = new RecordingAuthority();
        var resolution = new MissingResolution();

        var evaluated = evaluate(
                resolution,
                recording,
                new TemplateDependencyProjection(missingAtoms(200), List.of())
        );

        assertEquals(200, resolution.assetResolutions);
        assertFalse(evaluated.report().truncated());
        assertTrue(evaluated.report().confirmable());
        assertEquals(200, evaluated.report().problems().size());
        assertFalse(recording.observations.contains(
                observation("problems.ordinaryItemsWhenTruncated", "199")));
    }

    @Test
    void byteFailureOnCandidate201WinsAndStillLeavesRoomForOneMarker() {
        var recording = new RecordingAuthority("problems.canonicalBytesPerItem", 201);
        var resolution = new MissingResolution();

        var evaluated = evaluate(
                resolution,
                recording,
                new TemplateDependencyProjection(missingAtoms(201), List.of())
        );

        assertEquals(201, resolution.assetResolutions);
        assertTrue(evaluated.report().truncated());
        assertEquals(200, evaluated.report().problems().size());
        assertEquals(List.of("BYTES"), evaluated.report().problems().getLast().messageArgs());
    }

    @Test
    void invalidAuthorityDecisionFailsClosedBeforeDependencyWork() {
        var resolution = new MissingResolution();
        DesignInputExpressionCapacityAuthority invalid = ignored ->
                new DesignInputExpressionCapacityAuthority.Invalid(
                        DesignInputExpressionCapacityAuthority.InvalidReason.INVALID_OBSERVED_VALUE
                );

        var evaluated = evaluate(
                resolution,
                invalid,
                new TemplateDependencyProjection(missingAtoms(2), List.of())
        );

        assertEquals(0, resolution.assetResolutions);
        assertEquals(TemplateDependencyEvaluator.Classification.HARD_ERROR,
                evaluated.classification());
        assertTrue(evaluated.report().truncated());
    }

    private static TemplateDependencyEvaluator.Evaluation evaluate(
            DependencyResolution resolution,
            DesignInputExpressionCapacityAuthority capacity,
            TemplateDependencyProjection projection
    ) {
        return new TemplateDependencyEvaluator(
                resolution,
                TemplateTestData::resolvedEmpty,
                new CanonicalDesignDslAuthority(),
                capacity
        ).evaluate(
                projection,
                TemplateTestData.emptyDesignCanonical().getBytes(StandardCharsets.UTF_8),
                SCHEMA,
                SELF,
                OWNER
        );
    }

    private static DesignInputExpressionCapacityAuthority.Observation observation(
            String limitId,
            String observedValue
    ) {
        return new DesignInputExpressionCapacityAuthority.Observation(limitId, observedValue);
    }

    private static List<TemplateDependencyProjection.AssetRefAtom> missingAtoms(int count) {
        var atoms = new ArrayList<TemplateDependencyProjection.AssetRefAtom>();
        for (int index = 0; index < count; index++) {
            atoms.add(new TemplateDependencyProjection.AssetRefAtom(
                    "missing-" + index,
                    "imageRef",
                    "/designRoot/children/" + index + "/imageRef"
            ));
        }
        return atoms;
    }

    private static byte[] twoTemplateUseCanonical(String firstId, String secondId) {
        var raw = """
                {"dslVersion":"renderweave-design/1.0",
                 "expressionProfile":"renderweave-expression/1.0",
                 "displayName":"Problem capacity fixture","definitions":[],
                 "designRoot":{"nodeId":"00000000-0000-4000-8000-000000000001",
                 "kind":"canvas","widthMm":210,"heightMm":297,"bindings":[],"children":[
                 {"nodeId":"00000000-0000-4000-8000-000000000002","kind":"templateUse",
                  "bindings":[],"useId":"00000000-0000-4000-8000-000000000004",
                  "templateRef":{"templateId":"%s"},"contextSelector":{"kind":"empty"},
                  "fills":[],"placement":{"type":"ABSOLUTE","xMm":0,"yMm":0,
                  "widthMode":"HUG_CONTENT","heightMode":"HUG_CONTENT"}},
                 {"nodeId":"00000000-0000-4000-8000-000000000003","kind":"templateUse",
                  "bindings":[],"useId":"00000000-0000-4000-8000-000000000005",
                  "templateRef":{"templateId":"%s"},"contextSelector":{"kind":"empty"},
                  "fills":[],"placement":{"type":"ABSOLUTE","xMm":0,"yMm":0,
                  "widthMode":"HUG_CONTENT","heightMode":"HUG_CONTENT"}}]}}
                """.formatted(firstId, secondId).getBytes(StandardCharsets.UTF_8);
        return ((DesignDslAuthority.Admitted) new CanonicalDesignDslAuthority().admit(raw))
                .canonicalUtf8();
    }

    private static DependencyResolution.TemplateState childState(String templateId) {
        return new DependencyResolution.TemplateState(
                templateId,
                OWNER,
                0,
                DependencyResolution.Lifecycle.ACTIVE,
                TemplateApplication.Readiness.READY,
                SCHEMA,
                "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                List.of(),
                "corrupt"
        );
    }

    private static final class RecordingAuthority
            implements DesignInputExpressionCapacityAuthority {
        private final String rejectedLimitId;
        private final int rejectedOccurrence;
        private final List<Observation> observations = new ArrayList<>();
        private int matchingObservations;

        private RecordingAuthority() {
            this(null, 1);
        }

        private RecordingAuthority(String rejectedLimitId) {
            this(rejectedLimitId, 1);
        }

        private RecordingAuthority(String rejectedLimitId, int rejectedOccurrence) {
            this.rejectedLimitId = rejectedLimitId;
            this.rejectedOccurrence = rejectedOccurrence;
        }

        @Override
        public Decision evaluate(Observation observation) {
            observations.add(observation);
            if (observation.limitId().equals(rejectedLimitId)
                    && ++matchingObservations == rejectedOccurrence) {
                return new Rejected(new Terminal(
                        "PROBLEM_LIMIT_REACHED",
                        "BOUNDED_PROBLEM_COLLECTION",
                        "ORIGINATING_STAGE",
                        "BOUNDED_PROBLEM_PREFIX_ONLY",
                        List.of("boundedProblemPrefix=1", "problemLimitMarkers=1")
                ));
            }
            return CanonicalDesignInputExpressionCapacityAuthority.INSTANCE.evaluate(observation);
        }
    }

    private static final class MissingResolution implements DependencyResolution {
        private int assetResolutions;

        @Override
        public AssetResolution resolveAsset(String assetId) {
            assetResolutions++;
            return new AssetMissing();
        }

        @Override
        public TemplateResolution resolveTemplate(String targetTemplateId) {
            return new TemplateMissing();
        }
    }

    private static final class RejectingDesignAuthority implements DesignDslAuthority {
        private int admissions;

        @Override
        public Admission admit(byte[] rawUtf8) {
            admissions++;
            return new Rejected(
                    FailureCode.DESIGN_JSON_INVALID,
                    FailureStage.DESIGN_PARSE,
                    "",
                    Optional.empty()
            );
        }
    }
}
