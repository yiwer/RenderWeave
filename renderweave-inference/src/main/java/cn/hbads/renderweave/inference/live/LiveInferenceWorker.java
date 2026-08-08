package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.candidate.CandidateBundle;
import cn.hbads.renderweave.inference.candidate.CandidateJsonCodec;
import cn.hbads.renderweave.inference.candidate.CandidateProblem;
import cn.hbads.renderweave.inference.candidate.CandidateProblemJsonCodec;
import cn.hbads.renderweave.inference.candidate.CandidateProblemSeverity;
import cn.hbads.renderweave.inference.candidate.CandidateValidationContext;
import cn.hbads.renderweave.inference.candidate.CandidateValidator;
import cn.hbads.renderweave.inference.candidate.InvalidCandidateContractException;
import cn.hbads.renderweave.inference.input.BlobStore;
import cn.hbads.renderweave.inference.input.NormalizedArtifact;
import cn.hbads.renderweave.inference.profile.InferenceProfile;
import cn.hbads.renderweave.inference.profile.InferenceProfileRegistry;
import cn.hbads.renderweave.inference.profile.InferencePromptRegistry;
import cn.hbads.renderweave.inference.profile.JsonStructuralProfile;
import cn.hbads.renderweave.inference.profile.JsonStructuralProfiler;
import cn.hbads.renderweave.inference.provider.InferenceProvider;
import cn.hbads.renderweave.inference.provider.ProviderBudgetExceededException;
import cn.hbads.renderweave.inference.provider.ProviderBudgetStore;
import cn.hbads.renderweave.inference.provider.ProviderCallException;
import cn.hbads.renderweave.inference.provider.ProviderCostEstimator;
import cn.hbads.renderweave.inference.provider.ProviderImage;
import cn.hbads.renderweave.inference.provider.ProviderInferenceRequest;
import cn.hbads.renderweave.inference.provider.ProviderInferenceResponse;
import cn.hbads.renderweave.inference.provider.ProviderNotConfiguredException;
import cn.hbads.renderweave.inference.replay.InferenceAttempt;
import cn.hbads.renderweave.inference.replay.InferenceAttemptStatus;
import cn.hbads.renderweave.inference.replay.InferenceReplayStore;
import cn.hbads.renderweave.inference.run.InferenceLeaseLostException;
import cn.hbads.renderweave.inference.run.InferenceRunSnapshot;
import cn.hbads.renderweave.inference.run.InferenceRunState;
import cn.hbads.renderweave.inference.run.InferenceRunStore;
import cn.hbads.renderweave.inference.run.InferenceStage;

import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Executes the guarded live workflow. Provider output remains in memory until strict parsing succeeds;
 * persistence receives only parsed Candidates, deterministic problems and bounded attempt metadata.
 */
public final class LiveInferenceWorker {
    public static final String CANARY_BUDGET_KEY = "p5-synthetic-canary";
    private static final int MAX_STAGE_ADVANCES = 24;

    private final InferenceRunStore runStore;
    private final InferenceReplayStore workflowStore;
    private final ProviderBudgetStore budgetStore;
    private final InferenceProvider provider;
    private final BlobStore blobStore;
    private final Clock clock;
    private final Duration leaseDuration;
    private final InferenceProfileRegistry profiles;
    private final InferencePromptRegistry prompts;
    private final JsonStructuralProfiler structuralProfiler;
    private final CandidateValidator candidateValidator;
    private final CandidateJsonCodec candidateCodec;
    private final CandidateProblemJsonCodec problemCodec;
    private final LiveWorkflowJsonCodec workflowCodec;
    private final LiveTaskJsonCodec taskCodec;

    public LiveInferenceWorker(
            InferenceRunStore runStore,
            InferenceReplayStore workflowStore,
            ProviderBudgetStore budgetStore,
            InferenceProvider provider,
            BlobStore blobStore,
            Clock clock,
            Duration leaseDuration
    ) {
        this(
                runStore, workflowStore, budgetStore, provider, blobStore, clock, leaseDuration,
                new InferenceProfileRegistry(), new InferencePromptRegistry(), new JsonStructuralProfiler(),
                new CandidateValidator(), new CandidateJsonCodec(), new CandidateProblemJsonCodec(),
                new LiveWorkflowJsonCodec(), new LiveTaskJsonCodec()
        );
    }

