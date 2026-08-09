package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.eval.LiveEvaluationResult;
import cn.hbads.renderweave.inference.provider.ProviderBudgetExceededException;
import cn.hbads.renderweave.inference.provider.ProviderBudgetSnapshot;
import cn.hbads.renderweave.inference.replay.InferenceAttemptProblemTaxonomy;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Durable, payload-free certification state. Every mutation is serialized through a separate lock file
 * and replaced atomically so completed batches and worst-case reservations survive process restarts.
 */
final class LiveCertificationJournal {
    static final String VERSION = "renderweave-live-certification-journal/1.2";
    private static final String LEGACY_VERSION = "renderweave-live-certification-journal/1.1";
    private static final String REQUIRED_BUDGET_KEY = "p5-synthetic-canary";

    private final Path stateFile;
    private final Path lockFile;
    private final Path guardFile;
    private final Path batchLockFile;
    private final LiveCertificationAuthorization authorization;
    private final ObjectMapper json;
    private final Set<String> authorizedAssignmentKeys;
    private BatchLease activeBatchLease;

    LiveCertificationJournal(
            Path evidenceDirectory,
            LiveCertificationAuthorization authorization,
            ObjectMapper json,
            Instant now
    ) {
        Objects.requireNonNull(evidenceDirectory, "evidenceDirectory");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.json = Objects.requireNonNull(json, "json").rebuild()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .build();
        authorizedAssignmentKeys = authorization.assignments(
                new cn.hbads.renderweave.inference.eval.LiveEvaluationCorpus()
        ).stream().map(LiveCertificationAuthorization.Assignment::key)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Objects.requireNonNull(now, "now");
        stateFile = evidenceDirectory.resolve("state.json");
        lockFile = evidenceDirectory.resolve("state.lock");
        guardFile = evidenceDirectory.resolve("state.guard.json");
        batchLockFile = evidenceDirectory.resolve("batch.lock");
        withLock(() -> {
            if (Files.exists(stateFile)) {
                var state = read();
                validateIdentity(state);
                if (Files.exists(guardFile)) {
                    validateGuard(readGuard());
                } else if ("PROPOSED".equals(authorization.status())
                        && state.reservations().isEmpty() && state.executions().isEmpty()) {
                    // One-time migration for a zero-use preflight state created before the guard existed.
                    writeGuard();
                } else {
                    throw new IllegalStateException("LIVE_CERTIFICATION_JOURNAL_GUARD_MISSING");
                }
            } else {
                if (Files.exists(guardFile)) {
                    throw new IllegalStateException("LIVE_CERTIFICATION_JOURNAL_STATE_MISSING");
                }
                writeGuard();
                write(State.initial(authorization, now));
            }
            return null;
        });
    }

