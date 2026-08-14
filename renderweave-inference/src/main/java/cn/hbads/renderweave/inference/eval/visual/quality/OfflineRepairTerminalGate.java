package cn.hbads.renderweave.inference.eval.visual.quality;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** The fail-closed seam for conditional tickets after an authoritative route stop. */
public final class OfflineRepairTerminalGate {
    public static final String AUTHORITATIVE_EVIDENCE_PACK_IDENTITY =
            "renderweave-frozen-quality-evidence-pack/1.0:"
                    + "1b580f89085fcda6ad75a569819c4327eb34e293d391300b4dce5be799ffe6a9";
    public static final String AUTHORITATIVE_DECISION_IDENTITY =
            "renderweave-r2r5-trigger-decision/1.0:"
                    + "a243b41fd440e36e4b964665f926a7e2f85318858fbdc16b3be0310606c8fa9a";

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

    public OfflineRepairTerminalOutcome closeDownstream(
            OfflineRepairTerminalOutcome.Ticket ticket,
            R2R5TriggerDecision rootDecision,
            String rootDecisionIdentity,
            List<OfflineRepairTerminalOutcome> predecessors
    ) {
        Objects.requireNonNull(ticket, "ticket");
        rootDecision = requireStopToSpecR5(rootDecision, rootDecisionIdentity);
        var expectedTickets = switch (ticket) {
            case VRQ_10_SOLE_DEV_WINNER_SELECTION -> Set.of(
                    OfflineRepairTerminalOutcome.Ticket.VRQ_08_PP_STRUCTUREV3_DEV_SHADOW,
                    OfflineRepairTerminalOutcome.Ticket.VRQ_09_TESSERACT_DEV_BASELINE);
            case VRQ_11_WINNER_HOLDOUT -> Set.of(
                    OfflineRepairTerminalOutcome.Ticket.VRQ_10_SOLE_DEV_WINNER_SELECTION);
            case VRQ_12_IMAGE_ONLY_SCRIPTED_REPLAY -> Set.of(
                    OfflineRepairTerminalOutcome.Ticket.VRQ_11_WINNER_HOLDOUT);
            case VRQ_13_INDEPENDENT_A2_ADMISSION -> Set.of(
                    OfflineRepairTerminalOutcome.Ticket.VRQ_12_IMAGE_ONLY_SCRIPTED_REPLAY);
            case VRQ_14_FRESH_LIVE_REQUEST_ELIGIBILITY -> Set.of(
                    OfflineRepairTerminalOutcome.Ticket.VRQ_13_INDEPENDENT_A2_ADMISSION);
            default -> throw invalid("OFFLINE_TERMINAL_DOWNSTREAM_TICKET_INVALID");
        };
        predecessors = List.copyOf(Objects.requireNonNull(predecessors, "predecessors"));
        if (predecessors.size() != expectedTickets.size()
                || predecessors.stream().anyMatch(Objects::isNull)
                || !Set.copyOf(predecessors.stream().map(
                OfflineRepairTerminalOutcome::ticket).toList()).equals(expectedTickets)
                || predecessors.stream().anyMatch(outcome ->
                !rootDecisionIdentity.equals(outcome.rootDecisionIdentity())
                        || outcome.rootDisposition()
                        != R2R5TriggerDecision.OverallDisposition.STOP_TO_SPEC_R5)) {
            throw invalid("OFFLINE_TERMINAL_PREDECESSOR_SET_INVALID");
        }
        var codec = new OfflineRepairTerminalOutcomeJsonCodec();
        return new OfflineRepairTerminalOutcome(
                OfflineRepairTerminalOutcome.VERSION,
                ticket,
                rootDecisionIdentity,
                rootDecision.overallDisposition(),
                predecessors.stream().map(codec::outcomeIdentity).toList(),
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
        if (!AUTHORITATIVE_DECISION_IDENTITY.equals(expectedIdentity)
                || !AUTHORITATIVE_EVIDENCE_PACK_IDENTITY.equals(decision.evidencePackIdentity())
                || !new R2R5TriggerDecisionJsonCodec().decisionIdentity(decision).equals(expectedIdentity)
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
