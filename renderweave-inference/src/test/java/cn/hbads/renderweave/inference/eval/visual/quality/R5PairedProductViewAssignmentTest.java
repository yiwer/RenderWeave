package cn.hbads.renderweave.inference.eval.visual.quality;

import cn.hbads.renderweave.inference.eval.visual.LayeredEvaluationRecord;
import cn.hbads.renderweave.inference.live.BoundedVisualInspection;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class R5PairedProductViewAssignmentTest {
    private static final List<String> SEEN = List.of(
            "transit-board-v3", "restaurant-menu-v3",
            "hospital-schedule-v3", "transit-board-v5");
    private static final List<String> CONFIRMATION = List.of(
            "transit-board-v2", "invoice-lines-v3",
            "school-timetable-v4", "building-directory-v5");

    @Test
    void freezesDisjointSeenVetoAndSealedConfirmationBeforeResults() {
        var assignment = R5PairedProductViewAssignment.load();

        assertEquals(SEEN, assignment.seenCases().stream()
                .map(R5PairedProductViewAssignment.CaseAssignment::caseId).toList());
        assertTrue(assignment.seenCases().stream().allMatch(item ->
                item.cohort() == R5PairedProductViewAssignment.Cohort.SEEN_DIAGNOSTIC
                        && item.seenVeto()
                        && !item.contributesToConfirmation()
                        && !item.mayClaimAc021()));
        assertEquals(CONFIRMATION, assignment.confirmationCases().stream()
                .map(R5PairedProductViewAssignment.CaseAssignment::caseId).toList());
        assertEquals(3, assignment.confirmationCases().stream().filter(item ->
                item.sourcePartition() == LayeredEvaluationRecord.Partition.DEV).count());
        assertEquals(1, assignment.confirmationCases().stream().filter(item ->
                item.sourcePartition() == LayeredEvaluationRecord.Partition.HOLDOUT).count());
        assertTrue(assignment.confirmationCases().stream().allMatch(item ->
                item.cohort() == R5PairedProductViewAssignment.Cohort.SEALED_CONFIRMATION
                        && !item.seenVeto()
                        && item.contributesToConfirmation()
                        && !item.mayClaimAc021()));
        var seenIds = new HashSet<>(SEEN);
        assertTrue(CONFIRMATION.stream().noneMatch(seenIds::contains));
        assertFalse(CONFIRMATION.contains("transit-board-v5"));
        assertEquals("R5P_ASSIGNMENT_FROZEN", assignment.terminalCode());
        assertEquals(0, assignment.externalProviderUsage().attempts());
        assertEquals(0, assignment.apiKeyReads());
    }

    @Test
    void freezesRegionsThresholdsAndAllExecutionIdentities() {
        var assignment = R5PairedProductViewAssignment.load();

        assertEquals(
                "renderweave-r5p-paired-view-assignment/1.0:"
                        + "39266e24b85e0189577573e6e4e56905d41a43f7e0f81a9514fbdbcac954c3e8",
                assignment.identity());
        assertEquals(
                "renderweave-r5p-paired-view-evaluation/1.0:"
                        + "c8ad69263640ca49cd93ca24c6b558c6f913ff89a40c84052634c7cd79f66b65",
                assignment.evaluationIdentity());
        assertEquals(8, assignment.cases().size());
        for (var item : assignment.cases()) {
            assertEquals(2, item.regions().size());
            assertEquals(item.rawFixtureSha256(),
                    item.renderIdentity().substring("render-sha256:".length()));
            assertTrue(item.regions().stream().allMatch(region ->
                    "view-00-overview-00".equals(region.baseViewId())
                            && region.marginPreset()
                            == BoundedVisualInspection.MarginPreset.TIGHT_0000_BPS
                            && region.resolutionPreset()
                            == BoundedVisualInspection.ResolutionPreset.INSPECT_LONG_EDGE_2400));
        }
        var thresholds = assignment.thresholds();
        assertEquals("MATCHED_LINE_INCREASE_OR_CHARACTER_ERROR_REDUCTION",
                thresholds.perCaseTargetImprovementRule());
        assertEquals(0, thresholds.maximumPerCaseHallucinationIncrease());
        assertEquals(500, thresholds.minimumConfirmationLineRecallGainBps());
        assertEquals(1, thresholds.minimumConfirmationCharacterErrorReduction());
        assertEquals(100, thresholds.maximumConfirmationOrderRegressionBps());
        assertEquals(100, thresholds.maximumConfirmationRepeatRegressionBps());
        assertEquals(5_000, thresholds.coalescingIntersectionOverSmallerAreaBps());

        var identities = assignment.identities();
        assertEquals(BoundedVisualInspection.VERSION, identities.actionModuleVersion());
        assertEquals(BoundedVisualInspection.PLAN_VERSION, identities.successorPlanVersion());
        assertEquals(BoundedVisualInspection.AdaptiveInspectionPolicy.initial().identity(),
                identities.actionPolicyIdentity());
        assertEquals("renderweave-r5-product-raster-transform/1.0",
                identities.transformVersion());
        assertEquals("renderweave-visual-view-plan/1.0", identities.staticPlannerVersion());
        assertEquals(
                "rapidocr-3.9.2-openvino-2026.0.0-ppocrv6-small-c05805399d7d10b1",
                identities.capabilityIdentity());
        assertEquals(
                "AcquisitionPolicy/1.0:32ade47685c07163e10f77be8b8ed46e420af7b7d381e1363d30886a19e26c52",
                identities.acquisitionPolicyIdentity());
        assertEquals("renderweave-r5p-source-projection/1.0",
                identities.projectionIdentity());
        assertEquals("renderweave-r5p-observation-coalescing/1.0",
                identities.coalescingIdentity());
        assertEquals("renderweave-r5p-paired-product-view-evaluator/1.0",
                identities.evaluatorIdentity());
        assertTrue(identities.runtimeIdentity().matches(
                "renderweave-r5p-runtime/1\\.0:[0-9a-f]{64}"));
    }

    @Test
    void bindsEachRawFixtureToTheFrozenRepositoryRaster() throws Exception {
        var assignment = R5PairedProductViewAssignment.load();
        var loader = R5PairedProductViewAssignment.class.getClassLoader();

        for (var item : assignment.cases()) {
            try (var input = loader.getResourceAsStream(item.rawFixtureResource())) {
                assertTrue(input != null, item.rawFixtureResource());
                var bytes = input.readAllBytes();
                assertEquals(item.rawFixtureSha256(),
                        R5PairedProductViewAssignment.sha256(bytes));
                assertTrue(bytes.length > 0);
            }
        }
    }

    @Test
    void rejectsOverlapPartitionThresholdResultAndOldHoldoutAliasTampering() {
        assertMutationCode(
                "\"caseId\": \"transit-board-v2\"",
                "\"caseId\": \"transit-board-v3\"",
                "R5P_ASSIGNMENT_CASE_SET_DRIFT");
        assertMutationCode(
                "\"caseId\": \"building-directory-v5\",\n      \"cohort\": \"SEALED_CONFIRMATION\",\n      \"sourcePartition\": \"HOLDOUT\"",
                "\"caseId\": \"building-directory-v5\",\n      \"cohort\": \"SEALED_CONFIRMATION\",\n      \"sourcePartition\": \"DEV\"",
                "R5P_ASSIGNMENT_PARTITION_DRIFT");
        assertMutationCode(
                "\"minimumConfirmationLineRecallGainBps\": 500",
                "\"minimumConfirmationLineRecallGainBps\": 499",
                "R5P_ASSIGNMENT_THRESHOLD_DRIFT");
        assertMutationCode(
                "\"caseId\": \"building-directory-v5\"",
                "\"caseId\": \"transit-board-v5-copy\"",
                "R5P_ASSIGNMENT_CASE_SET_DRIFT");
        assertMutationCode(
                "\"terminalCode\": \"R5P_ASSIGNMENT_FROZEN\"",
                "\"observedResult\": \"PASS\",\n  \"terminalCode\": \"R5P_ASSIGNMENT_FROZEN\"",
                "R5P_ASSIGNMENT_INVALID");
    }

    private static void assertMutationCode(String before, String after, String code) {
        var loader = R5PairedProductViewAssignment.class.getClassLoader();
        var resource = "visual-eval/r5p/paired-view-assignment-v1.json";
        try (var input = loader.getResourceAsStream(resource)) {
            if (input == null) throw new AssertionError("assignment resource missing");
            var original = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            var mutated = original.replace(before, after);
            if (mutated.equals(original)) throw new AssertionError("mutation anchor missing");
            var classLoader = new ClassLoader(loader) {
                @Override
                public InputStream getResourceAsStream(String name) {
                    if (resource.equals(name)) {
                        return new ByteArrayInputStream(mutated.getBytes(StandardCharsets.UTF_8));
                    }
                    return super.getResourceAsStream(name);
                }
            };
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> R5PairedProductViewAssignment.load(classLoader));
            assertEquals(code, failure.getMessage());
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
    }
}
