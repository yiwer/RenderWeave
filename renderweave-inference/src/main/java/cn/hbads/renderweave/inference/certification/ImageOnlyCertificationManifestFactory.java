package cn.hbads.renderweave.inference.certification;

import cn.hbads.renderweave.inference.eval.visual.LayeredR1Evaluation;
import cn.hbads.renderweave.inference.eval.visual.LayeredVisualCorpus;
import cn.hbads.renderweave.inference.eval.visual.LayeredVisualEvaluator;
import cn.hbads.renderweave.inference.profile.InferenceProfileRegistry;
import cn.hbads.renderweave.inference.provider.ProfileRunBudgetPolicy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

public final class ImageOnlyCertificationManifestFactory {
    public static final String EVALUATOR_VERSION =
            "renderweave-image-only-certification-evaluator/1.0";

    public FrozenImageOnlyCertificationManifest create(
            String profileSha256,
            List<CertificationCanaryCase> canaryCases,
            String assignmentSeed
    ) {
        var profile = new InferenceProfileRegistry().require(
                ProfileRunBudgetPolicy.IMAGE_ONLY_V46_PROFILE_ID);
        if (!profile.canonicalSha256().equals(profileSha256)) {
            throw new IllegalArgumentException("CERTIFICATION_PROFILE_SHA_DRIFT");
        }
        canaryCases = List.copyOf(canaryCases);
        if (canaryCases.size() != 5
                || new HashSet<>(canaryCases.stream().map(CertificationCanaryCase::caseId).toList()).size() != 5
                || new HashSet<>(canaryCases.stream().map(CertificationCanaryCase::artifactSha256).toList()).size() != 5) {
            throw new IllegalArgumentException("CERTIFICATION_CANARY_SET_INVALID");
        }
        if (assignmentSeed == null || !assignmentSeed.matches("[a-z0-9][a-z0-9-]{2,95}")) {
            throw new IllegalArgumentException("CERTIFICATION_ASSIGNMENT_SEED_INVALID");
        }
        var orderedCanaries = canaryCases.stream().sorted(Comparator.comparing(
                CertificationCanaryCase::caseId)).toList();
        var corpus = new LayeredVisualCorpus();
        var evaluatorIdentity = evaluatorIdentity(corpus);
        var orderedCases = corpus.cases().stream().sorted(Comparator
                .comparing((LayeredVisualCorpus.Case item) -> CertificationIdentity.sha256Text(
                        assignmentSeed + "\u0000" + item.caseIdentity()))
                .thenComparing(LayeredVisualCorpus.Case::caseId)).toList();
        var assignments = new ArrayList<CertificationCaseAssignment>();
        for (var index = 0; index < orderedCases.size(); index++) {
            var item = orderedCases.get(index);
            var role = index < 20 ? CertificationCaseRole.DEV_VISIBLE
                    : index < 40 ? CertificationCaseRole.FINAL_DEV : CertificationCaseRole.HOLDOUT;
            assignments.add(new CertificationCaseAssignment(index, item.caseId(),
                    item.renderIdentity().substring("render-sha256:".length()), item.caseIdentity(), role));
        }
        var material = new ArrayList<String>();
        material.add(FrozenImageOnlyCertificationManifest.VERSION);
        material.add(ProfileRunBudgetPolicy.IMAGE_ONLY_V46_PROFILE_ID);
        material.add(profileSha256);
        material.add(corpus.corpusIdentity());
        material.add(LayeredR1Evaluation.VERSION);
        material.add(evaluatorIdentity);
        material.add(assignmentSeed);
        for (var stage : CertificationStage.values()) {
            material.add("threshold|" + stage.name() + "|" + stage.caseCount()
                    + "|" + stage.acceptanceThreshold());
        }
        orderedCanaries.forEach(item -> material.add(
                "canary|" + item.caseId() + "|" + item.artifactSha256()));
        assignments.forEach(item -> material.add("assignment|" + item.rank() + "|" + item.role()
                + "|" + item.caseId() + "|" + item.caseSha256() + "|" + item.caseIdentity()));
        var identity = FrozenImageOnlyCertificationManifest.VERSION + ":"
                + CertificationIdentity.sha256(material);
        return new FrozenImageOnlyCertificationManifest(identity, profileSha256, corpus.corpusIdentity(),
                evaluatorIdentity, LayeredR1Evaluation.VERSION, assignmentSeed,
                orderedCanaries, assignments);
    }

    private static String evaluatorIdentity(LayeredVisualCorpus corpus) {
        return EVALUATOR_VERSION + ":" + CertificationIdentity.sha256(List.of(
                EVALUATOR_VERSION,
                LayeredR1Evaluation.VERSION,
                LayeredVisualEvaluator.VERSION,
                corpus.version(),
                corpus.corpusIdentity(),
                "terminal=REVIEW_REQUIRED|COMPLETED",
                "manual-verdict=required",
                "thresholds=5/5|18/20|54/60",
                "low-confidence=7999bps-flag-only",
                "keys=snake_case|kebab-manual-normalization"
        ));
    }
}
