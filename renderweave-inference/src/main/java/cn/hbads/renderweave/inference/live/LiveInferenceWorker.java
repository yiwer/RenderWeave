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
import cn.hbads.renderweave.inference.input.InferenceMode;
import cn.hbads.renderweave.inference.input.NormalizedArtifact;
import cn.hbads.renderweave.inference.profile.InferenceProfile;
import cn.hbads.renderweave.inference.profile.InferenceProfileRegistry;
import cn.hbads.renderweave.inference.profile.InferencePromptRegistry;
import cn.hbads.renderweave.inference.profile.JsonCandidateProfiler;
import cn.hbads.renderweave.inference.profile.JsonGroundedCandidateComposer;
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
import cn.hbads.renderweave.inference.replay.InferenceAttemptProblemTaxonomy;
import cn.hbads.renderweave.inference.replay.InferenceAttemptStatus;
import cn.hbads.renderweave.inference.replay.InferenceReplayStore;
import cn.hbads.renderweave.inference.run.InferenceLeaseLostException;
import cn.hbads.renderweave.inference.run.InferenceRunSnapshot;
import cn.hbads.renderweave.inference.run.InferenceRunState;
import cn.hbads.renderweave.inference.run.InferenceRunStore;
import cn.hbads.renderweave.inference.run.InferenceStage;
import cn.hbads.renderweave.inference.vision.DocumentVisionArtifact;
import cn.hbads.renderweave.inference.vision.DocumentVisionException;
import cn.hbads.renderweave.inference.vision.DocumentVisionObservation;
import cn.hbads.renderweave.inference.vision.DocumentVisionPreprocessor;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
    public static final String PRODUCT_BUDGET_KEY = "product-live";
    private static final String GROUNDED_PIPELINE = "renderweave-inference-pipeline/2.0";
    private static final String SERIAL_VISUAL_PIPELINE = "renderweave-inference-pipeline/3.0";
    private static final String LOCAL_MATERIALIZER_PIPELINE = "renderweave-inference-pipeline/4.0";
    private static final String GROUNDED_VISUAL_PIPELINE = "renderweave-inference-pipeline/4.1";
    private static final String HYBRID_VISUAL_PIPELINE = "renderweave-inference-pipeline/4.2";
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
    private final JsonCandidateProfiler jsonCandidateProfiler;
    private final JsonGroundedCandidateComposer groundedComposer;
    private final CandidateValidator candidateValidator;
    private final ImageOnlyCandidateCanonicalizer imageOnlyCanonicalizer;
    private final CandidateJsonCodec candidateCodec;
    private final CandidateProblemJsonCodec problemCodec;
    private final LiveWorkflowJsonCodec workflowCodec;
    private final LiveTaskJsonCodec taskCodec;
    private final VisualAnalysisJsonCodec visualAnalysisCodec;
    private final VisualPlanCandidateValidator visualPlanValidator;
    private final VisualPlanCandidateMaterializer visualPlanMaterializer;
    private final MultiScaleVisualViewPlanner visualViewPlanner;
    private final VisualGroundingJsonCodec visualGroundingCodec;
    private final DocumentVisionPreprocessor documentVisionPreprocessor;

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
                DocumentVisionPreprocessor.unavailable("DOCUMENT_VISION_DISABLED")
        );
    }

    public LiveInferenceWorker(
            InferenceRunStore runStore,
            InferenceReplayStore workflowStore,
            ProviderBudgetStore budgetStore,
            InferenceProvider provider,
            BlobStore blobStore,
            Clock clock,
            Duration leaseDuration,
            DocumentVisionPreprocessor documentVisionPreprocessor
    ) {
        this(
                runStore, workflowStore, budgetStore, provider, blobStore, clock, leaseDuration,
                new InferenceProfileRegistry(), new InferencePromptRegistry(), new JsonStructuralProfiler(),
                new JsonCandidateProfiler(), new JsonGroundedCandidateComposer(),
                new CandidateValidator(), new CandidateJsonCodec(), new CandidateProblemJsonCodec(),
                new LiveWorkflowJsonCodec(), new LiveTaskJsonCodec(), documentVisionPreprocessor
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
            JsonCandidateProfiler jsonCandidateProfiler,
            JsonGroundedCandidateComposer groundedComposer,
            CandidateValidator candidateValidator,
            CandidateJsonCodec candidateCodec,
            CandidateProblemJsonCodec problemCodec,
            LiveWorkflowJsonCodec workflowCodec,
            LiveTaskJsonCodec taskCodec,
            DocumentVisionPreprocessor documentVisionPreprocessor
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
        this.jsonCandidateProfiler = Objects.requireNonNull(jsonCandidateProfiler, "jsonCandidateProfiler");
        this.groundedComposer = Objects.requireNonNull(groundedComposer, "groundedComposer");
        this.candidateValidator = Objects.requireNonNull(candidateValidator, "candidateValidator");
        this.imageOnlyCanonicalizer = new ImageOnlyCandidateCanonicalizer();
        this.candidateCodec = Objects.requireNonNull(candidateCodec, "candidateCodec");
        this.problemCodec = Objects.requireNonNull(problemCodec, "problemCodec");
        this.workflowCodec = Objects.requireNonNull(workflowCodec, "workflowCodec");
        this.taskCodec = Objects.requireNonNull(taskCodec, "taskCodec");
        this.visualAnalysisCodec = new VisualAnalysisJsonCodec();
        this.visualPlanValidator = new VisualPlanCandidateValidator();
        this.visualPlanMaterializer = new VisualPlanCandidateMaterializer();
        this.visualViewPlanner = new MultiScaleVisualViewPlanner();
        this.visualGroundingCodec = new VisualGroundingJsonCodec();
        this.documentVisionPreprocessor = Objects.requireNonNull(
                documentVisionPreprocessor, "documentVisionPreprocessor"
        );
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
        } catch (DocumentVisionException unavailable) {
            return Optional.of(failIfOwned(initial, unavailable.code()));
        } catch (RuntimeException failure) {
            return Optional.of(failIfOwned(initial, "LIVE_WORKFLOW_FAILED"));
        }
    }

    public InferenceRunSnapshot drain(InferenceRunSnapshot claimed) {
        var current = requireRunningLease(claimed);
        if (current.cancellationRequested()) {
            return runStore.acknowledgeCancellation(current.runId(), token(current), clock.instant());
        }
        var profile = validateRun(current);
        var documentVision = hybridVisual(profile) && visualProviderStage(current.stage())
                ? documentVision(current, profile) : null;
        for (var advance = 0; advance < MAX_STAGE_ADVANCES && current.state() == InferenceRunState.RUNNING; advance++) {
            current = advance(current, documentVision);
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
        var documentVision = hybridVisual(profile) && visualProviderStage(current.stage())
                ? documentVision(current, profile) : null;
        return advance(current, documentVision);
    }

    private InferenceRunSnapshot advance(
            InferenceRunSnapshot current,
            DocumentVisionObservation documentVision
    ) {
        current = requireRunningLease(current);
        if (current.cancellationRequested()) {
            return runStore.acknowledgeCancellation(current.runId(), token(current), clock.instant());
        }
        var profile = validateRun(current);
        return switch (current.stage()) {
            case OBSERVE -> observe(current, profile, documentVision);
            case HIERARCHY, ELEMENT_BINDING -> invoke(current, profile, false, documentVision);
            case STRUCTURE -> structure(current, profile, documentVision);
            case DETERMINISTIC_VALIDATE -> deterministicValidate(current, profile);
            case CRITIQUE -> critique(current, profile);
            case REPAIR -> invoke(current, profile, true, documentVision);
            case NORMALIZE, USER_APPROVAL, ATOMIC_CREATE -> throw new IllegalStateException(
                    "Live worker cannot execute stage " + current.stage()
            );
        };
    }

    private InferenceRunSnapshot structure(
            InferenceRunSnapshot current,
            InferenceProfile profile,
            DocumentVisionObservation documentVision
    ) {
        if (localMaterializer(profile)) {
            if (current.mode() != InferenceMode.IMAGE_ONLY) {
                throw new IllegalStateException("Pipeline 4 is restricted to IMAGE_ONLY");
            }
            var checkpoint = workflowCodec.parse(current.checkpointJson());
            if (checkpoint.completedStage() != InferenceStage.ELEMENT_BINDING
                    || checkpoint.elementInventory() == null
                    || checkpoint.hierarchyPlan() == null
                    || checkpoint.bindingPlan() == null) {
                throw new IllegalStateException("Local STRUCTURE requires the complete validated visual plan");
            }
            if (groundedVisual(profile)
                    && (checkpoint.groundingPlan() == null || checkpoint.entityRegionPlan() == null)) {
                throw new IllegalStateException("Pipeline 4.1 requires complete spatial grounding plans");
            }
            final CandidateBundle candidate;
            try {
                candidate = visualPlanMaterializer.materialize(
                        current.runId(), checkpoint.elementInventory(), checkpoint.hierarchyPlan(),
                        checkpoint.bindingPlan(), profile.lowConfidenceThresholdBps()
                );
            } catch (IllegalArgumentException | IllegalStateException invalidPlan) {
                return runStore.fail(
                        current.runId(), token(current),
                        "LIVE_LOCAL_MATERIALIZER_INVALID", clock.instant()
                );
            }
            var next = checkpoint.callResult(
                    InferenceStage.STRUCTURE,
                    checkpoint.providerCalls(),
                    checkpoint.repairRounds(),
                    candidate
            );
            return runStore.checkpoint(
                    current.runId(), token(current), InferenceStage.STRUCTURE,
                    InferenceStage.DETERMINISTIC_VALIDATE,
                    workflowCodec.write(next), clock.instant()
            );
        }
        if (grounded(profile) && current.mode() == InferenceMode.JSON_ONLY) {
            var checkpoint = workflowCodec.parse(current.checkpointJson());
            if (checkpoint.completedStage() != InferenceStage.OBSERVE) {
                throw new IllegalStateException("STRUCTURE requires the OBSERVE checkpoint");
            }
            var grounded = groundedBase(current);
            var next = checkpoint.callResult(
                    InferenceStage.STRUCTURE,
                    checkpoint.providerCalls(),
                    checkpoint.repairRounds(),
                    grounded.candidate(),
                    grounded.semanticProblems()
            );
            return runStore.checkpoint(
                    current.runId(), token(current), InferenceStage.STRUCTURE,
                    InferenceStage.DETERMINISTIC_VALIDATE,
                    workflowCodec.write(next), clock.instant()
            );
        }
        return invoke(current, profile, false, documentVision);
    }

    private InferenceRunSnapshot observe(
            InferenceRunSnapshot current,
            InferenceProfile profile,
            DocumentVisionObservation documentVision
    ) {
        if (serialVisual(current, profile)) return invoke(current, profile, false, documentVision);
        return runStore.checkpoint(
                current.runId(), token(current), InferenceStage.OBSERVE, InferenceStage.STRUCTURE,
                workflowCodec.write(LiveWorkflowCheckpoint.observed()), clock.instant()
        );
    }

    private InferenceRunSnapshot invoke(
            InferenceRunSnapshot current,
            InferenceProfile profile,
            boolean repair,
            DocumentVisionObservation documentVision
    ) {
        var checkpoint = current.stage() == InferenceStage.OBSERVE && serialVisual(current, profile)
                ? LiveWorkflowCheckpoint.started()
                : workflowCodec.parse(current.checkpointJson());
        requireInvocationCheckpoint(current, checkpoint, repair, profile);
        if (localMaterializer(profile)
                && (current.stage() == InferenceStage.STRUCTURE
                || current.stage() == InferenceStage.REPAIR)) {
            throw new IllegalStateException("Pipeline 4 STRUCTURE and REPAIR must not call the Provider");
        }
        if (grounded(profile) && current.mode() == InferenceMode.JSON_ONLY) {
            return runStore.fail(
                    current.runId(), token(current),
                    "LIVE_GROUNDED_JSON_EXTERNAL_CALL_BLOCKED", clock.instant()
            );
        }
        var attemptOrdinal = workflowStore.attempts(current.runId()).size();
        if (attemptOrdinal >= profile.maximumTotalCalls()) {
            return runStore.fail(current.runId(), token(current), "LIVE_CALL_BUDGET_EXHAUSTED", clock.instant());
        }
        if (!provider.configured()) throw new ProviderNotConfiguredException("DASHSCOPE_NOT_CONFIGURED");

        var request = request(current, profile, checkpoint, attemptOrdinal, documentVision);
        var maximumRequestCost = ProviderCostEstimator.maximumRequestCostMicrosCny(request);
        if (maximumRequestCost > profile.maximumEstimatedCostMicrosCny()) {
            throw new ProviderBudgetExceededException("PROVIDER_REQUEST_COST_BOUND_EXCEEDED");
        }
        var invocationNow = clock.instant();
        if (!runStore.renewLease(
                current.runId(), token(current), invocationNow, leaseDuration
        )) {
            throw new InferenceLeaseLostException(current.runId());
        }
        var reservation = budgetStore.reserve(
                budgetKey(profile), current.runId(), attemptOrdinal,
                maximumRequestCost, current.costLimitMicrosCny(), invocationNow
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
                            elapsedMillis(started), Map.of(), now
                    ),
                    now
            );
            return runStore.fail(current.runId(), token(failed), "DASHSCOPE_MODEL_MISMATCH", clock.instant());
        }

        if (serialVisual(current, profile)
                && Set.of(InferenceStage.OBSERVE, InferenceStage.HIERARCHY,
                InferenceStage.ELEMENT_BINDING).contains(current.stage())) {
            return acceptVisualAnalysis(
                    current, profile, checkpoint, attemptOrdinal, response,
                    estimatedCost, elapsedMillis(started)
            );
        }

        CandidateBundle candidate = null;
        List<CandidateProblem> prevalidationProblems = List.of();
        Map<String, Integer> problemCodeCounts = Map.of();
        var status = InferenceAttemptStatus.SUCCEEDED;
        var outcomeCode = "LIVE_OUTPUT_ACCEPTED";
        try {
            candidate = candidateCodec.parse(response.candidateJson());
            if (current.mode() == InferenceMode.IMAGE_ONLY) {
                var canonical = imageOnlyCanonicalizer.canonicalize(
                        candidate, imageArtifactDimensions(current)
                );
                candidate = canonical.candidate();
                var combined = new ArrayList<>(canonical.problems());
                if (serialVisual(current, profile)) {
                    combined.addAll(visualPlanValidator.validate(
                            candidate,
                            Objects.requireNonNull(checkpoint.elementInventory(), "elementInventory"),
                            Objects.requireNonNull(checkpoint.hierarchyPlan(), "hierarchyPlan"),
                            Objects.requireNonNull(checkpoint.bindingPlan(), "bindingPlan")
                    ));
                }
                prevalidationProblems = List.copyOf(combined);
            } else if (grounded(profile) && current.mode() == InferenceMode.COMBINED) {
                var composed = groundedComposer.compose(
                        current.runId(), groundedRootKey(current), "推断数据结构",
                        jsonProfile(current), Set.copyOf(imageArtifactIds(current)),
                        candidate, profile.lowConfidenceThresholdBps()
                );
                candidate = composed.candidate();
                prevalidationProblems = composed.semanticProblems();
            }
        } catch (InvalidCandidateContractException invalid) {
            status = InferenceAttemptStatus.REJECTED;
            outcomeCode = "LIVE_OUTPUT_REJECTED";
            problemCodeCounts = InferenceAttemptProblemTaxonomy.count(
                    List.of(invalid.diagnosticCode())
            );
            prevalidationProblems = List.of(new CandidateProblem(
                    invalid.diagnosticCode(), CandidateProblemSeverity.BLOCKER,
                    null, "/candidate", Map.of()
            ));
        }
        if (candidate != null) {
            problemCodeCounts = InferenceAttemptProblemTaxonomy.count(
                    validateCandidate(current, profile, candidate, prevalidationProblems).stream()
                            .map(CandidateProblem::code)
                            .toList()
            );
        }
        var repairRounds = checkpoint.repairRounds() + (repair ? 1 : 0);
        var next = checkpoint.callResult(
                current.stage(), attemptOrdinal + 1, repairRounds,
                candidate, prevalidationProblems
        );
        var now = clock.instant();
        return workflowStore.checkpointAttempt(
                current.runId(), token(current), current.stage(), InferenceStage.DETERMINISTIC_VALIDATE,
                workflowCodec.write(next),
                attempt(
                        current, attemptOrdinal, status, outcomeCode, response, estimatedCost,
                        elapsedMillis(started), problemCodeCounts, now
                ),
                now
        );
    }

    private void requireInvocationCheckpoint(
            InferenceRunSnapshot current,
            LiveWorkflowCheckpoint checkpoint,
            boolean repair,
            InferenceProfile profile
    ) {
        if (repair) {
            if (current.stage() != InferenceStage.REPAIR
                    || checkpoint.completedStage() != InferenceStage.CRITIQUE
                    || !requiresRepair(checkpoint)) {
                throw new IllegalStateException("REPAIR requires a rejected CRITIQUE checkpoint");
            }
            return;
        }
        switch (current.stage()) {
            case OBSERVE -> {
                if (!serialVisual(current, profile)
                        || checkpoint.completedStage() != InferenceStage.NORMALIZE) {
                    throw new IllegalStateException("OBSERVE provider call requires pipeline 3 IMAGE_ONLY");
                }
            }
            case HIERARCHY -> {
                if (!serialVisual(current, profile)
                        || checkpoint.completedStage() != InferenceStage.OBSERVE
                        || checkpoint.elementInventory() == null
                        || groundedVisual(profile) && checkpoint.groundingPlan() == null) {
                    throw new IllegalStateException("HIERARCHY requires a validated element inventory");
                }
            }
            case ELEMENT_BINDING -> {
                if (!serialVisual(current, profile)
                        || checkpoint.completedStage() != InferenceStage.HIERARCHY
                        || checkpoint.hierarchyPlan() == null
                        || groundedVisual(profile) && checkpoint.entityRegionPlan() == null) {
                    throw new IllegalStateException("ELEMENT_BINDING requires a validated hierarchy");
                }
            }
            case STRUCTURE -> {
                if (localMaterializer(profile)) {
                    throw new IllegalStateException("Pipeline 4 STRUCTURE is a local stage");
                }
                var expected = serialVisual(current, profile)
                        ? InferenceStage.ELEMENT_BINDING : InferenceStage.OBSERVE;
                if (checkpoint.completedStage() != expected
                        || serialVisual(current, profile) && checkpoint.bindingPlan() == null) {
                    throw new IllegalStateException("STRUCTURE requires its complete observation checkpoint");
                }
            }
            default -> throw new IllegalStateException("Unsupported provider stage " + current.stage());
        }
    }

    private InferenceRunSnapshot acceptVisualAnalysis(
            InferenceRunSnapshot current,
            InferenceProfile profile,
            LiveWorkflowCheckpoint checkpoint,
            int attemptOrdinal,
            ProviderInferenceResponse response,
            long estimatedCost,
            long durationMillis
    ) {
        final LiveWorkflowCheckpoint nextCheckpoint;
        final InferenceStage nextStage;
        final String outcomeCode;
        final Map<String, Integer> problemCodeCounts;
        try {
            if (groundedVisual(profile)) {
                return acceptGroundedVisualAnalysis(
                        current, profile, checkpoint, attemptOrdinal, response,
                        estimatedCost, durationMillis
                );
            }
            switch (current.stage()) {
                case OBSERVE -> {
                    var parsedInventory = visualAnalysisCodec.parseElements(
                            response.candidateJson(), Set.copyOf(imageArtifactIds(current))
                    );
                    var canonical = imageOnlyCanonicalizer.canonicalize(
                            parsedInventory, imageArtifactDimensions(current)
                    );
                    nextCheckpoint = checkpoint.elementsObserved(
                            canonical.inventory(), attemptOrdinal + 1
                    );
                    nextStage = InferenceStage.HIERARCHY;
                    outcomeCode = "LIVE_VISUAL_ELEMENTS_ACCEPTED";
                    problemCodeCounts = canonical.normalizedElements() == 0 ? Map.of() : Map.of(
                            "IMAGE_EVIDENCE_PIXEL_COORDINATES_NORMALIZED",
                            canonical.normalizedElements()
                    );
                }
                case HIERARCHY -> {
                    var hierarchy = visualAnalysisCodec.parseHierarchy(
                            response.candidateJson(),
                            Objects.requireNonNull(checkpoint.elementInventory(), "elementInventory")
                    );
                    nextCheckpoint = checkpoint.hierarchyAnalyzed(hierarchy, attemptOrdinal + 1);
                    nextStage = InferenceStage.ELEMENT_BINDING;
                    outcomeCode = "LIVE_VISUAL_HIERARCHY_ACCEPTED";
                    problemCodeCounts = Map.of();
                }
                case ELEMENT_BINDING -> {
                    var bindings = visualAnalysisCodec.parseBindings(
                            response.candidateJson(),
                            Objects.requireNonNull(checkpoint.elementInventory(), "elementInventory"),
                            Objects.requireNonNull(checkpoint.hierarchyPlan(), "hierarchyPlan")
                    );
                    nextCheckpoint = checkpoint.elementsBound(bindings, attemptOrdinal + 1);
                    nextStage = InferenceStage.STRUCTURE;
                    outcomeCode = "LIVE_VISUAL_BINDINGS_ACCEPTED";
                    problemCodeCounts = Map.of();
                }
                default -> throw new IllegalStateException("Not a visual analysis stage");
            }
        } catch (InvalidVisualAnalysisException invalid) {
            var now = clock.instant();
            var counts = InferenceAttemptProblemTaxonomy.count(List.of(invalid.diagnosticCode()));
            var recorded = workflowStore.recordAttempt(
                    current.runId(), token(current),
                    attempt(
                            current, attemptOrdinal, InferenceAttemptStatus.REJECTED,
                            "LIVE_VISUAL_ANALYSIS_REJECTED", response, estimatedCost,
                            durationMillis, counts, now
                    ),
                    now
            );
            if (attemptOrdinal + 1 < profile.maximumTotalCalls()) return recorded;
            return runStore.fail(
                    current.runId(), token(recorded), invalid.diagnosticCode(), clock.instant()
            );
        }
        var now = clock.instant();
        return workflowStore.checkpointAttempt(
                current.runId(), token(current), current.stage(), nextStage,
                workflowCodec.write(nextCheckpoint),
                attempt(
                        current, attemptOrdinal, InferenceAttemptStatus.SUCCEEDED,
                        outcomeCode, response, estimatedCost, durationMillis, problemCodeCounts, now
                ),
                now
        );
    }

    private InferenceRunSnapshot acceptGroundedVisualAnalysis(
            InferenceRunSnapshot current,
            InferenceProfile profile,
            LiveWorkflowCheckpoint checkpoint,
            int attemptOrdinal,
            ProviderInferenceResponse response,
            long estimatedCost,
            long durationMillis
    ) {
        final LiveWorkflowCheckpoint nextCheckpoint;
        final InferenceStage nextStage;
        final String outcomeCode;
        try {
            switch (current.stage()) {
                case OBSERVE -> {
                    var grounded = visualGroundingCodec.parseElements(
                            response.candidateJson(), visualViewPlan(current),
                            imageArtifactIds(current)
                    );
                    nextCheckpoint = checkpoint.elementsGrounded(
                            grounded.inventory(), grounded.grounding(), attemptOrdinal + 1
                    );
                    nextStage = InferenceStage.HIERARCHY;
                    outcomeCode = "LIVE_VISUAL_GROUNDING_ACCEPTED";
                }
                case HIERARCHY -> {
                    var grounded = visualGroundingCodec.parseHierarchy(
                            response.candidateJson(),
                            Objects.requireNonNull(checkpoint.elementInventory(), "elementInventory"),
                            Objects.requireNonNull(checkpoint.groundingPlan(), "groundingPlan")
                    );
                    nextCheckpoint = checkpoint.hierarchyGrounded(
                            grounded.hierarchy(), grounded.entityRegions(), attemptOrdinal + 1
                    );
                    nextStage = InferenceStage.ELEMENT_BINDING;
                    outcomeCode = "LIVE_VISUAL_HIERARCHY_V2_ACCEPTED";
                }
                case ELEMENT_BINDING -> {
                    var bindings = visualGroundingCodec.parseBindings(
                            response.candidateJson(),
                            Objects.requireNonNull(checkpoint.elementInventory(), "elementInventory"),
                            Objects.requireNonNull(checkpoint.hierarchyPlan(), "hierarchyPlan"),
                            Objects.requireNonNull(checkpoint.groundingPlan(), "groundingPlan"),
                            Objects.requireNonNull(checkpoint.entityRegionPlan(), "entityRegionPlan")
                    );
                    nextCheckpoint = checkpoint.elementsBound(bindings, attemptOrdinal + 1);
                    nextStage = InferenceStage.STRUCTURE;
                    outcomeCode = "LIVE_VISUAL_BINDINGS_V2_ACCEPTED";
                }
                default -> throw new IllegalStateException("Not a grounded visual analysis stage");
            }
        } catch (InvalidVisualAnalysisException invalid) {
            var now = clock.instant();
            var counts = InferenceAttemptProblemTaxonomy.count(List.of(invalid.diagnosticCode()));
            var recorded = workflowStore.recordAttempt(
                    current.runId(), token(current),
                    attempt(
                            current, attemptOrdinal, InferenceAttemptStatus.REJECTED,
                            "LIVE_VISUAL_ANALYSIS_REJECTED", response, estimatedCost,
                            durationMillis, counts, now
                    ),
                    now
            );
            if (attemptOrdinal + 1 < profile.maximumTotalCalls()) return recorded;
            return runStore.fail(
                    current.runId(), token(recorded), invalid.diagnosticCode(), clock.instant()
            );
        }
        var now = clock.instant();
        return workflowStore.checkpointAttempt(
                current.runId(), token(current), current.stage(), nextStage,
                workflowCodec.write(nextCheckpoint),
                attempt(
                        current, attemptOrdinal, InferenceAttemptStatus.SUCCEEDED,
                        outcomeCode, response, estimatedCost, durationMillis, Map.of(), now
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
            throw new IllegalStateException("DETERMINISTIC_VALIDATE requires a STRUCTURE or REPAIR checkpoint");
        }
        final List<CandidateProblem> problems;
        if (!checkpoint.outputValid()) {
            problems = checkpoint.validationProblems().isEmpty()
                    ? List.of(new CandidateProblem(
                            "LIVE_STRUCTURE_OUTPUT_INVALID", CandidateProblemSeverity.BLOCKER,
                            null, "/candidate", java.util.Map.of(
                                    "attemptOrdinal", Integer.toString(checkpoint.providerCalls() - 1)
                            )
                    ))
                    : checkpoint.validationProblems();
        } else {
            problems = validateCandidate(
                    current, profile, checkpoint.candidate(), checkpoint.validationProblems()
            );
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
        var repairDecision = checkpoint.outputValid()
                ? LiveRepairPolicy.decide(checkpoint.validationProblems())
                : LiveRepairPolicy.Decision.REPAIR;
        if (repairDecision == LiveRepairPolicy.Decision.REJECT) {
            return runStore.fail(
                    current.runId(), token(current), "LIVE_UNSAFE_BLOCKER_SET", clock.instant()
            );
        }
        if (repairDecision == LiveRepairPolicy.Decision.REPAIR) {
            if (localMaterializer(profile)) {
                return runStore.fail(
                        current.runId(), token(current),
                        "LIVE_LOCAL_MATERIALIZER_INVALID", clock.instant()
                );
            }
            if (grounded(profile) && current.mode() == InferenceMode.JSON_ONLY) {
                return runStore.fail(
                        current.runId(), token(current),
                        "LIVE_GROUNDED_JSON_EXTERNAL_CALL_BLOCKED", clock.instant()
                );
            }
            if (checkpoint.repairRounds() >= profile.maximumRepairRounds()
                    || checkpoint.providerCalls() >= profile.maximumTotalCalls()) {
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

    private static boolean requiresRepair(LiveWorkflowCheckpoint checkpoint) {
        return !checkpoint.outputValid()
                || LiveRepairPolicy.decide(checkpoint.validationProblems())
                == LiveRepairPolicy.Decision.REPAIR;
    }

    private ProviderInferenceRequest request(
            InferenceRunSnapshot current,
            InferenceProfile profile,
            LiveWorkflowCheckpoint checkpoint,
            int attemptOrdinal,
            DocumentVisionObservation documentVision
    ) {
        var problemCodes = current.stage() == InferenceStage.REPAIR
                ? checkpoint.validationProblems().stream().map(CandidateProblem::code).distinct().sorted().toList()
                : List.<String>of();
        if (serialVisual(current, profile)) {
            var retryProblemCodes = workflowStore.attempts(current.runId()).stream()
                    .filter(attempt -> attempt.stage() == current.stage()
                            && attempt.status() == InferenceAttemptStatus.REJECTED)
                    .reduce((left, right) -> right)
                    .map(attempt -> attempt.problemCodeCounts().keySet().stream().sorted().toList())
                    .orElse(List.of());
            if (groundedVisual(profile)) {
                var views = visualViewPlan(current);
                if (hybridVisual(profile)) {
                    if (documentVision == null) {
                        throw new DocumentVisionException("DOCUMENT_VISION_OBSERVATION_MISSING");
                    }
                    return new ProviderInferenceRequest(
                            current.runId(), attemptOrdinal, current.stage(), profile,
                            prompts.requireHybridVisualStage(
                                    promptVersion(profile, current.stage()),
                                    Objects.requireNonNull(
                                            profile.visualHintPackVersion(), "visualHintPackVersion"
                                    ),
                                    Objects.requireNonNull(
                                            profile.documentVisionPromptVersion(),
                                            "documentVisionPromptVersion"
                                    )
                            ).text(),
                            taskCodec.writeV5(
                                    current, current.stage(), views, profile.visualHintPackVersion(),
                                    documentVision, checkpoint.elementInventory(), checkpoint.groundingPlan(),
                                    checkpoint.hierarchyPlan(), checkpoint.entityRegionPlan(),
                                    checkpoint.bindingPlan(), problemCodes, retryProblemCodes
                            ),
                            views.providerImages()
                    );
                }
                return new ProviderInferenceRequest(
                        current.runId(), attemptOrdinal, current.stage(), profile,
                        prompts.requireVisualStage(
                                promptVersion(profile, current.stage()),
                                Objects.requireNonNull(
                                        profile.visualHintPackVersion(), "visualHintPackVersion"
                                )
                        ).text(),
                        taskCodec.writeV4(
                                current, current.stage(), views, profile.visualHintPackVersion(),
                                checkpoint.elementInventory(), checkpoint.groundingPlan(),
                                checkpoint.hierarchyPlan(), checkpoint.entityRegionPlan(),
                                checkpoint.bindingPlan(), problemCodes, retryProblemCodes
                        ),
                        views.providerImages()
                );
            }
            return new ProviderInferenceRequest(
                    current.runId(), attemptOrdinal, current.stage(), profile,
                    prompts.require(promptVersion(profile, current.stage())).text(),
                    taskCodec.writeV3(
                            current, current.stage(), checkpoint.elementInventory(),
                            checkpoint.hierarchyPlan(), checkpoint.bindingPlan(),
                            problemCodes, retryProblemCodes
                    ),
                    providerImages(current)
            );
        }
        var jsonProfile = jsonProfile(current);
        var groundedCandidate = grounded(profile) && jsonProfile != null
                ? groundedBase(current).candidate()
                : null;
        var taskJson = grounded(profile)
                ? taskCodec.writeV2(
                        current, current.stage(), jsonProfile, groundedCandidate, problemCodes
                )
                : taskCodec.writeV1(current, current.stage(), jsonProfile, problemCodes);
        return new ProviderInferenceRequest(
                current.runId(), attemptOrdinal, current.stage(), profile,
                prompts.require(profile.promptVersion()).text(),
                taskJson,
                providerImages(current)
        );
    }

    private cn.hbads.renderweave.inference.profile.CandidateProfileResult groundedBase(
            InferenceRunSnapshot current
    ) {
        return jsonCandidateProfiler.inferLive(
                current.runId(), groundedRootKey(current), "推断数据结构", jsonProfile(current)
        );
    }

    private static String groundedRootKey(InferenceRunSnapshot current) {
        return "inferred-" + current.inputFingerprint().substring(0, 16);
    }

    private static boolean grounded(InferenceProfile profile) {
        return GROUNDED_PIPELINE.equals(profile.pipelineVersion())
                || SERIAL_VISUAL_PIPELINE.equals(profile.pipelineVersion())
                || LOCAL_MATERIALIZER_PIPELINE.equals(profile.pipelineVersion())
                || GROUNDED_VISUAL_PIPELINE.equals(profile.pipelineVersion())
                || HYBRID_VISUAL_PIPELINE.equals(profile.pipelineVersion());
    }

    private static boolean serialVisual(InferenceRunSnapshot current, InferenceProfile profile) {
        return (SERIAL_VISUAL_PIPELINE.equals(profile.pipelineVersion())
                || LOCAL_MATERIALIZER_PIPELINE.equals(profile.pipelineVersion())
                || GROUNDED_VISUAL_PIPELINE.equals(profile.pipelineVersion())
                || HYBRID_VISUAL_PIPELINE.equals(profile.pipelineVersion()))
                && current.mode() == InferenceMode.IMAGE_ONLY;
    }

    private static boolean localMaterializer(InferenceProfile profile) {
        return LOCAL_MATERIALIZER_PIPELINE.equals(profile.pipelineVersion())
                || GROUNDED_VISUAL_PIPELINE.equals(profile.pipelineVersion())
                || HYBRID_VISUAL_PIPELINE.equals(profile.pipelineVersion());
    }

    private static boolean groundedVisual(InferenceProfile profile) {
        return GROUNDED_VISUAL_PIPELINE.equals(profile.pipelineVersion())
                || HYBRID_VISUAL_PIPELINE.equals(profile.pipelineVersion());
    }

    private static boolean hybridVisual(InferenceProfile profile) {
        return HYBRID_VISUAL_PIPELINE.equals(profile.pipelineVersion());
    }

    private static boolean visualProviderStage(InferenceStage stage) {
        return stage == InferenceStage.OBSERVE || stage == InferenceStage.HIERARCHY
                || stage == InferenceStage.ELEMENT_BINDING;
    }

    private static String promptVersion(InferenceProfile profile, InferenceStage stage) {
        return switch (stage) {
            case OBSERVE -> Objects.requireNonNull(profile.elementPromptVersion(), "elementPromptVersion");
            case HIERARCHY -> Objects.requireNonNull(profile.hierarchyPromptVersion(), "hierarchyPromptVersion");
            case ELEMENT_BINDING -> Objects.requireNonNull(profile.bindingPromptVersion(), "bindingPromptVersion");
            case STRUCTURE, REPAIR -> {
                if (localMaterializer(profile)) {
                    throw new IllegalArgumentException("Pipeline 4 local stages have no Provider prompt");
                }
                yield profile.promptVersion();
            }
            case NORMALIZE, DETERMINISTIC_VALIDATE, CRITIQUE, USER_APPROVAL, ATOMIC_CREATE ->
                    throw new IllegalArgumentException("Stage does not call the provider");
        };
    }

    private CandidateValidationContext validationContext(
            InferenceRunSnapshot current,
            InferenceProfile profile
    ) {
        var jsonProfile = jsonProfile(current);
        return CandidateValidationContext.liveProviderOutput(
                Set.copyOf(imageArtifactIds(current)),
                jsonProfile,
                profile.lowConfidenceThresholdBps()
        );
    }

    private List<CandidateProblem> validateCandidate(
            InferenceRunSnapshot current,
            InferenceProfile profile,
            CandidateBundle candidate,
            List<CandidateProblem> prevalidationProblems
    ) {
        var combined = new ArrayList<>(prevalidationProblems);
        combined.addAll(candidateValidator.validate(candidate, validationContext(current, profile)));
        return List.copyOf(combined);
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
                        blobStore.read(input.artifact().locator()),
                        input.artifact().width(), input.artifact().height()
                ))
                .toList();
    }

    private VisualViewPlan visualViewPlan(InferenceRunSnapshot current) {
        var sources = current.inputs().stream()
                .filter(input -> input.kind() == NormalizedArtifact.Kind.IMAGE)
                .sorted(Comparator.comparingInt(input -> input.ordinal()))
                .map(input -> new VisualSourceImage(
                        input.artifact().artifactId(), blobStore.read(input.artifact().locator()),
                        Objects.requireNonNull(input.artifact().width(), "image width"),
                        Objects.requireNonNull(input.artifact().height(), "image height")
                ))
                .toList();
        return visualViewPlanner.plan(sources, List.of());
    }

    private DocumentVisionObservation documentVision(
            InferenceRunSnapshot current,
            InferenceProfile profile
    ) {
        var capability = documentVisionPreprocessor.capability();
        if (!capability.available()) throw new DocumentVisionException(capability.diagnosticCode());
        if (!Objects.equals(profile.documentVisionCapabilityId(), capability.capabilityId())) {
            throw new DocumentVisionException("DOCUMENT_VISION_CAPABILITY_MISMATCH");
        }
        var artifacts = current.inputs().stream()
                .filter(input -> input.kind() == NormalizedArtifact.Kind.IMAGE)
                .sorted(Comparator.comparingInt(input -> input.ordinal()))
                .map(input -> new DocumentVisionArtifact(
                        input.artifact().artifactId(), input.ordinal(), input.artifact().mediaType(),
                        blobStore.read(input.artifact().locator()),
                        Objects.requireNonNull(input.artifact().width(), "image width"),
                        Objects.requireNonNull(input.artifact().height(), "image height")
                )).toList();
        var observation = documentVisionPreprocessor.preprocess(artifacts);
        if (!capability.capabilityId().equals(observation.capabilityId())) {
            throw new DocumentVisionException("DOCUMENT_VISION_CAPABILITY_MISMATCH");
        }
        return observation;
    }

    private static List<String> imageArtifactIds(InferenceRunSnapshot current) {
        return current.inputs().stream()
                .filter(input -> input.kind() == NormalizedArtifact.Kind.IMAGE)
                .sorted(Comparator.comparingInt(input -> input.ordinal()))
                .map(input -> input.artifact().artifactId())
                .toList();
    }

    private static Map<String, ImageOnlyCandidateCanonicalizer.ImageDimensions> imageArtifactDimensions(
            InferenceRunSnapshot current
    ) {
        var result = new java.util.HashMap<String, ImageOnlyCandidateCanonicalizer.ImageDimensions>();
        current.inputs().stream()
                .filter(input -> input.kind() == NormalizedArtifact.Kind.IMAGE
                        && input.artifact().width() != null
                        && input.artifact().height() != null)
                .forEach(input -> result.put(
                        input.artifact().artifactId(),
                        new ImageOnlyCandidateCanonicalizer.ImageDimensions(
                                input.artifact().width(), input.artifact().height()
                        )
                ));
        return Map.copyOf(result);
    }

    private InferenceProfile validateRun(InferenceRunSnapshot current) {
        var profile = profiles.parseSnapshot(current.profileSnapshotJson());
        if (!profile.profileId().equals(current.profileId()) || !profile.networkAllowed()
                || !"DASHSCOPE".equals(profile.provider())
                || !("SYNTHETIC_ONLY".equals(profile.inputClassification())
                || "USER_CONFIRMED".equals(profile.inputClassification()))
                || !profile.supportedModes().contains(current.mode())) {
            throw new IllegalStateException("Run does not carry an approved live profile");
        }
        return profile;
    }

    private static String budgetKey(InferenceProfile profile) {
        return "USER_CONFIRMED".equals(profile.inputClassification())
                ? PRODUCT_BUDGET_KEY
                : CANARY_BUDGET_KEY;
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
            Map<String, Integer> problemCodeCounts,
            java.time.Instant now
    ) {
        return new InferenceAttempt(
                current.runId(), attemptOrdinal, current.stage(), status, outcomeCode,
                Optional.of(response.providerRequestId()), Optional.of(response.model()),
                response.usage().inputTokens(), response.usage().outputTokens(),
                estimatedCost, durationMillis, problemCodeCounts, now
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
