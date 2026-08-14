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
    void refusesToCloseAChallengerAgainstAnythingExceptTheExactStopToSpecR5Decision() {
        var decision = stopToSpecR5();
        var identity = new R2R5TriggerDecisionJsonCodec().decisionIdentity(decision);
        var capabilities = ChallengerCapabilityAdmission.load();

        assertThrows(IllegalArgumentException.class, () -> gate.closeR2Challenger(
                OfflineRepairTerminalOutcome.Ticket.VRQ_08_PP_STRUCTUREV3_DEV_SHADOW,
                decision,
                identity.substring(0, identity.length() - 1) + "0",
                capabilities));
        assertThrows(IllegalArgumentException.class, () -> gate.closeR2Challenger(
                OfflineRepairTerminalOutcome.Ticket.VRQ_10_SOLE_DEV_WINNER_SELECTION,
                decision,
                identity,
                capabilities));
    }

    private static R2R5TriggerDecision stopToSpecR5() {
        var packIdentity = FrozenQualityEvidencePack.VERSION + ":" + "a".repeat(64);
        return new R2R5TriggerDecision(
                R2R5TriggerDecision.VERSION,
                packIdentity,
                List.of(
                        route(FrozenQualityEvidencePack.Route.R2,
                                FrozenQualityEvidencePack.PredicateResult.MISSING,
                                R2R5TriggerDecision.RouteDisposition.EVIDENCE_REQUIRED),
                        route(FrozenQualityEvidencePack.Route.R3,
                                FrozenQualityEvidencePack.PredicateResult.MISSING,
                                R2R5TriggerDecision.RouteDisposition.EVIDENCE_REQUIRED),
                        route(FrozenQualityEvidencePack.Route.R4,
                                FrozenQualityEvidencePack.PredicateResult.FAIL,
                                R2R5TriggerDecision.RouteDisposition.REJECTED_BY_CURRENT_EVIDENCE),
                        route(FrozenQualityEvidencePack.Route.R5,
                                FrozenQualityEvidencePack.PredicateResult.PASS,
                                R2R5TriggerDecision.RouteDisposition.TRIGGERED)),
                R2R5TriggerDecision.OverallDisposition.STOP_TO_SPEC_R5,
                new FrozenQualityEvidencePack.ExternalProviderUsage(0, 0, 0));
    }

    private static R2R5TriggerDecision.RouteDecision route(
            FrozenQualityEvidencePack.Route route,
            FrozenQualityEvidencePack.PredicateResult result,
            R2R5TriggerDecision.RouteDisposition disposition
    ) {
        return new R2R5TriggerDecision.RouteDecision(
                route,
                result == FrozenQualityEvidencePack.PredicateResult.PASS,
                disposition,
                List.of(new FrozenQualityEvidencePack.PredicateEvidence(
                        route.name() + "_TEST_PREDICATE",
                        "A1_A2",
                        result,
                        route.name() + "_TEST_REASON",
                        "sha256:" + Integer.toHexString(route.ordinal()).repeat(64).substring(0, 64))));
    }
}
