package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.eval.visual.VisualStageCorpus;
import cn.hbads.renderweave.inference.eval.visual.VisualStageEvaluationResult;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.EnumFeature;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Payload-free, one-authorization visual evaluation journal. The batch file lock serializes
 * external execution across processes; state writes additionally use a short state lock and
 * require atomic replace. Any run with a Goal reservation is never made retryable again.
 */
final class VisualEvaluationJournal {
    static final String VERSION = "renderweave-visual-evaluation-journal/1.0";
    private static final String GUARD_VERSION = "renderweave-visual-evaluation-journal-guard/1.0";

    private final VisualEvaluationAuthorization authorization;
    private final VisualStageCorpus corpus;
    private final ObjectMapper json;
    private final Path stateFile;
    private final Path guardFile;
    private final Path stateLockFile;
    private final Path batchLockFile;
    private FileChannel batchChannel;
    private FileLock batchLock;

    VisualEvaluationJournal(
            Path directory,
            VisualEvaluationAuthorization authorization,
            VisualStageCorpus corpus,
            ObjectMapper objectMapper,
            Instant now
    ) {
        Objects.requireNonNull(directory, "directory");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.corpus = Objects.requireNonNull(corpus, "corpus");
        authorization.requireCorpus(corpus);
        Objects.requireNonNull(now, "now");
        json = Objects.requireNonNull(objectMapper, "objectMapper").rebuild()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .build();
        stateFile = directory.resolve("state.json");
        guardFile = directory.resolve("state.guard.json");
        stateLockFile = directory.resolve("state.lock");
        batchLockFile = directory.resolve("batch.lock");
        withStateLock(() -> {
            var hasState = Files.exists(stateFile);
            var hasGuard = Files.exists(guardFile);
            if (hasState != hasGuard) {
                throw new IllegalStateException("VISUAL_EVALUATION_JOURNAL_PARTIAL_STATE");
            }
            if (hasState) {
                requireExpectedGuard(readGuard());
                validateState(readState());
            } else {
                writeAtomically(guardFile,
                        json.writerWithDefaultPrettyPrinter().writeValueAsString(Guard.from(authorization)));
                writeState(State.initial(authorization.authorizationId(), now));
            }
            return null;
        });
    }

    BatchLease acquireBatchLease(Instant now) {
        authorization.requireOpen(Objects.requireNonNull(now, "now"));
        return acquireLease();
    }

    BatchLease acquireClosedRecoveryLease() {
        authorization.requireClosed();
        return acquireLease();
    }

    private BatchLease acquireLease() {
        synchronized (this) {
            if (batchLock != null && batchLock.isValid()) {
                throw new IllegalStateException("VISUAL_EVALUATION_BATCH_ALREADY_ACTIVE");
            }
            try {
                Files.createDirectories(batchLockFile.getParent());
                batchChannel = FileChannel.open(batchLockFile, StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE);
                batchLock = batchChannel.tryLock();
                if (batchLock == null) {
                    closeBatchResources();
                    throw new IllegalStateException("VISUAL_EVALUATION_BATCH_ALREADY_ACTIVE");
                }
                return new BatchLease(UUID.randomUUID());
            } catch (OverlappingFileLockException busy) {
                closeBatchResources();
                throw new IllegalStateException("VISUAL_EVALUATION_BATCH_ALREADY_ACTIVE", busy);
            } catch (IOException failure) {
                closeBatchResources();
                throw new IllegalStateException("VISUAL_EVALUATION_BATCH_LOCK_FAILED", failure);
            }
        }
    }

    String beginAssignment(String caseId, Instant now) {
        requireBatchLease();
        authorization.requireOpen(now);
        requireAuthorizedCase(caseId);
        var assignmentKey = assignmentKey(caseId);
        return withStateLock(() -> {
            var state = readState();
            validateState(state);
            if (state.executions().stream().anyMatch(item -> item.assignmentKey().equals(assignmentKey))) {
                throw new IllegalStateException("VISUAL_EVALUATION_ASSIGNMENT_ALREADY_EXISTS");
            }
            var execution = Execution.inProgress(assignmentKey, authorization, caseId, now);
            var executions = new ArrayList<>(state.executions());
            executions.add(execution);
            writeState(state.withExecutions(executions, now));
            return execution.executionId();
        });
    }

