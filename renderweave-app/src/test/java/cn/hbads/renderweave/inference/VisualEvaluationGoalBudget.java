package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.provider.ProviderCostEstimator;
import cn.hbads.renderweave.inference.provider.ProviderInferenceRequest;
import cn.hbads.renderweave.inference.provider.ProviderUsage;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Cross-authorization, cross-process Goal token/cost/attempt guard. Unsettled reservations retain
 * their conservative upper bound; there is deliberately no release operation after a possible call.
 */
final class VisualEvaluationGoalBudget {
    static final String VERSION = "renderweave-visual-evaluation-goal-budget/1.0";
    static final String GOAL_ID = "renderweave-visual-recognition-vnext-20260810";
    private final Path stateFile;
    private final Path guardFile;
    private final Path lockFile;
    private final ObjectMapper json;

    VisualEvaluationGoalBudget(Path directory, ObjectMapper objectMapper, Instant now) {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(now, "now");
        json = Objects.requireNonNull(objectMapper, "objectMapper").rebuild()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .build();
        stateFile = directory.resolve("goal-budget.json");
        guardFile = directory.resolve("goal-budget.guard.json");
        lockFile = directory.resolve("goal-budget.lock");
        withLock(() -> {
            var stateExists = Files.exists(stateFile);
            var guardExists = Files.exists(guardFile);
            if (stateExists != guardExists) {
                throw new IllegalStateException("VISUAL_EVALUATION_GOAL_BUDGET_PARTIAL_STATE");
            }
            if (stateExists) {
                var guard = readGuard();
                if (Guard.previous().equals(guard)
                        || Guard.previousV2().equals(guard)
                        || Guard.legacy().equals(guard)) {
                    var state = readState(guard);
                    writeAtomically(guardFile, json.writeValueAsString(Guard.expected()));
                    validateState(state, Guard.expected());
                } else {
                    validateGuard(guard);
                    readState(guard);
                }
            } else {
                writeAtomically(guardFile, json.writeValueAsString(Guard.expected()));
                writeState(State.initial(now));
            }
            return null;
        });
    }

    Reservation reserve(
            VisualEvaluationAuthorization authorization,
            ProviderInferenceRequest request,
            Instant now
    ) {
        Objects.requireNonNull(authorization, "authorization").requireOpen(now);
        Objects.requireNonNull(request, "request");
        if (!authorization.profileId().equals(request.profile().profileId())
                || !authorization.model().equals(request.profile().model())) {
            throw new IllegalStateException("VISUAL_EVALUATION_REQUEST_PROFILE_MISMATCH");
        }
        var reservedTokens = ProviderCostEstimator.maximumRequestTokens(request);
        var reservedCost = ProviderCostEstimator.maximumRequestCostMicrosCny(request);
        return withLock(() -> {
            var state = readState();
            if (state.reservations().stream().anyMatch(item -> item.runId().equals(request.runId().toString())
                    && item.attemptOrdinal() == request.attemptOrdinal())) {
                throw new IllegalStateException("VISUAL_EVALUATION_DUPLICATE_PROVIDER_ATTEMPT");
            }
            requireCapacity(state, authorization, reservedTokens, reservedCost);
            var reservation = Reservation.reserved(
                    authorization, request, reservedTokens, reservedCost, now
            );
            var reservations = new ArrayList<>(state.reservations());
            reservations.add(reservation);
            writeState(state.withReservations(reservations, now));
            return reservation;
        });
    }

    void settle(UUID reservationId, ProviderUsage usage, long actualCostMicrosCny, Instant now) {
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(usage, "usage");
        if (actualCostMicrosCny < 0) throw new IllegalArgumentException("actualCostMicrosCny is invalid");
        var actualTokens = Math.addExact(usage.inputTokens(), usage.outputTokens());
        var underestimated = new boolean[1];
        withLock(() -> {
            var state = readState();
            var reservations = new ArrayList<Reservation>();
            var found = false;
            for (var item : state.reservations()) {
                if (!item.reservationId().equals(reservationId.toString())) {
                    reservations.add(item);
                    continue;
                }
                if (!"RESERVED".equals(item.state())) {
                    throw new IllegalStateException("VISUAL_EVALUATION_RESERVATION_ALREADY_FINAL");
                }
                found = true;
                underestimated[0] = actualTokens > item.reservedTokens()
                        || actualCostMicrosCny > item.reservedCostMicrosCny();
                reservations.add(item.settle(usage, actualCostMicrosCny, underestimated[0], now));
            }
            if (!found) throw new IllegalStateException("VISUAL_EVALUATION_RESERVATION_NOT_FOUND");
            var updated = state.withReservations(reservations, now);
            writeState(updated);
            return null;
        });
        if (underestimated[0]) {
            throw new IllegalStateException("VISUAL_EVALUATION_RESERVATION_UNDERESTIMATED");
        }
    }

