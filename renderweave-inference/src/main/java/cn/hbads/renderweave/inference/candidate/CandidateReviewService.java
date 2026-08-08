package cn.hbads.renderweave.inference.candidate;

import cn.hbads.renderweave.inference.input.NormalizedArtifact;
import cn.hbads.renderweave.inference.input.BlobStore;
import cn.hbads.renderweave.inference.profile.InferenceProfileRegistry;
import cn.hbads.renderweave.inference.profile.JsonStructuralProfiler;
import cn.hbads.renderweave.inference.replay.InferenceReplayStore;
import cn.hbads.renderweave.inference.run.InferenceRunState;
import cn.hbads.renderweave.inference.run.InferenceRunStore;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class CandidateReviewService {
    private final InferenceRunStore runStore;
    private final InferenceReplayStore replayStore;
    private final Clock clock;
    private final CandidateJsonCodec candidateCodec;
    private final CandidateProblemJsonCodec problemCodec;
    private final CandidateValidator validator;
    private final InferenceProfileRegistry profiles;
    private final BlobStore blobStore;
    private final JsonStructuralProfiler structuralProfiler;

    public CandidateReviewService(
            InferenceRunStore runStore,
            InferenceReplayStore replayStore,
            Clock clock,
            BlobStore blobStore
    ) {
        this(
                runStore, replayStore, clock, new CandidateJsonCodec(), new CandidateProblemJsonCodec(),
                new CandidateValidator(), new InferenceProfileRegistry(), blobStore, new JsonStructuralProfiler()
        );
    }

    CandidateReviewService(
            InferenceRunStore runStore,
            InferenceReplayStore replayStore,
            Clock clock,
            CandidateJsonCodec candidateCodec,
            CandidateProblemJsonCodec problemCodec,
            CandidateValidator validator,
            InferenceProfileRegistry profiles,
            BlobStore blobStore,
            JsonStructuralProfiler structuralProfiler
    ) {
        this.runStore = Objects.requireNonNull(runStore, "runStore");
        this.replayStore = Objects.requireNonNull(replayStore, "replayStore");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.candidateCodec = Objects.requireNonNull(candidateCodec, "candidateCodec");
        this.problemCodec = Objects.requireNonNull(problemCodec, "problemCodec");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.blobStore = Objects.requireNonNull(blobStore, "blobStore");
        this.structuralProfiler = Objects.requireNonNull(structuralProfiler, "structuralProfiler");
    }

    public CandidateReviewSnapshot get(UUID runId) {
        var run = runStore.find(runId).orElseThrow(() -> new InferenceCandidateNotFoundException(runId));
        var stored = replayStore.findCandidate(runId)
                .orElseThrow(() -> new InferenceCandidateNotFoundException(runId));
        return snapshot(run, stored);
    }

    public CandidateReviewSnapshot save(
            UUID runId,
            long expectedCandidateRevision,
            CandidateBundle proposed
    ) {
        if (expectedCandidateRevision < 0) {
            throw new IllegalArgumentException("expectedCandidateRevision must not be negative");
        }
        Objects.requireNonNull(proposed, "proposed");
        var currentReview = get(runId);
        if (currentReview.candidateRevision() != expectedCandidateRevision) {
            throw new InferenceCandidateRevisionConflictException(
                    runId, expectedCandidateRevision, currentReview.candidateRevision()
            );
        }
        if (currentReview.run().state() != InferenceRunState.REVIEW_REQUIRED) {
            throw new InvalidCandidateEditException(
                    "CANDIDATE_NOT_REVIEWABLE", "Candidate can only be edited while the run requires review"
            );
        }
        enforceEditPolicy(currentReview.original(), currentReview.current(), proposed);
        candidateCodec.parse(candidateCodec.write(proposed));
        var problems = new java.util.ArrayList<>(currentReview.problems().stream()
                .filter(problem -> problem.severity() == CandidateProblemSeverity.WARNING)
                .toList());
        problems.addAll(validator.validate(proposed, validationContext(currentReview.run())));
        var saved = replayStore.saveCandidate(
                runId, expectedCandidateRevision, candidateCodec.write(proposed),
                problemCodec.write(problems), clock.instant()
        );
        return snapshot(runStore.find(runId).orElseThrow(), saved);
    }

    private CandidateReviewSnapshot snapshot(
            cn.hbads.renderweave.inference.run.InferenceRunSnapshot run,
            InferenceCandidateSnapshot stored
    ) {
        return new CandidateReviewSnapshot(
                run,
                stored.revision(),
                candidateCodec.parse(stored.originalJson()),
                candidateCodec.parse(stored.currentJson()),
                problemCodec.parse(stored.validationProblemsJson()),
                stored.finalJson().map(candidateCodec::parse),
                stored.appliedAt()
        );
    }

    /** Re-runs deterministic Candidate validation immediately before materialization. */
    public List<CandidateProblem> validateForApply(CandidateReviewSnapshot review) {
        Objects.requireNonNull(review, "review");
        return validator.validate(review.current(), validationContext(review.run()));
    }

    private CandidateValidationContext validationContext(
            cn.hbads.renderweave.inference.run.InferenceRunSnapshot run
    ) {
        var imageIds = run.inputs().stream()
                .filter(input -> input.kind() == NormalizedArtifact.Kind.IMAGE)
                .map(input -> input.artifact().artifactId())
                .collect(java.util.stream.Collectors.toSet());
        var jsonInputs = run.inputs().stream()
                .filter(input -> input.kind() == NormalizedArtifact.Kind.JSON_PROFILE)
                .toList();
        if (jsonInputs.size() > 1) throw new IllegalStateException("A run may contain one JSON profile artifact");
        var sampleCount = jsonInputs.isEmpty() ? 0 : structuralProfiler.profile(
                blobStore.read(jsonInputs.getFirst().artifact().locator())
        ).sampleCount();
        return new CandidateValidationContext(
                imageIds,
                sampleCount,
                profiles.parseSnapshot(run.profileSnapshotJson()).lowConfidenceThresholdBps()
        );
    }

    private static void enforceEditPolicy(
            CandidateBundle original,
            CandidateBundle current,
            CandidateBundle proposed
    ) {
        if (!original.rootCandidateSchemaId().equals(proposed.rootCandidateSchemaId())) {
            invalid("CANDIDATE_ROOT_ID_IMMUTABLE", "The original root candidate identity cannot change");
        }
        var originalItems = items(original);
        var currentItems = items(current);
        var proposedItems = items(proposed);

        var resolutionChanges = 0;
        for (var currentItem : currentItems.values()) {
            var proposedItem = proposedItems.get(currentItem.id());
            if (proposedItem == null) {
                if (currentItem.source() == CandidateSource.AI) {
                    invalid("AI_ITEM_REMOVAL_REQUIRES_RESOLUTION",
                            "AI items must be retained with resolution REMOVED");
                }
                continue;
            }
            if (currentItem.source() != proposedItem.source()) {
                invalid("CANDIDATE_SOURCE_IMMUTABLE", "Candidate item source cannot change");
            }
            if (!currentItem.parentId().equals(proposedItem.parentId())) {
                invalid("CANDIDATE_ITEM_PARENT_IMMUTABLE", "Existing candidate items cannot move between schemas");
            }
            if (currentItem.resolution() != proposedItem.resolution()) resolutionChanges++;
        }
        if (resolutionChanges > 1) {
            invalid("CANDIDATE_BULK_RESOLUTION_FORBIDDEN",
                    "Resolve one AI candidate item per autosave; confirm-all is not supported");
        }

        for (var originalItem : originalItems.values()) {
            if (originalItem.source() != CandidateSource.AI) continue;
            var proposedItem = proposedItems.get(originalItem.id());
            if (proposedItem == null) {
                invalid("AI_ITEM_REMOVAL_REQUIRES_RESOLUTION",
                        "AI items must be retained with resolution REMOVED");
            }
            if (proposedItem.source() != CandidateSource.AI
                    || !originalItem.provenance().equals(proposedItem.provenance())) {
                invalid("AI_PROVENANCE_IMMUTABLE", "AI evidence, confidence and inferred state are immutable");
            }
            if (!originalItem.semanticValue().equals(proposedItem.semanticValue())
                    && proposedItem.resolution() != CandidateResolution.RESOLVED_BY_EDIT
                    && proposedItem.resolution() != CandidateResolution.REMOVED) {
                invalid("AI_EDIT_REQUIRES_RESOLUTION",
                        "Editing an AI item requires RESOLVED_BY_EDIT or REMOVED");
            }
        }

        for (var proposedItem : proposedItems.values()) {
            if (currentItems.containsKey(proposedItem.id())) continue;
            if (proposedItem.source() != CandidateSource.USER) {
                invalid("NEW_CANDIDATE_ITEM_MUST_BE_USER",
                        "New review items must use source USER without AI provenance");
            }
        }
    }

    private static Map<UUID, ReviewItem> items(CandidateBundle bundle) {
        var result = new LinkedHashMap<UUID, ReviewItem>();
        for (var schema : bundle.schemas()) {
            put(result, new ReviewItem(
                    schema.candidateSchemaId(), schema.candidateSchemaId(), schema.source(),
                    provenance(schema.assessment()), schema.assessment().resolution(),
                    java.util.Arrays.asList(schema.proposedSchemaKey(), schema.displayName())
            ));
            for (var field : schema.fields()) {
                put(result, new ReviewItem(
                        field.candidateFieldId(), schema.candidateSchemaId(), field.source(),
                        provenance(field.assessment()), field.assessment().resolution(),
                        java.util.Arrays.asList(
                                field.proposedFieldKey(), field.displayName(), field.required(), field.value()
                        )
                ));
            }
        }
        return result;
    }

    private static void put(Map<UUID, ReviewItem> items, ReviewItem item) {
        if (items.putIfAbsent(item.id(), item) != null) {
            invalid("CANDIDATE_ITEM_ID_DUPLICATE", "Candidate item IDs must be unique");
        }
    }

    private static Provenance provenance(CandidateAssessment assessment) {
        return new Provenance(
                assessment.confidenceBps(), assessment.inferred(), assessment.evidence()
        );
    }

    private static void invalid(String code, String message) {
        throw new InvalidCandidateEditException(code, message);
    }

    private record ReviewItem(
            UUID id,
            UUID parentId,
            CandidateSource source,
            Provenance provenance,
            CandidateResolution resolution,
            List<Object> semanticValue
    ) {
        ReviewItem {
            semanticValue = Collections.unmodifiableList(new ArrayList<>(semanticValue));
        }
    }

    private record Provenance(
            Integer confidenceBps,
            boolean inferred,
            List<CandidateEvidence> evidence
    ) {
        Provenance {
            evidence = List.copyOf(evidence);
        }
    }
}
