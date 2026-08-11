package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.eval.visual.VisualStageCorpus;
import cn.hbads.renderweave.inference.eval.visual.VisualStageEvaluationResult;
import cn.hbads.renderweave.inference.eval.visual.VisualStageReporter;
import cn.hbads.renderweave.inference.profile.InferenceProfileRegistry;
import cn.hbads.renderweave.inference.provider.ProviderImage;
import cn.hbads.renderweave.inference.provider.ProviderInferenceRequest;
import cn.hbads.renderweave.inference.provider.ProviderUsage;
import cn.hbads.renderweave.inference.run.InferenceStage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisualEvaluationJournalTest {
    private static final Instant NOW = Instant.parse("2026-08-10T10:00:00Z");
    private static final String PROFILE_ID = "dashscope-qwen37-plus-product-v4";
    private final VisualStageCorpus corpus = new VisualStageCorpus();
    private final InferenceProfileRegistry.ProfileResource profile =
            new InferenceProfileRegistry().require(PROFILE_ID);

    @Test
    void completedStageMetricsAndAttemptsSurviveStrictPayloadFreeReload(@TempDir Path directory)
            throws Exception {
        var authorization = authorization("visual-journal-reload", 3);
        var budget = new VisualEvaluationGoalBudget(directory.resolve("goal"),
                JsonMapper.builder().build(), NOW);
        var journalDirectory = directory.resolve("journal");
        var journal = journal(journalDirectory, authorization, NOW);
        var gold = corpus.require(authorization.caseIds().getFirst());
        var runId = UUID.randomUUID();
        try (var ignored = journal.acquireBatchLease(NOW)) {
            var executionId = journal.beginAssignment(gold.caseId(), NOW);
            var assignmentKey = PROFILE_ID + "|" + gold.caseId();
            journal.bindRun(assignmentKey, executionId, runId, NOW);
            var reservation = budget.reserve(authorization, request(runId, 0), NOW);
            budget.settle(UUID.fromString(reservation.reservationId()), new ProviderUsage(120, 80),
                    1_000, NOW.plusSeconds(1));
            journal.completeCase(
                    assignmentKey, executionId, runId, exactResult(gold, 1),
                    List.of(new VisualEvaluationJournal.AttemptResult(
                            reservation.reservationId(), 0, "OBSERVE", "LIVE_OUTPUT_ACCEPTED",
                            "qwen3.7-plus", 120L, 80L, 1_000L, 240,
                            Map.of("LIVE_OUTPUT_ACCEPTED", 1)
                    )), budget, NOW.plusSeconds(2)
            );
        }

        var reloaded = journal(journalDirectory, authorization, NOW.plusSeconds(3));
        assertEquals(1, reloaded.completedResults().size());
        assertEquals(List.of(PROFILE_ID + "|" + gold.caseId()), reloaded.terminalAssignmentKeys());
        var report = new VisualStageReporter().report(corpus, reloaded.completedResults());
        assertFalse(report.complete());
        assertEquals(1, report.observedCaseCount());
        var serialized = Files.readString(journalDirectory.resolve("state.json"));
        assertFalse(serialized.contains("providerRequestId"));
        assertFalse(serialized.contains("candidateJson"));
        assertFalse(serialized.contains("prompt"));
        assertTrue(serialized.contains("LIVE_OUTPUT_ACCEPTED"));
    }

    @Test
    void interruptedUnreservedCaseIsRetriableButReservedCaseIsNeverCalledAgain(@TempDir Path directory) {
        var authorization = authorization("visual-journal-recovery", 3);
        var budget = new VisualEvaluationGoalBudget(directory.resolve("goal"),
                JsonMapper.builder().build(), NOW);
        var journal = journal(directory.resolve("journal"), authorization, NOW);
        var first = authorization.caseIds().get(0);
        var second = authorization.caseIds().get(1);

        try (var ignored = journal.acquireBatchLease(NOW)) {
            journal.beginAssignment(first, NOW);
            var recovery = journal.recoverInterrupted(budget, NOW.plusSeconds(1));
            assertEquals(List.of(first), recovery.retriableCaseIds());
            assertTrue(recovery.abandonedCaseIds().isEmpty());

            var execution = journal.beginAssignment(second, NOW.plusSeconds(2));
            var runId = UUID.randomUUID();
            journal.bindRun(PROFILE_ID + "|" + second, execution, runId, NOW.plusSeconds(2));
            budget.reserve(authorization, request(runId, 0), NOW.plusSeconds(2));
            var guarded = journal.recoverInterrupted(budget, NOW.plusSeconds(3));
            assertEquals(List.of(second), guarded.abandonedCaseIds());
            assertTrue(guarded.retriableCaseIds().isEmpty());
            assertThrows(IllegalStateException.class,
                    () -> journal.beginAssignment(second, NOW.plusSeconds(4)));
        }

        assertEquals(List.of(PROFILE_ID + "|" + second), journal.terminalAssignmentKeys());
        assertTrue(journal.completedResults().isEmpty());
    }

    @Test
    void closedAuthorizationCanOnlyArchiveInterruptedReservedExecution(@TempDir Path directory) {
        var open = authorization("visual-journal-closed-recovery", 2);
        var budget = new VisualEvaluationGoalBudget(directory.resolve("goal"),
                JsonMapper.builder().build(), NOW);
        var journalDirectory = directory.resolve("journal");
        var openJournal = journal(journalDirectory, open, NOW);
        var caseId = open.caseIds().getFirst();
        var runId = UUID.randomUUID();
        try (var ignored = openJournal.acquireBatchLease(NOW)) {
            var execution = openJournal.beginAssignment(caseId, NOW);
            openJournal.bindRun(PROFILE_ID + "|" + caseId, execution, runId, NOW);
            budget.reserve(open, request(runId, 0), NOW);
        }

        var closedJournal = journal(journalDirectory, closed(open), NOW.plusSeconds(1));
        assertThrows(IllegalStateException.class,
                () -> closedJournal.acquireBatchLease(NOW.plusSeconds(1)));
        try (var ignored = closedJournal.acquireClosedRecoveryLease()) {
            var recovery = closedJournal.recoverInterruptedAfterClosure(budget, NOW.plusSeconds(2));
            assertEquals(List.of(caseId), recovery.abandonedCaseIds());
            assertTrue(recovery.retriableCaseIds().isEmpty());
            assertThrows(IllegalStateException.class,
                    () -> closedJournal.beginAssignment(open.caseIds().get(1), NOW.plusSeconds(3)));
        }
        assertEquals(List.of(PROFILE_ID + "|" + caseId), closedJournal.terminalAssignmentKeys());
    }

    @Test
    void completionMustExactlyMatchGoalReservationUsage(@TempDir Path directory) {
        var authorization = authorization("visual-journal-binding", 1);
        var budget = new VisualEvaluationGoalBudget(directory.resolve("goal"),
                JsonMapper.builder().build(), NOW);
        var journal = journal(directory.resolve("journal"), authorization, NOW);
        var gold = corpus.require(authorization.caseIds().getFirst());
        var runId = UUID.randomUUID();
        try (var ignored = journal.acquireBatchLease(NOW)) {
            var execution = journal.beginAssignment(gold.caseId(), NOW);
            var key = PROFILE_ID + "|" + gold.caseId();
            journal.bindRun(key, execution, runId, NOW);
            var reservation = budget.reserve(authorization, request(runId, 0), NOW);
            budget.settle(UUID.fromString(reservation.reservationId()), new ProviderUsage(50, 25),
                    500, NOW.plusSeconds(1));

            assertThrows(IllegalStateException.class, () -> journal.completeCase(
                    key, execution, runId, exactResult(gold, 1),
                    List.of(new VisualEvaluationJournal.AttemptResult(
                            reservation.reservationId(), 0, "OBSERVE", "LIVE_OUTPUT_ACCEPTED",
                            "qwen3.7-plus", 51L, 25L, 500L, 10, Map.of()
                    )), budget, NOW.plusSeconds(2)
            ));
        }
    }

    @Test
    void authorizationDriftDuplicateBatchAndTamperedStateFailClosed(@TempDir Path directory)
            throws Exception {
        var authorization = authorization("visual-journal-strict", 1);
        var journalDirectory = directory.resolve("journal");
        var first = journal(journalDirectory, authorization, NOW);
        var second = journal(journalDirectory, authorization, NOW);
        try (var ignored = first.acquireBatchLease(NOW)) {
            assertThrows(IllegalStateException.class, () -> second.acquireBatchLease(NOW));
        }

        var drifted = new VisualEvaluationAuthorization(
                authorization.authorizationVersion(), authorization.authorizationId(), "CLOSED",
                authorization.phase(), authorization.inputClassification(), authorization.corpusVersion(),
                authorization.corpusSourceSha256(), authorization.evaluationIdentity(),
                authorization.profileId(), "c".repeat(64), authorization.model(), authorization.caseIds(),
                authorization.maximumProviderAttempts(), authorization.maximumTotalTokens(),
                authorization.maximumCostMicrosCny(), authorization.maximumCasesPerBatch(),
                authorization.approvedBy(), authorization.approvedAt(), authorization.expiresAt(),
                authorization.approvalScope()
        );
        assertThrows(IllegalStateException.class,
                () -> journal(journalDirectory, drifted, NOW.plusSeconds(1)));

        var guardFile = journalDirectory.resolve("state.guard.json");
        var originalGuard = Files.readString(guardFile, StandardCharsets.UTF_8);
        Files.writeString(guardFile, "{}", StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, first::snapshot);
        Files.writeString(guardFile, originalGuard, StandardCharsets.UTF_8);

        var stateFile = journalDirectory.resolve("state.json");
        Files.writeString(stateFile, Files.readString(stateFile).replaceFirst(
                "\\{", "{\"candidateJson\":\"forbidden\","));
        assertThrows(IllegalStateException.class,
                () -> journal(journalDirectory, authorization, NOW.plusSeconds(2)));
    }

    private VisualEvaluationJournal journal(
            Path directory,
            VisualEvaluationAuthorization authorization,
            Instant now
    ) {
        return new VisualEvaluationJournal(directory, authorization, corpus,
                JsonMapper.builder().build(), now);
    }

    private VisualEvaluationAuthorization authorization(String id, int caseCount) {
        return new VisualEvaluationAuthorization(
                VisualEvaluationAuthorization.VERSION, id, "OPEN", "BASELINE",
                VisualEvaluationAuthorization.INPUT_CLASSIFICATION, VisualStageCorpus.VERSION,
                corpus.sourceSha256(), VisualEvaluationIdentity.VERSION + ":" + "b".repeat(64),
                PROFILE_ID, sha256(profile.snapshotJson()), "qwen3.7-plus",
                corpus.cases().stream().limit(caseCount).map(VisualStageCorpus.EvaluationCase::caseId).toList(),
                Math.multiplyExact(caseCount, 8), 500_000, 4_000_000, Math.min(caseCount, 5),
                "yiwer", NOW.minusSeconds(60).toString(), NOW.plusSeconds(43_200).toString(),
                "Repository synthetic visual evaluation"
        );
    }

    private static VisualEvaluationAuthorization closed(VisualEvaluationAuthorization value) {
        return new VisualEvaluationAuthorization(
                value.authorizationVersion(), value.authorizationId(), "CLOSED", value.phase(),
                value.inputClassification(), value.corpusVersion(), value.corpusSourceSha256(),
                value.evaluationIdentity(), value.profileId(), value.profileSnapshotSha256(), value.model(),
                value.caseIds(), value.maximumProviderAttempts(), value.maximumTotalTokens(),
                value.maximumCostMicrosCny(), value.maximumCasesPerBatch(), value.approvedBy(),
                value.approvedAt(), value.expiresAt(), value.approvalScope()
        );
    }

    private ProviderInferenceRequest request(UUID runId, int ordinal) {
        return new ProviderInferenceRequest(
                runId, ordinal, InferenceStage.OBSERVE, profile.profile(),
                "Return one bounded JSON object.", "{}",
                List.of(new ProviderImage("c".repeat(64), "image/png", new byte[]{1}))
        );
    }

    static VisualStageEvaluationResult exactResult(
            VisualStageCorpus.EvaluationCase gold,
            int providerCalls
    ) {
        var slots = (int) gold.scene().elements().stream()
                .filter(item -> item.kind() == VisualStageCorpus.ElementKind.SLOT).count();
        var groups = gold.scene().elements().size() - slots;
        var entities = gold.scene().entities().size();
        var relationships = gold.scene().relationships().size();
        var bindings = gold.scene().bindings().size();
        var calibration = new ArrayList<VisualStageEvaluationResult.CalibrationBin>();
        for (var index = 0; index < 10; index++) {
            calibration.add(index == 9
                    ? new VisualStageEvaluationResult.CalibrationBin(index, slots, slots,
                    Math.multiplyExact(10_000L, slots), 0)
                    : new VisualStageEvaluationResult.CalibrationBin(index, 0, 0, 0, 0));
        }
        var fieldCount = gold.expectedShapes().values().stream().mapToInt(Map::size).sum();
        return new VisualStageEvaluationResult(
                gold.caseId(), gold.partition(), gold.scene().domainPack(), gold.style(), "EVALUATED",
                providerCalls, Math.max(0, providerCalls - 1),
                new VisualStageEvaluationResult.StageCounts(slots, slots, slots),
                new VisualStageEvaluationResult.StageCounts(groups, groups, groups),
                new VisualStageEvaluationResult.GroundingMetrics(slots + groups, slots + groups,
                        slots + groups, Math.multiplyExact(10_000L, slots + groups)),
                new VisualStageEvaluationResult.StageCounts(entities, entities, entities),
                new VisualStageEvaluationResult.StageCounts(relationships, relationships, relationships),
                new VisualStageEvaluationResult.StageCounts(bindings, bindings, bindings),
                new VisualStageEvaluationResult.SurvivalMetrics(slots, slots, slots, slots),
                0, Math.max(1, entities + fieldCount), calibration,
                new VisualStageEvaluationResult.FinalCandidateMetrics(
                        "EVALUATED", true, 10_000,
                        new VisualStageEvaluationResult.StageCounts(entities, entities, entities),
                        new VisualStageEvaluationResult.StageCounts(fieldCount, fieldCount, fieldCount),
                        new VisualStageEvaluationResult.StageCounts(relationships, relationships, relationships),
                        fieldCount, fieldCount, entities + fieldCount, entities + fieldCount,
                        10_000, 0, 0
                )
        );
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
