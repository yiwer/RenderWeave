package cn.hbads.renderweave.inference.certification;

import java.util.Objects;

public record ProfileSuccessorDiagnosticEvaluation(
        CertificationTerminalState terminalState,
        boolean manuallyAccepted,
        boolean passed,
        String evidenceIdentity
) {
    public ProfileSuccessorDiagnosticEvaluation {
        Objects.requireNonNull(terminalState, "terminalState");
        if (passed != (terminalState == CertificationTerminalState.REVIEW_REQUIRED
                && manuallyAccepted)) {
            throw new IllegalArgumentException(
                    "PROFILE_SUCCESSOR_DIAGNOSTIC_PASS_CONTRACT_INVALID");
        }
        if (evidenceIdentity == null || !evidenceIdentity.matches(
                "renderweave-image-only-profile-successor-diagnostic-evidence/1\\.0:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "PROFILE_SUCCESSOR_DIAGNOSTIC_EVIDENCE_IDENTITY_INVALID");
        }
    }
}
