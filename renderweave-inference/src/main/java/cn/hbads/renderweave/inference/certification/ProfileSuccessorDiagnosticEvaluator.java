package cn.hbads.renderweave.inference.certification;

import java.util.List;
import java.util.Objects;

/** Non-scoring evaluator: it can report PASS but cannot create certification stage evidence. */
public final class ProfileSuccessorDiagnosticEvaluator {
    private static final String EVIDENCE_VERSION =
            "renderweave-image-only-profile-successor-diagnostic-evidence/1.0";

    public ProfileSuccessorDiagnosticEvaluation evaluate(
            ProfileSuccessorDiagnosticManifest manifest,
            CertificationCaseVerdict verdict
    ) {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(verdict, "verdict");
        if (!manifest.diagnosticCase().caseId().equals(verdict.caseId())) {
            throw new IllegalArgumentException(
                    "PROFILE_SUCCESSOR_DIAGNOSTIC_CASE_MISMATCH");
        }
        var passed = verdict.terminalState() == CertificationTerminalState.REVIEW_REQUIRED
                && verdict.manuallyAccepted();
        var evidenceIdentity = EVIDENCE_VERSION + ":" + CertificationIdentity.sha256(List.of(
                EVIDENCE_VERSION,
                manifest.manifestIdentity(),
                manifest.evaluatorIdentity(),
                verdict.caseId(),
                verdict.terminalState().name(),
                Boolean.toString(verdict.manuallyAccepted()),
                "passed=" + passed,
                "certification-credit=0",
                "grant=false"
        ));
        return new ProfileSuccessorDiagnosticEvaluation(
                verdict.terminalState(), verdict.manuallyAccepted(), passed, evidenceIdentity
        );
    }
}
