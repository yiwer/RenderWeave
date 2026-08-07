package cn.hbads.renderweave.inference.replay;

import cn.hbads.renderweave.inference.candidate.CandidateJsonCodec;
import cn.hbads.renderweave.inference.candidate.CandidateProblem;
import cn.hbads.renderweave.inference.candidate.CandidateProblemSeverity;
import cn.hbads.renderweave.inference.candidate.CandidateValidationContext;
import cn.hbads.renderweave.inference.candidate.CandidateValidator;
import cn.hbads.renderweave.inference.input.BlobStore;
import cn.hbads.renderweave.inference.input.NormalizedArtifact;
import cn.hbads.renderweave.inference.profile.CandidateProfileResult;
import cn.hbads.renderweave.inference.profile.InferenceProfileRegistry;
import cn.hbads.renderweave.inference.profile.JsonStructuralProfile;
import cn.hbads.renderweave.inference.profile.JsonStructuralProfiler;
import cn.hbads.renderweave.inference.run.InferenceLeaseLostException;
import cn.hbads.renderweave.inference.run.InferenceRunSnapshot;
import cn.hbads.renderweave.inference.run.InferenceRunState;
import cn.hbads.renderweave.inference.run.InferenceRunStore;
import cn.hbads.renderweave.inference.run.InferenceStage;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Executes one bounded, deterministic replay run. This class has no transport/provider dependency and cannot
 * perform network calls or mutate the Schema repository.
 */
public final class ReplayInferenceWorker {
    private static final int MAX_STAGE_ADVANCES = 16;

    private final InferenceRunStore runStore;
    private final InferenceReplayStore replayStore;
    private final BlobStore blobStore;
    private final Clock clock;
    private final Duration leaseDuration;
    private final InferenceProfileRegistry profiles;
    private final ReplayCorpus corpus;
    private final JsonStructuralProfiler structuralProfiler;
    private final CandidateValidator candidateValidator;
    private final CandidateJsonCodec candidateCodec;
    private final ReplayWorkflowJsonCodec workflowCodec;

    public ReplayInferenceWorker(
            InferenceRunStore runStore,
            InferenceReplayStore replayStore,
            BlobStore blobStore,
            Clock clock,
            Duration leaseDuration
    ) {
        this(
                runStore, replayStore, blobStore, clock, leaseDuration,
                new InferenceProfileRegistry(), new ReplayCorpus(), new JsonStructuralProfiler(),
                new CandidateValidator(), new CandidateJsonCodec(), new ReplayWorkflowJsonCodec()
        );
    }

    ReplayInferenceWorker(
            InferenceRunStore runStore,
            InferenceReplayStore replayStore,
            BlobStore blobStore,
            Clock clock,
            Duration leaseDuration,
            InferenceProfileRegistry profiles,
            ReplayCorpus corpus,
            JsonStructuralProfiler structuralProfiler,
            CandidateValidator candidateValidator,
            CandidateJsonCodec candidateCodec,
            ReplayWorkflowJsonCodec workflowCodec
    ) {
        this.runStore = Objects.requireNonNull(runStore, "runStore");
        this.replayStore = Objects.requireNonNull(replayStore, "replayStore");
        this.blobStore = Objects.requireNonNull(blobStore, "blobStore");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.corpus = Objects.requireNonNull(corpus, "corpus");
        this.structuralProfiler = Objects.requireNonNull(structuralProfiler, "structuralProfiler");
        this.candidateValidator = Objects.requireNonNull(candidateValidator, "candidateValidator");
        this.candidateCodec = Objects.requireNonNull(candidateCodec, "candidateCodec");
        this.workflowCodec = Objects.requireNonNull(workflowCodec, "workflowCodec");
        if (leaseDuration.isNegative() || leaseDuration.isZero()
                || leaseDuration.compareTo(Duration.ofMinutes(15)) > 0) {
            throw new IllegalArgumentException("leaseDuration must be positive and no longer than 15 minutes");
        }
    }

