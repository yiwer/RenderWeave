package cn.hbads.renderweave.inference.certification;

import cn.hbads.renderweave.inference.eval.visual.LayeredR1Evaluation;
import cn.hbads.renderweave.inference.profile.InferenceProfileRegistry;
import cn.hbads.renderweave.inference.provider.ProfileRunBudgetPolicy;

import java.util.ArrayList;
import java.util.List;

/** Package-private verifier projection. It contains no Provider adapter or credential access seam. */
final class ImageOnlyCertificationP0Evidence {
    ImageOnlyCertificationP0Report generate() {
        var registry = new InferenceProfileRegistry();
        var candidate = registry.require(ProfileRunBudgetPolicy.IMAGE_ONLY_V46_PROFILE_ID);
        var inventory = CertificationAuthorityInventory.loadCanonical();
        var manifest = new ImageOnlyCertificationManifestFactory().create(
                candidate.canonicalSha256(), syntheticCanaries(), "image-only-certification-seed-v1");
        var evaluator = new ImageOnlyCertificationEvaluator();
        var canary = evaluate(evaluator, manifest, CertificationStage.CANARY_5, 5, false);
        var dev = evaluate(evaluator, manifest, CertificationStage.DEV_20, 18, true);
        var finalStage = evaluate(evaluator, manifest, CertificationStage.FINAL_60, 54, false);
        var negative = evaluate(evaluator, manifest, CertificationStage.CANARY_5, 4, false);
        var invalidKey = evaluateInvalidKey(evaluator, manifest);
        var r1 = new LayeredR1Evaluation().evaluate().report();
        var runtime = r1.global().runtime();

        return new ImageOnlyCertificationP0Report(
                ImageOnlyCertificationP0Report.VERSION,
                new ImageOnlyCertificationP0Report.ProfileProof(
                        candidate.profile().profileId(), candidate.canonicalSha256(),
                        candidate.profile().maximumTotalCalls(),
                        candidate.profile().maximumEstimatedCostMicrosCny(),
                        !registry.isProductLiveProfile(candidate.profile().profileId()),
                        List.of("maximumEstimatedCostMicrosCny", "maximumTotalCalls", "profileId")
                ),
                new ImageOnlyCertificationP0Report.AuthorityProof(
                        inventory.canonicalSha256(),
                        inventory.require("dashscope-qwen38-max-product-v45-hybrid-generic").lifecycle(),
                        inventory.reusableReferenceIds().stream().sorted().toList(),
                        List.of("historical-v45-j1", "historical-v45-ledger", "n7",
                                "n7-closeout-evidence", "r5-v2", "r5p-v1", "r5p2")
                ),
                new ImageOnlyCertificationP0Report.ManifestProof(
                        FrozenImageOnlyCertificationManifest.VERSION,
                        manifest.manifestIdentity(), manifest.profileId(), manifest.profileSha256(),
                        manifest.corpusIdentity(), manifest.r1InfrastructureIdentity(),
                        manifest.evaluatorIdentity(), manifest.assignmentSeed(),
                        java.util.Arrays.stream(CertificationStage.scoredStages()).map(stage ->
                                new ImageOnlyCertificationP0Report.ThresholdProof(
                                        stage.name(), stage.caseCount(), stage.acceptanceThreshold())).toList(),
                        manifest.canariesForIndependentReplay(),
                        manifest.assignmentsForIndependentReplay()
                ),
                new ImageOnlyCertificationP0Report.LayeredR1Proof(
                        r1.evaluationIdentity(), r1.corpusIdentity(), r1.observedCaseCount(),
                        r1.global().metricsBps().size()
                ),
                new ImageOnlyCertificationP0Report.DryRunProof(
                        proof(canary), proof(dev), proof(finalStage), proof(negative),
                        proof(invalidKey)
                ),
                new ImageOnlyCertificationP0Report.AuthorizationProof(
                        0, 48, 1_000_000L,
                        "plans/live-canary-authorizations/image-only-profile-certification-authorization.schema.json",
                        "plans/live-canary-authorizations/TEMPLATE-image-only-profile-certification.json"
                ),
                new ImageOnlyCertificationP0Report.ExternalProviderProof(
                        runtime.providerAttempts(), runtime.providerReservations(),
                        runtime.externalProviderCostMicrosCny(), 0
                )
        );
    }

    private static DryRun evaluate(
            ImageOnlyCertificationEvaluator evaluator,
            FrozenImageOnlyCertificationManifest manifest,
            CertificationStage stage,
            int accepted,
            boolean includeFlags
    ) {
        var cases = manifest.stageView(stage).cases();
        var verdicts = new ArrayList<CertificationCaseVerdict>();
        for (var index = 0; index < cases.size(); index++) {
            var terminal = includeFlags && index == 2
                    ? CertificationTerminalState.COMPLETED
                    : includeFlags && index == 18
                    ? CertificationTerminalState.FAILED
                    : CertificationTerminalState.REVIEW_REQUIRED;
            verdicts.add(new CertificationCaseVerdict(
                    cases.get(index).caseId(), terminal,
                    index < accepted, includeFlags && index == 0 ? 7_999 : 9_000,
                    List.of(includeFlags && index == 1 ? "route-name" : "route_name")
            ));
        }
        return new DryRun(evaluator.evaluate(manifest, stage, verdicts), List.copyOf(verdicts));
    }

    private static DryRun evaluateInvalidKey(
            ImageOnlyCertificationEvaluator evaluator,
            FrozenImageOnlyCertificationManifest manifest
    ) {
        var cases = manifest.stageView(CertificationStage.CANARY_5).cases();
        var verdicts = new ArrayList<CertificationCaseVerdict>();
        for (var index = 0; index < cases.size(); index++) {
            verdicts.add(new CertificationCaseVerdict(
                    cases.get(index).caseId(), CertificationTerminalState.REVIEW_REQUIRED,
                    true, 9_000, List.of(index == 0 ? "RouteName" : "route_name")
            ));
        }
        return new DryRun(evaluator.evaluate(
                manifest, CertificationStage.CANARY_5, verdicts), List.copyOf(verdicts));
    }

    private static ImageOnlyCertificationP0Report.StageProof proof(
            DryRun dryRun
    ) {
        var result = dryRun.result();
        return new ImageOnlyCertificationP0Report.StageProof(
                result.stage().name(), result.acceptedCases(), result.totalCases(),
                result.passed(), result.evidenceIdentity(), dryRun.verdicts().stream().map(verdict ->
                        new ImageOnlyCertificationP0Report.VerdictProof(
                                verdict.caseId(), verdict.terminalState().name(),
                                verdict.manuallyAccepted(), verdict.confidenceBps(),
                                verdict.proposedKeys().stream().map(
                                        ImageOnlyCertificationP0Evidence::keyShape).toList()
                        )).toList());
    }

    private static String keyShape(String key) {
        if (key.matches("[a-z][a-z0-9]*(?:_[a-z0-9]+)*")) return "SNAKE_CASE";
        if (key.matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)+")) return "KEBAB_CASE";
        return "INVALID";
    }

    private static List<CertificationCanaryCase> syntheticCanaries() {
        var result = new ArrayList<CertificationCanaryCase>();
        for (var index = 1; index <= 5; index++) {
            result.add(new CertificationCanaryCase("p0-synthetic-canary-" + index,
                    String.format("%064x", index)));
        }
        return List.copyOf(result);
    }

    private record DryRun(
            CertificationStageEvaluation result,
            List<CertificationCaseVerdict> verdicts
    ) { }
}
