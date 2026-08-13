package cn.hbads.renderweave.inference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import cn.hbads.renderweave.inference.eval.visual.N7QualificationProtocol;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class N7LiveAdmissionGateTest {
    private static final Instant NOW = Instant.parse("2026-08-13T04:00:00Z");
    private static final String EVALUATION_IDENTITY =
            "renderweave-visual-evaluation-tree-sha256/2:" + "a".repeat(64);

    @Test
    void plusCanaryContractBindsEveryExactJ1Dimension() {
        assertEquals("snapshot-sha256:da922ed9f778f98eb364ce967bf617cb8f14633dd40c028aee6550eb7d258db9",
                N7QualificationProtocol.load().plus().profileSnapshotIdentity());
        var contract = N7LiveTicketContract.plusCanary();

        assertEquals("N7-04", contract.ticketId());
        assertEquals("DASHSCOPE", contract.provider());
        assertEquals("qwen3.7-plus", contract.model());
        assertEquals("dashscope-qwen37-plus-product-v45-hybrid-generic", contract.profileId());
        assertEquals("da922ed9f778f98eb364ce967bf617cb8f14633dd40c028aee6550eb7d258db9",
                contract.profileSnapshotSha256());
        assertEquals("renderweave-inference-pipeline/4.28", contract.pipelineVersion());
        assertEquals("renderweave-visual-elements-prompt/12.0", contract.elementPromptVersion());
        assertEquals("renderweave-visual-hierarchy-prompt/7.0", contract.hierarchyPromptVersion());
        assertEquals("renderweave-visual-bindings-prompt/4.0", contract.bindingPromptVersion());
        assertEquals("renderweave-visual-stage-corpus/2.0", contract.corpusVersion());
        assertTrue(contract.corpusIdentity().startsWith("renderweave-visual-stage-corpus/2.0:"));
        assertTrue(contract.evaluatorIdentity().startsWith("renderweave-layered-evaluator/1.0:"));
        assertEquals(5, contract.caseIds().size());
        assertEquals(35, contract.maximumProviderAttempts());
        assertEquals(500_000, contract.maximumTotalTokens());
        assertEquals(5_000_000, contract.maximumCostMicrosCny());
        assertEquals(5, contract.maximumCasesPerBatch());
        assertEquals(86_400, contract.maximumAuthorizationWindowSeconds());
        assertEquals("REPOSITORY_SYNTHETIC_ONLY", contract.inputClassification());
        assertTrue(contract.contractIdentity().matches(
                "renderweave-n7-live-ticket-contract/1\\.0:[0-9a-f]{64}"));
    }

    @Test
    void proposedAndEveryExactBindingDriftFailBeforeAdmission() {
        var contract = N7LiveTicketContract.plusCanary();
        var proposed = authorization(contract, "PROPOSED", EVALUATION_IDENTITY,
                contract.profileSnapshotSha256(), contract.caseIds(), contract.contractIdentity(),
                NOW.minusSeconds(60).toString(), NOW.plusSeconds(3600).toString(),
                contract.maximumProviderAttempts(), contract.maximumTotalTokens(),
                contract.maximumCostMicrosCny());

        assertCode("N7_LIVE_AUTHORIZATION_NOT_OPEN",
                () -> N7LiveAdmissionGate.requireExactAuthorization(
                        contract, proposed, EVALUATION_IDENTITY, NOW));
        assertCode("N7_LIVE_EVALUATION_IDENTITY_MISMATCH",
                () -> N7LiveAdmissionGate.requireExactAuthorization(contract,
                        authorization(contract, "OPEN",
                                "renderweave-visual-evaluation-tree-sha256/2:" + "b".repeat(64),
                                contract.profileSnapshotSha256(), contract.caseIds(),
                                contract.contractIdentity(), NOW.minusSeconds(60).toString(),
                                NOW.plusSeconds(3600).toString(), contract.maximumProviderAttempts(),
                                contract.maximumTotalTokens(), contract.maximumCostMicrosCny()),
                        EVALUATION_IDENTITY, NOW));
        assertCode("N7_LIVE_PROFILE_SNAPSHOT_MISMATCH",
                () -> N7LiveAdmissionGate.requireExactAuthorization(contract,
                        authorization(contract, "OPEN", EVALUATION_IDENTITY, "b".repeat(64),
                                contract.caseIds(), contract.contractIdentity(),
                                NOW.minusSeconds(60).toString(), NOW.plusSeconds(3600).toString(),
                                contract.maximumProviderAttempts(), contract.maximumTotalTokens(),
                                contract.maximumCostMicrosCny()), EVALUATION_IDENTITY, NOW));
        assertCode("N7_LIVE_CASE_ASSIGNMENT_MISMATCH",
                () -> N7LiveAdmissionGate.requireExactAuthorization(contract,
                        authorization(contract, "OPEN", EVALUATION_IDENTITY,
                                contract.profileSnapshotSha256(), List.of(
                                        contract.caseIds().get(0), contract.caseIds().get(1),
                                        contract.caseIds().get(2), contract.caseIds().get(3),
                                        "transit-board-v1"),
                                contract.contractIdentity(), NOW.minusSeconds(60).toString(),
                                NOW.plusSeconds(3600).toString(), contract.maximumProviderAttempts(),
                                contract.maximumTotalTokens(), contract.maximumCostMicrosCny()),
                        EVALUATION_IDENTITY, NOW));
        assertCode("N7_LIVE_EXACT_J1_MISMATCH",
                () -> N7LiveAdmissionGate.requireExactAuthorization(contract,
                        authorization(contract, "OPEN", EVALUATION_IDENTITY,
                                contract.profileSnapshotSha256(), contract.caseIds(),
                                "renderweave-n7-live-ticket-contract/1.0:" + "c".repeat(64),
                                NOW.minusSeconds(60).toString(), NOW.plusSeconds(3600).toString(),
                                contract.maximumProviderAttempts(), contract.maximumTotalTokens(),
                                contract.maximumCostMicrosCny()), EVALUATION_IDENTITY, NOW));
        assertCode("N7_LIVE_AUTHORIZATION_EXPIRED",
                () -> N7LiveAdmissionGate.requireExactAuthorization(contract,
                        authorization(contract, "OPEN", EVALUATION_IDENTITY,
                                contract.profileSnapshotSha256(), contract.caseIds(),
                                contract.contractIdentity(), NOW.minusSeconds(3600).toString(),
                                NOW.toString(), contract.maximumProviderAttempts(),
                                contract.maximumTotalTokens(), contract.maximumCostMicrosCny()),
                        EVALUATION_IDENTITY, NOW));
        assertCode("N7_LIVE_AUTHORIZATION_BUDGET_MISMATCH",
                () -> N7LiveAdmissionGate.requireExactAuthorization(contract,
                        authorization(contract, "OPEN", EVALUATION_IDENTITY,
                                contract.profileSnapshotSha256(), contract.caseIds(),
                                contract.contractIdentity(), NOW.minusSeconds(60).toString(),
                                NOW.plusSeconds(3600).toString(),
                                contract.maximumProviderAttempts() - 1,
                                contract.maximumTotalTokens(), contract.maximumCostMicrosCny()),
                        EVALUATION_IDENTITY, NOW));
    }

    @Test
    void exactOpenAuthorizationIsAdmittedOnlyWithinItsBoundWindow() {
        var contract = N7LiveTicketContract.plusCanary();
        var open = authorization(contract, "OPEN", EVALUATION_IDENTITY,
                contract.profileSnapshotSha256(), contract.caseIds(), contract.contractIdentity(),
                NOW.minusSeconds(60).toString(), NOW.plusSeconds(3600).toString(),
                contract.maximumProviderAttempts(), contract.maximumTotalTokens(),
                contract.maximumCostMicrosCny());

        N7LiveAdmissionGate.requireExactAuthorization(contract, open, EVALUATION_IDENTITY, NOW);
        open.requireCorpus(new cn.hbads.renderweave.inference.eval.visual.LayeredVisualCorpus());
    }

    @Test
    void existingGoalLedgerIsReadOnlyReconstructedAndNonterminalStateBlocks(@TempDir Path root)
            throws Exception {
        var goal = root.resolve(VisualEvaluationGoalBudget.GOAL_ID);
        Files.createDirectories(goal);
        Files.writeString(goal.resolve("goal-budget.lock"), "", StandardCharsets.UTF_8);
        Files.writeString(goal.resolve("goal-budget.guard.json"), """
                {"guardVersion":"renderweave-visual-evaluation-goal-guard/4.0",
                 "goalId":"renderweave-visual-recognition-vnext-20260810",
                 "maximumTokensPerModel":1500000,"maximumAttemptsPerModel":180,
                 "maximumCostMicrosCnyByModel":{"qwen3.8-max":18000000,
                 "qwen3.7-plus":10000000,"qwen3.7-flash":10000000}}
                """, StandardCharsets.UTF_8);
        Files.writeString(goal.resolve("goal-budget.json"), stateWithReservations(List.of(
                reservation("qwen3.7-plus", "SETTLED", 7_000L, 21_000L),
                reservation("qwen3.7-flash-2026-07-15", "RESERVED", null, null))),
                StandardCharsets.UTF_8);

        var before = Files.readAllBytes(goal.resolve("goal-budget.json"));
        var audit = VisualEvaluationGoalBudget.inspectExisting(goal, JsonMapper.builder().build());

        assertEquals(2, audit.totalReservations());
        assertEquals(1, audit.nonTerminalReservations());
        assertEquals(1, audit.slots().get("qwen3.7-plus").attempts());
        assertEquals(7_000, audit.slots().get("qwen3.7-plus").tokens());
        assertEquals(21_000, audit.slots().get("qwen3.7-plus").costMicrosCny());
        assertEquals(1, audit.slots().get("qwen3.7-flash").attempts());
        assertTrue(java.util.Arrays.equals(before,
                Files.readAllBytes(goal.resolve("goal-budget.json"))));
        assertCode("N7_LIVE_GOAL_NONTERMINAL_RESERVATION",
                () -> N7LiveAdmissionGate.requireGoalReady(
                        N7LiveTicketContract.plusCanary(), audit));
    }

    @Test
    void missingOrOvercommittedGoalAuthorityFailsClosedWithoutCreatingFiles(@TempDir Path root)
            throws Exception {
        var missing = root.resolve("missing");
        assertCode("VISUAL_EVALUATION_GOAL_BUDGET_MISSING",
                () -> VisualEvaluationGoalBudget.inspectExisting(missing,
                        JsonMapper.builder().build()));
        assertTrue(Files.notExists(missing));

        var contract = N7LiveTicketContract.plusCanary();
        var audit = new VisualEvaluationGoalBudget.ExistingSnapshot(
                Map.of(
                        "qwen3.7-plus", new VisualEvaluationGoalBudget.UsageAggregate(
                                179, 1_087_500, 4_159_620, false),
                        "qwen3.8-max", new VisualEvaluationGoalBudget.UsageAggregate(82, 491_919,
                                10_289_316, false),
                        "qwen3.7-flash", new VisualEvaluationGoalBudget.UsageAggregate(157,
                                1_148_324, 560_618, false)),
                418, 0, 0, "a".repeat(64), "b".repeat(64));
        assertCode("N7_LIVE_GOAL_CAPACITY_INSUFFICIENT",
                () -> N7LiveAdmissionGate.requireGoalReady(contract, audit));
    }

    private static VisualEvaluationAuthorization authorization(
            N7LiveTicketContract contract,
            String status,
            String evaluationIdentity,
            String profileSnapshot,
            List<String> caseIds,
            String approvalScope,
            String approvedAt,
            String expiresAt,
            int attempts,
            long tokens,
            long cost
    ) {
        return new VisualEvaluationAuthorization(
                VisualEvaluationAuthorization.VERSION,
                "n7-04-plus-canary-product-v45-20260813", status, "CANARY",
                VisualEvaluationAuthorization.INPUT_CLASSIFICATION,
                contract.corpusVersion(), contract.corpusSourceSha256(), evaluationIdentity,
                contract.profileId(), profileSnapshot, contract.model(), caseIds,
                attempts, tokens, cost, contract.maximumCasesPerBatch(), "yiwer", approvedAt,
                expiresAt, approvalScope);
    }

    private static Map<String, Object> reservation(
            String model,
            String state,
            Long actualTokens,
            Long actualCost
    ) {
        var settled = "SETTLED".equals(state);
        var value = new LinkedHashMap<String, Object>();
        value.put("reservationId", java.util.UUID.randomUUID().toString());
        value.put("authorizationId", "historical-authorization");
        value.put("profileId", "qwen3.7-plus".equals(model)
                ? "dashscope-qwen37-plus-product-v40-hybrid-generic"
                : "dashscope-qwen37-flash-20260715-product-v40-hybrid-generic");
        value.put("model", model);
        value.put("runId", java.util.UUID.randomUUID().toString());
        value.put("attemptOrdinal", 0);
        value.put("stage", "OBSERVE");
        value.put("reservedTokens", 12_000);
        value.put("reservedCostMicrosCny", 50_000);
        value.put("actualInputTokens", settled ? actualTokens : null);
        value.put("actualOutputTokens", settled ? 0 : null);
        value.put("actualCostMicrosCny", settled ? actualCost : null);
        value.put("state", state);
        value.put("createdAt", "2026-08-11T00:00:00Z");
        value.put("updatedAt", "2026-08-11T00:01:00Z");
        return value;
    }

    private static String stateWithReservations(List<Map<String, Object>> reservations)
            throws Exception {
        return JsonMapper.builder().build().writeValueAsString(Map.of(
                "stateVersion", "renderweave-visual-evaluation-goal-budget/1.0",
                "goalId", "renderweave-visual-recognition-vnext-20260810",
                "reservations", reservations,
                "createdAt", "2026-08-10T00:00:00Z",
                "updatedAt", "2026-08-11T00:01:00Z"));
    }

    private static void assertCode(String code, org.junit.jupiter.api.function.Executable action) {
        var failure = assertThrows(IllegalStateException.class, action);
        assertEquals(code, failure.getMessage());
    }
}
