package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.candidate.CandidateBundle;
import cn.hbads.renderweave.inference.candidate.InferenceCandidateSnapshot;
import cn.hbads.renderweave.inference.candidate.InferenceCandidateNotFoundException;
import cn.hbads.renderweave.inference.candidate.InferenceCandidateRevisionConflictException;
import cn.hbads.renderweave.inference.input.InferenceMode;
import cn.hbads.renderweave.inference.input.NormalizedArtifact;
import cn.hbads.renderweave.inference.run.InferenceArtifactDeletion;
import cn.hbads.renderweave.inference.run.InferenceIdempotencyConflictException;
import cn.hbads.renderweave.inference.run.InferenceLease;
import cn.hbads.renderweave.inference.run.InferenceLeaseLostException;
import cn.hbads.renderweave.inference.run.InferenceRunEvent;
import cn.hbads.renderweave.inference.run.InferenceRunInput;
import cn.hbads.renderweave.inference.run.InferenceRunNotFoundException;
import cn.hbads.renderweave.inference.run.InferenceRunSnapshot;
import cn.hbads.renderweave.inference.run.InferenceRunState;
import cn.hbads.renderweave.inference.run.InferenceRunStore;
import cn.hbads.renderweave.inference.run.InferenceStage;
import cn.hbads.renderweave.inference.run.InvalidInferenceRunTransitionException;
import cn.hbads.renderweave.inference.run.NewInferenceRun;
import cn.hbads.renderweave.inference.replay.InferenceAttempt;
import cn.hbads.renderweave.inference.replay.InferenceAttemptProblemTaxonomyJsonCodec;
import cn.hbads.renderweave.inference.replay.InferenceAttemptStatus;
import cn.hbads.renderweave.inference.replay.InferenceReplayStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PostgresInferenceRunStore implements InferenceRunStore, InferenceReplayStore {
    private static final int MAX_EVENT_PAGE = 1000;
    private static final InferenceAttemptProblemTaxonomyJsonCodec ATTEMPT_PROBLEM_CODEC =
            new InferenceAttemptProblemTaxonomyJsonCodec();

    private final JdbcClient jdbcClient;

    public PostgresInferenceRunStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    @Transactional
    public CreationResult create(NewInferenceRun command) {
        lockIdempotencyKey(command.idempotencyKey());
        var existing = findByIdempotencyKey(command.idempotencyKey());
        if (existing.isPresent()) {
            return replayOrConflict(existing.orElseThrow(), command.requestFingerprint(), command.idempotencyKey());
        }

        for (var artifact : command.normalizedInput().artifacts()) {
            upsertAndVerifyArtifact(artifact, command.createdAt());
        }

        jdbcClient.sql("""
                        insert into inference_run (
                            run_id, idempotency_key, request_fingerprint, input_fingerprint,
                            mode, state, stage, sequence, profile_id, profile_snapshot,
                            source_reference, cost_limit_micros_cny, retry_of_run_id, checkpoint_json,
                            created_at, updated_at
                        ) values (
                            :runId, :idempotencyKey, :requestFingerprint, :inputFingerprint,
                            :mode, 'QUEUED', 'OBSERVE', 1, :profileId, cast(:profileSnapshot as jsonb),
                            :sourceReference, :costLimitMicrosCny, :retryOfRunId, cast(:checkpointJson as jsonb),
                            :createdAt, :createdAt
                        )
                        """)
                .param("runId", command.runId())
                .param("idempotencyKey", command.idempotencyKey())
                .param("requestFingerprint", command.requestFingerprint())
                .param("inputFingerprint", command.normalizedInput().inputFingerprint())
                .param("mode", command.normalizedInput().mode().name())
                .param("profileId", command.normalizedInput().profileId())
                .param("profileSnapshot", command.profileSnapshotJson())
                .param("sourceReference", command.normalizedInput().sourceReference())
                .param("costLimitMicrosCny", command.costLimitMicrosCny())
                .param("retryOfRunId", command.retryOfRunId().orElse(null))
                .param("checkpointJson", initialCheckpoint(command.normalizedInput().inputFingerprint()))
                .param("createdAt", OffsetDateTime.ofInstant(command.createdAt(), java.time.ZoneOffset.UTC))
                .update();

        for (var reference : command.normalizedInput().references()) {
            jdbcClient.sql("""
                            insert into inference_run_input (run_id, input_kind, input_ordinal, artifact_id)
                            values (:runId, :kind, :ordinal, :artifactId)
                            """)
                    .param("runId", command.runId())
                    .param("kind", reference.kind().name())
                    .param("ordinal", reference.ordinal())
                    .param("artifactId", reference.artifactId())
                    .update();
        }
        insertEvent(command.runId(), 1, "QUEUED", InferenceRunState.QUEUED,
                InferenceStage.OBSERVE, "{}", command.createdAt());
        return new CreationResult(require(command.runId()), true);
    }

    @Override
    public Optional<InferenceRunSnapshot> find(UUID runId) {
        return findRow(runId).map(row -> row.toSnapshot(loadInputs(runId)));
    }

    @Override
    public RunPage list(int page, int size) {
        if (page < 1 || size < 1 || size > 20) {
            throw new IllegalArgumentException("page must be >= 1 and size must be 1..20");
        }
        final int offset;
        try {
            offset = Math.multiplyExact(page - 1, size);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("page is too large", overflow);
        }
        var total = jdbcClient.sql("select count(*) from inference_run")
                .query(Long.class)
                .single();
        var items = jdbcClient.sql("""
                        select run.run_id, run.mode, run.state, run.stage, run.sequence,
                               run.profile_id, run.source_reference, run.cost_limit_micros_cny,
                               run.cancellation_requested,
                               run.retry_of_run_id, run.failure_code, candidate.revision as candidate_revision,
                               run.created_at, run.updated_at, run.finished_at
                        from inference_run run
                        left join inference_candidate candidate on candidate.run_id = run.run_id
                        order by run.created_at desc, run.run_id desc
                        limit :size offset :offset
                        """)
                .param("size", size)
                .param("offset", offset)
                .query(PostgresInferenceRunStore::mapRunSummary)
                .list();
        return new RunPage(page, size, total, items);
    }

    @Override
    @Transactional
    public Optional<InferenceRunSnapshot> claimNext(String workerId, Instant now, Duration leaseDuration) {
        return claimNextByNetwork(false, workerId, now, leaseDuration);
    }

    @Override
    @Transactional
    public Optional<InferenceRunSnapshot> claimNextLive(String workerId, Instant now, Duration leaseDuration) {
        return claimNextByNetwork(true, workerId, now, leaseDuration);
    }

    private Optional<InferenceRunSnapshot> claimNextByNetwork(
            boolean networkAllowed,
            String workerId,
            Instant now,
            Duration leaseDuration
    ) {
        workerId = requireWorkerId(workerId);
        var expiresAt = leaseExpiry(now, leaseDuration);
        var candidate = jdbcClient.sql("""
                        select run_id, state
                        from inference_run
                        where (profile_snapshot ->> 'networkAllowed')::boolean = :networkAllowed
                          and (state = 'QUEUED'
                           or (state = 'RUNNING' and lease_expires_at <= :now))
                        order by created_at, run_id
                        for update skip locked
                        fetch first 1 row only
                        """)
                .param("now", offset(now))
                .param("networkAllowed", networkAllowed)
                .query((resultSet, rowNumber) -> new ClaimCandidate(
                        resultSet.getObject("run_id", UUID.class),
                        InferenceRunState.valueOf(resultSet.getString("state"))
                ))
                .optional();
        if (candidate.isEmpty()) return Optional.empty();

        var selected = candidate.orElseThrow();
        var leaseToken = UUID.randomUUID();
        var updated = jdbcClient.sql("""
                        update inference_run
                        set state = 'RUNNING',
                            lease_owner = :workerId,
                            lease_token = :leaseToken,
                            lease_expires_at = :expiresAt,
                            sequence = sequence + 1,
                            updated_at = :now
                        where run_id = :runId
                        returning sequence, stage
                        """)
                .param("workerId", workerId)
                .param("leaseToken", leaseToken)
                .param("expiresAt", offset(expiresAt))
                .param("now", offset(now))
                .param("runId", selected.runId())
                .query((resultSet, rowNumber) -> new SequenceAndStage(
                        resultSet.getLong("sequence"),
                        InferenceStage.valueOf(resultSet.getString("stage"))
                ))
                .single();
        var eventType = selected.state() == InferenceRunState.QUEUED ? "LEASE_ACQUIRED" : "LEASE_RECLAIMED";
        insertEvent(selected.runId(), updated.sequence(), eventType, InferenceRunState.RUNNING,
                updated.stage(), "{}", now);
        return Optional.of(require(selected.runId()));
    }

    @Override
    @Transactional
    public Optional<InferenceRunSnapshot> claim(
            UUID runId,
            String workerId,
            Instant now,
            Duration leaseDuration
    ) {
        Objects.requireNonNull(runId, "runId");
        workerId = requireWorkerId(workerId);
        var selected = jdbcClient.sql("""
                        select run_id, state
                        from inference_run
                        where run_id = :runId
                          and (state = 'QUEUED' or (state = 'RUNNING' and lease_expires_at <= :now))
                        for update
                        """)
                .param("runId", runId)
                .param("now", offset(now))
                .query((resultSet, rowNumber) -> new ClaimCandidate(
                        resultSet.getObject("run_id", UUID.class),
                        InferenceRunState.valueOf(resultSet.getString("state"))
                ))
                .optional();
        if (selected.isEmpty()) return Optional.empty();

        var candidate = selected.orElseThrow();
        var leaseToken = UUID.randomUUID();
        var expiresAt = leaseExpiry(now, leaseDuration);
        var updated = jdbcClient.sql("""
                        update inference_run
                        set state = 'RUNNING',
                            lease_owner = :workerId,
                            lease_token = :leaseToken,
                            lease_expires_at = :expiresAt,
                            sequence = sequence + 1,
                            updated_at = :now
                        where run_id = :runId
                        returning sequence, stage
                        """)
                .param("workerId", workerId)
                .param("leaseToken", leaseToken)
                .param("expiresAt", offset(expiresAt))
                .param("now", offset(now))
                .param("runId", runId)
                .query((resultSet, rowNumber) -> new SequenceAndStage(
                        resultSet.getLong("sequence"),
                        InferenceStage.valueOf(resultSet.getString("stage"))
                ))
                .single();
        var eventType = candidate.state() == InferenceRunState.QUEUED ? "LEASE_ACQUIRED" : "LEASE_RECLAIMED";
        insertEvent(runId, updated.sequence(), eventType, InferenceRunState.RUNNING,
                updated.stage(), "{}", now);
        return Optional.of(require(runId));
    }

    @Override
    @Transactional
    public boolean renewLease(UUID runId, UUID leaseToken, Instant now, Duration leaseDuration) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(leaseToken, "leaseToken");
        var expiresAt = leaseExpiry(now, leaseDuration);
        return jdbcClient.sql("""
                        update inference_run
                        set lease_expires_at = greatest(lease_expires_at, :expiresAt),
                            updated_at = :now
                        where run_id = :runId
                          and state = 'RUNNING'
                          and lease_token = :leaseToken
                          and lease_expires_at > :now
                          and not cancellation_requested
                        """)
                .param("expiresAt", offset(expiresAt))
                .param("now", offset(now))
                .param("runId", runId)
                .param("leaseToken", leaseToken)
                .update() == 1;
    }

    @Override
    @Transactional
    public InferenceRunSnapshot checkpoint(
            UUID runId,
            UUID leaseToken,
            InferenceStage expectedStage,
            InferenceStage nextStage,
            String checkpointJson,
            Instant now
    ) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(leaseToken, "leaseToken");
        Objects.requireNonNull(expectedStage, "expectedStage");
        Objects.requireNonNull(nextStage, "nextStage");
        Objects.requireNonNull(now, "now");
        if (!expectedStage.canTransitionTo(nextStage)) {
            throw new InvalidInferenceRunTransitionException(
                    runId, "stage " + expectedStage + " cannot advance to " + nextStage
            );
        }
        if (checkpointJson == null || checkpointJson.isBlank()) {
            throw new IllegalArgumentException("checkpointJson is required");
        }
        return applyCheckpoint(
                runId, leaseToken, expectedStage, nextStage, checkpointJson, now
        );
    }

    private InferenceRunSnapshot applyCheckpoint(
            UUID runId,
            UUID leaseToken,
            InferenceStage expectedStage,
            InferenceStage nextStage,
            String checkpointJson,
            Instant now
    ) {
        var entersReview = nextStage == InferenceStage.USER_APPROVAL;
        var updated = jdbcClient.sql("""
                        update inference_run
                        set state = case when :entersReview then 'REVIEW_REQUIRED' else state end,
                            stage = :nextStage,
                            checkpoint_json = cast(:checkpointJson as jsonb),
                            sequence = sequence + 1,
                            lease_owner = case when :entersReview then null else lease_owner end,
                            lease_token = case when :entersReview then null else lease_token end,
                            lease_expires_at = case when :entersReview then null else lease_expires_at end,
                            updated_at = :now
                        where run_id = :runId
                          and state = 'RUNNING'
                          and stage = :expectedStage
                          and lease_token = :leaseToken
                          and lease_expires_at > :now
                          and not cancellation_requested
                        returning sequence
                        """)
                .param("entersReview", entersReview)
                .param("nextStage", nextStage.name())
                .param("checkpointJson", checkpointJson)
                .param("now", offset(now))
                .param("runId", runId)
                .param("expectedStage", expectedStage.name())
                .param("leaseToken", leaseToken)
                .query(Long.class)
                .optional();
        if (updated.isEmpty()) throw new InferenceLeaseLostException(runId);
        var state = entersReview ? InferenceRunState.REVIEW_REQUIRED : InferenceRunState.RUNNING;
        insertEvent(runId, updated.orElseThrow(), entersReview ? "REVIEW_REQUIRED" : "CHECKPOINT_ADVANCED",
                state, nextStage, "{}", now);
        return require(runId);
    }

    @Override
    @Transactional
    public InferenceRunSnapshot requestCancellation(UUID runId, Instant now) {
        var current = lockAndRequire(runId);
        if (current.state().terminal()) return current;
        if (current.state() == InferenceRunState.APPLYING) {
            throw new InvalidInferenceRunTransitionException(runId, "APPLYING cannot be cancelled");
        }
        if (current.state() == InferenceRunState.RUNNING && current.cancellationRequested()) return current;

        var cooperative = current.state() == InferenceRunState.RUNNING;
        var targetState = cooperative ? InferenceRunState.RUNNING : InferenceRunState.CANCELLED;
        var sequence = jdbcClient.sql("""
                        update inference_run
                        set state = :state,
                            cancellation_requested = true,
                            sequence = sequence + 1,
                            lease_owner = case when :cooperative then lease_owner else null end,
                            lease_token = case when :cooperative then lease_token else null end,
                            lease_expires_at = case when :cooperative then lease_expires_at else null end,
                            finished_at = case when :cooperative then null else :now end,
                            updated_at = :now
                        where run_id = :runId
                        returning sequence
                        """)
                .param("state", targetState.name())
                .param("cooperative", cooperative)
                .param("now", offset(now))
                .param("runId", runId)
                .query(Long.class)
                .single();
        insertEvent(runId, sequence, cooperative ? "CANCELLATION_REQUESTED" : "CANCELLED",
                targetState, current.stage(), "{}", now);
        return require(runId);
    }

    @Override
    @Transactional
    public InferenceRunSnapshot acknowledgeCancellation(UUID runId, UUID leaseToken, Instant now) {
        var updated = jdbcClient.sql("""
                        update inference_run
                        set state = 'CANCELLED',
                            sequence = sequence + 1,
                            lease_owner = null,
                            lease_token = null,
                            lease_expires_at = null,
                            finished_at = :now,
                            updated_at = :now
                        where run_id = :runId
                          and state = 'RUNNING'
                          and cancellation_requested
                          and lease_token = :leaseToken
                          and lease_expires_at > :now
                        returning sequence, stage
                        """)
                .param("now", offset(now))
                .param("runId", runId)
                .param("leaseToken", leaseToken)
                .query((resultSet, rowNumber) -> new SequenceAndStage(
                        resultSet.getLong("sequence"),
                        InferenceStage.valueOf(resultSet.getString("stage"))
                ))
                .optional();
        if (updated.isEmpty()) throw new InferenceLeaseLostException(runId);
        var result = updated.orElseThrow();
        insertEvent(runId, result.sequence(), "CANCELLED", InferenceRunState.CANCELLED,
                result.stage(), "{}", now);
        return require(runId);
    }

    @Override
    @Transactional
    public InferenceRunSnapshot fail(UUID runId, UUID leaseToken, String failureCode, Instant now) {
        failureCode = requireFailureCode(failureCode);
        var updated = jdbcClient.sql("""
                        update inference_run
                        set state = 'FAILED',
                            sequence = sequence + 1,
                            failure_code = :failureCode,
                            lease_owner = null,
                            lease_token = null,
                            lease_expires_at = null,
                            finished_at = :now,
                            updated_at = :now
                        where run_id = :runId
                          and state = 'RUNNING'
                          and lease_token = :leaseToken
                          and lease_expires_at > :now
                        returning sequence, stage
                        """)
                .param("failureCode", failureCode)
                .param("now", offset(now))
                .param("runId", runId)
                .param("leaseToken", leaseToken)
                .query((resultSet, rowNumber) -> new SequenceAndStage(
                        resultSet.getLong("sequence"),
                        InferenceStage.valueOf(resultSet.getString("stage"))
                ))
                .optional();
        if (updated.isEmpty()) throw new InferenceLeaseLostException(runId);
        var result = updated.orElseThrow();
        insertEvent(runId, result.sequence(), "FAILED", InferenceRunState.FAILED,
                result.stage(), "{}", now);
        return require(runId);
    }

    @Override
    @Transactional
    public CreationResult retry(UUID sourceRunId, UUID newRunId, String idempotencyKey, Instant now) {
        Objects.requireNonNull(newRunId, "newRunId");
        idempotencyKey = NewInferenceRun.validateIdempotencyKey(idempotencyKey);
        var source = lockAndRequire(sourceRunId);
        if (source.state() != InferenceRunState.FAILED && source.state() != InferenceRunState.CANCELLED) {
            throw new InvalidInferenceRunTransitionException(sourceRunId, "only FAILED or CANCELLED runs can be retried");
        }
        var requestFingerprint = retryFingerprint(sourceRunId);
        lockIdempotencyKey(idempotencyKey);
        var existing = findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) return replayOrConflict(existing.orElseThrow(), requestFingerprint, idempotencyKey);

        jdbcClient.sql("""
                        insert into inference_run (
                            run_id, idempotency_key, request_fingerprint, input_fingerprint,
                            mode, state, stage, sequence, profile_id, profile_snapshot,
                            source_reference, cost_limit_micros_cny, retry_of_run_id, checkpoint_json,
                            created_at, updated_at
                        )
                        select :newRunId, :idempotencyKey, :requestFingerprint, input_fingerprint,
                               mode, 'QUEUED', 'OBSERVE', 1, profile_id, profile_snapshot,
                               source_reference, cost_limit_micros_cny, run_id, cast(:checkpointJson as jsonb),
                               :now, :now
                        from inference_run
                        where run_id = :sourceRunId
                        """)
                .param("newRunId", newRunId)
                .param("idempotencyKey", idempotencyKey)
                .param("requestFingerprint", requestFingerprint)
                .param("checkpointJson", retryCheckpoint(sourceRunId))
                .param("now", offset(now))
                .param("sourceRunId", sourceRunId)
                .update();
        jdbcClient.sql("""
                        insert into inference_run_input (run_id, input_kind, input_ordinal, artifact_id)
                        select :newRunId, input_kind, input_ordinal, artifact_id
                        from inference_run_input
                        where run_id = :sourceRunId
                        """)
                .param("newRunId", newRunId)
                .param("sourceRunId", sourceRunId)
                .update();
        jdbcClient.sql("""
                        update inference_artifact
                        set deletion_pending = false
                        where artifact_id in (
                            select artifact_id from inference_run_input where run_id = :newRunId
                        )
                        """)
                .param("newRunId", newRunId)
                .update();
        insertEvent(newRunId, 1, "RETRIED", InferenceRunState.QUEUED,
                InferenceStage.OBSERVE, "{\"retryOfRunId\":\"" + sourceRunId + "\"}", now);
        return new CreationResult(require(newRunId), true);
    }

    @Override
    @Transactional
    public List<InferenceArtifactDeletion> delete(UUID runId) {
        var current = lockAndRequire(runId);
        if (current.state() == InferenceRunState.APPLYING) {
            throw new InvalidInferenceRunTransitionException(runId, "APPLYING run cannot be deleted");
        }
        var artifactIds = jdbcClient.sql("""
                        select distinct artifact_id from inference_run_input where run_id = :runId
                        """)
                .param("runId", runId)
                .query(String.class)
                .list();
        jdbcClient.sql("delete from inference_run where run_id = :runId")
                .param("runId", runId)
                .update();
        if (artifactIds.isEmpty()) return List.of();
        return jdbcClient.sql("""
                        update inference_artifact artifact
                        set deletion_pending = true
                        where artifact.artifact_id in (:artifactIds)
                          and not exists (
                              select 1 from inference_run_input input
                              where input.artifact_id = artifact.artifact_id
                          )
                        returning artifact_id, locator
                        """)
                .param("artifactIds", artifactIds)
                .query(PostgresInferenceRunStore::mapDeletion)
                .list();
    }

    @Override
    public List<InferenceArtifactDeletion> pendingArtifactDeletions(int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be 1..1000");
        return jdbcClient.sql("""
                        select artifact_id, locator
                        from inference_artifact
                        where deletion_pending
                        order by created_at, artifact_id
                        fetch first :limit rows only
                        """)
                .param("limit", limit)
                .query(PostgresInferenceRunStore::mapDeletion)
                .list();
    }

    @Override
    @Transactional
    public boolean confirmArtifactDeletion(String artifactId) {
        return jdbcClient.sql("""
                        delete from inference_artifact artifact
                        where artifact_id = :artifactId
                          and deletion_pending
                          and not exists (
                              select 1 from inference_run_input input
                              where input.artifact_id = artifact.artifact_id
                          )
                        """)
                .param("artifactId", artifactId)
                .update() == 1;
    }

    @Override
    public List<InferenceRunEvent> eventsAfter(UUID runId, long sequenceExclusive, int limit) {
        if (sequenceExclusive < 0) throw new IllegalArgumentException("sequenceExclusive must not be negative");
        if (limit < 1 || limit > MAX_EVENT_PAGE) throw new IllegalArgumentException("limit must be 1..1000");
        return jdbcClient.sql("""
                        select run_id, sequence, event_type, state, stage,
                               data_json::text as data_json, occurred_at
                        from inference_run_event
                        where run_id = :runId and sequence > :sequence
                        order by sequence
                        fetch first :limit rows only
                        """)
                .param("runId", runId)
                .param("sequence", sequenceExclusive)
                .param("limit", limit)
                .query(PostgresInferenceRunStore::mapEvent)
                .list();
    }

    @Override
    @Transactional
    public InferenceRunSnapshot recordAttempt(
            UUID runId,
            UUID leaseToken,
            InferenceAttempt attempt,
            Instant now
    ) {
        Objects.requireNonNull(attempt, "attempt");
        if (!runId.equals(attempt.runId())
                || attempt.status() != InferenceAttemptStatus.FAILED
                && attempt.status() != InferenceAttemptStatus.REJECTED) {
            throw new IllegalArgumentException(
                    "Attempt-only checkpoints require a matching FAILED or REJECTED attempt"
            );
        }
        if (lockAttemptBoundary(runId, leaseToken, attempt.stage(), now)) {
            insertAttempt(attempt);
            return finishCancellationAfterAttempt(runId, leaseToken, attempt.stage(), now);
        }
        var updated = jdbcClient.sql("""
                        update inference_run
                        set sequence = sequence + 1, updated_at = :now
                        where run_id = :runId
                          and state = 'RUNNING'
                          and stage = :stage
                          and lease_token = :leaseToken
                          and lease_expires_at > :now
                          and not cancellation_requested
                        returning sequence, stage
                        """)
                .param("now", offset(now))
                .param("runId", runId)
                .param("stage", attempt.stage().name())
                .param("leaseToken", leaseToken)
                .query((resultSet, rowNumber) -> new SequenceAndStage(
                        resultSet.getLong("sequence"),
                        InferenceStage.valueOf(resultSet.getString("stage"))
                ))
                .optional();
        if (updated.isEmpty()) throw new InferenceLeaseLostException(runId);
        insertAttempt(attempt);
        var result = updated.orElseThrow();
        insertEvent(
                runId, result.sequence(), attempt.status() == InferenceAttemptStatus.REJECTED
                        ? "PROVIDER_ATTEMPT_REJECTED" : "PROVIDER_ATTEMPT_FAILED",
                InferenceRunState.RUNNING, result.stage(),
                "{\"attemptOrdinal\":" + attempt.attemptOrdinal()
                        + ",\"outcomeCode\":\"" + attempt.outcomeCode() + "\"}",
                now
        );
        return require(runId);
    }

    @Override
    @Transactional
    public InferenceRunSnapshot checkpointAttempt(
            UUID runId,
            UUID leaseToken,
            InferenceStage expectedStage,
            InferenceStage nextStage,
            String checkpointJson,
            InferenceAttempt attempt,
            Instant now
    ) {
        Objects.requireNonNull(attempt, "attempt");
        if (!runId.equals(attempt.runId()) || expectedStage != attempt.stage()) {
            throw new IllegalArgumentException("Attempt identity and expected run stage must agree");
        }
        Objects.requireNonNull(nextStage, "nextStage");
        Objects.requireNonNull(now, "now");
        var semanticObservationRewind = expectedStage == InferenceStage.HIERARCHY
                && nextStage == InferenceStage.OBSERVE
                && attempt.status() == InferenceAttemptStatus.REJECTED
                && attempt.problemCodeCounts().equals(Map.of(
                        "VISUAL_SEMANTIC_OBSERVE_RELATIONSHIP_GROUP_MISSING", 1
                ));
        if (!expectedStage.canTransitionTo(nextStage) && !semanticObservationRewind) {
            throw new InvalidInferenceRunTransitionException(
                    runId, "stage " + expectedStage + " cannot advance to " + nextStage
            );
        }
        if (checkpointJson == null || checkpointJson.isBlank()) {
            throw new IllegalArgumentException("checkpointJson is required");
        }
        var cancellationRequested = lockAttemptBoundary(runId, leaseToken, expectedStage, now);
        insertAttempt(attempt);
        if (cancellationRequested) {
            return finishCancellationAfterAttempt(runId, leaseToken, expectedStage, now);
        }
        return applyCheckpoint(
                runId, leaseToken, expectedStage, nextStage, checkpointJson, now
        );
    }

    private boolean lockAttemptBoundary(
            UUID runId,
            UUID leaseToken,
            InferenceStage expectedStage,
            Instant now
    ) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(leaseToken, "leaseToken");
        Objects.requireNonNull(expectedStage, "expectedStage");
        Objects.requireNonNull(now, "now");
        return jdbcClient.sql("""
                        select cancellation_requested
                        from inference_run
                        where run_id = :runId
                          and state = 'RUNNING'
                          and stage = :expectedStage
                          and lease_token = :leaseToken
                          and lease_expires_at > :now
                        for update
                        """)
                .param("runId", runId)
                .param("expectedStage", expectedStage.name())
                .param("leaseToken", leaseToken)
                .param("now", offset(now))
                .query(Boolean.class)
                .optional()
                .orElseThrow(() -> new InferenceLeaseLostException(runId));
    }

    private InferenceRunSnapshot finishCancellationAfterAttempt(
            UUID runId,
            UUID leaseToken,
            InferenceStage expectedStage,
            Instant now
    ) {
        var sequence = jdbcClient.sql("""
                        update inference_run
                        set state = 'CANCELLED',
                            sequence = sequence + 1,
                            lease_owner = null,
                            lease_token = null,
                            lease_expires_at = null,
                            finished_at = :now,
                            updated_at = :now
                        where run_id = :runId
                          and state = 'RUNNING'
                          and stage = :expectedStage
                          and cancellation_requested
                          and lease_token = :leaseToken
                          and lease_expires_at > :now
                        returning sequence
                        """)
                .param("now", offset(now))
                .param("runId", runId)
                .param("expectedStage", expectedStage.name())
                .param("leaseToken", leaseToken)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new InferenceLeaseLostException(runId));
        insertEvent(
                runId, sequence, "CANCELLED", InferenceRunState.CANCELLED,
                expectedStage, "{}", now
        );
        return require(runId);
    }

    @Override
    @Transactional
    public InferenceRunSnapshot completeForReview(
            UUID runId,
            UUID leaseToken,
            String candidateJson,
            String validationProblemsJson,
            Instant now
    ) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(leaseToken, "leaseToken");
        Objects.requireNonNull(now, "now");
        if (candidateJson == null || candidateJson.isBlank()
                || validationProblemsJson == null || validationProblemsJson.isBlank()) {
            throw new IllegalArgumentException("Candidate and validation problems are required");
        }
        var sequence = jdbcClient.sql("""
                        update inference_run
                        set state = 'REVIEW_REQUIRED',
                            stage = 'USER_APPROVAL',
                            sequence = sequence + 1,
                            lease_owner = null,
                            lease_token = null,
                            lease_expires_at = null,
                            updated_at = :now
                        where run_id = :runId
                          and state = 'RUNNING'
                          and stage = 'CRITIQUE'
                          and lease_token = :leaseToken
                          and lease_expires_at > :now
                          and not cancellation_requested
                        returning sequence
                        """)
                .param("now", offset(now))
                .param("runId", runId)
                .param("leaseToken", leaseToken)
                .query(Long.class)
                .optional();
        if (sequence.isEmpty()) throw new InferenceLeaseLostException(runId);

        jdbcClient.sql("""
                        insert into inference_candidate (
                            run_id, revision, contract_version, original_json, current_json,
                            validation_problems, created_at, updated_at
                        ) values (
                            :runId, 0, :contractVersion, cast(:candidateJson as jsonb),
                            cast(:candidateJson as jsonb), cast(:validationProblemsJson as jsonb),
                            :now, :now
                        )
                        """)
                .param("runId", runId)
                .param("contractVersion", CandidateBundle.CONTRACT_VERSION)
                .param("candidateJson", candidateJson)
                .param("validationProblemsJson", validationProblemsJson)
                .param("now", offset(now))
                .update();
        insertEvent(
                runId, sequence.orElseThrow(), "REVIEW_REQUIRED",
                InferenceRunState.REVIEW_REQUIRED, InferenceStage.USER_APPROVAL,
                "{\"candidateRevision\":0}", now
        );
        return require(runId);
    }

    @Override
    public Optional<InferenceCandidateSnapshot> findCandidate(UUID runId) {
        return jdbcClient.sql("""
                        select run_id, revision, contract_version,
                               original_json::text as original_json,
                               current_json::text as current_json,
                               validation_problems::text as validation_problems,
                               final_json::text as final_json, applied_at,
                               created_at, updated_at
                        from inference_candidate
                        where run_id = :runId
                        """)
                .param("runId", runId)
                .query(PostgresInferenceRunStore::mapCandidate)
                .optional();
    }

    @Override
    @Transactional
    public InferenceCandidateSnapshot saveCandidate(
            UUID runId,
            long expectedRevision,
            String candidateJson,
            String validationProblemsJson,
            Instant now
    ) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(now, "now");
        if (expectedRevision < 0) throw new IllegalArgumentException("expectedRevision must not be negative");
        if (candidateJson == null || candidateJson.isBlank()
                || validationProblemsJson == null || validationProblemsJson.isBlank()) {
            throw new IllegalArgumentException("Candidate and validation problems are required");
        }
        var currentRevision = jdbcClient.sql("""
                        select revision from inference_candidate where run_id = :runId for update
                        """)
                .param("runId", runId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new InferenceCandidateNotFoundException(runId));
        if (currentRevision != expectedRevision) {
            throw new InferenceCandidateRevisionConflictException(runId, expectedRevision, currentRevision);
        }
        var run = lockAndRequire(runId);
        if (run.state() != InferenceRunState.REVIEW_REQUIRED || run.stage() != InferenceStage.USER_APPROVAL) {
            throw new InvalidInferenceRunTransitionException(runId, "candidate is not reviewable");
        }
        var nextRevision = expectedRevision + 1;
        jdbcClient.sql("""
                        update inference_candidate
                        set revision = :nextRevision,
                            current_json = cast(:candidateJson as jsonb),
                            validation_problems = cast(:validationProblemsJson as jsonb),
                            updated_at = :now
                        where run_id = :runId and revision = :expectedRevision
                        """)
                .param("nextRevision", nextRevision)
                .param("candidateJson", candidateJson)
                .param("validationProblemsJson", validationProblemsJson)
                .param("now", offset(now))
                .param("runId", runId)
                .param("expectedRevision", expectedRevision)
                .update();
        var sequence = jdbcClient.sql("""
                        update inference_run
                        set sequence = sequence + 1, updated_at = :now
                        where run_id = :runId
                        returning sequence
                        """)
                .param("now", offset(now))
                .param("runId", runId)
                .query(Long.class)
                .single();
        insertEvent(
                runId, sequence, "CANDIDATE_UPDATED",
                InferenceRunState.REVIEW_REQUIRED, InferenceStage.USER_APPROVAL,
                "{\"candidateRevision\":" + nextRevision + "}", now
        );
        return findCandidate(runId).orElseThrow(() -> new InferenceCandidateNotFoundException(runId));
    }

    @Override
    public List<InferenceAttempt> attempts(UUID runId) {
        return jdbcClient.sql("""
                        select run_id, attempt_ordinal, stage, status, outcome_code,
                               provider_request_id, provider_model, input_tokens, output_tokens,
                               estimated_cost_micros_cny, duration_millis,
                               problem_code_counts, completed_at
                        from inference_attempt
                        where run_id = :runId
                        order by attempt_ordinal
                        """)
                .param("runId", runId)
                .query(PostgresInferenceRunStore::mapAttempt)
                .list();
    }

    private void insertAttempt(InferenceAttempt attempt) {
        jdbcClient.sql("""
                        insert into inference_attempt (
                            run_id, attempt_ordinal, stage, status, outcome_code,
                            provider_request_id, provider_model, input_tokens, output_tokens,
                            estimated_cost_micros_cny, duration_millis,
                            problem_code_counts, completed_at
                        ) values (
                            :runId, :attemptOrdinal, :stage, :status, :outcomeCode,
                            :providerRequestId, :providerModel, :inputTokens, :outputTokens,
                            :estimatedCostMicrosCny, :durationMillis,
                            cast(:problemCodeCounts as jsonb), :completedAt
                        )
                        """)
                .param("runId", attempt.runId())
                .param("attemptOrdinal", attempt.attemptOrdinal())
                .param("stage", attempt.stage().name())
                .param("status", attempt.status().name())
                .param("outcomeCode", attempt.outcomeCode())
                .param("providerRequestId", attempt.providerRequestId().orElse(null))
                .param("providerModel", attempt.providerModel().orElse(null))
                .param("inputTokens", attempt.inputTokens())
                .param("outputTokens", attempt.outputTokens())
                .param("estimatedCostMicrosCny", attempt.estimatedCostMicrosCny())
                .param("durationMillis", attempt.durationMillis())
                .param("problemCodeCounts", ATTEMPT_PROBLEM_CODEC.write(attempt.problemCodeCounts()))
                .param("completedAt", offset(attempt.completedAt()))
                .update();
    }

    private void upsertAndVerifyArtifact(NormalizedArtifact artifact, Instant createdAt) {
        jdbcClient.sql("""
                        insert into inference_artifact (
                            artifact_id, kind, locator, media_type, byte_length,
                            width, height, deletion_pending, created_at
                        ) values (
                            :artifactId, :kind, :locator, :mediaType, :byteLength,
                            :width, :height, false, :createdAt
                        )
                        on conflict (artifact_id) do nothing
                        """)
                .param("artifactId", artifact.artifactId())
                .param("kind", artifact.kind().name())
                .param("locator", artifact.locator())
                .param("mediaType", artifact.mediaType())
                .param("byteLength", artifact.byteLength())
                .param("width", artifact.width())
                .param("height", artifact.height())
                .param("createdAt", offset(createdAt))
                .update();
        var persisted = jdbcClient.sql("""
                        select artifact_id, kind, locator, media_type, byte_length, width, height
                        from inference_artifact where artifact_id = :artifactId
                        for update
                        """)
                .param("artifactId", artifact.artifactId())
                .query(PostgresInferenceRunStore::mapArtifact)
                .single();
        if (!persisted.equals(artifact)) {
            throw new IllegalStateException("Content-addressed artifact metadata does not match " + artifact.artifactId());
        }
        jdbcClient.sql("""
                        update inference_artifact set deletion_pending = false where artifact_id = :artifactId
                        """)
                .param("artifactId", artifact.artifactId())
                .update();
    }

    private InferenceRunSnapshot lockAndRequire(UUID runId) {
        var locked = jdbcClient.sql("select run_id from inference_run where run_id = :runId for update")
                .param("runId", runId)
                .query(UUID.class)
                .optional();
        if (locked.isEmpty()) throw new InferenceRunNotFoundException(runId);
        return require(runId);
    }

    private InferenceRunSnapshot require(UUID runId) {
        return find(runId).orElseThrow(() -> new InferenceRunNotFoundException(runId));
    }

    private Optional<RunRow> findRow(UUID runId) {
        return jdbcClient.sql("""
                        select run_id, mode, state, stage, sequence, profile_id,
                               profile_snapshot::text as profile_snapshot,
                               source_reference, cost_limit_micros_cny, input_fingerprint, retry_of_run_id,
                               cancellation_requested, lease_owner, lease_token, lease_expires_at,
                               failure_code, checkpoint_json::text as checkpoint_json,
                               created_at, updated_at, finished_at
                        from inference_run
                        where run_id = :runId
                        """)
                .param("runId", runId)
                .query(PostgresInferenceRunStore::mapRunRow)
                .optional();
    }

    private List<InferenceRunInput> loadInputs(UUID runId) {
        return jdbcClient.sql("""
                        select input.input_kind, input.input_ordinal,
                               artifact.artifact_id, artifact.kind, artifact.locator,
                               artifact.media_type, artifact.byte_length, artifact.width, artifact.height
                        from inference_run_input input
                        join inference_artifact artifact on artifact.artifact_id = input.artifact_id
                        where input.run_id = :runId
                        order by case input.input_kind when 'IMAGE' then 0 else 1 end, input.input_ordinal
                        """)
                .param("runId", runId)
                .query((resultSet, rowNumber) -> new InferenceRunInput(
                        NormalizedArtifact.Kind.valueOf(resultSet.getString("input_kind")),
                        resultSet.getInt("input_ordinal"),
                        mapArtifact(resultSet, rowNumber)
                ))
                .list();
    }

    private void lockIdempotencyKey(String idempotencyKey) {
        jdbcClient.sql("select pg_advisory_xact_lock(hashtextextended(:idempotencyKey, 0))")
                .param("idempotencyKey", idempotencyKey)
                .query((resultSet, rowNumber) -> resultSet.getObject(1))
                .single();
    }

    private Optional<IdempotencyRow> findByIdempotencyKey(String idempotencyKey) {
        return jdbcClient.sql("""
                        select run_id, request_fingerprint
                        from inference_run where idempotency_key = :idempotencyKey
                        """)
                .param("idempotencyKey", idempotencyKey)
                .query((resultSet, rowNumber) -> new IdempotencyRow(
                        resultSet.getObject("run_id", UUID.class),
                        resultSet.getString("request_fingerprint")
                ))
                .optional();
    }

    private CreationResult replayOrConflict(
            IdempotencyRow existing,
            String requestFingerprint,
            String idempotencyKey
    ) {
        if (!existing.requestFingerprint().equals(requestFingerprint)) {
            throw new InferenceIdempotencyConflictException(idempotencyKey);
        }
        return new CreationResult(require(existing.runId()), false);
    }

    private void insertEvent(
            UUID runId,
            long sequence,
            String type,
            InferenceRunState state,
            InferenceStage stage,
            String dataJson,
            Instant occurredAt
    ) {
        jdbcClient.sql("""
                        insert into inference_run_event (
                            run_id, sequence, event_type, state, stage, data_json, occurred_at
                        ) values (
                            :runId, :sequence, :eventType, :state, :stage, cast(:dataJson as jsonb), :occurredAt
                        )
                        """)
                .param("runId", runId)
                .param("sequence", sequence)
                .param("eventType", type)
                .param("state", state.name())
                .param("stage", stage.name())
                .param("dataJson", dataJson)
                .param("occurredAt", offset(occurredAt))
                .update();
    }

    private static RunRow mapRunRow(ResultSet resultSet, int rowNumber) throws SQLException {
        var leaseToken = resultSet.getObject("lease_token", UUID.class);
        var lease = leaseToken == null
                ? Optional.<InferenceLease>empty()
                : Optional.of(new InferenceLease(
                        leaseToken,
                        resultSet.getString("lease_owner"),
                        instant(resultSet, "lease_expires_at").orElseThrow()
                ));
        return new RunRow(
                resultSet.getObject("run_id", UUID.class),
                InferenceMode.valueOf(resultSet.getString("mode")),
                InferenceRunState.valueOf(resultSet.getString("state")),
                InferenceStage.valueOf(resultSet.getString("stage")),
                resultSet.getLong("sequence"),
                resultSet.getString("profile_id"),
                resultSet.getString("profile_snapshot"),
                resultSet.getString("source_reference"),
                resultSet.getObject("cost_limit_micros_cny", Long.class),
                resultSet.getString("input_fingerprint"),
                Optional.ofNullable(resultSet.getObject("retry_of_run_id", UUID.class)),
                resultSet.getBoolean("cancellation_requested"),
                lease,
                Optional.ofNullable(resultSet.getString("failure_code")),
                resultSet.getString("checkpoint_json"),
                instant(resultSet, "created_at").orElseThrow(),
                instant(resultSet, "updated_at").orElseThrow(),
                instant(resultSet, "finished_at")
        );
    }

    private static NormalizedArtifact mapArtifact(ResultSet resultSet, int rowNumber) throws SQLException {
        return new NormalizedArtifact(
                resultSet.getString("artifact_id"),
                NormalizedArtifact.Kind.valueOf(resultSet.getString("kind")),
                resultSet.getString("locator"),
                resultSet.getString("media_type"),
                resultSet.getLong("byte_length"),
                resultSet.getObject("width", Integer.class),
                resultSet.getObject("height", Integer.class)
        );
    }

    private static InferenceArtifactDeletion mapDeletion(ResultSet resultSet, int rowNumber) throws SQLException {
        return new InferenceArtifactDeletion(
                resultSet.getString("artifact_id"), resultSet.getString("locator")
        );
    }

    private static InferenceRunEvent mapEvent(ResultSet resultSet, int rowNumber) throws SQLException {
        return new InferenceRunEvent(
                resultSet.getObject("run_id", UUID.class),
                resultSet.getLong("sequence"),
                resultSet.getString("event_type"),
                InferenceRunState.valueOf(resultSet.getString("state")),
                InferenceStage.valueOf(resultSet.getString("stage")),
                resultSet.getString("data_json"),
                instant(resultSet, "occurred_at").orElseThrow()
        );
    }

    private static RunSummary mapRunSummary(ResultSet resultSet, int rowNumber) throws SQLException {
        return new RunSummary(
                resultSet.getObject("run_id", UUID.class),
                resultSet.getString("mode"),
                resultSet.getString("state"),
                resultSet.getString("stage"),
                resultSet.getLong("sequence"),
                resultSet.getString("profile_id"),
                resultSet.getString("source_reference"),
                resultSet.getObject("cost_limit_micros_cny", Long.class),
                resultSet.getBoolean("cancellation_requested"),
                resultSet.getObject("retry_of_run_id", UUID.class),
                resultSet.getString("failure_code"),
                resultSet.getObject("candidate_revision", Long.class),
                instant(resultSet, "created_at").orElseThrow(),
                instant(resultSet, "updated_at").orElseThrow(),
                instant(resultSet, "finished_at").orElse(null)
        );
    }

    private static InferenceCandidateSnapshot mapCandidate(ResultSet resultSet, int rowNumber) throws SQLException {
        return new InferenceCandidateSnapshot(
                resultSet.getObject("run_id", UUID.class),
                resultSet.getLong("revision"),
                resultSet.getString("contract_version"),
                resultSet.getString("original_json"),
                resultSet.getString("current_json"),
                resultSet.getString("validation_problems"),
                Optional.ofNullable(resultSet.getString("final_json")),
                instant(resultSet, "applied_at"),
                instant(resultSet, "created_at").orElseThrow(),
                instant(resultSet, "updated_at").orElseThrow()
        );
    }

    private static InferenceAttempt mapAttempt(ResultSet resultSet, int rowNumber) throws SQLException {
        return new InferenceAttempt(
                resultSet.getObject("run_id", UUID.class),
                resultSet.getInt("attempt_ordinal"),
                InferenceStage.valueOf(resultSet.getString("stage")),
                InferenceAttemptStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("outcome_code"),
                Optional.ofNullable(resultSet.getString("provider_request_id")),
                Optional.ofNullable(resultSet.getString("provider_model")),
                resultSet.getLong("input_tokens"),
                resultSet.getLong("output_tokens"),
                resultSet.getLong("estimated_cost_micros_cny"),
                resultSet.getLong("duration_millis"),
                ATTEMPT_PROBLEM_CODEC.parse(resultSet.getString("problem_code_counts")),
                instant(resultSet, "completed_at").orElseThrow()
        );
    }

    private static Optional<Instant> instant(ResultSet resultSet, String column) throws SQLException {
        var value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? Optional.empty() : Optional.of(value.toInstant());
    }

    private static OffsetDateTime offset(Instant instant) {
        return OffsetDateTime.ofInstant(Objects.requireNonNull(instant, "instant"), java.time.ZoneOffset.UTC);
    }

    private static Instant leaseExpiry(Instant now, Duration leaseDuration) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (leaseDuration.isZero() || leaseDuration.isNegative() || leaseDuration.compareTo(Duration.ofMinutes(15)) > 0) {
            throw new IllegalArgumentException("leaseDuration must be positive and no longer than 15 minutes");
        }
        return now.plus(leaseDuration);
    }

    private static String requireWorkerId(String workerId) {
        if (workerId == null || workerId.isBlank() || workerId.length() > 128) {
            throw new IllegalArgumentException("workerId must contain 1..128 characters");
        }
        return workerId;
    }

    private static String requireFailureCode(String failureCode) {
        if (failureCode == null || !failureCode.matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw new IllegalArgumentException("failureCode must be a stable uppercase code");
        }
        return failureCode;
    }

    private static String initialCheckpoint(String inputFingerprint) {
        return "{\"completedStage\":\"NORMALIZE\",\"inputFingerprint\":\"" + inputFingerprint + "\"}";
    }

    private static String retryCheckpoint(UUID sourceRunId) {
        return "{\"completedStage\":\"NORMALIZE\",\"retryOfRunId\":\"" + sourceRunId + "\"}";
    }

    private static String retryFingerprint(UUID sourceRunId) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    ("renderweave-retry/1\u0000" + sourceRunId).getBytes(StandardCharsets.UTF_8)
            ));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JVM", impossible);
        }
    }

    private record ClaimCandidate(UUID runId, InferenceRunState state) { }

    private record SequenceAndStage(long sequence, InferenceStage stage) { }

    private record IdempotencyRow(UUID runId, String requestFingerprint) { }

    private record RunRow(
            UUID runId,
            InferenceMode mode,
            InferenceRunState state,
            InferenceStage stage,
            long sequence,
            String profileId,
            String profileSnapshotJson,
            String sourceReference,
            Long costLimitMicrosCny,
            String inputFingerprint,
            Optional<UUID> retryOfRunId,
            boolean cancellationRequested,
            Optional<InferenceLease> lease,
            Optional<String> failureCode,
            String checkpointJson,
            Instant createdAt,
            Instant updatedAt,
            Optional<Instant> finishedAt
    ) {
        InferenceRunSnapshot toSnapshot(List<InferenceRunInput> inputs) {
            return new InferenceRunSnapshot(
                    runId, mode, state, stage, sequence, profileId, profileSnapshotJson,
                    sourceReference, costLimitMicrosCny, inputFingerprint, retryOfRunId,
                    cancellationRequested,
                    lease, failureCode, checkpointJson, createdAt, updatedAt, finishedAt, inputs
            );
        }
    }
}
