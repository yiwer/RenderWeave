package cn.hbads.renderweave.inference.candidate;

import cn.hbads.renderweave.inference.run.InferenceRunState;
import cn.hbads.renderweave.inference.run.InvalidInferenceRunTransitionException;
import cn.hbads.renderweave.schema.definition.InvalidSchemaDefinitionException;
import cn.hbads.renderweave.schema.draft.DraftAlreadyExistsException;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.UUID;

/** Human-triggered application boundary from the review model into create-only Draft commands. */
public final class CandidateApplyService {
    private final CandidateReviewService reviews;
    private final CandidateApplyStore store;
    private final Clock clock;
    private final CandidateMaterializer materializer;
    private final CandidateJsonCodec codec;

    public CandidateApplyService(
            CandidateReviewService reviews,
            CandidateApplyStore store,
            Clock clock
    ) {
        this(reviews, store, clock, new CandidateMaterializer(), new CandidateJsonCodec());
    }

    CandidateApplyService(
            CandidateReviewService reviews,
            CandidateApplyStore store,
            Clock clock,
            CandidateMaterializer materializer,
            CandidateJsonCodec codec
    ) {
        this.reviews = Objects.requireNonNull(reviews, "reviews");
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.materializer = Objects.requireNonNull(materializer, "materializer");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    public CandidateApplyResult apply(UUID runId, long expectedCandidateRevision) {
        Objects.requireNonNull(runId, "runId");
        if (expectedCandidateRevision < 0) {
            throw new IllegalArgumentException("expectedCandidateRevision must not be negative");
        }
        var review = reviews.get(runId);
        if (review.candidateRevision() != expectedCandidateRevision) {
            throw new InferenceCandidateRevisionConflictException(
                    runId, expectedCandidateRevision, review.candidateRevision()
            );
        }
        if (review.run().state() != InferenceRunState.REVIEW_REQUIRED
                && review.run().state() != InferenceRunState.COMPLETED) {
            throw new InvalidInferenceRunTransitionException(
                    runId, "Candidate can only be applied from REVIEW_REQUIRED"
            );
        }

        var blockers = new LinkedHashMap<String, CandidateProblem>();
        review.problems().stream()
                .filter(problem -> problem.severity() == CandidateProblemSeverity.BLOCKER)
                .forEach(problem -> blockers.put(problemIdentity(problem), problem));
        reviews.validateForApply(review).stream()
                .filter(problem -> problem.severity() == CandidateProblemSeverity.BLOCKER)
                .forEach(problem -> blockers.put(problemIdentity(problem), problem));
        if (!blockers.isEmpty()) throw new CandidateApplyBlockedException(blockers.values().stream().toList());

        var candidate = review.finalCandidate().orElse(review.current());
        var materialized = materializer.materialize(candidate);
        final CandidateApplyStore.PersistenceResult persisted;
        try {
            persisted = store.apply(
                    runId,
                    expectedCandidateRevision,
                    codec.write(candidate),
                    materialized,
                    clock.instant()
            );
        } catch (DraftAlreadyExistsException conflict) {
            throw new CandidateApplyConflictException(
                    "CANDIDATE_SCHEMA_KEY_CONFLICT",
                    "Draft key is already active or tombstoned: " + conflict.schemaKey().value(),
                    conflict
            );
        } catch (InvalidSchemaDefinitionException conflict) {
            throw new CandidateApplyConflictException(
                    "CANDIDATE_REFERENCE_CONFLICT",
                    "Draft references changed while the Candidate was under review",
                    conflict
            );
        }
        return new CandidateApplyResult(
                persisted.run(), expectedCandidateRevision, candidate,
                materialized.rootSchemaKey(),
                java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(materialized.rootSchemaKey()),
                        materialized.draftsInCreationOrder().stream()
                                .map(MaterializedDraft::schemaKey)
                                .filter(key -> !key.equals(materialized.rootSchemaKey()))
                ).toList(),
                persisted.appliedAt()
        );
    }

    private static String problemIdentity(CandidateProblem problem) {
        return problem.code() + "|" + problem.itemId() + "|" + problem.pointer();
    }
}