    void bindRun(String assignmentKey, String executionId, UUID runId, Instant now) {
        requireBatchLease();
        authorization.requireOpen(now);
        requireUuid(executionId, "executionId");
        Objects.requireNonNull(runId, "runId");
        withStateLock(() -> {
            var state = readState();
            var executions = new ArrayList<Execution>();
            var found = false;
            for (var item : state.executions()) {
                if (!item.assignmentKey().equals(assignmentKey)) {
                    if (runId.toString().equals(item.runId())) {
                        throw new IllegalStateException("VISUAL_EVALUATION_RUN_ALREADY_BOUND");
                    }
                    executions.add(item);
                    continue;
                }
                requireExecution(item, executionId, "IN_PROGRESS");
                if (item.runId() != null && !item.runId().equals(runId.toString())) {
                    throw new IllegalStateException("VISUAL_EVALUATION_RUN_BINDING_MISMATCH");
                }
                executions.add(item.bindRun(runId, now));
                found = true;
            }
            if (!found) throw new IllegalStateException("VISUAL_EVALUATION_ASSIGNMENT_NOT_FOUND");
            writeState(state.withExecutions(executions, now));
            return null;
        });
    }

    void completeCase(
            String assignmentKey,
            String executionId,
            UUID runId,
            VisualStageEvaluationResult evaluation,
            List<AttemptResult> attempts,
            VisualEvaluationGoalBudget goalBudget,
            Instant now
    ) {
        requireBatchLease();
        authorization.requireOpen(now);
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(evaluation, "evaluation");
        attempts = List.copyOf(Objects.requireNonNull(attempts, "attempts"));
        requireEvaluationIdentity(evaluation);
        requireAttemptBindings(runId, attempts, goalBudget);
        if (evaluation.providerCalls() != attempts.size()) {
            throw new IllegalArgumentException("Visual evaluation provider call count is inconsistent");
        }
        var immutableAttempts = attempts;
        withStateLock(() -> {
            var state = readState();
            var executions = new ArrayList<Execution>();
            var found = false;
            for (var item : state.executions()) {
                if (!item.assignmentKey().equals(assignmentKey)) {
                    executions.add(item);
                    continue;
                }
                requireExecution(item, executionId, "IN_PROGRESS");
                if (!runId.toString().equals(item.runId())
                        || !item.caseId().equals(evaluation.caseId())) {
                    throw new IllegalStateException("VISUAL_EVALUATION_EXECUTION_BINDING_MISMATCH");
                }
                executions.add(item.complete(evaluation, immutableAttempts, now));
                found = true;
            }
            if (!found) throw new IllegalStateException("VISUAL_EVALUATION_ASSIGNMENT_NOT_FOUND");
            writeState(state.withExecutions(executions, now));
            return null;
        });
    }

    Recovery recoverInterrupted(VisualEvaluationGoalBudget goalBudget, Instant now) {
        requireBatchLease();
        authorization.requireOpen(now);
        return recoverInterruptedInternal(goalBudget, now);
    }

    Recovery recoverInterruptedAfterClosure(VisualEvaluationGoalBudget goalBudget, Instant now) {
        requireBatchLease();
        authorization.requireClosed();
        return recoverInterruptedInternal(goalBudget, now);
    }

