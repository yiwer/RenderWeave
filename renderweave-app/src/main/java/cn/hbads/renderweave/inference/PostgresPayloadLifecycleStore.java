package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.admission.NewLiveInferenceRun;
import cn.hbads.renderweave.inference.input.BlobStore;
import cn.hbads.renderweave.inference.retention.PayloadAccess;
import cn.hbads.renderweave.inference.retention.PayloadAccessGuard;
import cn.hbads.renderweave.inference.retention.PayloadDeletionReason;
import cn.hbads.renderweave.inference.retention.PayloadLifecycleException;
import cn.hbads.renderweave.inference.retention.PayloadLifecycleReadiness;
import cn.hbads.renderweave.inference.run.InferenceRunNotFoundException;
import cn.hbads.renderweave.inference.run.InferenceRunState;
import cn.hbads.renderweave.inference.run.InferenceStage;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL authority for immutable payload grants/tombstones and the retryable physical erasure queue.
 * All persisted values are payload-free identities, timestamps, counters and closed reason codes.
 */
@Repository
public class PostgresPayloadLifecycleStore implements PayloadAccessGuard, PayloadLifecycleReadiness {
    static final Duration MAX_RETENTION = Duration.ofDays(7);
    static final Duration MINIMUM_REUSE_REMAINING = Duration.ofHours(24);
    static final Duration DELETE_SLO = Duration.ofHours(24);
    private static final Duration DELETE_LEASE = Duration.ofMinutes(5);