    public Optional<InferenceRunSnapshot> processNext(String workerId) {
        var claimed = runStore.claimNext(workerId, clock.instant(), leaseDuration);
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
        } catch (RuntimeException failure) {
            var latest = runStore.find(initial.runId()).orElseThrow();
            if (latest.state() == InferenceRunState.RUNNING && sameLease(initial, latest)) {
                return Optional.of(runStore.fail(
                        latest.runId(), latest.lease().orElseThrow().token(),
                        "REPLAY_WORKFLOW_FAILED", clock.instant()
                ));
            }
            throw failure;
        }
    }

    public InferenceRunSnapshot drain(InferenceRunSnapshot claimed) {
        var current = requireRunningLease(claimed);
        for (var advance = 0; advance < MAX_STAGE_ADVANCES && current.state() == InferenceRunState.RUNNING; advance++) {
            current = advance(current);
        }
        if (current.state() == InferenceRunState.RUNNING) {
            throw new IllegalStateException("Replay stage budget was exhausted");
        }
        return current;
    }

    /** Advances exactly one stage so recovery tests can stop at a durable checkpoint. */
    public InferenceRunSnapshot advance(InferenceRunSnapshot current) {
        current = requireRunningLease(current);
        if (current.cancellationRequested()) {
            return runStore.acknowledgeCancellation(
                    current.runId(), current.lease().orElseThrow().token(), clock.instant()
            );
        }
        var fixture = validateRun(current);
        return switch (current.stage()) {
            case OBSERVE -> observe(current);
            case STRUCTURE -> structure(current, fixture);
            case DETERMINISTIC_VALIDATE -> deterministicValidate(current, fixture);
            case CRITIQUE -> critique(current);
            case REPAIR -> repair(current, fixture);
            case NORMALIZE, USER_APPROVAL, ATOMIC_CREATE -> throw new IllegalStateException(
                    "Replay worker cannot execute stage " + current.stage()
            );
        };
    }

    private InferenceRunSnapshot observe(InferenceRunSnapshot current) {
        return runStore.checkpoint(
                current.runId(), token(current), InferenceStage.OBSERVE, InferenceStage.STRUCTURE,
                workflowCodec.writeCheckpoint(ReplayWorkflowCheckpoint.observed()), clock.instant()
        );
    }

    private InferenceRunSnapshot structure(InferenceRunSnapshot current, ReplayCase fixture) {
        var checkpoint = workflowCodec.parseCheckpoint(current.checkpointJson());
        if (checkpoint.completedStage() != InferenceStage.OBSERVE || checkpoint.structureCalls() != 0) {
            throw new IllegalStateException("STRUCTURE requires the OBSERVE checkpoint");
        }
        return invokeStructure(current, fixture, checkpoint, false);
    }

    private InferenceRunSnapshot repair(InferenceRunSnapshot current, ReplayCase fixture) {
        var checkpoint = workflowCodec.parseCheckpoint(current.checkpointJson());
        if (checkpoint.completedStage() != InferenceStage.CRITIQUE || checkpoint.outputValid()) {
            throw new IllegalStateException("REPAIR requires a rejected CRITIQUE checkpoint");
        }
        var profile = profiles.require(current.profileId()).profile();
        if (checkpoint.repairRounds() >= profile.maximumRepairRounds()) {
            return runStore.fail(current.runId(), token(current), "REPLAY_REPAIR_BUDGET_EXHAUSTED", clock.instant());
        }
        return invokeStructure(current, fixture, checkpoint, true);
    }

    private InferenceRunSnapshot invokeStructure(
            InferenceRunSnapshot current,
            ReplayCase fixture,
            ReplayWorkflowCheckpoint checkpoint,
            boolean repair
    ) {
        var attemptOrdinal = checkpoint.structureCalls();
        var repairRounds = checkpoint.repairRounds() + (repair ? 1 : 0);
        var accepted = attemptOrdinal >= fixture.structureFailuresBeforeSuccess();
        CandidateProfileResult result = accepted ? infer(current, fixture) : null;
        if (result != null) candidateCodec.parse(candidateCodec.write(result.candidate()));
        var nextCheckpoint = checkpoint.structureResult(
                current.stage(), attemptOrdinal + 1, repairRounds,
                result == null ? null : result.candidate(),
                result == null ? List.of() : result.semanticProblems()
        );
        var now = clock.instant();
        var attempt = new InferenceAttempt(
                current.runId(), attemptOrdinal, current.stage(),
                accepted ? InferenceAttemptStatus.SUCCEEDED : InferenceAttemptStatus.REJECTED,
                accepted ? "REPLAY_OUTPUT_ACCEPTED" : "REPLAY_OUTPUT_REJECTED",
                now
        );
        return replayStore.checkpointAttempt(
                current.runId(), token(current), current.stage(), InferenceStage.DETERMINISTIC_VALIDATE,
                workflowCodec.writeCheckpoint(nextCheckpoint), attempt, now
        );
    }

    private InferenceRunSnapshot deterministicValidate(
            InferenceRunSnapshot current,
            ReplayCase fixture
    ) {
        var checkpoint = workflowCodec.parseCheckpoint(current.checkpointJson());
        if (checkpoint.completedStage() != InferenceStage.STRUCTURE
                && checkpoint.completedStage() != InferenceStage.REPAIR) {
            throw new IllegalStateException("DETERMINISTIC_VALIDATE requires a structure attempt checkpoint");
        }
        List<CandidateProblem> problems;
        if (!checkpoint.outputValid()) {
            problems = List.of(new CandidateProblem(
                    "REPLAY_STRUCTURE_OUTPUT_INVALID", CandidateProblemSeverity.BLOCKER,
                    null, "/candidate", java.util.Map.of(
                            "attemptOrdinal", Integer.toString(checkpoint.structureCalls() - 1)
                    )
            ));
        } else {
            problems = candidateValidator.validate(
                    checkpoint.candidate(),
                    new CandidateValidationContext(
                            Set.copyOf(imageArtifactIds(current)),
                            fixture.jsonSamples().size(),
                            profiles.require(current.profileId()).profile().lowConfidenceThresholdBps()
                    )
            );
        }
        return runStore.checkpoint(
                current.runId(), token(current), InferenceStage.DETERMINISTIC_VALIDATE, InferenceStage.CRITIQUE,
                workflowCodec.writeCheckpoint(checkpoint.validated(problems)), clock.instant()
        );
    }

    private InferenceRunSnapshot critique(InferenceRunSnapshot current) {
        var checkpoint = workflowCodec.parseCheckpoint(current.checkpointJson());
        if (checkpoint.completedStage() != InferenceStage.DETERMINISTIC_VALIDATE) {
            throw new IllegalStateException("CRITIQUE requires a deterministic validation checkpoint");
        }
        if (!checkpoint.outputValid()) {
            var profile = profiles.require(current.profileId()).profile();
            if (checkpoint.repairRounds() >= profile.maximumRepairRounds()) {
                return runStore.fail(
                        current.runId(), token(current), "REPLAY_REPAIR_BUDGET_EXHAUSTED", clock.instant()
                );
            }
            return runStore.checkpoint(
                    current.runId(), token(current), InferenceStage.CRITIQUE, InferenceStage.REPAIR,
                    workflowCodec.writeCheckpoint(checkpoint.critiqued()), clock.instant()
            );
        }

        var problems = new ArrayList<>(checkpoint.semanticProblems());
        problems.addAll(checkpoint.validationProblems());
        return replayStore.completeForReview(
                current.runId(), token(current), candidateCodec.write(checkpoint.candidate()),
                workflowCodec.writeProblems(problems), clock.instant()
        );
    }

    private CandidateProfileResult infer(InferenceRunSnapshot current, ReplayCase fixture) {
        var profile = profiles.require(current.profileId()).profile();
        var profiler = new ReplayCandidateProfiler(profile.lowConfidenceThresholdBps());
        return profiler.infer(
                current.runId(), fixture, imageArtifactIds(current), jsonProfile(current)
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

    private static List<String> imageArtifactIds(InferenceRunSnapshot current) {
        return current.inputs().stream()
                .filter(input -> input.kind() == NormalizedArtifact.Kind.IMAGE)
                .sorted(Comparator.comparingInt(input -> input.ordinal()))
                .map(input -> input.artifact().artifactId())
                .toList();
    }

    private ReplayCase validateRun(InferenceRunSnapshot current) {
        var profile = profiles.require(current.profileId()).profile();
        if (profile.networkAllowed() || !"REPLAY".equals(profile.provider())
                || !profile.supportedModes().contains(current.mode())) {
            throw new IllegalStateException("Run does not use an allowed zero-network replay profile");
        }
        var fixture = corpus.require(current.replayFixtureId());
        if (fixture.mode() != current.mode()) {
            throw new IllegalStateException("Replay fixture mode does not match the run");
        }
        var imageCount = imageArtifactIds(current).size();
        var jsonCount = current.inputs().stream()
                .filter(input -> input.kind() == NormalizedArtifact.Kind.JSON_PROFILE).count();
        if (imageCount != fixture.imageCount()
                || jsonCount != (fixture.jsonSamples().isEmpty() ? 0 : 1)) {
            throw new IllegalStateException("Replay fixture inputs do not match the durable run");
        }
        return fixture;
    }

    private static InferenceRunSnapshot requireRunningLease(InferenceRunSnapshot current) {
        Objects.requireNonNull(current, "current");
        if (current.state() != InferenceRunState.RUNNING || current.lease().isEmpty()) {
            throw new IllegalArgumentException("Replay worker requires a leased RUNNING snapshot");
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