    private Recovery recoverInterruptedInternal(VisualEvaluationGoalBudget goalBudget, Instant now) {
        Objects.requireNonNull(goalBudget, "goalBudget");
        Objects.requireNonNull(now, "now");
        return withStateLock(() -> {
            var state = readState();
            var executions = new ArrayList<Execution>();
            var retriable = new ArrayList<String>();
            var abandoned = new ArrayList<String>();
            for (var item : state.executions()) {
                if (!"IN_PROGRESS".equals(item.status())) {
                    executions.add(item);
                    continue;
                }
                var reservations = item.runId() == null ? List.<VisualEvaluationGoalBudget.Reservation>of()
                        : goalBudget.reservationsForRun(UUID.fromString(item.runId()));
                if (reservations.isEmpty()) {
                    retriable.add(item.caseId());
                } else {
                    executions.add(item.abandon(now));
                    abandoned.add(item.caseId());
                }
            }
            writeState(state.withExecutions(executions, now));
            return new Recovery(List.copyOf(retriable), List.copyOf(abandoned));
        });
    }

    List<String> terminalAssignmentKeys() {
        return withStateLock(() -> readState().executions().stream()
                .filter(item -> !"IN_PROGRESS".equals(item.status()))
                .map(Execution::assignmentKey).toList());
    }

    List<VisualStageEvaluationResult> completedResults() {
        return withStateLock(() -> readState().executions().stream()
                .filter(item -> "COMPLETED".equals(item.status()))
                .map(Execution::evaluation).toList());
    }

    State snapshot() {
        return withStateLock(this::readState);
    }

    private void requireEvaluationIdentity(VisualStageEvaluationResult result) {
        var gold = corpus.require(result.caseId());
        if (!authorization.caseIds().contains(result.caseId())
                || result.partition() != gold.partition()
                || result.style() != gold.style()
                || result.domainPack() != gold.scene().domainPack()) {
            throw new IllegalArgumentException("Visual evaluation result is outside the authorized gold slice");
        }
    }

    private void requireAttemptBindings(
            UUID runId,
            List<AttemptResult> attempts,
            VisualEvaluationGoalBudget goalBudget
    ) {
        Objects.requireNonNull(goalBudget, "goalBudget");
        var reservations = goalBudget.reservationsForRun(runId);
        if (reservations.size() != attempts.size()) {
            throw new IllegalStateException("VISUAL_EVALUATION_ATTEMPT_RESERVATION_COUNT_MISMATCH");
        }
        var reservationIds = new HashSet<String>();
        var ordinals = new HashSet<Integer>();
        for (var attempt : attempts) {
            if (!reservationIds.add(attempt.reservationId()) || !ordinals.add(attempt.attemptOrdinal())) {
                throw new IllegalArgumentException("Visual evaluation attempts must be unique");
            }
            var reservation = goalBudget.reservation(UUID.fromString(attempt.reservationId()));
            if (!authorization.authorizationId().equals(reservation.authorizationId())
                    || !authorization.profileId().equals(reservation.profileId())
                    || !authorization.model().equals(reservation.model())
                    || !runId.toString().equals(reservation.runId())
                    || attempt.attemptOrdinal() != reservation.attemptOrdinal()
                    || !attempt.stage().equals(reservation.stage())
                    || !attempt.model().equals(reservation.model())) {
                throw new IllegalStateException("VISUAL_EVALUATION_ATTEMPT_RESERVATION_MISMATCH");
            }
            if ("SETTLED".equals(reservation.state())) {
                if (!Objects.equals(attempt.inputTokens(), reservation.actualInputTokens())
                        || !Objects.equals(attempt.outputTokens(), reservation.actualOutputTokens())
                        || !Objects.equals(attempt.costMicrosCny(), reservation.actualCostMicrosCny())) {
                    throw new IllegalStateException("VISUAL_EVALUATION_ATTEMPT_USAGE_MISMATCH");
                }
            } else if (attempt.inputTokens() != null) {
                throw new IllegalStateException("VISUAL_EVALUATION_UNSETTLED_ATTEMPT_HAS_USAGE");
            }
        }
    }

    private State readState() throws IOException {
        try {
            requireExpectedGuard(readGuard());
            var raw = Files.readString(stateFile, StandardCharsets.UTF_8);
            PayloadFreeLiveEvidenceGuard.requirePayloadFree(raw);
            var state = json.readValue(raw, State.class);
            validateState(state);
            return state;
        } catch (IOException | RuntimeException invalid) {
            throw new IllegalStateException("VISUAL_EVALUATION_JOURNAL_INVALID", invalid);
        }
    }

