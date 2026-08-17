package cn.hbads.renderweave.inference.certification;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;

public final class ImageOnlyCertificationEvaluator {
    private static final java.util.regex.Pattern SNAKE =
            java.util.regex.Pattern.compile("[a-z][a-z0-9]*(?:_[a-z0-9]+)*");
    private static final java.util.regex.Pattern KEBAB =
            java.util.regex.Pattern.compile("[a-z][a-z0-9]*(?:-[a-z0-9]+)+");

    public CertificationStageEvaluation evaluate(
            FrozenImageOnlyCertificationManifest manifest,
            CertificationStage stage,
            List<CertificationCaseVerdict> verdicts
    ) {
        var expected = manifest.stageView(stage);
        verdicts = List.copyOf(verdicts);
        var expectedIds = expected.cases().stream().map(CertificationStageCase::caseId)
                .collect(java.util.stream.Collectors.toSet());
        var observedIds = new HashSet<String>();
        if (verdicts.size() != expected.cases().size()
                || verdicts.stream().anyMatch(item -> !observedIds.add(item.caseId()))
                || !observedIds.equals(expectedIds)) {
            throw new IllegalArgumentException("CERTIFICATION_STAGE_CASE_ACCOUNTING_INVALID");
        }
        var byId = verdicts.stream().collect(java.util.stream.Collectors.toMap(
                CertificationCaseVerdict::caseId, item -> item));
        var flags = new LinkedHashMap<String, List<String>>();
        var accepted = 0;
        var evidenceMaterial = new ArrayList<String>();
        evidenceMaterial.add(manifest.manifestIdentity());
        evidenceMaterial.add(stage.name());
        for (var expectedCase : expected.cases()) {
            var verdict = byId.get(expectedCase.caseId());
            var caseFlags = new ArrayList<String>();
            if (verdict.confidenceBps() < 8_000) caseFlags.add("LOW_CONFIDENCE_REVIEW_FLAG");
            var keyContractValid = true;
            for (var key : verdict.proposedKeys()) {
                if (SNAKE.matcher(key).matches()) continue;
                if (KEBAB.matcher(key).matches()) {
                    caseFlags.add("KEBAB_CASE_MANUAL_NORMALIZATION_REQUIRED");
                } else {
                    caseFlags.add("FIELD_KEY_CONTRACT_INVALID");
                    keyContractValid = false;
                }
            }
            var terminalEligible = verdict.terminalState() == CertificationTerminalState.REVIEW_REQUIRED
                    || verdict.terminalState() == CertificationTerminalState.COMPLETED;
            var caseAccepted = terminalEligible && verdict.manuallyAccepted() && keyContractValid;
            if (caseAccepted) accepted++;
            if (!caseFlags.isEmpty()) flags.put(verdict.caseId(), List.copyOf(caseFlags));
            evidenceMaterial.add(verdict.caseId() + "|" + verdict.terminalState() + "|"
                    + verdict.manuallyAccepted() + "|" + verdict.confidenceBps() + "|"
                    + keyContractValid + "|" + caseAccepted + "|" + String.join(",", caseFlags));
        }
        var passed = accepted >= stage.acceptanceThreshold();
        evidenceMaterial.add("accepted=" + accepted);
        evidenceMaterial.add("passed=" + passed);
        var evidenceIdentity = "renderweave-image-only-certification-stage-evidence/1.0:"
                + CertificationIdentity.sha256(evidenceMaterial);
        return new CertificationStageEvaluation(stage, accepted, expected.cases().size(), passed,
                evidenceIdentity, flags);
    }
}
