package cn.hbads.renderweave.inference;

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
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PostgresInferenceRunStore implements InferenceRunStore {
    private static final int MAX_EVENT_PAGE = 1000;

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
                            replay_fixture_id, retry_of_run_id, checkpoint_json,
                            created_at, updated_at
                        ) values (
                            :runId, :idempotencyKey, :requestFingerprint, :inputFingerprint,
                            :mode, 'QUEUED', 'OBSERVE', 1, :profileId, cast(:profileSnapshot as jsonb),
                            :replayFixtureId, :retryOfRunId, cast(:checkpointJson as jsonb),
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
                .param("replayFixtureId", command.normalizedInput().replayFixtureId())
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
    @Transactional
    public Optional<InferenceRunSnapshot> claimNext(String workerId, Instant now, Duration leaseDuration) {
        workerId = requireWorkerId(workerId);
        var expiresAt = leaseExpiry(now, leaseDuration);
        var candidate = jdbcClient.sql("""
                        select run_id, state
                        from inference_run
                        where state = 'QUEUED'
                           or (state = 'RUNNING' and lease_expires_at <= :now)
                        order by created_at, run_id
                        for update skip locked
                        fetch first 1 row only
                        """)
                .param("now", offset(now))
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
                            replay_fixture_id, retry_of_run_id, checkpoint_json,
                            created_at, updated_at
                        )
                        select :newRunId, :idempotencyKey, :requestFingerprint, input_fingerprint,
                               mode, 'QUEUED', 'OBSERVE', 1, profile_id, profile_snapshot,
                               replay_fixture_id, run_id, cast(:checkpointJson as jsonb),
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
                               replay_fixture_id, input_fingerprint, retry_of_run_id,
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
                resultSet.getString("replay_fixture_id"),
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
            String replayFixtureId,
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
                    replayFixtureId, inputFingerprint, retryOfRunId, cancellationRequested,
                    lease, failureCode, checkpointJson, createdAt, updatedAt, finishedAt, inputs
            );
        }
    }
}
