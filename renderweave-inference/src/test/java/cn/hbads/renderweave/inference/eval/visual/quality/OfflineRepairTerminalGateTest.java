package cn.hbads.renderweave.inference.eval.visual.quality;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfflineRepairTerminalGateTest {
    private final OfflineRepairTerminalGate gate = new OfflineRepairTerminalGate();
    private final OfflineRepairTerminalOutcomeJsonCodec codec =
            new OfflineRepairTerminalOutcomeJsonCodec();

    @Test
    void stopsPpStructureV3BeforeDevWhenTheSoleDecisionRequiresAnR5SuccessorSpec() {
        var decision = stopToSpecR5();
        var decisionIdentity = new R2R5TriggerDecisionJsonCodec().decisionIdentity(decision);
        var capabilities = ChallengerCapabilityAdmission.load();

        var outcome = gate.closeR2Challenger(
                OfflineRepairTerminalOutcome.Ticket.VRQ_08_PP_STRUCTUREV3_DEV_SHADOW,
                decision,
                decisionIdentity,
                capabilities);

        assertEquals(OfflineRepairTerminalOutcome.Disposition.STOPPED_FOR_R5_SUCCESSOR_SPEC,
                outcome.disposition());
        assertEquals("R5_TRIGGERED_REQUIRES_SUCCESSOR_SPEC", outcome.reasonCode());
        assertEquals(List.of(
                capabilities.identity(),
                capabilities.require("pp-structurev3").identity()), outcome.supportingIdentities());
        assertTrue(outcome.offlineWorkUsage().zeroWork());
        assertTrue(outcome.externalProviderUsage().zeroUsage());
        assertEquals(decisionIdentity, outcome.rootDecisionIdentity());

        var encoded = codec.write(outcome);
        assertArrayEquals(encoded, codec.write(codec.read(encoded)));
    }

    @Test
    void stopsTheIndependentTesseractBaselineWithoutInstallingOrExecutingIt() {
        var decision = stopToSpecR5();
        var decisionIdentity = new R2R5TriggerDecisionJsonCodec().decisionIdentity(decision);
        var capabilities = ChallengerCapabilityAdmission.load();

        var outcome = gate.closeR2Challenger(
                OfflineRepairTerminalOutcome.Ticket.VRQ_09_TESSERACT_DEV_BASELINE,
                decision,
                decisionIdentity,
                capabilities);

        assertEquals(OfflineRepairTerminalOutcome.Disposition.STOPPED_FOR_R5_SUCCESSOR_SPEC,
                outcome.disposition());
        assertEquals(List.of(
                capabilities.identity(),
                capabilities.require("tesseract-tsv-hocr").identity()),
                outcome.supportingIdentities());
        assertTrue(outcome.offlineWorkUsage().zeroWork());
        assertTrue(outcome.externalProviderUsage().zeroUsage());
    }

    @Test
    void blocksWinnerSelectionOnlyWhenBothStoppedDevOutcomesArePresent() {
        var decision = stopToSpecR5();
        var decisionIdentity = new R2R5TriggerDecisionJsonCodec().decisionIdentity(decision);
        var capabilities = ChallengerCapabilityAdmission.load();
        var pp = gate.closeR2Challenger(
                OfflineRepairTerminalOutcome.Ticket.VRQ_08_PP_STRUCTUREV3_DEV_SHADOW,
                decision, decisionIdentity, capabilities);
        var tesseract = gate.closeR2Challenger(
                OfflineRepairTerminalOutcome.Ticket.VRQ_09_TESSERACT_DEV_BASELINE,
                decision, decisionIdentity, capabilities);

        var outcome = gate.closeDownstream(
                OfflineRepairTerminalOutcome.Ticket.VRQ_10_SOLE_DEV_WINNER_SELECTION,
                decision,
                decisionIdentity,
                List.of(tesseract, pp));

        assertEquals(OfflineRepairTerminalOutcome.Disposition.BLOCKED_BY_PREDECESSOR,
                outcome.disposition());
        assertEquals("R2_DEV_REPORTS_UNAVAILABLE", outcome.reasonCode());
        assertEquals(List.of(codec.outcomeIdentity(pp), codec.outcomeIdentity(tesseract)).stream()
                        .sorted().toList(),
                outcome.supportingIdentities());
        assertTrue(outcome.offlineWorkUsage().zeroWork());
        assertThrows(IllegalArgumentException.class, () -> gate.closeDownstream(
                OfflineRepairTerminalOutcome.Ticket.VRQ_10_SOLE_DEV_WINNER_SELECTION,
                decision, decisionIdentity, List.of(pp)));
    }

    @Test
    void blocksHoldoutBeforeAnyGoldAccessWhenThereIsNoSoleDevWinner() {
        var decision = stopToSpecR5();
        var decisionIdentity = new R2R5TriggerDecisionJsonCodec().decisionIdentity(decision);
        var capabilities = ChallengerCapabilityAdmission.load();
        var pp = gate.closeR2Challenger(
                OfflineRepairTerminalOutcome.Ticket.VRQ_08_PP_STRUCTUREV3_DEV_SHADOW,
                decision, decisionIdentity, capabilities);
        var tesseract = gate.closeR2Challenger(
                OfflineRepairTerminalOutcome.Ticket.VRQ_09_TESSERACT_DEV_BASELINE,
                decision, decisionIdentity, capabilities);
        var selection = gate.closeDownstream(
                OfflineRepairTerminalOutcome.Ticket.VRQ_10_SOLE_DEV_WINNER_SELECTION,
                decision, decisionIdentity, List.of(pp, tesseract));

        var outcome = gate.closeDownstream(
                OfflineRepairTerminalOutcome.Ticket.VRQ_11_WINNER_HOLDOUT,
                decision,
                decisionIdentity,
                List.of(selection));

        assertEquals(OfflineRepairTerminalOutcome.Disposition.BLOCKED_BY_PREDECESSOR,
                outcome.disposition());
        assertEquals("R2_SOLE_WINNER_UNAVAILABLE", outcome.reasonCode());
        assertEquals(0, outcome.offlineWorkUsage().holdoutCasesAccessed());
        assertEquals(List.of(codec.outcomeIdentity(selection)), outcome.supportingIdentities());
    }

    @Test
    void doesNotStartTheImageOnlyReplayWithoutAQualifiedRepair() {
        var decision = stopToSpecR5();
        var decisionIdentity = new R2R5TriggerDecisionJsonCodec().decisionIdentity(decision);
        var holdout = holdoutOutcome(decision, decisionIdentity);

        var outcome = gate.closeDownstream(
                OfflineRepairTerminalOutcome.Ticket.VRQ_12_IMAGE_ONLY_SCRIPTED_REPLAY,
                decision,
                decisionIdentity,
                List.of(holdout));

        assertEquals("R2_QUALIFIED_REPAIR_UNAVAILABLE", outcome.reasonCode());
        assertEquals(0, outcome.offlineWorkUsage().scriptedWorkflowReplays());
        assertEquals(0, outcome.offlineWorkUsage().productWrites());
        assertEquals(List.of(codec.outcomeIdentity(holdout)), outcome.supportingIdentities());
    }

    @Test
    void recordsThatIndependentAdmissionCannotRunWithoutReplayEvidence() {
        var decision = stopToSpecR5();
        var decisionIdentity = new R2R5TriggerDecisionJsonCodec().decisionIdentity(decision);
        var holdout = holdoutOutcome(decision, decisionIdentity);
        var replay = gate.closeDownstream(
                OfflineRepairTerminalOutcome.Ticket.VRQ_12_IMAGE_ONLY_SCRIPTED_REPLAY,
                decision, decisionIdentity, List.of(holdout));

        var outcome = gate.closeDownstream(
                OfflineRepairTerminalOutcome.Ticket.VRQ_13_INDEPENDENT_A2_ADMISSION,
                decision,
                decisionIdentity,
                List.of(replay));

        assertEquals("IMAGE_ONLY_REPLAY_UNAVAILABLE", outcome.reasonCode());
        assertEquals(0, outcome.offlineWorkUsage().independentAdmissionReplays());
        assertEquals(List.of(codec.outcomeIdentity(replay)), outcome.supportingIdentities());
    }

    @Test
    void deniesFreshLiveRequestEligibilityWithoutIndependentOfflineAdmission() {
        var decision = stopToSpecR5();
        var decisionIdentity = new R2R5TriggerDecisionJsonCodec().decisionIdentity(decision);
        var holdout = holdoutOutcome(decision, decisionIdentity);
        var replay = gate.closeDownstream(
                OfflineRepairTerminalOutcome.Ticket.VRQ_12_IMAGE_ONLY_SCRIPTED_REPLAY,
                decision, decisionIdentity, List.of(holdout));
        var admission = gate.closeDownstream(
                OfflineRepairTerminalOutcome.Ticket.VRQ_13_INDEPENDENT_A2_ADMISSION,
                decision, decisionIdentity, List.of(replay));

        var outcome = gate.closeDownstream(
                OfflineRepairTerminalOutcome.Ticket.VRQ_14_FRESH_LIVE_REQUEST_ELIGIBILITY,
                decision,
                decisionIdentity,
                List.of(admission));

        assertEquals(OfflineRepairTerminalOutcome.Disposition.LIVE_J1_REQUEST_NOT_ELIGIBLE,
                outcome.disposition());
        assertEquals("INDEPENDENT_OFFLINE_ADMISSION_UNAVAILABLE", outcome.reasonCode());
        assertEquals(0, outcome.offlineWorkUsage().apiKeyReads());
        assertTrue(outcome.externalProviderUsage().zeroUsage());
        assertEquals(List.of(codec.outcomeIdentity(admission)), outcome.supportingIdentities());
    }

    @Test
    void refusesToCloseAChallengerAgainstAnythingExceptTheExactStopToSpecR5Decision() {
        var decision = stopToSpecR5();
        var identity = new R2R5TriggerDecisionJsonCodec().decisionIdentity(decision);
        var capabilities = ChallengerCapabilityAdmission.load();

        assertThrows(IllegalArgumentException.class, () -> gate.closeR2Challenger(
                OfflineRepairTerminalOutcome.Ticket.VRQ_08_PP_STRUCTUREV3_DEV_SHADOW,
                decision,
                identity.substring(0, identity.length() - 1) + (identity.endsWith("0") ? "1" : "0"),
                capabilities));
        var alternateDecision = new R2R5TriggerDecision(
                decision.decisionVersion(),
                FrozenQualityEvidencePack.VERSION + ":" + "a".repeat(64),
                decision.routes(),
                decision.overallDisposition(),
                decision.externalProviderUsage());
        var alternateIdentity = new R2R5TriggerDecisionJsonCodec().decisionIdentity(alternateDecision);
        assertThrows(IllegalArgumentException.class, () -> gate.closeR2Challenger(
                OfflineRepairTerminalOutcome.Ticket.VRQ_08_PP_STRUCTUREV3_DEV_SHADOW,
                alternateDecision,
                alternateIdentity,
                capabilities));
        assertThrows(IllegalArgumentException.class, () -> gate.closeR2Challenger(
                OfflineRepairTerminalOutcome.Ticket.VRQ_10_SOLE_DEV_WINNER_SELECTION,
                decision,
                identity,
                capabilities));
    }

    private static R2R5TriggerDecision stopToSpecR5() {
        try (var input = OfflineRepairTerminalGateTest.class.getResourceAsStream(
                "/visual-eval/quality-repair/authoritative-vrq07-decision.json")) {
            if (input == null) throw new IllegalStateException("AUTHORITATIVE_VRQ07_FIXTURE_MISSING");
            return new R2R5TriggerDecisionJsonCodec().read(input.readAllBytes());
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("AUTHORITATIVE_VRQ07_FIXTURE_UNREADABLE", failure);
        }
    }

    private OfflineRepairTerminalOutcome holdoutOutcome(
            R2R5TriggerDecision decision,
            String decisionIdentity
    ) {
        var capabilities = ChallengerCapabilityAdmission.load();
        var pp = gate.closeR2Challenger(
                OfflineRepairTerminalOutcome.Ticket.VRQ_08_PP_STRUCTUREV3_DEV_SHADOW,
                decision, decisionIdentity, capabilities);
        var tesseract = gate.closeR2Challenger(
                OfflineRepairTerminalOutcome.Ticket.VRQ_09_TESSERACT_DEV_BASELINE,
                decision, decisionIdentity, capabilities);
        var selection = gate.closeDownstream(
                OfflineRepairTerminalOutcome.Ticket.VRQ_10_SOLE_DEV_WINNER_SELECTION,
                decision, decisionIdentity, List.of(pp, tesseract));
        return gate.closeDownstream(
                OfflineRepairTerminalOutcome.Ticket.VRQ_11_WINNER_HOLDOUT,
                decision, decisionIdentity, List.of(selection));
    }

}