    LiveInferenceWorker(
            InferenceRunStore runStore,
            InferenceReplayStore workflowStore,
            ProviderBudgetStore budgetStore,
            InferenceProvider provider,
            BlobStore blobStore,
            Clock clock,
            Duration leaseDuration,
            InferenceProfileRegistry profiles,
            InferencePromptRegistry prompts,
            JsonStructuralProfiler structuralProfiler,
            CandidateValidator candidateValidator,
            CandidateJsonCodec candidateCodec,
            CandidateProblemJsonCodec problemCodec,
            LiveWorkflowJsonCodec workflowCodec,
            LiveTaskJsonCodec taskCodec
    ) {
        this.runStore = Objects.requireNonNull(runStore, "runStore");
        this.workflowStore = Objects.requireNonNull(workflowStore, "workflowStore");
        this.budgetStore = Objects.requireNonNull(budgetStore, "budgetStore");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.blobStore = Objects.requireNonNull(blobStore, "blobStore");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.prompts = Objects.requireNonNull(prompts, "prompts");
        this.structuralProfiler = Objects.requireNonNull(structuralProfiler, "structuralProfiler");
        this.candidateValidator = Objects.requireNonNull(candidateValidator, "candidateValidator");
        this.candidateCodec = Objects.requireNonNull(candidateCodec, "candidateCodec");
        this.problemCodec = Objects.requireNonNull(problemCodec, "problemCodec");
        this.workflowCodec = Objects.requireNonNull(workflowCodec, "workflowCodec");
        this.taskCodec = Objects.requireNonNull(taskCodec, "taskCodec");
        if (leaseDuration.isZero() || leaseDuration.isNegative()
                || leaseDuration.compareTo(Duration.ofMinutes(15)) > 0) {
            throw new IllegalArgumentException("leaseDuration must be positive and no longer than 15 minutes");
        }
    }

    public Optional<InferenceRunSnapshot> processNext(String workerId) {
        return processClaimed(runStore.claimNextLive(workerId, clock.instant(), leaseDuration));
    }

    private Optional<InferenceRunSnapshot> processClaimed(Optional<InferenceRunSnapshot> claimed) {
        if (claimed.isEmpty()) return Optional.empty();
        var initial = claimed.orElseThrow();
        try {
            return Optional.of(drain(initial));
        } catch (InferenceLeaseLostException lost) {
            var latest = runStore.find(initial.runId()).orElseThrow();
            if (latest.state() == InferenceRunState.RUNNING && latest.cancellationRequested()
                    && sameLease(initial, latest)) {
                return Optional.of(runStore.acknowledgeCancellation(
                        latest.runId(), latest.lease().orElseThrow().token(), clock.instant()
                ));
            }
            throw lost;
        } catch (ProviderBudgetExceededException exhausted) {
            return Optional.of(failIfOwned(initial, exhausted.code()));
        } catch (ProviderNotConfiguredException missing) {
            return Optional.of(failIfOwned(initial, missing.code()));
        } catch (RuntimeException failure) {
            return Optional.of(failIfOwned(initial, "LIVE_WORKFLOW_FAILED"));
        }
    }

    public InferenceRunSnapshot drain(InferenceRunSnapshot claimed) {
        var current = requireRunningLease(claimed);
        for (var advance = 0; advance < MAX_STAGE_ADVANCES && current.state() == InferenceRunState.RUNNING; advance++) {
            current = advance(current);
        }
        if (current.state() == InferenceRunState.RUNNING) {
            throw new IllegalStateException("Live stage budget was exhausted");
        }
        return current;
    }