    private final JdbcClient jdbcClient;
    private final BlobStore blobStore;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public PostgresPayloadLifecycleStore(
            JdbcClient jdbcClient,
            BlobStore blobStore,
            Clock inferenceClock,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
        this.blobStore = Objects.requireNonNull(blobStore, "blobStore");
        this.clock = Objects.requireNonNull(inferenceClock, "inferenceClock");
        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager")
        );
    }

    @Override
    public void require(UUID runId, PayloadAccess access) {
        requireAt(runId, access, clock.instant());
    }

    void requireAt(UUID runId, PayloadAccess access, Instant now) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(now, "now");
        if (!isManaged(runId)) return;
        if (hasTombstone(runId)) {
            throw problem("LIVE_PAYLOAD_DELETED", "The live payload has been deleted.");
        }

        var expected = jdbcClient.sql("""
                        select artifact_count from live_input_manifest where run_id = :runId
                        """)
                .param("runId", runId)
                .query(Integer.class)
                .optional()
                .orElseThrow(() -> problem(
                        "LIVE_PAYLOAD_RETENTION_UNAVAILABLE",
                        "The live payload retention grant is incomplete."
                ));
        var retained = jdbcClient.sql("""
                        select count(*) from inference_payload_retention where run_id = :runId
                        """)
                .param("runId", runId)
                .query(Long.class)
                .single();
        if (retained != expected.longValue()) {
            throw problem(
                    "LIVE_PAYLOAD_RETENTION_UNAVAILABLE",
                    "The live payload retention grant is incomplete."
            );
        }
        var physicallyDeleted = jdbcClient.sql("""
                        select exists (
                            select 1
                            from inference_payload_retention retention
                            join inference_artifact artifact on artifact.artifact_id = retention.artifact_id
                            where retention.run_id = :runId and artifact.payload_deleted_at is not null
                        )
                        """)
                .param("runId", runId)
                .query(Boolean.class)
                .single();
        if (physicallyDeleted) {
            throw problem("LIVE_PAYLOAD_DELETED", "The live payload has been deleted.");
        }
        var expiresAt = earliestExpiry(runId).orElseThrow(() -> problem(
                "LIVE_PAYLOAD_RETENTION_UNAVAILABLE",
                "The live payload retention grant is incomplete."
        ));
        if (!now.isBefore(expiresAt)) {
            throw problem("LIVE_PAYLOAD_EXPIRED", "The live payload retention period has expired.");
        }
        if (access == PayloadAccess.RETRY) {
            if (Duration.between(now, expiresAt).compareTo(MINIMUM_REUSE_REMAINING) < 0) {
                throw problem(
                        "LIVE_PAYLOAD_REUPLOAD_REQUIRED",
                        "Less than 24 hours of payload retention remain; upload the input again."
                );
            }
            throw problem(
                    "LIVE_RETRY_REQUIRES_FRESH_CONFIRMATION",
                    "A live retry requires a new run and fresh external-transfer confirmation."
            );
        }
    }

    @Override
    public Snapshot snapshot() {
        var now = clock.instant();
        var oldest = jdbcClient.sql("""
                        select min(delete_deadline_at)
                        from payload_artifact_deletion_task
                        where state in ('PENDING', 'IN_PROGRESS')
                          and delete_deadline_at <= :now
                        """)
                .param("now", offset(now))
                .query(OffsetDateTime.class)
                .optional()
                .map(OffsetDateTime::toInstant);
        return oldest.<Snapshot>map(value -> new Snapshot(
                false, "PAYLOAD_DELETION_UNHEALTHY", value
        )).orElseGet(() -> new Snapshot(true, null, null));
    }

    public boolean isManaged(UUID runId) {
        Objects.requireNonNull(runId, "runId");
        return jdbcClient.sql("""
                        select exists (
                            select 1 from external_transfer_confirmation where run_id = :runId
                        )
                        """)
                .param("runId", runId)
                .query(Boolean.class)
                .single();
    }

    /** Joins the admission transaction; a response-loss replay never inserts or extends a grant. */
    public void registerFreshAdmission(NewLiveInferenceRun command) {
        Objects.requireNonNull(command, "command");
        var confirmedAt = command.confirmation().confirmedAt();
        var configuredRetention = Duration.ofSeconds(command.notice().localPayloadRetentionSeconds());
        if (configuredRetention.isZero() || configuredRetention.isNegative()
                || configuredRetention.compareTo(MAX_RETENTION) > 0) {
            throw new IllegalArgumentException("Live payload retention must be positive and at most seven days");
        }
        var artifactIds = command.manifest().items().stream()
                .map(item -> item.artifactSha256())
                .distinct()
                .sorted()
                .toList();
        for (var artifactId : artifactIds) {
            lockArtifact(artifactId);
            var freshUpload = activeIngestLease(artifactId, confirmedAt);
            var shared = freshUpload.isEmpty()
                    ? activeGrant(artifactId, confirmedAt) : Optional.<RetentionOrigin>empty();
            var origin = freshUpload
                    .map(observedAt -> new RetentionOrigin(
                            command.run().runId(), observedAt, observedAt.plus(configuredRetention)
                    ))
                    .or(() -> shared)
                    .orElseGet(() -> new RetentionOrigin(
                            command.run().runId(), confirmedAt, confirmedAt.plus(configuredRetention)
                    ));
            jdbcClient.sql("""
                            insert into inference_payload_retention (
                                run_id, artifact_id, origin_run_id,
                                first_uploaded_at, payload_expires_at, created_at
                            ) values (
                                :runId, :artifactId, :originRunId,
                                :firstUploadedAt, :payloadExpiresAt, :createdAt
                            )
                            """)
                    .param("runId", command.run().runId())
                    .param("artifactId", artifactId)
                    .param("originRunId", origin.runId())
                    .param("firstUploadedAt", offset(origin.firstUploadedAt()))
                    .param("payloadExpiresAt", offset(origin.payloadExpiresAt()))
                    .param("createdAt", offset(confirmedAt))
                    .update();
            clearIngestLease(artifactId);
            jdbcClient.sql("""
                            update inference_artifact
                            set payload_deleted_at = null, deletion_pending = false
                            where artifact_id = :artifactId
                            """)
                    .param("artifactId", artifactId)
                    .update();
            supersedeTask(artifactId, confirmedAt);
        }
    }

    /** Clears the normalization lease created by an idempotent replay without changing retention. */
    public void finishAdmissionReplay(UUID originalRunId, List<String> artifactIds, Instant now) {
        Objects.requireNonNull(originalRunId, "originalRunId");
        Objects.requireNonNull(artifactIds, "artifactIds");
        Objects.requireNonNull(now, "now");
        var tombstone = tombstone(originalRunId);
        for (var artifactId : artifactIds.stream().distinct().sorted().toList()) {
            lockArtifact(artifactId);
            clearIngestLease(artifactId);
            tombstone.ifPresent(value -> scheduleDeletion(
                    artifactId, value.tombstonedAt(), value.deleteDeadlineAt()
            ));
        }
    }

    public TombstoneResult tombstone(UUID runId, PayloadDeletionReason reason) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(reason, "reason");
        return Objects.requireNonNull(transactions.execute(
                status -> tombstoneLocked(runId, reason, clock.instant())
        ));
    }

    public TombstoneResult tombstoneCompleted(UUID runId, Instant now) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(now, "now");
        return Objects.requireNonNull(transactions.execute(
                status -> tombstoneLocked(runId, PayloadDeletionReason.COMPLETED, now)
        ));
    }

    /** Must be called after the apply transaction has locked the run row. */
    public void requireForApplyLocked(UUID runId, Instant now) {
        requireAt(runId, PayloadAccess.APPLY, now);
    }

    public int sweepDueRuns(int limit) {
        requireLimit(limit);
        var now = clock.instant();
        var runIds = jdbcClient.sql("""
                        select run.run_id
                        from inference_run run
                        join external_transfer_confirmation confirmation on confirmation.run_id = run.run_id
                        where not exists (
                            select 1 from payload_deletion_tombstone tombstone
                            where tombstone.run_id = run.run_id
                        )
                          and run.state <> 'APPLYING'
                          and (
                              run.state = 'COMPLETED'
                              or exists (
                                  select 1 from inference_payload_retention retention
                                  where retention.run_id = run.run_id
                                    and retention.payload_expires_at <= :now
                              )
                              or (run.state in ('FAILED', 'CANCELLED')
                                  and run.finished_at + interval '24 hours' <= :now)
                          )
                        order by coalesce(run.finished_at, run.updated_at), run.run_id
                        fetch first :limit rows only
                        """)
                .param("now", offset(now))
                .param("limit", limit)
                .query(UUID.class)
                .list();
        var swept = 0;
        for (var runId : runIds) {
            var reason = dueReason(runId, now);
            if (reason.isPresent() && tombstone(runId, reason.orElseThrow()).created()) swept++;
        }
        return swept;
    }

    public int sweepExpiredIngestLeases(int limit) {
        requireLimit(limit);
        var now = clock.instant();
        var artifactIds = jdbcClient.sql("""
                        select artifact_id
                        from inference_artifact_ingest_lease
                        where expires_at <= :now
                        order by expires_at, artifact_id
                        fetch first :limit rows only
                        """)
                .param("now", offset(now))
                .param("limit", limit)
                .query(String.class)
                .list();
        var swept = 0;
        for (var artifactId : artifactIds) {
            var scheduled = transactions.execute(status -> {
                lockArtifact(artifactId);
                var lease = jdbcClient.sql("""
                                select observed_at, expires_at
                                from inference_artifact_ingest_lease
                                where artifact_id = :artifactId and expires_at <= :now
                                """)
                        .param("artifactId", artifactId)
                        .param("now", offset(now))
                        .query((resultSet, rowNumber) -> new IngestLease(
                                resultSet.getObject("observed_at", OffsetDateTime.class).toInstant(),
                                resultSet.getObject("expires_at", OffsetDateTime.class).toInstant()
                        ))
                        .optional();
                if (lease.isEmpty()) return false;
                if (!hasAnyReference(artifactId)) {
                    var value = lease.orElseThrow();
                    scheduleDeletion(
                            artifactId, value.expiresAt(), value.expiresAt().plus(DELETE_SLO)
                    );
                }
                clearIngestLease(artifactId);
                return true;
            });
            if (Boolean.TRUE.equals(scheduled)) swept++;
        }
        return swept;
    }

    public DeletionDrainResult drainDeletionTasks(int limit) {
        requireLimit(limit);
        var claimed = 0;
        var deleted = 0;
        var deferred = 0;
        var failed = 0;
        for (var index = 0; index < limit; index++) {
            var task = claimTask();
            if (task.isEmpty()) break;
            claimed++;
            var value = task.orElseThrow();
            try {
                var outcome = executeTask(value);
                if (outcome == DeletionOutcome.DELETED) deleted++;
                else deferred++;
            } catch (RuntimeException failure) {
                recordFailure(value);
                failed++;
            }
        }
        return new DeletionDrainResult(claimed, deleted, deferred, failed);
    }

    private TombstoneResult tombstoneLocked(
            UUID runId,
            PayloadDeletionReason reason,
            Instant now
    ) {
        var run = lockRun(runId);
        if (!isManaged(runId)) {
            throw problem(
                    "LIVE_PAYLOAD_RETENTION_UNAVAILABLE",
                    "Only a confirmed live run has a managed payload lifecycle."
            );
        }
        if (run.state() == InferenceRunState.APPLYING) {
            throw problem(
                    "LIVE_PAYLOAD_APPLY_IN_PROGRESS",
                    "Payload deletion cannot interrupt the atomic apply transaction."
            );
        }
        if (reason == PayloadDeletionReason.COMPLETED
                && run.state() != InferenceRunState.COMPLETED) {
            throw new IllegalStateException("Only a completed run may use the COMPLETED deletion reason");
        }
        var inserted = jdbcClient.sql("""
                        insert into payload_deletion_tombstone (
                            run_id, reason, tombstoned_at, delete_deadline_at
                        ) values (:runId, :reason, :now, :deadline)
                        on conflict (run_id) do nothing
                        """)
                .param("runId", runId)
                .param("reason", reason.name())
                .param("now", offset(now))
                .param("deadline", offset(now.plus(DELETE_SLO)))
                .update() == 1;

        if (inserted && !run.state().terminal()) {
            var failureCode = reason == PayloadDeletionReason.PAYLOAD_EXPIRED
                    && run.state() == InferenceRunState.REVIEW_REQUIRED
                    ? "LIVE_REVIEW_EXPIRED"
                    : reason == PayloadDeletionReason.USER_REQUESTED
                    ? "LIVE_PAYLOAD_DELETED"
                    : "LIVE_PAYLOAD_EXPIRED";
            var target = reason == PayloadDeletionReason.USER_REQUESTED
                    ? InferenceRunState.CANCELLED : InferenceRunState.FAILED;
            var sequence = jdbcClient.sql("""
                            update inference_run
                            set state = :state,
                                sequence = sequence + 1,
                                cancellation_requested = case when :cancelled then true else cancellation_requested end,
                                lease_owner = null,
                                lease_token = null,
                                lease_expires_at = null,
                                failure_code = :failureCode,
                                finished_at = :now,
                                updated_at = :now
                            where run_id = :runId
                            returning sequence
                            """)
                    .param("state", target.name())
                    .param("cancelled", target == InferenceRunState.CANCELLED)
                    .param("failureCode", failureCode)
                    .param("now", offset(now))
                    .param("runId", runId)
                    .query(Long.class)
                    .single();
            insertRunEvent(runId, sequence, target, run.stage(), failureCode, now);
        }

        var persisted = tombstone(runId).orElseThrow();
        for (var artifactId : retentionArtifacts(runId)) {
            lockArtifact(artifactId);
            scheduleDeletion(
                    artifactId, persisted.tombstonedAt(), persisted.deleteDeadlineAt()
            );
        }
        return new TombstoneResult(
                runId, persisted.reason(), persisted.tombstonedAt(),
                persisted.deleteDeadlineAt(), inserted
        );
    }

    private Optional<PayloadDeletionReason> dueReason(UUID runId, Instant now) {
        var row = jdbcClient.sql("""
                        select state, finished_at,
                               (select min(payload_expires_at)
                                from inference_payload_retention retention
                                where retention.run_id = run.run_id) as payload_expires_at
                        from inference_run run where run_id = :runId
                        """)
                .param("runId", runId)
                .query((resultSet, rowNumber) -> new DueRow(
                        InferenceRunState.valueOf(resultSet.getString("state")),
                        instant(resultSet.getObject("finished_at", OffsetDateTime.class)),
                        instant(resultSet.getObject("payload_expires_at", OffsetDateTime.class))
                ))
                .optional();
        if (row.isEmpty()) return Optional.empty();
        var value = row.orElseThrow();
        if (value.state() == InferenceRunState.COMPLETED) {
            return Optional.of(PayloadDeletionReason.COMPLETED);
        }
        if (value.payloadExpiresAt() != null && !now.isBefore(value.payloadExpiresAt())) {
            return Optional.of(PayloadDeletionReason.PAYLOAD_EXPIRED);
        }
        if ((value.state() == InferenceRunState.FAILED || value.state() == InferenceRunState.CANCELLED)
                && value.finishedAt() != null
                && !now.isBefore(value.finishedAt().plus(MINIMUM_REUSE_REMAINING))) {
            return Optional.of(PayloadDeletionReason.TERMINAL_RETENTION_ELAPSED);
        }
        return Optional.empty();
    }

    private Optional<ClaimedTask> claimTask() {
        var now = clock.instant();
        return Objects.requireNonNull(transactions.execute(status -> {
            var artifactId = jdbcClient.sql("""
                            select artifact_id
                            from payload_artifact_deletion_task
                            where (state = 'PENDING' and next_attempt_at <= :now)
                               or (state = 'IN_PROGRESS' and lease_expires_at <= :now)
                            order by next_attempt_at, scheduled_at, artifact_id
                            for update skip locked
                            fetch first 1 row only
                            """)
                    .param("now", offset(now))
                    .query(String.class)
                    .optional();
            if (artifactId.isEmpty()) return Optional.<ClaimedTask>empty();
            var token = UUID.randomUUID();
            return Optional.of(jdbcClient.sql("""
                            update payload_artifact_deletion_task
                            set state = 'IN_PROGRESS',
                                attempts = attempts + 1,
                                lease_token = :leaseToken,
                                lease_expires_at = :leaseExpiresAt,
                                completed_at = null,
                                updated_at = :now
                            where artifact_id = :artifactId
                            returning artifact_id, attempts
                            """)
                    .param("leaseToken", token)
                    .param("leaseExpiresAt", offset(now.plus(DELETE_LEASE)))
                    .param("now", offset(now))
                    .param("artifactId", artifactId.orElseThrow())
                    .query((resultSet, rowNumber) -> new ClaimedTask(
                            resultSet.getString("artifact_id"), token, resultSet.getInt("attempts")
                    ))
                    .single());
        }));
    }

    private DeletionOutcome executeTask(ClaimedTask task) {
        var now = clock.instant();
        return Objects.requireNonNull(transactions.execute(status -> {
            lockArtifact(task.artifactId());
            if (!ownsTask(task)) return DeletionOutcome.SUPERSEDED;
            var ingestLeaseExpiry = activeIngestLeaseExpiry(task.artifactId(), now);
            if (ingestLeaseExpiry.isPresent()) {
                deferTask(task, ingestLeaseExpiry.orElseThrow(), now);
                return DeletionOutcome.DEFERRED;
            }
            if (hasActiveGrant(task.artifactId(), now) || hasUnmanagedReference(task.artifactId())) {
                completeTask(task, "SUPERSEDED", now);
                return DeletionOutcome.SUPERSEDED;
            }

            blobStore.delete(task.artifactId());
            jdbcClient.sql("""
                            update inference_artifact
                            set payload_deleted_at = :now, deletion_pending = false
                            where artifact_id = :artifactId
                            """)
                    .param("now", offset(now))
                    .param("artifactId", task.artifactId())
                    .update();
            clearIngestLease(task.artifactId());
            completeTask(task, "DELETED", now);
            return DeletionOutcome.DELETED;
        }));
    }

    private void recordFailure(ClaimedTask task) {
        var now = clock.instant();
        var exponent = Math.min(Math.max(task.attempts() - 1, 0), 6);
        var delay = Duration.ofMinutes(1L << exponent);
        transactions.executeWithoutResult(status -> jdbcClient.sql("""
                        update payload_artifact_deletion_task
                        set state = 'PENDING',
                            next_attempt_at = :nextAttemptAt,
                            lease_token = null,
                            lease_expires_at = null,
                            last_failure_code = 'PAYLOAD_DELETE_FAILED',
                            completed_at = null,
                            updated_at = :now
                        where artifact_id = :artifactId
                          and state = 'IN_PROGRESS'
                          and lease_token = :leaseToken
                        """)
                .param("nextAttemptAt", offset(now.plus(delay)))
                .param("now", offset(now))
                .param("artifactId", task.artifactId())
                .param("leaseToken", task.leaseToken())
                .update());
    }

    private RunRow lockRun(UUID runId) {
        return jdbcClient.sql("""
                        select state, stage, sequence
                        from inference_run where run_id = :runId for update
                        """)
                .param("runId", runId)
                .query((resultSet, rowNumber) -> new RunRow(
                        InferenceRunState.valueOf(resultSet.getString("state")),
                        InferenceStage.valueOf(resultSet.getString("stage")),
                        resultSet.getLong("sequence")
                ))
                .optional()
                .orElseThrow(() -> new InferenceRunNotFoundException(runId));
    }

    private void insertRunEvent(
            UUID runId,
            long sequence,
            InferenceRunState state,
            InferenceStage stage,
            String failureCode,
            Instant now
    ) {
        jdbcClient.sql("""
                        insert into inference_run_event (
                            run_id, sequence, event_type, state, stage, data_json, occurred_at
                        ) values (
                            :runId, :sequence, 'PAYLOAD_TOMBSTONED', :state, :stage,
                            cast(:dataJson as jsonb), :now
                        )
                        """)
                .param("runId", runId)
                .param("sequence", sequence)
                .param("state", state.name())
                .param("stage", stage.name())
                .param("dataJson", "{\"reasonCode\":\"" + failureCode + "\"}")
                .param("now", offset(now))
                .update();
    }

    private List<String> retentionArtifacts(UUID runId) {
        return jdbcClient.sql("""
                        select artifact_id from inference_payload_retention
                        where run_id = :runId order by artifact_id
                        """)
                .param("runId", runId)
                .query(String.class)
                .list();
    }

    private void scheduleDeletion(String artifactId, Instant scheduledAt, Instant deadline) {
        jdbcClient.sql("""
                        insert into payload_artifact_deletion_task (
                            artifact_id, state, scheduled_at, delete_deadline_at,
                            attempts, next_attempt_at, updated_at
                        ) values (
                            :artifactId, 'PENDING', :scheduledAt, :deadline,
                            0, :scheduledAt, :scheduledAt
                        )
                        on conflict (artifact_id) do update
                        set state = 'PENDING',
                            scheduled_at = case
                                when payload_artifact_deletion_task.state in ('DELETED', 'SUPERSEDED')
                                    then excluded.scheduled_at
                                else least(payload_artifact_deletion_task.scheduled_at,
                                           excluded.scheduled_at)
                            end,
                            delete_deadline_at = case
                                when payload_artifact_deletion_task.state in ('DELETED', 'SUPERSEDED')
                                    then excluded.delete_deadline_at
                                else least(payload_artifact_deletion_task.delete_deadline_at,
                                           excluded.delete_deadline_at)
                            end,
                            attempts = case
                                when payload_artifact_deletion_task.state in ('DELETED', 'SUPERSEDED')
                                    then 0
                                else payload_artifact_deletion_task.attempts
                            end,
                            next_attempt_at = case
                                when payload_artifact_deletion_task.state in ('DELETED', 'SUPERSEDED')
                                    then excluded.next_attempt_at
                                else least(payload_artifact_deletion_task.next_attempt_at,
                                           excluded.next_attempt_at)
                            end,
                            lease_token = null,
                            lease_expires_at = null,
                            last_failure_code = null,
                            completed_at = null,
                            updated_at = excluded.updated_at
                        """)
                .param("artifactId", artifactId)
                .param("scheduledAt", offset(scheduledAt))
                .param("deadline", offset(deadline))
                .update();
    }

    private void supersedeTask(String artifactId, Instant now) {
        jdbcClient.sql("""
                        update payload_artifact_deletion_task
                        set state = 'SUPERSEDED',
                            lease_token = null,
                            lease_expires_at = null,
                            completed_at = :now,
                            updated_at = :now
                        where artifact_id = :artifactId
                          and state in ('PENDING', 'IN_PROGRESS')
                        """)
                .param("artifactId", artifactId)
                .param("now", offset(now))
                .update();
    }

    private void completeTask(ClaimedTask task, String state, Instant now) {
        var updated = jdbcClient.sql("""
                        update payload_artifact_deletion_task
                        set state = :state,
                            lease_token = null,
                            lease_expires_at = null,
                            completed_at = :now,
                            updated_at = :now
                        where artifact_id = :artifactId
                          and state = 'IN_PROGRESS'
                          and lease_token = :leaseToken
                        """)
                .param("state", state)
                .param("now", offset(now))
                .param("artifactId", task.artifactId())
                .param("leaseToken", task.leaseToken())
                .update();
        if (updated != 1) throw new IllegalStateException("Payload deletion task lease was lost");
    }

    private void deferTask(ClaimedTask task, Instant nextAttemptAt, Instant now) {
        var updated = jdbcClient.sql("""
                        update payload_artifact_deletion_task
                        set state = 'PENDING',
                            next_attempt_at = :nextAttemptAt,
                            lease_token = null,
                            lease_expires_at = null,
                            completed_at = null,
                            updated_at = :now
                        where artifact_id = :artifactId
                          and state = 'IN_PROGRESS'
                          and lease_token = :leaseToken
                        """)
                .param("nextAttemptAt", offset(nextAttemptAt))
                .param("now", offset(now))
                .param("artifactId", task.artifactId())
                .param("leaseToken", task.leaseToken())
                .update();
        if (updated != 1) throw new IllegalStateException("Payload deletion task lease was lost");
    }

    private boolean ownsTask(ClaimedTask task) {
        return jdbcClient.sql("""
                        select exists (
                            select 1 from payload_artifact_deletion_task
                            where artifact_id = :artifactId
                              and state = 'IN_PROGRESS'
                              and lease_token = :leaseToken
                        )
                        """)
                .param("artifactId", task.artifactId())
                .param("leaseToken", task.leaseToken())
                .query(Boolean.class)
                .single();
    }

    private boolean hasActiveGrant(String artifactId, Instant now) {
        return jdbcClient.sql("""
                        select exists (
                            select 1
                            from inference_payload_retention retention
                            left join payload_deletion_tombstone tombstone
                              on tombstone.run_id = retention.run_id
                            where retention.artifact_id = :artifactId
                              and retention.payload_expires_at > :now
                              and tombstone.run_id is null
                        )
                        """)
                .param("artifactId", artifactId)
                .param("now", offset(now))
                .query(Boolean.class)
                .single();
    }

    private boolean hasUnmanagedReference(String artifactId) {
        return jdbcClient.sql("""
                        select exists (
                            select 1 from inference_run_input input
                            where input.artifact_id = :artifactId
                              and not exists (
                                  select 1 from inference_payload_retention retention
                                  where retention.run_id = input.run_id
                                    and retention.artifact_id = input.artifact_id
                              )
                        )
                        """)
                .param("artifactId", artifactId)
                .query(Boolean.class)
                .single();
    }

    private boolean hasAnyReference(String artifactId) {
        return jdbcClient.sql("""
                        select exists (
                            select 1 from inference_run_input where artifact_id = :artifactId
                        )
                        """)
                .param("artifactId", artifactId)
                .query(Boolean.class)
                .single();
    }

    private Optional<Instant> activeIngestLease(String artifactId, Instant now) {
        return jdbcClient.sql("""
                        select observed_at from inference_artifact_ingest_lease
                        where artifact_id = :artifactId
                          and observed_at <= :now
                          and expires_at > :now
                        """)
                .param("artifactId", artifactId)
                .param("now", offset(now))
                .query(OffsetDateTime.class)
                .optional()
                .map(OffsetDateTime::toInstant);
    }

    private Optional<Instant> activeIngestLeaseExpiry(String artifactId, Instant now) {
        return jdbcClient.sql("""
                        select expires_at from inference_artifact_ingest_lease
                        where artifact_id = :artifactId and expires_at > :now
                        """)
                .param("artifactId", artifactId)
                .param("now", offset(now))
                .query(OffsetDateTime.class)
                .optional()
                .map(OffsetDateTime::toInstant);
    }

    private Optional<RetentionOrigin> activeGrant(String artifactId, Instant now) {
        return jdbcClient.sql("""
                        select retention.origin_run_id,
                               retention.first_uploaded_at,
                               retention.payload_expires_at
                        from inference_payload_retention retention
                        left join payload_deletion_tombstone tombstone
                          on tombstone.run_id = retention.run_id
                        where retention.artifact_id = :artifactId
                          and retention.payload_expires_at > :now
                          and tombstone.run_id is null
                        order by retention.first_uploaded_at, retention.run_id
                        fetch first 1 row only
                        """)
                .param("artifactId", artifactId)
                .param("now", offset(now))
                .query((resultSet, rowNumber) -> new RetentionOrigin(
                        resultSet.getObject("origin_run_id", UUID.class),
                        resultSet.getObject("first_uploaded_at", OffsetDateTime.class).toInstant(),
                        resultSet.getObject("payload_expires_at", OffsetDateTime.class).toInstant()
                ))
                .optional();
    }

    private Optional<Instant> earliestExpiry(UUID runId) {
        return jdbcClient.sql("""
                        select min(payload_expires_at)
                        from inference_payload_retention where run_id = :runId
                        """)
                .param("runId", runId)
                .query(OffsetDateTime.class)
                .optional()
                .map(OffsetDateTime::toInstant);
    }

    private boolean hasTombstone(UUID runId) {
        return tombstone(runId).isPresent();
    }

    private Optional<TombstoneRow> tombstone(UUID runId) {
        return jdbcClient.sql("""
                        select reason, tombstoned_at, delete_deadline_at
                        from payload_deletion_tombstone where run_id = :runId
                        """)
                .param("runId", runId)
                .query((resultSet, rowNumber) -> new TombstoneRow(
                        PayloadDeletionReason.valueOf(resultSet.getString("reason")),
                        resultSet.getObject("tombstoned_at", OffsetDateTime.class).toInstant(),
                        resultSet.getObject("delete_deadline_at", OffsetDateTime.class).toInstant()
                ))
                .optional();
    }

    private void clearIngestLease(String artifactId) {
        jdbcClient.sql("delete from inference_artifact_ingest_lease where artifact_id = :artifactId")
                .param("artifactId", artifactId)
                .update();
    }

    private void lockArtifact(String artifactId) {
        jdbcClient.sql("select pg_advisory_xact_lock(hashtextextended(:identity, 0))")
                .param("identity", "artifact-envelope:" + artifactId)
                .query((resultSet, rowNumber) -> resultSet.getObject(1))
                .single();
    }

    private static void requireLimit(int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be 1..1000");
    }

    private static PayloadLifecycleException problem(String code, String message) {
        return new PayloadLifecycleException(code, message);
    }

    private static OffsetDateTime offset(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    public record TombstoneResult(
            UUID runId,
            PayloadDeletionReason reason,
            Instant tombstonedAt,
            Instant deleteDeadlineAt,
            boolean created
    ) { }

    public record DeletionDrainResult(int claimed, int deleted, int deferred, int failed) {
        public DeletionDrainResult {
            if (claimed < 0 || deleted < 0 || deferred < 0 || failed < 0
                    || claimed != deleted + deferred + failed) {
                throw new IllegalArgumentException("Invalid payload deletion drain counters");
            }
        }
    }

    private enum DeletionOutcome { DELETED, DEFERRED, SUPERSEDED }

    private record RetentionOrigin(UUID runId, Instant firstUploadedAt, Instant payloadExpiresAt) { }

    private record TombstoneRow(
            PayloadDeletionReason reason,
            Instant tombstonedAt,
            Instant deleteDeadlineAt
    ) { }

    private record RunRow(InferenceRunState state, InferenceStage stage, long sequence) { }

    private record DueRow(InferenceRunState state, Instant finishedAt, Instant payloadExpiresAt) { }

    private record IngestLease(Instant observedAt, Instant expiresAt) { }

    private record ClaimedTask(String artifactId, UUID leaseToken, int attempts) { }
}
