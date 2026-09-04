package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.candidate.CandidateApplyStore;
import cn.hbads.renderweave.inference.candidate.InferenceCandidateNotFoundException;
import cn.hbads.renderweave.inference.candidate.InferenceCandidateRevisionConflictException;
import cn.hbads.renderweave.inference.candidate.MaterializedDraftBundle;
import cn.hbads.renderweave.inference.run.InferenceRunNotFoundException;
import cn.hbads.renderweave.inference.run.InferenceRunState;
import cn.hbads.renderweave.inference.run.InferenceRunStore;
import cn.hbads.renderweave.inference.run.InferenceStage;
import cn.hbads.renderweave.inference.run.InvalidInferenceRunTransitionException;
import cn.hbads.renderweave.schema.draft.CreationSource;
import cn.hbads.renderweave.schema.draft.DraftStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

/** Owns the one transaction that freezes a Candidate and creates its whole Draft bundle. */
@Repository
public class PostgresCandidateApplyStore implements CandidateApplyStore {
    private final JdbcClient jdbcClient;
    private final DraftStore drafts;
    private final InferenceRunStore runs;
    private final PostgresPayloadLifecycleStore payloadLifecycle;

    public PostgresCandidateApplyStore(
            JdbcClient jdbcClient,
            DraftStore drafts,
            InferenceRunStore runs,
            PostgresPayloadLifecycleStore payloadLifecycle
    ) {
        this.jdbcClient = jdbcClient;
        this.drafts = drafts;
        this.runs = runs;
        this.payloadLifecycle = payloadLifecycle;
    }

    @Override
    @Transactional
    public PersistenceResult apply(
            UUID runId,
            long expectedCandidateRevision,
            String finalCandidateJson,
            MaterializedDraftBundle bundle,
            Instant now
    ) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(bundle, "bundle");
        Objects.requireNonNull(now, "now");
        if (expectedCandidateRevision < 0) {
            throw new IllegalArgumentException("expectedCandidateRevision must not be negative");
        }
        if (finalCandidateJson == null || finalCandidateJson.isBlank()) {
            throw new IllegalArgumentException("finalCandidateJson is required");
        }

        var candidate = lockCandidate(runId, finalCandidateJson);
        if (candidate.revision() != expectedCandidateRevision) {
            throw new InferenceCandidateRevisionConflictException(
                    runId, expectedCandidateRevision, candidate.revision()
            );
        }
        if (!candidate.currentMatches()) {
            throw new IllegalStateException("Materialized Candidate does not match its locked current snapshot");
        }

        var state = lockRun(runId);
        payloadLifecycle.requireForApplyLocked(runId, now);
        if (state.state() == InferenceRunState.COMPLETED) {
            if (!candidate.finalMatches() || candidate.appliedAt() == null) {
                throw new InvalidInferenceRunTransitionException(
                        runId, "completed run does not match this final Candidate snapshot"
                );
            }
            return new PersistenceResult(
                    runs.find(runId).orElseThrow(() -> new InferenceRunNotFoundException(runId)),
                    candidate.appliedAt().toInstant()
            );
        }
        if (state.state() != InferenceRunState.REVIEW_REQUIRED
                || state.stage() != InferenceStage.USER_APPROVAL) {
            throw new InvalidInferenceRunTransitionException(
                    runId, "Candidate can only be applied from REVIEW_REQUIRED/USER_APPROVAL"
            );
        }
        if (candidate.appliedAt() != null || candidate.finalMatches()) {
            throw new IllegalStateException("Reviewable Candidate already has a final snapshot");
        }