    public InferenceRunSnapshot advance(InferenceRunSnapshot current) {
        current = requireRunningLease(current);
        if (current.cancellationRequested()) {
            return runStore.acknowledgeCancellation(current.runId(), token(current), clock.instant());
        }
        var profile = validateRun(current);
        return switch (current.stage()) {
            case OBSERVE -> observe(current);
            case STRUCTURE -> invoke(current, profile, false);
            case DETERMINISTIC_VALIDATE -> deterministicValidate(current, profile);
            case CRITIQUE -> critique(current, profile);
            case REPAIR -> invoke(current, profile, true);
            case NORMALIZE, USER_APPROVAL, ATOMIC_CREATE -> throw new IllegalStateException(
                    "Live worker cannot execute stage " + current.stage()
            );
        };
    }

    private InferenceRunSnapshot observe(InferenceRunSnapshot current) {
        return runStore.checkpoint(
                current.runId(), token(current), InferenceStage.OBSERVE, InferenceStage.STRUCTURE,
                workflowCodec.write(LiveWorkflowCheckpoint.observed()), clock.instant()
        );
    }

    private InferenceRunSnapshot invoke(
            InferenceRunSnapshot current,
            InferenceProfile profile,
            boolean repair
    ) {
        var checkpoint = workflowCodec.parse(current.checkpointJson());
        if (!repair && checkpoint.completedStage() != InferenceStage.OBSERVE) {
            throw new IllegalStateException("STRUCTURE requires the OBSERVE checkpoint");
        }
        if (repair && (checkpoint.completedStage() != InferenceStage.CRITIQUE || checkpoint.outputValid())) {
            throw new IllegalStateException("REPAIR requires a rejected CRITIQUE checkpoint");
        }
        var attemptOrdinal = workflowStore.attempts(current.runId()).size();
        if (attemptOrdinal >= profile.maximumTotalCalls()) {
            return runStore.fail(current.runId(), token(current), "LIVE_CALL_BUDGET_EXHAUSTED", clock.instant());
        }
        if (!provider.configured()) throw new ProviderNotConfiguredException("DASHSCOPE_NOT_CONFIGURED");

        var request = request(current, profile, checkpoint, attemptOrdinal);
        var maximumRequestCost = ProviderCostEstimator.maximumRequestCostMicrosCny(request);
        if (maximumRequestCost > profile.maximumEstimatedCostMicrosCny()) {
            throw new ProviderBudgetExceededException("PROVIDER_REQUEST_COST_BOUND_EXCEEDED");
        }
        var reservation = budgetStore.reserve(
                CANARY_BUDGET_KEY, current.runId(), attemptOrdinal,
                maximumRequestCost, clock.instant()
        );
        var started = System.nanoTime();
        final ProviderInferenceResponse response;
        try {
            response = provider.complete(request);
        } catch (ProviderCallException failure) {
            var now = clock.instant();
            var recorded = workflowStore.recordAttempt(
                    current.runId(), token(current),
                    new InferenceAttempt(
                            current.runId(), attemptOrdinal, current.stage(), InferenceAttemptStatus.FAILED,
                            failure.code(), Optional.empty(), Optional.empty(), 0, 0, 0,
                            elapsedMillis(started), now
                    ),
                    now
            );
            if (failure.retryable() && failure.retryAfter().isPresent()) {
                return runStore.fail(
                        current.runId(), token(recorded), "DASHSCOPE_RETRY_AFTER", clock.instant()
                );
            }
            if (failure.retryable() && attemptOrdinal + 1 < profile.maximumTotalCalls()) return recorded;
            return runStore.fail(current.runId(), token(recorded), failure.code(), clock.instant());
        }

        var estimatedCost = ProviderCostEstimator.estimateMicrosCny(profile, response.usage());
        budgetStore.settle(reservation.reservationId(), estimatedCost, clock.instant());
        if (!profile.model().equals(response.model())) {
            var now = clock.instant();
            var failed = workflowStore.recordAttempt(
                    current.runId(), token(current),
                    attempt(
                            current, attemptOrdinal, InferenceAttemptStatus.FAILED,
                            "DASHSCOPE_MODEL_MISMATCH", response, estimatedCost,
                            elapsedMillis(started), now
                    ),
                    now
            );
            return runStore.fail(current.runId(), token(failed), "DASHSCOPE_MODEL_MISMATCH", clock.instant());
        }

        CandidateBundle candidate = null;
        var status = InferenceAttemptStatus.SUCCEEDED;
        var outcomeCode = "LIVE_OUTPUT_ACCEPTED";
        try {
            candidate = candidateCodec.parse(response.candidateJson());
        } catch (InvalidCandidateContractException invalid) {
            status = InferenceAttemptStatus.REJECTED;
            outcomeCode = "LIVE_OUTPUT_REJECTED";
        }
        var repairRounds = checkpoint.repairRounds() + (repair ? 1 : 0);
        var next = checkpoint.callResult(current.stage(), attemptOrdinal + 1, repairRounds, candidate);
        var now = clock.instant();
        return workflowStore.checkpointAttempt(
                current.runId(), token(current), current.stage(), InferenceStage.DETERMINISTIC_VALIDATE,
                workflowCodec.write(next),
                attempt(
                        current, attemptOrdinal, status, outcomeCode, response, estimatedCost,
                        elapsedMillis(started), now
                ),
                now
        );
    }