    private Guard readGuard() throws IOException {
        try {
            var raw = Files.readString(guardFile, StandardCharsets.UTF_8);
            PayloadFreeLiveEvidenceGuard.requirePayloadFree(raw);
            return json.readValue(raw, Guard.class);
        } catch (IOException | RuntimeException invalid) {
            throw new IllegalStateException("VISUAL_EVALUATION_JOURNAL_GUARD_INVALID", invalid);
        }
    }

    private void writeState(State state) throws IOException {
        requireExpectedGuard(readGuard());
        validateState(state);
        writeAtomically(stateFile, json.writerWithDefaultPrettyPrinter().writeValueAsString(state));
    }

    private void writeAtomically(Path destination, String content) throws IOException {
        PayloadFreeLiveEvidenceGuard.requirePayloadFree(content);
        Files.createDirectories(destination.getParent());
        var temporary = destination.resolveSibling(destination.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                var bytes = StandardCharsets.UTF_8.encode(content);
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IllegalStateException("VISUAL_EVALUATION_ATOMIC_MOVE_REQUIRED", unsupported);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void requireExpectedGuard(Guard actual) {
        if (!Guard.from(authorization).equals(actual)) {
            throw new IllegalStateException("VISUAL_EVALUATION_JOURNAL_AUTHORIZATION_MISMATCH");
        }
    }

    private void validateState(State state) {
        if (!VERSION.equals(state.journalVersion())
                || !authorization.authorizationId().equals(state.authorizationId())) {
            throw new IllegalStateException("VISUAL_EVALUATION_JOURNAL_IDENTITY_MISMATCH");
        }
        var assignments = new HashSet<String>();
        var executions = new HashSet<String>();
        var runs = new HashSet<String>();
        for (var item : state.executions()) {
            requireAuthorizedCase(item.caseId());
            if (!assignmentKey(item.caseId()).equals(item.assignmentKey())
                    || !authorization.profileId().equals(item.profileId())
                    || !authorization.model().equals(item.model())
                    || !assignments.add(item.assignmentKey())
                    || !executions.add(item.executionId())
                    || item.runId() != null && !runs.add(item.runId())) {
                throw new IllegalStateException("VISUAL_EVALUATION_JOURNAL_EXECUTION_INVALID");
            }
            if (item.evaluation() != null) requireEvaluationIdentity(item.evaluation());
        }
        if (state.executions().size() > authorization.caseIds().size()) {
            throw new IllegalStateException("VISUAL_EVALUATION_JOURNAL_CASE_CAP_EXCEEDED");
        }
    }

    private void requireAuthorizedCase(String caseId) {
        corpus.require(caseId);
        if (!authorization.caseIds().contains(caseId)) {
            throw new IllegalArgumentException("VISUAL_EVALUATION_CASE_NOT_AUTHORIZED");
        }
    }

    private String assignmentKey(String caseId) {
        return authorization.profileId() + "|" + caseId;
    }

    private static void requireExecution(Execution execution, String executionId, String status) {
        if (!execution.executionId().equals(executionId) || !status.equals(execution.status())) {
            throw new IllegalStateException("VISUAL_EVALUATION_EXECUTION_STATE_MISMATCH");
        }
    }

    private synchronized void requireBatchLease() {
        if (batchLock == null || !batchLock.isValid()) {
            throw new IllegalStateException("VISUAL_EVALUATION_BATCH_LEASE_REQUIRED");
        }
    }

    private synchronized void closeBatchResources() {
        try {
            if (batchLock != null) batchLock.close();
        } catch (IOException ignored) {
            // Best effort after releasing the OS lock.
        }
        try {
            if (batchChannel != null) batchChannel.close();
        } catch (IOException ignored) {
            // Best effort after releasing the OS lock.
        }
        batchLock = null;
        batchChannel = null;
    }

    private synchronized <T> T withStateLock(IoOperation<T> operation) {
        try {
            Files.createDirectories(stateLockFile.getParent());
            try (var channel = FileChannel.open(stateLockFile, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE); var ignored = channel.lock()) {
                return operation.run();
            }
        } catch (IOException failure) {
            throw new IllegalStateException("VISUAL_EVALUATION_JOURNAL_IO_FAILED", failure);
        }
    }

    @FunctionalInterface
    private interface IoOperation<T> { T run() throws IOException; }

    final class BatchLease implements AutoCloseable {
        private final UUID leaseId;
        private boolean closed;

        private BatchLease(UUID leaseId) { this.leaseId = leaseId; }

        UUID leaseId() { return leaseId; }

        @Override
        public void close() {
            synchronized (VisualEvaluationJournal.this) {
                if (closed) return;
                closed = true;
                closeBatchResources();
            }
        }
    }

    record Recovery(List<String> retriableCaseIds, List<String> abandonedCaseIds) {
        Recovery {
            retriableCaseIds = List.copyOf(retriableCaseIds);
            abandonedCaseIds = List.copyOf(abandonedCaseIds);
        }
    }

    record Guard(
            String guardVersion,
            String authorizationVersion,
            String authorizationId,
            String phase,
            String inputClassification,
            String corpusVersion,
            String corpusSourceSha256,
            String evaluationIdentity,
            String profileId,
            String profileSnapshotSha256,
            String model,
            List<String> caseIds,
            int maximumProviderAttempts,
            long maximumTotalTokens,
            long maximumCostMicrosCny,
            int maximumCasesPerBatch
    ) {
        Guard { caseIds = List.copyOf(caseIds); }

        static Guard from(VisualEvaluationAuthorization value) {
            return new Guard(
                    GUARD_VERSION, value.authorizationVersion(), value.authorizationId(), value.phase(),
                    value.inputClassification(), value.corpusVersion(), value.corpusSourceSha256(),
                    value.evaluationIdentity(), value.profileId(), value.profileSnapshotSha256(), value.model(),
                    value.caseIds(), value.maximumProviderAttempts(), value.maximumTotalTokens(),
                    value.maximumCostMicrosCny(), value.maximumCasesPerBatch()
            );
        }
    }

    record State(
            String journalVersion,
            String authorizationId,
            List<Execution> executions,
            String createdAt,
            String updatedAt
    ) {
        State {
            executions = List.copyOf(Objects.requireNonNull(executions, "executions"));
            requireTime(createdAt);
            requireTime(updatedAt);
            if (Instant.parse(updatedAt).isBefore(Instant.parse(createdAt))) {
                throw new IllegalArgumentException("Visual journal timestamps are invalid");
            }
        }

        static State initial(String authorizationId, Instant now) {
            return new State(VERSION, authorizationId, List.of(), now.toString(), now.toString());
        }

        State withExecutions(List<Execution> value, Instant now) {
            return new State(journalVersion, authorizationId, value, createdAt, now.toString());
        }
    }

    record Execution(
            String assignmentKey,
            String executionId,
            String caseId,
            String profileId,
            String model,
            String runId,
            String status,
            VisualStageEvaluationResult evaluation,
            List<AttemptResult> attempts,
            String startedAt,
            String updatedAt,
            String completedAt
    ) {
        Execution {
            requireUuid(executionId, "executionId");
            if (assignmentKey == null || assignmentKey.isBlank() || caseId == null || caseId.isBlank()
                    || profileId == null || profileId.isBlank() || model == null || model.isBlank()
                    || !List.of("IN_PROGRESS", "COMPLETED", "ABANDONED_AFTER_RESERVATION").contains(status)) {
                throw new IllegalArgumentException("Visual execution identity is invalid");
            }
            if (runId != null) requireUuid(runId, "runId");
            attempts = List.copyOf(Objects.requireNonNull(attempts, "attempts"));
            var inProgress = "IN_PROGRESS".equals(status) && evaluation == null
                    && attempts.isEmpty() && completedAt == null;
            var complete = "COMPLETED".equals(status) && runId != null && evaluation != null
                    && completedAt != null;
            var abandoned = "ABANDONED_AFTER_RESERVATION".equals(status) && runId != null
                    && evaluation == null && attempts.isEmpty() && completedAt != null;
            if (!inProgress && !complete && !abandoned) {
                throw new IllegalArgumentException("Visual execution state is invalid");
            }
            requireTime(startedAt);
            requireTime(updatedAt);
            if (completedAt != null) requireTime(completedAt);
        }

        static Execution inProgress(
                String key,
                VisualEvaluationAuthorization authorization,
                String caseId,
                Instant now
        ) {
            return new Execution(key, UUID.randomUUID().toString(), caseId, authorization.profileId(),
                    authorization.model(), null, "IN_PROGRESS", null, List.of(), now.toString(),
                    now.toString(), null);
        }

        Execution bindRun(UUID value, Instant now) {
            return new Execution(assignmentKey, executionId, caseId, profileId, model, value.toString(),
                    status, evaluation, attempts, startedAt, now.toString(), completedAt);
        }

        Execution complete(VisualStageEvaluationResult value, List<AttemptResult> values, Instant now) {
            return new Execution(assignmentKey, executionId, caseId, profileId, model, runId,
                    "COMPLETED", value, values, startedAt, now.toString(), now.toString());
        }

        Execution abandon(Instant now) {
            return new Execution(assignmentKey, executionId, caseId, profileId, model, runId,
                    "ABANDONED_AFTER_RESERVATION", null, List.of(), startedAt, now.toString(), now.toString());
        }
    }

    record AttemptResult(
            String reservationId,
            int attemptOrdinal,
            String stage,
            String outcomeCode,
            String model,
            Long inputTokens,
            Long outputTokens,
            Long costMicrosCny,
            long latencyMillis,
            Map<String, Integer> problemCodeCounts
    ) {
        AttemptResult {
            requireUuid(reservationId, "reservationId");
            if (attemptOrdinal < 0 || attemptOrdinal > 7 || stage == null
                    || !stage.matches("[A-Z][A-Z0-9_]{0,63}") || outcomeCode == null
                    || !outcomeCode.matches("[A-Z][A-Z0-9_]{0,127}")
                    || !VisualEvaluationAuthorization.isApprovedModel(model)
                    || latencyMillis < 0 || latencyMillis > 3_600_000) {
                throw new IllegalArgumentException("Visual attempt identity is invalid");
            }
            var noUsage = inputTokens == null && outputTokens == null && costMicrosCny == null;
            var usage = inputTokens != null && inputTokens >= 0 && outputTokens != null
                    && outputTokens >= 0 && costMicrosCny != null && costMicrosCny >= 0;
            if (!noUsage && !usage) throw new IllegalArgumentException("Visual attempt usage is invalid");
            problemCodeCounts = canonicalProblemCounts(problemCodeCounts);
        }

        private static Map<String, Integer> canonicalProblemCounts(Map<String, Integer> source) {
            Objects.requireNonNull(source, "problemCodeCounts");
            var result = new LinkedHashMap<String, Integer>();
            source.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                if (entry.getKey() == null || !entry.getKey().matches("[A-Z][A-Z0-9_]{0,127}")
                        || entry.getValue() == null || entry.getValue() < 1 || entry.getValue() > 100_000) {
                    throw new IllegalArgumentException("Visual attempt problem taxonomy is invalid");
                }
                result.put(entry.getKey(), entry.getValue());
            });
            return Map.copyOf(result);
        }
    }

    private static void requireUuid(String value, String name) {
        try {
            UUID.fromString(value);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(name + " is invalid", invalid);
        }
    }

    private static void requireTime(String value) {
        try {
            Instant.parse(value);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Visual journal timestamp is invalid", invalid);
        }
    }
}