    Snapshot snapshot(String model, String authorizationId) {
        requireModel(model);
        return withLock(() -> {
            var state = readState();
            var goal = aggregate(state, model, null);
            var authorization = aggregate(state, model, authorizationId);
            return new Snapshot(goal, authorization, goal.breached());
        });
    }

    List<Reservation> reservations() {
        return withLock(() -> readState().reservations());
    }

    Reservation reservation(UUID reservationId) {
        Objects.requireNonNull(reservationId, "reservationId");
        return withLock(() -> readState().reservations().stream()
                .filter(item -> item.reservationId().equals(reservationId.toString()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "VISUAL_EVALUATION_RESERVATION_NOT_FOUND")));
    }

    List<Reservation> reservationsForRun(UUID runId) {
        Objects.requireNonNull(runId, "runId");
        return withLock(() -> readState().reservations().stream()
                .filter(item -> item.runId().equals(runId.toString()))
                .toList());
    }

    /** Strict read-only audit. It never creates, migrates or rewrites Goal state. */
    static ExistingSnapshot inspectExisting(Path directory, ObjectMapper objectMapper) {
        Objects.requireNonNull(directory, "directory");
        var stateFile = directory.resolve("goal-budget.json");
        var guardFile = directory.resolve("goal-budget.guard.json");
        var lockFile = directory.resolve("goal-budget.lock");
        if (!Files.isRegularFile(stateFile, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(guardFile, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(lockFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("VISUAL_EVALUATION_GOAL_BUDGET_MISSING");
        }
        var strict = Objects.requireNonNull(objectMapper, "objectMapper").rebuild()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .build();
        try (var channel = FileChannel.open(lockFile, StandardOpenOption.WRITE);
             var lock = channel.tryLock()) {
            if (lock == null) throw new IllegalStateException("VISUAL_EVALUATION_GOAL_BUDGET_BUSY");
            var stateBytes = Files.readAllBytes(stateFile);
            var guardBytes = Files.readAllBytes(guardFile);
            PayloadFreeLiveEvidenceGuard.requirePayloadFree(
                    new String(stateBytes, StandardCharsets.UTF_8));
            PayloadFreeLiveEvidenceGuard.requirePayloadFree(
                    new String(guardBytes, StandardCharsets.UTF_8));
            var guard = strict.readValue(guardBytes, Guard.class);
            validateGuard(guard);
            var state = strict.readValue(stateBytes, State.class);
            validateState(state, guard);
            var slots = Map.of(
                    "qwen3.8-max", aggregate(state, "qwen3.8-max", null),
                    "qwen3.7-plus", aggregate(state, "qwen3.7-plus", null),
                    "qwen3.7-flash", aggregate(state, "qwen3.7-flash", null));
            var nonTerminal = Math.toIntExact(state.reservations().stream()
                    .filter(item -> "RESERVED".equals(item.state())).count());
            var breached = Math.toIntExact(state.reservations().stream()
                    .filter(item -> "BREACHED".equals(item.state())).count());
            return new ExistingSnapshot(slots, state.reservations().size(), nonTerminal, breached,
                    sha256(stateBytes), sha256(guardBytes));
        } catch (OverlappingFileLockException busy) {
            throw new IllegalStateException("VISUAL_EVALUATION_GOAL_BUDGET_BUSY", busy);
        } catch (IllegalStateException expected) {
            throw expected;
        } catch (IOException | RuntimeException invalid) {
            throw new IllegalStateException("VISUAL_EVALUATION_GOAL_BUDGET_INVALID", invalid);
        }
    }

    private static void requireCapacity(
            State state,
            VisualEvaluationAuthorization authorization,
            long reservedTokens,
            long reservedCost
    ) {
        var goal = aggregate(state, authorization.model(), null);
        var ledger = aggregate(state, authorization.model(), authorization.authorizationId());
        if (goal.breached() || ledger.breached()
                || Math.addExact(goal.attempts(), 1) > VisualEvaluationAuthorization.GOAL_MAXIMUM_ATTEMPTS_PER_MODEL
                || Math.addExact(goal.tokens(), reservedTokens)
                > VisualEvaluationAuthorization.GOAL_MAXIMUM_TOKENS_PER_MODEL
                || Math.addExact(goal.costMicrosCny(), reservedCost)
                > VisualEvaluationAuthorization.goalMaximumCostMicrosCny(authorization.model())
                || Math.addExact(ledger.attempts(), 1) > authorization.maximumProviderAttempts()
                || Math.addExact(ledger.tokens(), reservedTokens) > authorization.maximumTotalTokens()
                || Math.addExact(ledger.costMicrosCny(), reservedCost) > authorization.maximumCostMicrosCny()) {
            throw new IllegalStateException("VISUAL_EVALUATION_GOAL_BUDGET_EXCEEDED");
        }
    }

    private static UsageAggregate aggregate(State state, String model, String authorizationId) {
        long tokens = 0;
        long cost = 0;
        var attempts = 0;
        var breached = false;
        var goalModel = VisualEvaluationAuthorization.goalModel(model);
        for (var item : state.reservations()) {
            var modelMatches = authorizationId == null
                    ? VisualEvaluationAuthorization.goalModel(item.model()).equals(goalModel)
                    : item.model().equals(model);
            if (!modelMatches
                    || authorizationId != null && !item.authorizationId().equals(authorizationId)) continue;
            attempts = Math.addExact(attempts, 1);
            tokens = Math.addExact(tokens, item.exposedTokens());
            cost = Math.addExact(cost, item.exposedCost());
            breached |= "BREACHED".equals(item.state());
        }
        return new UsageAggregate(attempts, tokens, cost, breached);
    }

    private State readState() {
        try {
            var guard = readGuard();
            validateGuard(guard);
            return readState(guard);
        } catch (IOException | RuntimeException invalid) {
            throw new IllegalStateException("VISUAL_EVALUATION_GOAL_BUDGET_INVALID", invalid);
        }
    }

    private State readState(Guard guard) throws IOException {
        var raw = Files.readString(stateFile, StandardCharsets.UTF_8);
        PayloadFreeLiveEvidenceGuard.requirePayloadFree(raw);
        var state = json.readValue(raw, State.class);
        validateState(state, guard);
        return state;
    }

    private Guard readGuard() {
        try {
            var raw = Files.readString(guardFile, StandardCharsets.UTF_8);
            PayloadFreeLiveEvidenceGuard.requirePayloadFree(raw);
            return json.readValue(raw, Guard.class);
        } catch (IOException | RuntimeException invalid) {
            throw new IllegalStateException("VISUAL_EVALUATION_GOAL_BUDGET_GUARD_INVALID", invalid);
        }
    }

    private void writeState(State state) throws IOException {
        var guard = readGuard();
        validateGuard(guard);
        validateState(state, guard);
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

    private static void validateGuard(Guard guard) {
        if (!Guard.expected().equals(guard)) {
            throw new IllegalStateException("VISUAL_EVALUATION_GOAL_BUDGET_GUARD_MISMATCH");
        }
    }

    private static void validateState(State state, Guard guard) {
        if (!VERSION.equals(state.stateVersion()) || !GOAL_ID.equals(state.goalId())) {
            throw new IllegalStateException("VISUAL_EVALUATION_GOAL_BUDGET_IDENTITY_MISMATCH");
        }
        var ids = new HashSet<String>();
        var attempts = new HashSet<String>();
        for (var item : state.reservations()) {
            if (!ids.add(item.reservationId())
                    || !attempts.add(item.runId() + "|" + item.attemptOrdinal())) {
                throw new IllegalStateException("VISUAL_EVALUATION_GOAL_BUDGET_DUPLICATE");
            }
        }
        for (var model : guard.maximumCostMicrosCnyByModel().keySet()) {
            var usage = aggregate(state, model, null);
            if (!usage.breached() && (usage.attempts()
                    > guard.maximumAttemptsPerModel()
                    || usage.tokens() > guard.maximumTokensPerModel()
                    || usage.costMicrosCny()
                    > guard.maximumCostMicrosCnyByModel().get(model))) {
                throw new IllegalStateException("VISUAL_EVALUATION_GOAL_BUDGET_INVALID");
            }
        }
    }

    private <T> T withLock(LockedOperation<T> operation) {
        try {
            Files.createDirectories(lockFile.getParent());
            try (var channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var ignored = channel.lock()) {
                return operation.run();
            }
        } catch (OverlappingFileLockException busy) {
            throw new IllegalStateException("VISUAL_EVALUATION_GOAL_BUDGET_BUSY", busy);
        } catch (IOException failure) {
            throw new IllegalStateException("VISUAL_EVALUATION_GOAL_BUDGET_IO_FAILED", failure);
        }
    }

    @FunctionalInterface
    private interface LockedOperation<T> {
        T run() throws IOException;
    }

    record Guard(
            String guardVersion,
            String goalId,
            long maximumTokensPerModel,
            int maximumAttemptsPerModel,
            Map<String, Long> maximumCostMicrosCnyByModel
    ) {
        private static final String VERSION = "renderweave-visual-evaluation-goal-guard/4.0";
        private static final String PREVIOUS_VERSION = "renderweave-visual-evaluation-goal-guard/3.0";
        private static final String PREVIOUS_V2_VERSION =
                "renderweave-visual-evaluation-goal-guard/2.0";
        private static final String LEGACY_VERSION = "renderweave-visual-evaluation-goal-guard/1.0";
        private static final Map<String, Long> HISTORICAL_MAXIMUM_COST_MICROS_CNY = Map.of(
                "qwen3.8-max", 18_000_000L,
                "qwen3.7-plus", 4_000_000L,
                "qwen3.7-flash", 400_000L
        );

        Guard {
            maximumCostMicrosCnyByModel = Map.copyOf(maximumCostMicrosCnyByModel);
        }

        static Guard expected() {
            return new Guard(
                    VERSION, GOAL_ID,
                    VisualEvaluationAuthorization.GOAL_MAXIMUM_TOKENS_PER_MODEL,
                    VisualEvaluationAuthorization.GOAL_MAXIMUM_ATTEMPTS_PER_MODEL,
                    VisualEvaluationAuthorization.GOAL_MAXIMUM_COST_MICROS_CNY
            );
        }

        static Guard previous() {
            return new Guard(
                    PREVIOUS_VERSION, GOAL_ID, 1_500_000L,
                    VisualEvaluationAuthorization.GOAL_MAXIMUM_ATTEMPTS_PER_MODEL,
                    HISTORICAL_MAXIMUM_COST_MICROS_CNY
            );
        }

        static Guard previousV2() {
            return new Guard(
                    PREVIOUS_V2_VERSION, GOAL_ID, 1_000_000L,
                    VisualEvaluationAuthorization.GOAL_MAXIMUM_ATTEMPTS_PER_MODEL,
                    HISTORICAL_MAXIMUM_COST_MICROS_CNY
            );
        }

        static Guard legacy() {
            return new Guard(
                    LEGACY_VERSION, GOAL_ID, 500_000L,
                    VisualEvaluationAuthorization.GOAL_MAXIMUM_ATTEMPTS_PER_MODEL,
                    HISTORICAL_MAXIMUM_COST_MICROS_CNY
            );
        }
    }

    record State(
            String stateVersion,
            String goalId,
            List<Reservation> reservations,
            String createdAt,
            String updatedAt
    ) {
        State {
            reservations = List.copyOf(Objects.requireNonNull(reservations, "reservations"));
            requireTime(createdAt);
            requireTime(updatedAt);
            if (Instant.parse(updatedAt).isBefore(Instant.parse(createdAt))) {
                throw new IllegalArgumentException("Visual goal budget timestamps are invalid");
            }
        }

        static State initial(Instant now) {
            return new State(VERSION, GOAL_ID, List.of(), now.toString(), now.toString());
        }

        State withReservations(List<Reservation> value, Instant now) {
            return new State(stateVersion, goalId, value, createdAt, now.toString());
        }
    }

    record Reservation(
            String reservationId,
            String authorizationId,
            String profileId,
            String model,
            String runId,
            int attemptOrdinal,
            String stage,
            long reservedTokens,
            long reservedCostMicrosCny,
            Long actualInputTokens,
            Long actualOutputTokens,
            Long actualCostMicrosCny,
            String state,
            String createdAt,
            String updatedAt
    ) {
        Reservation {
            requireUuid(reservationId, "reservationId");
            if (authorizationId == null || !authorizationId.matches("[a-z0-9][a-z0-9-]{0,95}")
                    || profileId == null || !profileId.matches("[a-z0-9][a-z0-9-]{0,127}")) {
                throw new IllegalArgumentException("Visual reservation authorization is invalid");
            }
            requireModel(model);
            requireUuid(runId, "runId");
            if (attemptOrdinal < 0 || attemptOrdinal > 7 || stage == null
                    || !stage.matches("[A-Z][A-Z0-9_]{0,63}")
                    || reservedTokens < 1 || reservedCostMicrosCny < 1) {
                throw new IllegalArgumentException("Visual reservation bound is invalid");
            }
            var reserved = "RESERVED".equals(state) && actualInputTokens == null
                    && actualOutputTokens == null && actualCostMicrosCny == null;
            var finalState = List.of("SETTLED", "BREACHED").contains(state)
                    && actualInputTokens != null && actualInputTokens >= 0
                    && actualOutputTokens != null && actualOutputTokens >= 0
                    && actualCostMicrosCny != null && actualCostMicrosCny >= 0;
            if (!reserved && !finalState) throw new IllegalArgumentException("Visual reservation state is invalid");
            if ("SETTLED".equals(state) && (Math.addExact(actualInputTokens, actualOutputTokens) > reservedTokens
                    || actualCostMicrosCny > reservedCostMicrosCny)) {
                throw new IllegalArgumentException("Settled visual reservation exceeds its bound");
            }
            requireTime(createdAt);
            requireTime(updatedAt);
        }

        static Reservation reserved(
                VisualEvaluationAuthorization authorization,
                ProviderInferenceRequest request,
                long tokens,
                long cost,
                Instant now
        ) {
            return new Reservation(
                    UUID.randomUUID().toString(), authorization.authorizationId(),
                    authorization.profileId(), authorization.model(), request.runId().toString(),
                    request.attemptOrdinal(), request.stage().name(), tokens, cost,
                    null, null, null, "RESERVED", now.toString(), now.toString()
            );
        }

        Reservation settle(ProviderUsage usage, long actualCost, boolean breached, Instant now) {
            return new Reservation(
                    reservationId, authorizationId, profileId, model, runId, attemptOrdinal, stage,
                    reservedTokens, reservedCostMicrosCny, usage.inputTokens(), usage.outputTokens(),
                    actualCost, breached ? "BREACHED" : "SETTLED", createdAt, now.toString()
            );
        }

        long exposedTokens() {
            if (actualInputTokens == null) return reservedTokens;
            var actual = Math.addExact(actualInputTokens, actualOutputTokens);
            return "BREACHED".equals(state) ? Math.max(reservedTokens, actual) : actual;
        }

        long exposedCost() {
            if (actualCostMicrosCny == null) return reservedCostMicrosCny;
            return "BREACHED".equals(state) ? Math.max(reservedCostMicrosCny, actualCostMicrosCny)
                    : actualCostMicrosCny;
        }
    }

    record UsageAggregate(int attempts, long tokens, long costMicrosCny, boolean breached) { }

    record Snapshot(UsageAggregate goal, UsageAggregate authorization, boolean breached) { }

    record ExistingSnapshot(
            Map<String, UsageAggregate> slots,
            int totalReservations,
            int nonTerminalReservations,
            int breachedReservations,
            String stateSha256,
            String guardSha256
    ) {
        ExistingSnapshot {
            slots = Map.copyOf(Objects.requireNonNull(slots, "slots"));
            if (!slots.keySet().equals(Set.of("qwen3.8-max", "qwen3.7-plus", "qwen3.7-flash"))
                    || totalReservations < 0 || nonTerminalReservations < 0
                    || breachedReservations < 0 || nonTerminalReservations > totalReservations
                    || breachedReservations > totalReservations
                    || stateSha256 == null || !stateSha256.matches("[0-9a-f]{64}")
                    || guardSha256 == null || !guardSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Visual Goal audit snapshot is invalid");
            }
        }
    }

    private static void requireModel(String model) {
        VisualEvaluationAuthorization.goalModel(model);
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
            throw new IllegalArgumentException("Visual goal budget timestamp is invalid", invalid);
        }
    }

    private static String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA_256_UNAVAILABLE", impossible);
        }
    }
}