    private InferenceRunSnapshot deterministicValidate(
            InferenceRunSnapshot current,
            InferenceProfile profile
    ) {
        var checkpoint = workflowCodec.parse(current.checkpointJson());
        if (checkpoint.completedStage() != InferenceStage.STRUCTURE
                && checkpoint.completedStage() != InferenceStage.REPAIR) {
            throw new IllegalStateException("DETERMINISTIC_VALIDATE requires a provider attempt checkpoint");
        }
        final List<CandidateProblem> problems;
        if (!checkpoint.outputValid()) {
            problems = List.of(new CandidateProblem(
                    "LIVE_STRUCTURE_OUTPUT_INVALID", CandidateProblemSeverity.BLOCKER,
                    null, "/candidate", java.util.Map.of(
                            "attemptOrdinal", Integer.toString(checkpoint.structureCalls() - 1)
                    )
            ));
        } else {
            problems = candidateValidator.validate(checkpoint.candidate(), validationContext(current, profile));
        }
        return runStore.checkpoint(
                current.runId(), token(current), InferenceStage.DETERMINISTIC_VALIDATE, InferenceStage.CRITIQUE,
                workflowCodec.write(checkpoint.validated(problems)), clock.instant()
        );
    }

    private InferenceRunSnapshot critique(InferenceRunSnapshot current, InferenceProfile profile) {
        var checkpoint = workflowCodec.parse(current.checkpointJson());
        if (checkpoint.completedStage() != InferenceStage.DETERMINISTIC_VALIDATE) {
            throw new IllegalStateException("CRITIQUE requires deterministic validation");
        }
        if (!checkpoint.outputValid()) {
            if (checkpoint.repairRounds() >= profile.maximumRepairRounds()
                    || checkpoint.structureCalls() >= profile.maximumTotalCalls()) {
                return runStore.fail(
                        current.runId(), token(current), "LIVE_REPAIR_BUDGET_EXHAUSTED", clock.instant()
                );
            }
            return runStore.checkpoint(
                    current.runId(), token(current), InferenceStage.CRITIQUE, InferenceStage.REPAIR,
                    workflowCodec.write(checkpoint.critiqued()), clock.instant()
            );
        }
        return workflowStore.completeForReview(
                current.runId(), token(current), candidateCodec.write(checkpoint.candidate()),
                problemCodec.write(checkpoint.validationProblems()), clock.instant()
        );
    }

    private ProviderInferenceRequest request(
            InferenceRunSnapshot current,
            InferenceProfile profile,
            LiveWorkflowCheckpoint checkpoint,
            int attemptOrdinal
    ) {
        var problemCodes = current.stage() == InferenceStage.REPAIR
                ? checkpoint.validationProblems().stream().map(CandidateProblem::code).distinct().sorted().toList()
                : List.<String>of();
        return new ProviderInferenceRequest(
                current.runId(), attemptOrdinal, current.stage(), profile,
                prompts.require(profile.promptVersion()).text(),
                taskCodec.write(current, current.stage(), jsonProfile(current), problemCodes),
                providerImages(current)
        );
    }

