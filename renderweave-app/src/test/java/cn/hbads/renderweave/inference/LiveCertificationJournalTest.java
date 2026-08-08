package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.eval.LiveEvaluationCorpus;
import cn.hbads.renderweave.inference.eval.LiveEvaluationCase;
import cn.hbads.renderweave.inference.eval.LiveEvaluationResult;
import cn.hbads.renderweave.inference.eval.LiveCertificationPolicy;
import cn.hbads.renderweave.inference.eval.LiveCertificationStatus;
import cn.hbads.renderweave.inference.provider.ProviderBudgetReservation;
import cn.hbads.renderweave.inference.provider.ProviderBudgetSnapshot;
import cn.hbads.renderweave.inference.provider.ProviderBudgetStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LiveCertificationJournalTest {
    private static final Instant NOW = Instant.parse("2026-08-08T08:00:00Z");
    private static final String BUDGET = "p5-synthetic-canary";
    private static final String EVALUATION_IDENTITY =
            "renderweave-repository-tree-sha256/1:" + "a".repeat(64);

    @TempDir
    Path temporaryDirectory;

    @Test
    void reservationsAndCompletedCasesSurviveReloadWithoutSensitivePayloads() throws Exception {
        var authorization = openAuthorization("journal-reload");
        var delegate = new FakeBudgetStore();
        var journal = new LiveCertificationJournal(temporaryDirectory, authorization, new ObjectMapper(), NOW);
        var budgets = new JournaledCertificationBudgetStore(authorization, journal, delegate);
        var runId = UUID.randomUUID();
        try (var ignored = journal.acquireBatchLease(NOW)) {
            journal.beginAssignment("dashscope-qwen37-flash-v1|live-json-01-scalars",
                    "dashscope-qwen37-flash-v1", "live-json-01-scalars", NOW);
            journal.bindRun("dashscope-qwen37-flash-v1|live-json-01-scalars", runId, NOW);
            var reservation = budgets.reserve(BUDGET, runId, 0, 20_000, NOW);
            budgets.settle(reservation.reservationId(), 5_000, NOW.plusSeconds(1));
            journal.completeCase(new LiveCertificationJournal.CaseResult(
                    "dashscope-qwen37-flash-v1|live-json-01-scalars",
                    "dashscope-qwen37-flash-v1", "live-json-01-scalars", "REVIEW_REQUIRED", null,
                    LiveCertificationJournal.EvaluationMetrics.from(
                            failureResult("live-json-01-scalars")
                    ),
                    List.of(new LiveCertificationJournal.AttemptResult(
                            0, "STRUCTURE", "SUCCEEDED", "LIVE_OUTPUT_ACCEPTED",
                            "qwen3.7-flash", 100, 50, 5_000, 120
                    )), NOW.plusSeconds(2).toString()
            ), NOW.plusSeconds(2));
        }

        var reloaded = new LiveCertificationJournal(
                temporaryDirectory, authorization, new ObjectMapper(), NOW.plusSeconds(3)
        );
        var snapshot = reloaded.snapshot(BUDGET);

        assertThat(snapshot.consumedAttempts()).isEqualTo(1);
        assertThat(snapshot.consumedCostMicrosCny()).isEqualTo(5_000);
        assertThat(reloaded.completedAssignmentKeys())
                .containsExactly("dashscope-qwen37-flash-v1|live-json-01-scalars");
        assertThat(reloaded.resultsFor("dashscope-qwen37-flash-v1")).hasSize(1);
        var serialized = Files.readString(temporaryDirectory.resolve("state.json"));
        assertThat(serialized).doesNotContain(
                "providerRequestId", "candidateJson", "prompt", "apiKey", "/field"
        );
    }

    @Test
    void sixtyCasesCompleteExactlyOnceAcrossTwelveFiveCaseJournalReloadBatches() {
        var authorization = openAuthorization("full-corpus-batches");
        var corpus = new LiveEvaluationCorpus();
        var assignments = authorization.assignments(corpus);

        for (var batch = 0; batch < 12; batch++) {
            var now = NOW.plusSeconds(batch);
            var journal = new LiveCertificationJournal(
                    temporaryDirectory, authorization, new ObjectMapper(), now
            );
            try (var ignored = journal.acquireBatchLease(now)) {
                var completed = journal.completedAssignmentKeys();
                var pending = assignments.stream()
                        .filter(item -> !completed.contains(item.key()))
                        .limit(authorization.maximumCasesPerBatch())
                        .toList();
                assertThat(pending).hasSize(5);
                for (var assignment : pending) {
                    journal.beginAssignment(
                            assignment.key(), assignment.profileId(),
                            assignment.evaluationCase().caseId(), now
                    );
                    journal.bindRun(assignment.key(), UUID.randomUUID(), now);
                    journal.completeCase(new LiveCertificationJournal.CaseResult(
                            assignment.key(), assignment.profileId(),
                            assignment.evaluationCase().caseId(), "REVIEW_REQUIRED", null,
                            LiveCertificationJournal.EvaluationMetrics.from(
                                    exactResult(assignment.evaluationCase())
                            ),
                            List.of(), now.toString()
                    ), now);
                }
            }
        }

        var completed = new LiveCertificationJournal(
                temporaryDirectory, authorization, new ObjectMapper(), NOW.plusSeconds(12)
        );
        assertThat(completed.completedAssignmentKeys())
                .containsExactlyElementsOf(assignments.stream()
                        .map(LiveCertificationAuthorization.Assignment::key).toList());
        assertThat(completed.completedAssignmentKeys()).doesNotHaveDuplicates();
        var results = completed.resultsFor(LiveCertificationAuthorization.FLASH_PROFILE).stream()
                .map(LiveCertificationJournal.CaseResult::evaluation)
                .map(LiveCertificationJournal.EvaluationMetrics::toResult)
                .toList();
        assertThat(results).hasSize(60);
        assertThat(new LiveCertificationPolicy().decide(
                LiveCertificationAuthorization.FLASH_PROFILE, corpus, results
        ).status()).isEqualTo(LiveCertificationStatus.CERTIFIED);
    }

    @Test
    void preparedReservationConsumesWorstCaseBudgetWhenDelegateFails() {
        var authorization = openAuthorization("delegate-failure");
        var delegate = new FakeBudgetStore();
        delegate.failReservation = true;
        var journal = new LiveCertificationJournal(temporaryDirectory, authorization, new ObjectMapper(), NOW);
        var budgets = new JournaledCertificationBudgetStore(authorization, journal, delegate);
        var runId = UUID.randomUUID();

        try (var ignored = journal.acquireBatchLease(NOW)) {
            beginRun(journal, "live-json-01-scalars", runId, NOW);
            assertThatThrownBy(() -> budgets.reserve(BUDGET, runId, 0, 20_000, NOW))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("DELEGATE_RESERVATION_FAILED");
        }

        var reloaded = new LiveCertificationJournal(
                temporaryDirectory, authorization, new ObjectMapper(), NOW.plusSeconds(1)
        );
        assertThat(reloaded.snapshot(BUDGET).consumedAttempts()).isEqualTo(1);
        assertThat(reloaded.snapshot(BUDGET).consumedCostMicrosCny()).isEqualTo(20_000);
    }

    @Test
    void settlementCannotExceedReservationAndAttemptConsumptionNeverMovesBackward() {
        var authorization = openAuthorization("settlement-bound");
        var journal = new LiveCertificationJournal(temporaryDirectory, authorization, new ObjectMapper(), NOW);
        var budgets = new JournaledCertificationBudgetStore(authorization, journal, new FakeBudgetStore());
        var runId = UUID.randomUUID();
        try (var ignored = journal.acquireBatchLease(NOW)) {
            beginRun(journal, "live-json-01-scalars", runId, NOW);
            var reservation = budgets.reserve(BUDGET, runId, 0, 20_000, NOW);

            assertThatThrownBy(() -> budgets.settle(reservation.reservationId(), 20_001, NOW.plusSeconds(1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Actual cost exceeds the certification reservation");
            assertThat(journal.snapshot(BUDGET).consumedCostMicrosCny()).isEqualTo(20_000);

            budgets.settle(reservation.reservationId(), 7_000, NOW.plusSeconds(2));
            assertThat(journal.snapshot(BUDGET).consumedAttempts()).isEqualTo(1);
            assertThat(journal.snapshot(BUDGET).consumedCostMicrosCny()).isEqualTo(7_000);
            assertThatThrownBy(() -> budgets.settle(
                    reservation.reservationId(), 6_999, NOW.plusSeconds(3)
            )).isInstanceOf(IllegalStateException.class)
                    .hasMessage("Certification reservation was already settled differently");
        }
    }

    @Test
    void existingStateCannotBeReopenedUnderDifferentAuthorization() {
        new LiveCertificationJournal(
                temporaryDirectory, openAuthorization("authorization-a"), new ObjectMapper(), NOW
        );

        assertThatThrownBy(() -> new LiveCertificationJournal(
                temporaryDirectory, openAuthorization("authorization-b"),
                new ObjectMapper(), NOW.plusSeconds(1)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("LIVE_CERTIFICATION_JOURNAL_AUTHORIZATION_MISMATCH");
    }

    @Test
    void completedEvidenceCannotBeReopenedUnderADifferentEvaluationIdentity() {
        var authorization = openAuthorization("identity-drift");
        new LiveCertificationJournal(
                temporaryDirectory, authorization, new ObjectMapper(), NOW
        );
        var drifted = new LiveCertificationAuthorization(
                authorization.authorizationVersion(), authorization.authorizationId(),
                authorization.status(), authorization.inputClassification(), authorization.corpusVersion(),
                "renderweave-repository-tree-sha256/1:" + "b".repeat(64),
                authorization.profileIds(), authorization.maximumProviderAttempts(),
                authorization.maximumCostMicrosCny(), authorization.maximumCasesPerBatch(),
                authorization.approvedBy(), authorization.approvedAt(), authorization.expiresAt(),
                authorization.approvalScope()
        );

        assertThatThrownBy(() -> new LiveCertificationJournal(
                temporaryDirectory, drifted, new ObjectMapper(), NOW.plusSeconds(1)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("LIVE_CERTIFICATION_JOURNAL_AUTHORIZATION_MISMATCH");
    }

    @Test
    void proposedOrExpiredAuthorizationCannotReserveEvenWithAnInitializedJournal() {
        var proposed = authorization("proposed", "PROPOSED", null, null, null, null);
        var proposedJournal = new LiveCertificationJournal(
                temporaryDirectory.resolve("proposed"), proposed, new ObjectMapper(), NOW
        );
        var proposedBudgets = new JournaledCertificationBudgetStore(
                proposed, proposedJournal, new FakeBudgetStore()
        );
        assertThatThrownBy(() -> proposedBudgets.reserve(BUDGET, UUID.randomUUID(), 0, 20_000, NOW))
                .hasMessage("LIVE_CERTIFICATION_AUTHORIZATION_NOT_OPEN");

        var expired = authorization(
                "expired", "OPEN", "user", "2026-08-08T06:00:00Z",
                "2026-08-08T07:00:00Z", "synthetic flash certification"
        );
        var expiredJournal = new LiveCertificationJournal(
                temporaryDirectory.resolve("expired"), expired, new ObjectMapper(), NOW
        );
        var expiredBudgets = new JournaledCertificationBudgetStore(
                expired, expiredJournal, new FakeBudgetStore()
        );
        assertThatThrownBy(() -> expiredBudgets.reserve(BUDGET, UUID.randomUUID(), 0, 20_000, NOW))
                .hasMessage("LIVE_CERTIFICATION_AUTHORIZATION_EXPIRED");
    }

    @Test
    void interruptedAssignmentCanOnlyBeRetriedWhenNoExternalCallWasReserved() {
        var authorization = openAuthorization("interruption-recovery");
        var journal = new LiveCertificationJournal(temporaryDirectory, authorization, new ObjectMapper(), NOW);
        var retryableKey = "dashscope-qwen37-flash-v1|live-json-01-scalars";
        try (var ignored = journal.acquireBatchLease(NOW)) {
            journal.beginAssignment(retryableKey, "dashscope-qwen37-flash-v1",
                    "live-json-01-scalars", NOW);
            journal.discardUnreservedAssignment(retryableKey, NOW.plusSeconds(1));
            assertThat(journal.inProgressExecutions()).isEmpty();

            var guardedKey = "dashscope-qwen37-flash-v1|live-json-02-nested-object";
            var runId = UUID.randomUUID();
            beginRun(journal, "live-json-02-nested-object", runId, NOW.plusSeconds(2));
            new JournaledCertificationBudgetStore(authorization, journal, new FakeBudgetStore())
                    .reserve(BUDGET, runId, 0, 20_000, NOW.plusSeconds(2));

            assertThatThrownBy(() -> journal.discardUnreservedAssignment(
                    guardedKey, NOW.plusSeconds(3)
            )).hasMessage("Certification assignment has an external-call reservation");
        }
    }

    @Test
    void onlyOneProcessCanOwnTheCertificationBatchLease() {
        var authorization = openAuthorization("exclusive-batch");
        var first = new LiveCertificationJournal(temporaryDirectory, authorization, new ObjectMapper(), NOW);
        var second = new LiveCertificationJournal(temporaryDirectory, authorization, new ObjectMapper(), NOW);

        try (var ignored = first.acquireBatchLease(NOW)) {
            assertThatThrownBy(() -> second.acquireBatchLease(NOW))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("LIVE_CERTIFICATION_BATCH_ALREADY_ACTIVE");
        }
    }

    @Test
    void providerReservationRequiresTheLeasedRunToBelongToTheActiveAssignment() {
        var authorization = openAuthorization("claimed-run");
        var journal = new LiveCertificationJournal(temporaryDirectory, authorization, new ObjectMapper(), NOW);
        var budgets = new JournaledCertificationBudgetStore(
                authorization, journal, new FakeBudgetStore()
        );
        try (var ignored = journal.acquireBatchLease(NOW)) {
            assertThatThrownBy(() -> budgets.reserve(
                    BUDGET, UUID.randomUUID(), 0, 20_000, NOW
            )).isInstanceOf(IllegalStateException.class)
                    .hasMessage("LIVE_CERTIFICATION_RUN_NOT_CLAIMED");
        }
    }

    @Test
    void missingStateBehindAnInitializationGuardFailsClosed() throws Exception {
        var authorization = openAuthorization("missing-state");
        new LiveCertificationJournal(temporaryDirectory, authorization, new ObjectMapper(), NOW);
        assertThat(temporaryDirectory.resolve("state.guard.json")).exists();
        Files.delete(temporaryDirectory.resolve("state.json"));

        assertThatThrownBy(() -> new LiveCertificationJournal(
                temporaryDirectory, authorization, new ObjectMapper(), NOW.plusSeconds(1)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("LIVE_CERTIFICATION_JOURNAL_STATE_MISSING");
    }

    private static LiveEvaluationResult failureResult(String caseId) {
        return new LiveEvaluationResult(
                caseId, "HARNESS_TEST_FAILURE", false, 0,
                1, 0, 0, 1, 0, 0,
                1, 0, 0, 0, 0,
                1, 0, 0, 0, 1,
                List.of("/"), List.of(), List.of("/field"), List.of(), List.of(), List.of()
        );
    }

    private static LiveEvaluationResult exactResult(LiveEvaluationCase gold) {
        var entityCount = gold.expectedSchemas().size();
        var shapes = gold.expectedSchemas().values().stream()
                .flatMap(fields -> fields.values().stream()).toList();
        var fieldCount = shapes.size();
        var supportedTypeCount = (int) shapes.stream()
                .filter(shape -> !shape.endsWith("UNRESOLVED") && !shape.endsWith("CONFLICT"))
                .count();
        var edgeCount = (int) shapes.stream()
                .filter(shape -> shape.equals("REFERENCE") || shape.equals("ARRAY:REFERENCE"))
                .count();
        var evidenceCount = entityCount + fieldCount;
        return new LiveEvaluationResult(
                gold.caseId(), "EVALUATED", true, 10_000,
                entityCount, entityCount, entityCount,
                fieldCount, fieldCount, fieldCount,
                supportedTypeCount, supportedTypeCount,
                edgeCount, edgeCount, edgeCount,
                evidenceCount, evidenceCount, 10_000, 0, 0,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        );
    }

    private static void beginRun(
            LiveCertificationJournal journal,
            String caseId,
            UUID runId,
            Instant now
    ) {
        var key = "dashscope-qwen37-flash-v1|" + caseId;
        journal.beginAssignment(key, "dashscope-qwen37-flash-v1", caseId, now);
        journal.bindRun(key, runId, now);
    }

    private static LiveCertificationAuthorization openAuthorization(String id) {
        return authorization(
                id, "OPEN", "user", "2026-08-08T07:00:00Z",
                "2026-08-08T09:00:00Z", "60 synthetic flash cases"
        );
    }

    private static LiveCertificationAuthorization authorization(
            String id,
            String status,
            String approvedBy,
            String approvedAt,
            String expiresAt,
            String approvalScope
    ) {
        return new LiveCertificationAuthorization(
                LiveCertificationAuthorization.VERSION,
                "p5-certification-" + id,
                status,
                LiveCertificationAuthorization.INPUT_CLASSIFICATION,
                LiveEvaluationCorpus.VERSION,
                EVALUATION_IDENTITY,
                List.of(LiveCertificationAuthorization.FLASH_PROFILE),
                180,
                3_600_000,
                5,
                approvedBy,
                approvedAt,
                expiresAt,
                approvalScope
        );
    }

    private static final class FakeBudgetStore implements ProviderBudgetStore {
        private final List<ProviderBudgetReservation> reservations = new ArrayList<>();
        private boolean failReservation;

        @Override
        public ProviderBudgetReservation reserve(
                String budgetKey,
                UUID runId,
                int attemptOrdinal,
                long maximumCostMicrosCny,
                Instant now
        ) {
            if (failReservation) throw new IllegalStateException("DELEGATE_RESERVATION_FAILED");
            var reservation = new ProviderBudgetReservation(
                    UUID.randomUUID(), budgetKey, runId, attemptOrdinal, maximumCostMicrosCny
            );
            reservations.add(reservation);
            return reservation;
        }

        @Override
        public void settle(UUID reservationId, long actualCostMicrosCny, Instant now) {
            if (reservations.stream().noneMatch(item -> item.reservationId().equals(reservationId))) {
                throw new IllegalArgumentException("Unknown fake reservation");
            }
        }

        @Override
        public ProviderBudgetSnapshot snapshot(String budgetKey) {
            return new ProviderBudgetSnapshot(budgetKey, 180, reservations.size(), 3_600_000,
                    reservations.stream().mapToLong(ProviderBudgetReservation::reservedCostMicrosCny).sum());
        }
    }
}
