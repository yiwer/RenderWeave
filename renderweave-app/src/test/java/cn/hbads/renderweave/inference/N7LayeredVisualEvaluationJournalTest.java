package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.eval.visual.LayeredVisualCorpus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class N7LayeredVisualEvaluationJournalTest {
    private static final Instant NOW = Instant.parse("2026-08-13T13:00:00Z");

    @Test
    void journalValidatesAssignmentsAgainstTheAuthorizedLayeredCorpus(@TempDir Path directory) throws Exception {
        var contract = N7LiveTicketContract.plusCanary();
        var authorization = new VisualEvaluationAuthorization(
                VisualEvaluationAuthorization.VERSION,
                contract.authorizationId(), "OPEN", "CANARY", contract.inputClassification(),
                contract.corpusVersion(), contract.corpusSourceSha256(),
                "renderweave-visual-evaluation-tree-sha256/2:" + "a".repeat(64),
                contract.profileId(), contract.profileSnapshotSha256(), contract.model(), contract.caseIds(),
                contract.maximumProviderAttempts(), contract.maximumTotalTokens(),
                contract.maximumCostMicrosCny(), contract.maximumCasesPerBatch(), "yiwer",
                NOW.minusSeconds(60).toString(), NOW.plusSeconds(3600).toString(),
                contract.contractIdentity()
        );

        var journal = new VisualEvaluationJournal(
                directory, authorization, new LayeredVisualCorpus(),
                JsonMapper.builder().build(), NOW
        );

        assertTrue(Files.isRegularFile(directory.resolve("state.json")));
        assertTrue(journal.snapshot().executions().isEmpty());
        assertTrue(Files.readString(directory.resolve("state.json"))
                .contains(VisualEvaluationJournal.N7_VERSION));
        assertTrue(Files.readString(directory.resolve("state.guard.json"))
                .contains("renderweave-n7-visual-evaluation-journal-guard/2.0"));
    }

    @Test
    void n7TerminalStateSurvivesReloadAndTamperingFailsClosed(@TempDir Path directory) throws Exception {
        var contract = N7LiveTicketContract.plusCanary();
        var authorization = authorization(contract, "n7-journal-terminal-state");
        var corpus = new LayeredVisualCorpus();
        var journalDirectory = directory.resolve("journal");
        var budget = new VisualEvaluationGoalBudget(
                directory.resolve("goal"), JsonMapper.builder().build(), NOW);
        var journal = new VisualEvaluationJournal(
                journalDirectory, authorization, corpus, JsonMapper.builder().build(), NOW);
        var gold = corpus.require(contract.caseIds().getFirst()).renderCase();
        var assignment = contract.profileId() + "|" + gold.caseId();
        var runId = UUID.randomUUID();

        try (var ignored = journal.acquireBatchLease(NOW)) {
            var execution = journal.beginAssignment(gold.caseId(), NOW);
            journal.bindRun(assignment, execution, runId, NOW);
            journal.completeCase(
                    assignment, execution, runId,
                    VisualEvaluationJournalTest.exactResult(gold, 0), "REVIEW_REQUIRED",
                    List.of(), budget, NOW.plusSeconds(1));
        }

        var reloaded = new VisualEvaluationJournal(
                journalDirectory, authorization, corpus, JsonMapper.builder().build(), NOW.plusSeconds(2));
        assertEquals(List.of(assignment), reloaded.terminalAssignmentKeys());
        var serialized = Files.readString(journalDirectory.resolve("state.json"));
        assertTrue(serialized.contains("\"terminalState\" : \"REVIEW_REQUIRED\""));

        Files.writeString(
                journalDirectory.resolve("state.json"),
                serialized.replace("\"REVIEW_REQUIRED\"", "\"SUCCEEDED\""));
        assertThrows(IllegalStateException.class, () -> new VisualEvaluationJournal(
                journalDirectory, authorization, corpus, JsonMapper.builder().build(), NOW.plusSeconds(3)));
    }

    private static VisualEvaluationAuthorization authorization(
            N7LiveTicketContract contract,
            String authorizationId
    ) {
        return new VisualEvaluationAuthorization(
                VisualEvaluationAuthorization.VERSION,
                authorizationId, "OPEN", "CANARY", contract.inputClassification(),
                contract.corpusVersion(), contract.corpusSourceSha256(),
                "renderweave-visual-evaluation-tree-sha256/2:" + "a".repeat(64),
                contract.profileId(), contract.profileSnapshotSha256(), contract.model(), contract.caseIds(),
                contract.maximumProviderAttempts(), contract.maximumTotalTokens(),
                contract.maximumCostMicrosCny(), contract.maximumCasesPerBatch(), "yiwer",
                NOW.minusSeconds(60).toString(), NOW.plusSeconds(3600).toString(),
                contract.contractIdentity()
        );
    }
}
