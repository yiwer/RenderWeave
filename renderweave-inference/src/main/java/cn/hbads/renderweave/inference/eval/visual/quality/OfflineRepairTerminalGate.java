package cn.hbads.renderweave.inference.eval.visual.quality;

import java.util.List;
import java.util.Objects;

/** The fail-closed seam for conditional tickets after an authoritative route stop. */
public final class OfflineRepairTerminalGate {
    public OfflineRepairTerminalOutcome closeR2Challenger(
            OfflineRepairTerminalOutcome.Ticket ticket,
            R2R5TriggerDecision rootDecision,
            String rootDecisionIdentity,
            ChallengerCapabilityAdmission capabilities
    ) {
        Objects.requireNonNull(ticket, "ticket");
        rootDecision = requireStopToSpecR5(rootDecision, rootDecisionIdentity);
        capabilities = Objects.requireNonNull(capabilities, "capabilities");
        var challengerId = switch (ticket) {
            case VRQ_08_PP_STRUCTUREV3_DEV_SHADOW -> "pp-structurev3";
            case VRQ_09_TESSERACT_DEV_BASELINE -> "tesseract-tsv-hocr";
            default -> throw invalid("OFFLINE_TERMINAL_R2_TICKET_INVALID");
        };
        var capability = capabilities.require(challengerId);
        if (capability.admissionDisposition()
                != ChallengerCapabilityAdmission.AdmissionDisposition.NOT_ADMITTED
                || capability.executable()) {
            throw invalid("OFFLINE_TERMINAL_CAPABILITY_STATE_DRIFT");
        }
        return new OfflineRepairTerminalOutcome(
                OfflineRepairTerminalOutcome.VERSION,
                ticket,
                rootDecisionIdentity,
                rootDecision.overallDisposition(),
                List.of(capabilities.identity(), capability.identity()),
                OfflineRepairTerminalOutcome.expectedDisposition(ticket),
                OfflineRepairTerminalOutcome.expectedReasonCode(ticket),
                OfflineRepairTerminalOutcome.OfflineWorkUsage.zero(),
                new FrozenQualityEvidencePack.ExternalProviderUsage(0, 0, 0));
    }

    private static R2R5TriggerDecision requireStopToSpecR5(
            R2R5TriggerDecision decision,
            String expectedIdentity
    ) {
        Objects.requireNonNull(decision, "rootDecision");
        if (!new R2R5TriggerDecisionJsonCodec().decisionIdentity(decision).equals(expectedIdentity)
                || decision.overallDisposition()
                != R2R5TriggerDecision.OverallDisposition.STOP_TO_SPEC_R5
                || decision.requireRoute(FrozenQualityEvidencePack.Route.R5).disposition()
                != R2R5TriggerDecision.RouteDisposition.TRIGGERED
                || !decision.requireRoute(FrozenQualityEvidencePack.Route.R5).triggerSatisfied()
                || !decision.externalProviderUsage().zeroUsage()) {
            throw invalid("OFFLINE_TERMINAL_ROOT_DECISION_INVALID");
        }
        return decision;
    }

    private static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }
}