    private CandidateValidationContext validationContext(
            InferenceRunSnapshot current,
            InferenceProfile profile
    ) {
        var jsonProfile = jsonProfile(current);
        return new CandidateValidationContext(
                Set.copyOf(imageArtifactIds(current)),
                jsonProfile == null ? 0 : jsonProfile.sampleCount(),
                profile.lowConfidenceThresholdBps()
        );
    }

    private JsonStructuralProfile jsonProfile(InferenceRunSnapshot current) {
        var jsonInputs = current.inputs().stream()
                .filter(input -> input.kind() == NormalizedArtifact.Kind.JSON_PROFILE)
                .toList();
        if (jsonInputs.isEmpty()) return null;
        if (jsonInputs.size() != 1) throw new IllegalStateException("A run may contain one JSON profile artifact");
        return structuralProfiler.profile(blobStore.read(jsonInputs.getFirst().artifact().locator()));
    }

    private List<ProviderImage> providerImages(InferenceRunSnapshot current) {
        return current.inputs().stream()
                .filter(input -> input.kind() == NormalizedArtifact.Kind.IMAGE)
                .sorted(Comparator.comparingInt(input -> input.ordinal()))
                .map(input -> new ProviderImage(
                        input.artifact().artifactId(), input.artifact().mediaType(),
                        blobStore.read(input.artifact().locator())
                ))
                .toList();
    }

    private static List<String> imageArtifactIds(InferenceRunSnapshot current) {
        return current.inputs().stream()
                .filter(input -> input.kind() == NormalizedArtifact.Kind.IMAGE)
                .sorted(Comparator.comparingInt(input -> input.ordinal()))
                .map(input -> input.artifact().artifactId())
                .toList();
    }

    private InferenceProfile validateRun(InferenceRunSnapshot current) {
        var profile = profiles.parseSnapshot(current.profileSnapshotJson());
        if (!profile.profileId().equals(current.profileId()) || !profile.networkAllowed()
                || !"DASHSCOPE".equals(profile.provider())
                || !"SYNTHETIC_ONLY".equals(profile.inputClassification())
                || !profile.supportedModes().contains(current.mode())) {
            throw new IllegalStateException("Run does not carry an approved synthetic-only live profile");
        }
        return profile;
    }

    private InferenceRunSnapshot failIfOwned(InferenceRunSnapshot initial, String code) {
        var latest = runStore.find(initial.runId()).orElseThrow();
        if (latest.state() == InferenceRunState.RUNNING && sameLease(initial, latest)) {
            return runStore.fail(latest.runId(), token(latest), code, clock.instant());
        }
        return latest;
    }

    private static InferenceAttempt attempt(
            InferenceRunSnapshot current,
            int attemptOrdinal,
            InferenceAttemptStatus status,
            String outcomeCode,
            ProviderInferenceResponse response,
            long estimatedCost,
            long durationMillis,
            java.time.Instant now
    ) {
        return new InferenceAttempt(
                current.runId(), attemptOrdinal, current.stage(), status, outcomeCode,
                Optional.of(response.providerRequestId()), Optional.of(response.model()),
                response.usage().inputTokens(), response.usage().outputTokens(),
                estimatedCost, durationMillis, now
        );
    }

    private static long elapsedMillis(long startedNanos) {
        return Duration.ofNanos(Math.max(0, System.nanoTime() - startedNanos)).toMillis();
    }

    private static InferenceRunSnapshot requireRunningLease(InferenceRunSnapshot current) {
        Objects.requireNonNull(current, "current");
        if (current.state() != InferenceRunState.RUNNING || current.lease().isEmpty()) {
            throw new IllegalArgumentException("Live worker requires a leased RUNNING snapshot");
        }
        return current;
    }

    private static UUID token(InferenceRunSnapshot current) {
        return current.lease().orElseThrow().token();
    }

    private static boolean sameLease(InferenceRunSnapshot expected, InferenceRunSnapshot actual) {
        return expected.lease().isPresent() && actual.lease().isPresent()
                && expected.lease().orElseThrow().token().equals(actual.lease().orElseThrow().token());
    }
}
