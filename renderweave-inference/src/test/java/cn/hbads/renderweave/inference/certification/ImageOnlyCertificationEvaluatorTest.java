package cn.hbads.renderweave.inference.certification;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageOnlyCertificationEvaluatorTest {
    private static final String PROFILE_SHA =
            "22f561c88b30fabbf3ba660bcfe203fb570975f770ff122f2ce1c7216454ac0c";

    @Test
    void seededManifestFreezesFiveTwentySixtyAndKeepsTwentyHoldoutCasesOutOfDevView() {
        var factory = new ImageOnlyCertificationManifestFactory();
        var first = factory.create(PROFILE_SHA, canaries(), "image-only-certification-seed-v1");
        var second = factory.create(PROFILE_SHA, canaries(), "image-only-certification-seed-v1");
        var drift = factory.create(PROFILE_SHA, canaries(), "image-only-certification-seed-v2");

        assertEquals(first.manifestIdentity(), second.manifestIdentity());
        assertEquals(first.assignmentsForIndependentReplay(), second.assignmentsForIndependentReplay());
        assertNotEquals(first.manifestIdentity(), drift.manifestIdentity());
        assertEquals(5, first.stageView(CertificationStage.CANARY_5).cases().size());
        assertEquals(20, first.stageView(CertificationStage.DEV_20).cases().size());
        assertEquals(60, first.stageView(CertificationStage.FINAL_60).cases().size());
        assertEquals(20, first.assignmentsForIndependentReplay().stream()
                .filter(item -> item.role() == CertificationCaseRole.HOLDOUT).count());
        var devIds = first.stageView(CertificationStage.DEV_20).cases().stream()
                .map(CertificationStageCase::caseId).collect(java.util.stream.Collectors.toSet());
        var holdoutIds = first.assignmentsForIndependentReplay().stream()
                .filter(item -> item.role() == CertificationCaseRole.HOLDOUT)
                .map(CertificationCaseAssignment::caseId)
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(java.util.Collections.disjoint(devIds, holdoutIds));
        assertEquals("renderweave-layered-r1-evaluation/1.0", first.r1InfrastructureIdentity());
        assertTrue(first.evaluatorIdentity().matches(
                "renderweave-image-only-certification-evaluator/1\\.0:[0-9a-f]{64}"));
    }

    @Test
    void manualVerdictsDriveClosedThresholdsWhileLowConfidenceAndKebabRemainFlags() {
        var manifest = new ImageOnlyCertificationManifestFactory().create(
                PROFILE_SHA, canaries(), "image-only-certification-seed-v1");
        var evaluator = new ImageOnlyCertificationEvaluator();
        var cases = manifest.stageView(CertificationStage.DEV_20).cases();
        var verdicts = new ArrayList<CertificationCaseVerdict>();
        for (var index = 0; index < cases.size(); index++) {
            verdicts.add(new CertificationCaseVerdict(
                    cases.get(index).caseId(), CertificationTerminalState.REVIEW_REQUIRED,
                    index < 18, index == 0 ? 7_999 : 9_000,
                    List.of(index == 1 ? "route-name" : "route_name")
            ));
        }

        var result = evaluator.evaluate(manifest, CertificationStage.DEV_20, verdicts);
        assertTrue(result.passed());
        assertEquals(18, result.acceptedCases());
        assertTrue(result.flags().get(cases.getFirst().caseId()).contains("LOW_CONFIDENCE_REVIEW_FLAG"));
        assertTrue(result.flags().get(cases.get(1).caseId())
                .contains("KEBAB_CASE_MANUAL_NORMALIZATION_REQUIRED"));

        verdicts.set(17, new CertificationCaseVerdict(
                cases.get(17).caseId(), CertificationTerminalState.FAILED, true, 9_000,
                List.of("route_name")));
        assertFalse(evaluator.evaluate(manifest, CertificationStage.DEV_20, verdicts).passed());
    }

    @Test
    void missingDuplicateExtraAndNonContractKeysAreRejectedFailClosed() {
        var manifest = new ImageOnlyCertificationManifestFactory().create(
                PROFILE_SHA, canaries(), "image-only-certification-seed-v1");
        var evaluator = new ImageOnlyCertificationEvaluator();
        var cases = manifest.stageView(CertificationStage.CANARY_5).cases();
        var verdicts = cases.stream().map(item -> new CertificationCaseVerdict(
                item.caseId(), CertificationTerminalState.COMPLETED, true, 9_000,
                List.of("route_name"))).toList();

        assertTrue(evaluator.evaluate(manifest, CertificationStage.CANARY_5, verdicts).passed());
        assertThrows(IllegalArgumentException.class, () -> evaluator.evaluate(
                manifest, CertificationStage.CANARY_5, verdicts.subList(0, 4)));
        var duplicate = new ArrayList<>(verdicts);
        duplicate.set(4, verdicts.getFirst());
        assertThrows(IllegalArgumentException.class, () -> evaluator.evaluate(
                manifest, CertificationStage.CANARY_5, duplicate));
        var invalidKey = new ArrayList<>(verdicts);
        invalidKey.set(0, new CertificationCaseVerdict(cases.getFirst().caseId(),
                CertificationTerminalState.COMPLETED, true, 9_000, List.of("RouteName")));
        assertFalse(evaluator.evaluate(manifest, CertificationStage.CANARY_5, invalidKey).passed());
    }

    private static List<CertificationCanaryCase> canaries() {
        var result = new ArrayList<CertificationCanaryCase>();
        for (var index = 1; index <= 5; index++) {
            result.add(new CertificationCanaryCase("owner-canary-" + index,
                    String.format("%064x", index)));
        }
        assertEquals(5, new HashSet<>(result).size());
        return List.copyOf(result);
    }
}