        var applyingSequence = jdbcClient.sql("""
                        update inference_run
                        set state = 'APPLYING',
                            stage = 'ATOMIC_CREATE',
                            sequence = sequence + 1,
                            updated_at = :now
                        where run_id = :runId
                          and state = 'REVIEW_REQUIRED'
                          and stage = 'USER_APPROVAL'
                        returning sequence
                        """)
                .param("runId", runId)
                .param("now", offset(now))
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new InvalidInferenceRunTransitionException(runId, "apply state changed"));
        insertEvent(
                runId, applyingSequence, "APPLYING", InferenceRunState.APPLYING,
                "{\"candidateRevision\":" + expectedCandidateRevision + "}", now
        );

        var frozen = jdbcClient.sql("""
                        update inference_candidate
                        set final_json = cast(:finalCandidateJson as jsonb),
                            applied_at = :now,
                            updated_at = :now
                        where run_id = :runId and revision = :revision and final_json is null
                        """)
                .param("finalCandidateJson", finalCandidateJson)
                .param("now", offset(now))
                .param("runId", runId)
                .param("revision", expectedCandidateRevision)
                .update();
        if (frozen != 1) {
            throw new IllegalStateException("Locked Candidate final snapshot could not be frozen");
        }

        for (var draft : bundle.draftsInCreationOrder()) {
            drafts.create(
                    draft.schemaKey(), draft.definitionJson(), CreationSource.AI,
                    draft.draftReferences(), draft.staticReferences()
            );
        }

        var completedSequence = jdbcClient.sql("""
                        update inference_run
                        set state = 'COMPLETED',
                            sequence = sequence + 1,
                            finished_at = :now,
                            updated_at = :now
                        where run_id = :runId and state = 'APPLYING' and stage = 'ATOMIC_CREATE'
                        returning sequence
                        """)
                .param("runId", runId)
                .param("now", offset(now))
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException("Applying run could not be completed"));
        insertEvent(
                runId, completedSequence, "CANDIDATE_APPLIED", InferenceRunState.COMPLETED,
                completedEventData(expectedCandidateRevision, bundle), now
        );
        if (payloadLifecycle.isManaged(runId)) {
            payloadLifecycle.tombstoneCompleted(runId, now);
        }
        var persistedAppliedAt = jdbcClient.sql("""
                        select applied_at from inference_candidate where run_id = :runId
                        """)
                .param("runId", runId)
                .query(OffsetDateTime.class)
                .single()
                .toInstant();
        return new PersistenceResult(
                runs.find(runId).orElseThrow(() -> new InferenceRunNotFoundException(runId)),
                persistedAppliedAt
        );
    }

    private CandidateRow lockCandidate(UUID runId, String finalCandidateJson) {
        return jdbcClient.sql("""
                        select revision,
                               current_json = cast(:candidateJson as jsonb) as current_matches,
                               coalesce(final_json = cast(:candidateJson as jsonb), false) as final_matches,
                               applied_at
                        from inference_candidate
                        where run_id = :runId
                        for update
                        """)
                .param("candidateJson", finalCandidateJson)
                .param("runId", runId)
                .query(PostgresCandidateApplyStore::mapCandidateRow)
                .optional()
                .orElseThrow(() -> new InferenceCandidateNotFoundException(runId));
    }

    private RunStateRow lockRun(UUID runId) {
        return jdbcClient.sql("""
                        select state, stage from inference_run where run_id = :runId for update
                        """)
                .param("runId", runId)
                .query((resultSet, rowNumber) -> new RunStateRow(
                        InferenceRunState.valueOf(resultSet.getString("state")),
                        InferenceStage.valueOf(resultSet.getString("stage"))
                ))
                .optional()
                .orElseThrow(() -> new InferenceRunNotFoundException(runId));
    }

    private void insertEvent(
            UUID runId,
            long sequence,
            String eventType,
            InferenceRunState state,
            String dataJson,
            Instant now
    ) {
        jdbcClient.sql("""
                        insert into inference_run_event (
                            run_id, sequence, event_type, state, stage, data_json, occurred_at
                        ) values (
                            :runId, :sequence, :eventType, :state, 'ATOMIC_CREATE',
                            cast(:dataJson as jsonb), :now
                        )
                        """)
                .param("runId", runId)
                .param("sequence", sequence)
                .param("eventType", eventType)
                .param("state", state.name())
                .param("dataJson", dataJson)
                .param("now", offset(now))
                .update();
    }

    private static String completedEventData(long revision, MaterializedDraftBundle bundle) {
        var keys = bundle.draftsInCreationOrder().stream()
                .map(draft -> "\"" + draft.schemaKey().value() + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        return "{\"candidateRevision\":" + revision
                + ",\"rootSchemaKey\":\"" + bundle.rootSchemaKey().value()
                + "\",\"createdSchemaKeys\":[" + keys + "]}";
    }

    private static CandidateRow mapCandidateRow(ResultSet resultSet, int rowNumber) throws SQLException {
        return new CandidateRow(
                resultSet.getLong("revision"),
                resultSet.getBoolean("current_matches"),
                resultSet.getBoolean("final_matches"),
                resultSet.getObject("applied_at", OffsetDateTime.class)
        );
    }

    private static OffsetDateTime offset(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record CandidateRow(
            long revision,
            boolean currentMatches,
            boolean finalMatches,
            OffsetDateTime appliedAt
    ) { }

    private record RunStateRow(InferenceRunState state, InferenceStage stage) { }
}