    synchronized BatchLease acquireBatchLease(Instant now) {
        authorization.requireOpen(Objects.requireNonNull(now, "now"));
        if (activeBatchLease != null && activeBatchLease.valid()) {
            throw new IllegalStateException("LIVE_CERTIFICATION_BATCH_ALREADY_ACTIVE");
        }
        FileChannel channel = null;
        try {
            Files.createDirectories(batchLockFile.getParent());
            channel = FileChannel.open(
                    batchLockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE
            );
            final FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException overlapping) {
                channel.close();
                throw new IllegalStateException("LIVE_CERTIFICATION_BATCH_ALREADY_ACTIVE", overlapping);
            }
            if (lock == null) {
                channel.close();
                throw new IllegalStateException("LIVE_CERTIFICATION_BATCH_ALREADY_ACTIVE");
            }
            activeBatchLease = new BatchLease(this, channel, lock);
            return activeBatchLease;
        } catch (IOException failure) {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            throw new IllegalStateException("LIVE_CERTIFICATION_BATCH_LEASE_FAILED", failure);
        }
    }

    String prepareReservation(
            String budgetKey,
            UUID runId,
            int attemptOrdinal,
            long reservedCostMicrosCny,
            Instant now
    ) {
        authorization.requireOpen(now);
        requireActiveBatchLease();
        requireBudgetKey(budgetKey);
        Objects.requireNonNull(runId, "runId");
        if (attemptOrdinal < 0 || attemptOrdinal > 2 || reservedCostMicrosCny <= 0) {
            throw new IllegalArgumentException("Certification reservation bounds are invalid");
        }
        return withLock(() -> {
            var state = readAndValidate();
            var claimed = state.executions().stream()
                    .filter(item -> "IN_PROGRESS".equals(item.status())
                            && runId.toString().equals(item.runId()))
                    .toList();
            if (claimed.size() != 1) {
                throw new IllegalStateException("LIVE_CERTIFICATION_RUN_NOT_CLAIMED");
            }
            var priorAttempts = state.reservations().stream()
                    .filter(item -> item.runId().equals(runId.toString())).count();
            if (attemptOrdinal != priorAttempts) {
                throw new IllegalStateException("LIVE_CERTIFICATION_ATTEMPT_SEQUENCE_INVALID");
            }
            if (state.reservations().stream().anyMatch(item ->
                    item.runId().equals(runId.toString()) && item.attemptOrdinal() == attemptOrdinal)) {
                throw new ProviderBudgetExceededException("PROVIDER_ATTEMPT_ALREADY_RESERVED");
            }
            if (state.reservations().size() >= authorization.maximumProviderAttempts()) {
                throw new ProviderBudgetExceededException("PROVIDER_ATTEMPT_BUDGET_EXHAUSTED");
            }
            if (reservedCostMicrosCny > authorization.maximumCostMicrosCny() - consumedCost(state)) {
                throw new ProviderBudgetExceededException("PROVIDER_COST_BUDGET_EXHAUSTED");
            }
            var journalId = UUID.randomUUID().toString();
            var reservations = new ArrayList<>(state.reservations());
            reservations.add(new Reservation(
                    journalId, null, budgetKey, runId.toString(), attemptOrdinal,
                    reservedCostMicrosCny, null, "PREPARED", now.toString(), now.toString()
            ));
            write(state.withReservations(reservations, now));
            return journalId;
        });
    }

    void bindReservation(String journalReservationId, UUID delegateReservationId, Instant now) {
        requireActiveBatchLease();
        Objects.requireNonNull(journalReservationId, "journalReservationId");
        Objects.requireNonNull(delegateReservationId, "delegateReservationId");
        Objects.requireNonNull(now, "now");
        withLock(() -> {
            var state = readAndValidate();
            var reservations = new ArrayList<Reservation>();
            var found = false;
            for (var item : state.reservations()) {
                if (!item.journalReservationId().equals(journalReservationId)) {
                    reservations.add(item);
                    continue;
                }
                found = true;
                if (item.delegateReservationId() != null
                        && !item.delegateReservationId().equals(delegateReservationId.toString())) {
                    throw new IllegalStateException("Certification reservation was already bound differently");
                }
                reservations.add(item.bind(delegateReservationId, now));
            }
            if (!found) throw new IllegalArgumentException("Unknown certification reservation");
            write(state.withReservations(reservations, now));
            return null;
        });
    }

    Reservation settleByDelegate(UUID delegateReservationId, long actualCostMicrosCny, Instant now) {
        requireActiveBatchLease();
        Objects.requireNonNull(delegateReservationId, "delegateReservationId");
        Objects.requireNonNull(now, "now");
        if (actualCostMicrosCny < 0) throw new IllegalArgumentException("Actual cost must not be negative");
        return withLock(() -> {
            var state = readAndValidate();
            var reservations = new ArrayList<Reservation>();
            Reservation settled = null;
            for (var item : state.reservations()) {
                if (!delegateReservationId.toString().equals(item.delegateReservationId())) {
                    reservations.add(item);
                    continue;
                }
                if (actualCostMicrosCny > item.reservedCostMicrosCny()) {
                    throw new IllegalArgumentException("Actual cost exceeds the certification reservation");
                }
                if (item.actualCostMicrosCny() != null
                        && item.actualCostMicrosCny() != actualCostMicrosCny) {
                    throw new IllegalStateException(
                            "Certification reservation was already settled differently"
                    );
                }
                settled = item.settle(actualCostMicrosCny, now);
                reservations.add(settled);
            }
            if (settled == null) throw new IllegalArgumentException("Unknown certification reservation");
            write(state.withReservations(reservations, now));
            return settled;
        });
    }

    ProviderBudgetSnapshot snapshot(String budgetKey) {
        requireBudgetKey(budgetKey);
        return withLock(() -> {
            var state = readAndValidate();
            return new ProviderBudgetSnapshot(
                    budgetKey,
                    authorization.maximumProviderAttempts(),
                    state.reservations().size(),
                    authorization.maximumCostMicrosCny(),
                    consumedCost(state)
            );
        });
    }

    void beginAssignment(String assignmentKey, String profileId, String caseId, Instant now) {
        requireActiveBatchLease();
        validateAssignment(assignmentKey, profileId, caseId);
        Objects.requireNonNull(now, "now");
        withLock(() -> {
            var state = readAndValidate();
            if (state.executions().stream().anyMatch(item -> item.assignmentKey().equals(assignmentKey))) {
                throw new IllegalStateException("Certification assignment already exists");
            }
            var executions = new ArrayList<>(state.executions());
            executions.add(new Execution(
                    assignmentKey, profileId, caseId, null, "IN_PROGRESS", null,
                    now.toString(), now.toString()
            ));
            write(state.withExecutions(executions, now));
            return null;
        });
    }

    void bindRun(String assignmentKey, UUID runId, Instant now) {
        requireActiveBatchLease();
        Objects.requireNonNull(assignmentKey, "assignmentKey");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(now, "now");
        withLock(() -> {
            var state = readAndValidate();
            if (state.executions().stream().anyMatch(item ->
                    !item.assignmentKey().equals(assignmentKey)
                            && runId.toString().equals(item.runId()))) {
                throw new IllegalStateException("Certification run is already bound to another assignment");
            }
            var executions = new ArrayList<Execution>();
            var found = false;
            for (var item : state.executions()) {
                if (!item.assignmentKey().equals(assignmentKey)) {
                    executions.add(item);
                    continue;
                }
                found = true;
                if (!"IN_PROGRESS".equals(item.status())) {
                    throw new IllegalStateException("Certification assignment is not in progress");
                }
                if (item.runId() != null && !item.runId().equals(runId.toString())) {
                    throw new IllegalStateException("Certification assignment was already bound differently");
                }
                executions.add(item.bindRun(runId, now));
            }
            if (!found) throw new IllegalArgumentException("Unknown certification assignment");
            write(state.withExecutions(executions, now));
            return null;
        });
    }

    void completeCase(CaseResult result, Instant now) {
        requireActiveBatchLease();
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(now, "now");
        validateAssignment(result.assignmentKey(), result.profileId(), result.caseId());
        withLock(() -> {
            var state = readAndValidate();
            var executions = new ArrayList<Execution>();
            var found = false;
            for (var item : state.executions()) {
                if (!item.assignmentKey().equals(result.assignmentKey())) {
                    executions.add(item);
                    continue;
                }
                found = true;
                if (!item.profileId().equals(result.profileId()) || !item.caseId().equals(result.caseId())) {
                    throw new IllegalStateException("Certification result identity does not match assignment");
                }
                if ("COMPLETED".equals(item.status())) {
                    if (!Objects.equals(item.result(), result)) {
                        throw new IllegalStateException("Certification assignment was already completed differently");
                    }
                    executions.add(item);
                } else {
                    executions.add(item.complete(result, now));
                }
            }
            if (!found) throw new IllegalArgumentException("Unknown certification assignment");
            write(state.withExecutions(executions, now));
            return null;
        });
    }

    List<String> completedAssignmentKeys() {
        return withLock(() -> readAndValidate().executions().stream()
                .filter(item -> "COMPLETED".equals(item.status()))
                .map(Execution::assignmentKey)
                .toList());
    }

    List<CaseResult> resultsFor(String profileId) {
        if (!authorization.profileIds().contains(profileId)) {
            throw new IllegalArgumentException("Profile is outside certification authorization");
        }
        return withLock(() -> readAndValidate().executions().stream()
                .filter(item -> "COMPLETED".equals(item.status()) && item.profileId().equals(profileId))
                .map(Execution::result)
                .toList());
    }

    List<Execution> inProgressExecutions() {
        return withLock(() -> readAndValidate().executions().stream()
                .filter(item -> "IN_PROGRESS".equals(item.status()))
                .toList());
    }

    void discardUnreservedAssignment(String assignmentKey, Instant now) {
        requireActiveBatchLease();
        Objects.requireNonNull(assignmentKey, "assignmentKey");
        Objects.requireNonNull(now, "now");
        withLock(() -> {
            var state = readAndValidate();
            var executions = new ArrayList<Execution>();
            var found = false;
            for (var item : state.executions()) {
                if (!item.assignmentKey().equals(assignmentKey)) {
                    executions.add(item);
                    continue;
                }
                found = true;
                if (!"IN_PROGRESS".equals(item.status())) {
                    throw new IllegalStateException("Certification assignment is not in progress");
                }
                if (item.runId() != null && state.reservations().stream()
                        .anyMatch(reservation -> reservation.runId().equals(item.runId()))) {
                    throw new IllegalStateException(
                            "Certification assignment has an external-call reservation"
                    );
                }
            }
            if (!found) throw new IllegalArgumentException("Unknown certification assignment");
            write(state.withExecutions(executions, now));
            return null;
        });
    }

    boolean hasReservationForRun(String runId) {
        Objects.requireNonNull(runId, "runId");
        return withLock(() -> readAndValidate().reservations().stream()
                .anyMatch(item -> item.runId().equals(runId)));
    }

    private void validateAssignment(String assignmentKey, String profileId, String caseId) {
        if (profileId == null || !authorization.profileIds().contains(profileId)
                || caseId == null || caseId.isBlank()
                || !Objects.equals(assignmentKey, profileId + "|" + caseId)
                || !authorizedAssignmentKeys.contains(assignmentKey)) {
            throw new IllegalArgumentException("Certification assignment identity is invalid");
        }
    }

    private State readAndValidate() throws IOException {
        var state = read();
        validateIdentity(state);
        return state;
    }

    private State read() throws IOException {
        try {
            var raw = PayloadFreeLiveEvidenceGuard.requirePayloadFree(
                    Files.readString(stateFile, StandardCharsets.UTF_8)
            );
            var tree = json.readTree(raw);
            prepareAttemptProblemCounts(tree);
            return json.treeToValue(tree, State.class);
        } catch (IOException | RuntimeException invalid) {
            throw new IllegalStateException("LIVE_CERTIFICATION_JOURNAL_INVALID", invalid);
        }
    }

    private void prepareAttemptProblemCounts(JsonNode root) {
        var legacy = LEGACY_VERSION.equals(root.path("journalVersion").asText());
        var executions = root.path("executions");
        if (!executions.isArray()) return;
        for (var execution : executions) {
            var result = execution.get("result");
            if (result == null || result.isNull()) continue;
            var attempts = result.path("attempts");
            if (!attempts.isArray()) continue;
            for (var attempt : attempts) {
                var counts = attempt.get("problemCodeCounts");
                if (counts != null && !counts.isNull()) continue;
                if (!legacy || !(attempt instanceof ObjectNode objectAttempt)) {
                    throw new IllegalArgumentException("Attempt problem taxonomy is required");
                }
                objectAttempt.set("problemCodeCounts", json.createObjectNode());
            }
        }
    }

    private void write(State state) throws IOException {
        var temporary = stateFile.resolveSibling(stateFile.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            var content = PayloadFreeLiveEvidenceGuard.requirePayloadFree(
                    json.writerWithDefaultPrettyPrinter().writeValueAsString(state)
            );
            Files.writeString(
                    temporary,
                    content,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
            );
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(temporary, stateFile,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Guard readGuard() throws IOException {
        try {
            return json.readValue(Files.readString(guardFile, StandardCharsets.UTF_8), Guard.class);
        } catch (IOException | RuntimeException invalid) {
            throw new IllegalStateException("LIVE_CERTIFICATION_JOURNAL_GUARD_INVALID", invalid);
        }
    }

    private void writeGuard() throws IOException {
        Files.writeString(
                guardFile,
                json.writerWithDefaultPrettyPrinter().writeValueAsString(Guard.from(authorization)),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
        );
        try (var channel = FileChannel.open(guardFile, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private void validateGuard(Guard guard) {
        if (!Guard.VERSION.equals(guard.guardVersion())
                || !authorization.authorizationId().equals(guard.authorizationId())
                || !authorization.authorizationVersion().equals(guard.authorizationVersion())
                || !authorization.corpusVersion().equals(guard.corpusVersion())
                || !authorization.evaluationIdentity().equals(guard.evaluationIdentity())
                || !authorization.profileIds().equals(guard.profileIds())
                || authorization.maximumProviderAttempts() != guard.maximumProviderAttempts()
                || authorization.maximumCostMicrosCny() != guard.maximumCostMicrosCny()
                || authorization.maximumCasesPerBatch() != guard.maximumCasesPerBatch()) {
            throw new IllegalStateException("LIVE_CERTIFICATION_JOURNAL_GUARD_MISMATCH");
        }
    }

    private void validateIdentity(State state) {
        if (!Set.of(VERSION, LEGACY_VERSION).contains(state.journalVersion())
                || !authorization.authorizationId().equals(state.authorizationId())
                || !authorization.authorizationVersion().equals(state.authorizationVersion())
                || !authorization.corpusVersion().equals(state.corpusVersion())
                || !authorization.evaluationIdentity().equals(state.evaluationIdentity())
                || !authorization.profileIds().equals(state.profileIds())
                || authorization.maximumProviderAttempts() != state.maximumProviderAttempts()
                || authorization.maximumCostMicrosCny() != state.maximumCostMicrosCny()
                || authorization.maximumCasesPerBatch() != state.maximumCasesPerBatch()) {
            throw new IllegalStateException("LIVE_CERTIFICATION_JOURNAL_AUTHORIZATION_MISMATCH");
        }
        if (LEGACY_VERSION.equals(state.journalVersion())
                && "OPEN".equals(authorization.status())) {
            throw new IllegalStateException("LIVE_CERTIFICATION_JOURNAL_LEGACY_READ_ONLY");
        }
        if (state.reservations().size() > state.maximumProviderAttempts()
                || consumedCost(state) > state.maximumCostMicrosCny()) {
            throw new IllegalStateException("LIVE_CERTIFICATION_JOURNAL_BUDGET_INVALID");
        }
        var reservationIds = new LinkedHashSet<String>();
        var delegateIds = new LinkedHashSet<String>();
        var runAttempts = new LinkedHashSet<String>();
        for (var reservation : state.reservations()) {
            if (!reservationIds.add(reservation.journalReservationId())
                    || reservation.delegateReservationId() != null
                    && !delegateIds.add(reservation.delegateReservationId())
                    || !runAttempts.add(reservation.runId() + "|" + reservation.attemptOrdinal())) {
                throw new IllegalStateException("LIVE_CERTIFICATION_JOURNAL_DUPLICATE_RESERVATION");
            }
        }
        var assignmentKeys = new LinkedHashSet<String>();
        var inProgress = 0;
        for (var execution : state.executions()) {
            validateAssignment(execution.assignmentKey(), execution.profileId(), execution.caseId());
            if (!assignmentKeys.add(execution.assignmentKey())) {
                throw new IllegalStateException("LIVE_CERTIFICATION_JOURNAL_DUPLICATE_ASSIGNMENT");
            }
            if ("IN_PROGRESS".equals(execution.status())) inProgress++;
        }
        if (state.executions().size() > authorizedAssignmentKeys.size() || inProgress > 1) {
            throw new IllegalStateException("LIVE_CERTIFICATION_JOURNAL_EXECUTION_STATE_INVALID");
        }
    }

    private static long consumedCost(State state) {
        return state.reservations().stream().mapToLong(item ->
                item.actualCostMicrosCny() == null
                        ? item.reservedCostMicrosCny()
                        : item.actualCostMicrosCny()
        ).sum();
    }

    private static void requireBudgetKey(String budgetKey) {
        if (!REQUIRED_BUDGET_KEY.equals(budgetKey)) {
            throw new IllegalArgumentException("Certification budget key is invalid");
        }
    }

    private synchronized void requireActiveBatchLease() {
        if (activeBatchLease == null || !activeBatchLease.valid()) {
            throw new IllegalStateException("LIVE_CERTIFICATION_BATCH_LEASE_REQUIRED");
        }
    }

    private synchronized void releaseBatchLease(BatchLease lease) {
        if (activeBatchLease == lease) activeBatchLease = null;
    }

    private <T> T withLock(LockedOperation<T> operation) {
        try {
            Files.createDirectories(stateFile.getParent());
            try (var channel = FileChannel.open(
                    lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE
            ); var ignored = channel.lock()) {
                return operation.run();
            }
        } catch (IOException failure) {
            throw new IllegalStateException("LIVE_CERTIFICATION_JOURNAL_IO_FAILED", failure);
        }
    }

    @FunctionalInterface
    private interface LockedOperation<T> {
        T run() throws IOException;
    }

    static final class BatchLease implements AutoCloseable {
        private final LiveCertificationJournal owner;
        private final FileChannel channel;
        private final FileLock lock;
        private final AtomicBoolean closed = new AtomicBoolean();

        private BatchLease(LiveCertificationJournal owner, FileChannel channel, FileLock lock) {
            this.owner = owner;
            this.channel = channel;
            this.lock = lock;
        }

        private boolean valid() {
            return !closed.get() && channel.isOpen() && lock.isValid();
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            IOException failure = null;
            try {
                lock.release();
            } catch (IOException releaseFailure) {
                failure = releaseFailure;
            }
            try {
                channel.close();
            } catch (IOException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            } finally {
                owner.releaseBatchLease(this);
            }
            if (failure != null) {
                throw new IllegalStateException("LIVE_CERTIFICATION_BATCH_LEASE_RELEASE_FAILED", failure);
            }
        }
    }

    record Guard(
            String guardVersion,
            String authorizationId,
            String authorizationVersion,
            String corpusVersion,
            String evaluationIdentity,
            List<String> profileIds,
            int maximumProviderAttempts,
            long maximumCostMicrosCny,
            int maximumCasesPerBatch
    ) {
        private static final String VERSION = "renderweave-live-certification-guard/1.1";

        Guard {
            Objects.requireNonNull(evaluationIdentity, "evaluationIdentity");
            profileIds = List.copyOf(profileIds);
        }

        static Guard from(LiveCertificationAuthorization authorization) {
            return new Guard(
                    VERSION,
                    authorization.authorizationId(),
                    authorization.authorizationVersion(),
                    authorization.corpusVersion(),
                    authorization.evaluationIdentity(),
                    authorization.profileIds(),
                    authorization.maximumProviderAttempts(),
                    authorization.maximumCostMicrosCny(),
                    authorization.maximumCasesPerBatch()
            );
        }
    }

    record State(
            String journalVersion,
            String authorizationId,
            String authorizationVersion,
            String corpusVersion,
            String evaluationIdentity,
            List<String> profileIds,
            int maximumProviderAttempts,
            long maximumCostMicrosCny,
            int maximumCasesPerBatch,
            List<Reservation> reservations,
            List<Execution> executions,
            String createdAt,
            String updatedAt
    ) {
        State {
            Objects.requireNonNull(journalVersion, "journalVersion");
            Objects.requireNonNull(authorizationId, "authorizationId");
            Objects.requireNonNull(authorizationVersion, "authorizationVersion");
            Objects.requireNonNull(corpusVersion, "corpusVersion");
            Objects.requireNonNull(evaluationIdentity, "evaluationIdentity");
            profileIds = List.copyOf(profileIds);
            reservations = List.copyOf(reservations);
            executions = List.copyOf(executions);
            if (maximumProviderAttempts < 1 || maximumCostMicrosCny < 1
                    || maximumCasesPerBatch < 1) {
                throw new IllegalArgumentException("Certification journal limits are invalid");
            }
            requireOrderedTimes(createdAt, updatedAt, "Certification journal timestamps are invalid");
        }

        static State initial(LiveCertificationAuthorization authorization, Instant now) {
            return new State(
                    VERSION,
                    authorization.authorizationId(),
                    authorization.authorizationVersion(),
                    authorization.corpusVersion(),
                    authorization.evaluationIdentity(),
                    authorization.profileIds(),
                    authorization.maximumProviderAttempts(),
                    authorization.maximumCostMicrosCny(),
                    authorization.maximumCasesPerBatch(),
                    List.of(), List.of(), now.toString(), now.toString()
            );
        }

        State withReservations(List<Reservation> value, Instant now) {
            return new State(
                    journalVersion, authorizationId, authorizationVersion, corpusVersion,
                    evaluationIdentity, profileIds,
                    maximumProviderAttempts, maximumCostMicrosCny, maximumCasesPerBatch,
                    value, executions, createdAt, now.toString()
            );
        }

        State withExecutions(List<Execution> value, Instant now) {
            return new State(
                    journalVersion, authorizationId, authorizationVersion, corpusVersion,
                    evaluationIdentity, profileIds,
                    maximumProviderAttempts, maximumCostMicrosCny, maximumCasesPerBatch,
                    reservations, value, createdAt, now.toString()
            );
        }
    }

    record Reservation(
            String journalReservationId,
            String delegateReservationId,
            String budgetKey,
            String runId,
            int attemptOrdinal,
            long reservedCostMicrosCny,
            Long actualCostMicrosCny,
            String state,
            String createdAt,
            String updatedAt
    ) {
        Reservation {
            requireUuid(journalReservationId, "journalReservationId");
            if (delegateReservationId != null) requireUuid(delegateReservationId, "delegateReservationId");
            requireBudgetKey(budgetKey);
            requireUuid(runId, "runId");
            if (attemptOrdinal < 0 || attemptOrdinal > 2 || reservedCostMicrosCny <= 0
                    || actualCostMicrosCny != null
                    && (actualCostMicrosCny < 0 || actualCostMicrosCny > reservedCostMicrosCny)) {
                throw new IllegalArgumentException("Certification journal reservation is invalid");
            }
            var validState = "PREPARED".equals(state)
                    && delegateReservationId == null && actualCostMicrosCny == null
                    || "RESERVED".equals(state)
                    && delegateReservationId != null && actualCostMicrosCny == null
                    || "SETTLED".equals(state)
                    && delegateReservationId != null && actualCostMicrosCny != null;
            if (!validState) throw new IllegalArgumentException("Certification reservation state is invalid");
            requireOrderedTimes(createdAt, updatedAt, "Certification reservation timestamps are invalid");
        }

        Reservation bind(UUID delegateId, Instant now) {
            return new Reservation(
                    journalReservationId, delegateId.toString(), budgetKey, runId, attemptOrdinal,
                    reservedCostMicrosCny, actualCostMicrosCny,
                    actualCostMicrosCny == null ? "RESERVED" : "SETTLED", createdAt, now.toString()
            );
        }

        Reservation settle(long actualCost, Instant now) {
            return new Reservation(
                    journalReservationId, delegateReservationId, budgetKey, runId, attemptOrdinal,
                    reservedCostMicrosCny, actualCost, "SETTLED", createdAt, now.toString()
            );
        }
    }

    record Execution(
            String assignmentKey,
            String profileId,
            String caseId,
            String runId,
            String status,
            CaseResult result,
            String startedAt,
            String updatedAt
    ) {
        Execution {
            Objects.requireNonNull(assignmentKey, "assignmentKey");
            Objects.requireNonNull(profileId, "profileId");
            Objects.requireNonNull(caseId, "caseId");
            if (runId != null) requireUuid(runId, "runId");
            var validState = "IN_PROGRESS".equals(status) && result == null
                    || "COMPLETED".equals(status) && result != null;
            if (!validState) throw new IllegalArgumentException("Certification execution state is invalid");
            requireOrderedTimes(startedAt, updatedAt, "Certification execution timestamps are invalid");
        }

        Execution bindRun(UUID value, Instant now) {
            return new Execution(
                    assignmentKey, profileId, caseId, value.toString(), status, result,
                    startedAt, now.toString()
            );
        }

        Execution complete(CaseResult value, Instant now) {
            return new Execution(
                    assignmentKey, profileId, caseId, runId, "COMPLETED", value,
                    startedAt, now.toString()
            );
        }
    }

    record CaseResult(
            String assignmentKey,
            String profileId,
            String caseId,
            String runState,
            String failureCode,
            EvaluationMetrics evaluation,
            List<AttemptResult> attempts,
            String completedAt
    ) {
        CaseResult {
            Objects.requireNonNull(assignmentKey, "assignmentKey");
            Objects.requireNonNull(profileId, "profileId");
            Objects.requireNonNull(caseId, "caseId");
            Objects.requireNonNull(runState, "runState");
            Objects.requireNonNull(evaluation, "evaluation");
            if (!caseId.equals(evaluation.caseId())) {
                throw new IllegalArgumentException("Evaluation case does not match certification result");
            }
            attempts = List.copyOf(attempts);
            if (failureCode != null && !failureCode.matches("[A-Z][A-Z0-9_]{0,127}")) {
                throw new IllegalArgumentException("Certification failure code is invalid");
            }
            try {
                cn.hbads.renderweave.inference.run.InferenceRunState.valueOf(runState);
                Instant.parse(completedAt);
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("Certification result state is invalid", invalid);
            }
            if (attempts.stream().map(AttemptResult::ordinal).distinct().count() != attempts.size()) {
                throw new IllegalArgumentException("Certification attempt ordinals are duplicated");
            }
        }
    }

    /** Scalar sufficient statistics only; model-produced names and diff details never enter evidence. */
    record EvaluationMetrics(
            String caseId,
            String outcomeCode,
            boolean passed,
            int bundleContractBps,
            int expectedEntityCount,
            int actualEntityCount,
            int matchedEntityCount,
            int expectedFieldCount,
            int actualFieldCount,
            int matchedFieldCount,
            int supportedTypeExpectedCount,
            int supportedTypeMatchedCount,
            int expectedEdgeCount,
            int actualEdgeCount,
            int matchedEdgeCount,
            int evidenceExpectedCount,
            int evidencePresentCount,
            int dagValidityBps,
            int criticalHallucinationCount,
            int blockerCount
    ) {
        EvaluationMetrics {
            // Reuse the domain result's complete invariant set at every deserialize boundary.
            new LiveEvaluationResult(
                    caseId, outcomeCode, passed, bundleContractBps,
                    expectedEntityCount, actualEntityCount, matchedEntityCount,
                    expectedFieldCount, actualFieldCount, matchedFieldCount,
                    supportedTypeExpectedCount, supportedTypeMatchedCount,
                    expectedEdgeCount, actualEdgeCount, matchedEdgeCount,
                    evidenceExpectedCount, evidencePresentCount, dagValidityBps,
                    criticalHallucinationCount, blockerCount,
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
            );
        }

        static EvaluationMetrics from(LiveEvaluationResult value) {
            Objects.requireNonNull(value, "value");
            return new EvaluationMetrics(
                    value.caseId(), value.outcomeCode(), value.passed(), value.bundleContractBps(),
                    value.expectedEntityCount(), value.actualEntityCount(), value.matchedEntityCount(),
                    value.expectedFieldCount(), value.actualFieldCount(), value.matchedFieldCount(),
                    value.supportedTypeExpectedCount(), value.supportedTypeMatchedCount(),
                    value.expectedEdgeCount(), value.actualEdgeCount(), value.matchedEdgeCount(),
                    value.evidenceExpectedCount(), value.evidencePresentCount(), value.dagValidityBps(),
                    value.criticalHallucinationCount(), value.blockerCount()
            );
        }

        LiveEvaluationResult toResult() {
            return new LiveEvaluationResult(
                    caseId, outcomeCode, passed, bundleContractBps,
                    expectedEntityCount, actualEntityCount, matchedEntityCount,
                    expectedFieldCount, actualFieldCount, matchedFieldCount,
                    supportedTypeExpectedCount, supportedTypeMatchedCount,
                    expectedEdgeCount, actualEdgeCount, matchedEdgeCount,
                    evidenceExpectedCount, evidencePresentCount, dagValidityBps,
                    criticalHallucinationCount, blockerCount,
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
            );
        }
    }

    /** Bounded telemetry intentionally excludes provider request ids and all provider/candidate payloads. */
    record AttemptResult(
            int ordinal,
            String stage,
            String status,
            String outcomeCode,
            String providerModel,
            long inputTokens,
            long outputTokens,
            long estimatedCostMicrosCny,
            long durationMillis,
            Map<String, Integer> problemCodeCounts
    ) {
        AttemptResult {
            if (ordinal < 0 || ordinal > 2
                    || stage == null || !List.of("STRUCTURE", "REPAIR").contains(stage)
                    || status == null || !List.of("SUCCEEDED", "REJECTED", "FAILED").contains(status)
                    || outcomeCode == null || !outcomeCode.matches("[A-Z][A-Z0-9_]{0,127}")
                    || providerModel != null && !providerModel.matches("[A-Za-z0-9._/-]{1,128}")
                    || inputTokens < 0 || outputTokens < 0 || estimatedCostMicrosCny < 0
                    || durationMillis < 0) {
                throw new IllegalArgumentException("Certification attempt telemetry is invalid");
            }
            problemCodeCounts = InferenceAttemptProblemTaxonomy.normalize(
                    Objects.requireNonNull(problemCodeCounts, "problemCodeCounts")
            );
        }
    }

    private static void requireUuid(String value, String name) {
        try {
            UUID.fromString(value);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(name + " is invalid", invalid);
        }
    }

    private static void requireOrderedTimes(String createdAt, String updatedAt, String message) {
        try {
            var created = Instant.parse(createdAt);
            var updated = Instant.parse(updatedAt);
            if (updated.isBefore(created)) throw new IllegalArgumentException(message);
        } catch (RuntimeException invalid) {
            if (invalid instanceof IllegalArgumentException
                    && Objects.equals(invalid.getMessage(), message)) throw invalid;
            throw new IllegalArgumentException(message, invalid);
        }
    }
}
