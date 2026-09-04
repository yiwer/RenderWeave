package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.certification.AuthorizationStatus;
import cn.hbads.renderweave.inference.certification.AuthorizedCertificationCase;
import cn.hbads.renderweave.inference.certification.CertificationProviderCallPermit;
import cn.hbads.renderweave.inference.certification.CertificationStage;
import cn.hbads.renderweave.inference.certification.CertificationStageExecutionStore;
import cn.hbads.renderweave.inference.certification.CertificationStageLedgerSnapshot;
import cn.hbads.renderweave.inference.certification.CertificationStageLedgerStatus;
import cn.hbads.renderweave.inference.certification.CertificationStageLedgerViolation;
import cn.hbads.renderweave.inference.certification.ImageOnlyCertificationAuthorization;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

@Repository
public class PostgresCertificationStageExecutionStore
        implements CertificationStageExecutionStore {
    private final JdbcClient jdbcClient;

    public PostgresCertificationStageExecutionStore(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
    }

    @Override
    @Transactional
    public void open(ImageOnlyCertificationAuthorization authorization, Instant openedAt) {
        Objects.requireNonNull(authorization, "authorization");
        Objects.requireNonNull(openedAt, "openedAt");
        if (authorization.status() != AuthorizationStatus.OPEN
                || authorization.maximumRuns() != authorization.stage().caseCount()
                || authorization.cases().size() != authorization.maximumRuns()) {
            fail("CERTIFICATION_STAGE_AUTHORIZATION_INVALID");
        }
        requireWithinWindow(authorization.effectiveAt(), authorization.expiresAt(), openedAt);
        jdbcClient.sql("""
                        insert into certification_stage_ledger (
                            authorization_id, cycle_id, stage, profile_id, profile_sha256,
                            manifest_identity, evaluator_identity,
                            maximum_runs, maximum_provider_calls, maximum_model_tokens,
                            maximum_cost_micros_cny, maximum_provider_calls_per_run,
                            maximum_cost_per_run_micros_cny, effective_at, expires_at,
                            approved_by, approved_at, status, opened_at
                        ) values (
                            :authorizationId, :cycleId, :stage, :profileId, :profileSha256,
                            :manifestIdentity, :evaluatorIdentity,
                            :maximumRuns, :maximumProviderCalls, :maximumModelTokens,
                            :maximumCostMicrosCny, :maximumProviderCallsPerRun,
                            :maximumCostPerRunMicrosCny, :effectiveAt, :expiresAt,
                            :approvedBy, :approvedAt, 'OPEN', :openedAt
                        )
                        """)
                .param("authorizationId", authorization.authorizationId())
                .param("cycleId", authorization.cycleId())
                .param("stage", authorization.stage().name())
                .param("profileId", authorization.profileId())
                .param("profileSha256", authorization.profileSha256())
                .param("manifestIdentity", authorization.manifestIdentity())
                .param("evaluatorIdentity", authorization.evaluatorIdentity())
                .param("maximumRuns", authorization.maximumRuns())
                .param("maximumProviderCalls", authorization.maximumProviderCalls())
                .param("maximumModelTokens", authorization.maximumModelTokens())
                .param("maximumCostMicrosCny", authorization.maximumCostMicrosCny())
                .param("maximumProviderCallsPerRun", authorization.maximumProviderCallsPerRun())
                .param("maximumCostPerRunMicrosCny", authorization.maximumCostPerRunMicrosCny())
                .param("effectiveAt", offset(authorization.effectiveAt()))
                .param("expiresAt", offset(authorization.expiresAt()))
                .param("approvedBy", authorization.approvedBy())
                .param("approvedAt", offset(authorization.approvedAt()))
                .param("openedAt", offset(openedAt))
                .update();
        for (var authorizedCase : authorization.cases()) {
            jdbcClient.sql("""
                            insert into certification_stage_case (
                                authorization_id, case_id, artifact_sha256
                            ) values (:authorizationId, :caseId, :artifactSha256)
                            """)
                    .param("authorizationId", authorization.authorizationId())
                    .param("caseId", authorizedCase.caseId())
                    .param("artifactSha256", authorizedCase.artifactSha256())
                    .update();
        }
    }

    @Override
    @Transactional
    public void startRun(
            String authorizationId,
            UUID runId,
            AuthorizedCertificationCase authorizedCase,
            Instant startedAt
    ) {
        requireAuthorizationId(authorizationId);
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(authorizedCase, "authorizedCase");
        Objects.requireNonNull(startedAt, "startedAt");
        var ledger = requireOpenLedger(authorizationId, startedAt);
        var expectedSha = jdbcClient.sql("""
                        select artifact_sha256 from certification_stage_case
                        where authorization_id = :authorizationId and case_id = :caseId
                        """)
                .param("authorizationId", authorizationId)
                .param("caseId", authorizedCase.caseId())
                .query(String.class)
                .optional()
                .orElse(null);
        if (!authorizedCase.artifactSha256().equals(expectedSha)) {
            fail("CERTIFICATION_STAGE_CASE_MISMATCH");
        }
        var caseAlreadyStarted = jdbcClient.sql("""
                        select count(*) from certification_stage_run
                        where authorization_id = :authorizationId and case_id = :caseId
                        """)
                .param("authorizationId", authorizationId)
                .param("caseId", authorizedCase.caseId())
                .query(Long.class)
                .single();
        if (caseAlreadyStarted > 0) {
            fail("CERTIFICATION_STAGE_CASE_ALREADY_STARTED");
        }
        var runs = jdbcClient.sql("""
                        select count(*) from certification_stage_run
                        where authorization_id = :authorizationId
                        """)
                .param("authorizationId", authorizationId)
                .query(Long.class)
                .single();
        if (runs >= ledger.maximumRuns()) {
            fail("CERTIFICATION_STAGE_RUN_BUDGET_EXHAUSTED");
        }
        jdbcClient.sql("""
                        insert into certification_stage_run (
                            authorization_id, run_id, case_id, started_at
                        ) values (:authorizationId, :runId, :caseId, :startedAt)
                        """)
                .param("authorizationId", authorizationId)
                .param("runId", runId)
                .param("caseId", authorizedCase.caseId())
                .param("startedAt", offset(startedAt))
                .update();
    }

    @Override
    @Transactional
    public CertificationProviderCallPermit reserveCall(
            String authorizationId,
            UUID runId,
            int attemptOrdinal,
            long maximumModelTokens,
            long maximumCostMicrosCny,
            Instant reservedAt
    ) {
        requireAuthorizationId(authorizationId);
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(reservedAt, "reservedAt");
        if (attemptOrdinal < 0 || attemptOrdinal >= 12
                || maximumModelTokens < 1 || maximumCostMicrosCny < 1) {
            fail("CERTIFICATION_STAGE_RESERVATION_BOUNDS_INVALID");
        }
        var ledger = requireOpenLedger(authorizationId, reservedAt);
        var run = jdbcClient.sql("""
                        select case_id from certification_stage_run
                        where authorization_id = :authorizationId and run_id = :runId
                        """)
                .param("authorizationId", authorizationId)
                .param("runId", runId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new CertificationStageLedgerViolation(
                        "CERTIFICATION_STAGE_RUN_UNKNOWN"));
        var duplicate = jdbcClient.sql("""
                        select count(*) from certification_stage_call_reservation
                        where run_id = :runId and attempt_ordinal = :attemptOrdinal
                        """)
                .param("runId", runId)
                .param("attemptOrdinal", attemptOrdinal)
                .query(Long.class)
                .single();
        if (duplicate > 0) {
            fail("CERTIFICATION_STAGE_CALL_ALREADY_RESERVED");
        }
        var consumed = consumption(authorizationId);
        if (consumed.calls() >= ledger.maximumProviderCalls()) {
            fail("CERTIFICATION_STAGE_CALL_BUDGET_EXHAUSTED");
        }
        if (maximumModelTokens > ledger.maximumModelTokens() - consumed.modelTokens()) {
            fail("CERTIFICATION_STAGE_TOKEN_BUDGET_EXHAUSTED");
        }
        if (maximumCostMicrosCny > ledger.maximumCostMicrosCny() - consumed.costMicrosCny()) {
            fail("CERTIFICATION_STAGE_COST_BUDGET_EXHAUSTED");
        }
        var runConsumption = runConsumption(runId);
        if (runConsumption.calls() >= ledger.maximumProviderCallsPerRun()) {
            fail("CERTIFICATION_STAGE_RUN_CALL_BUDGET_EXHAUSTED");
        }
        if (maximumCostMicrosCny
                > ledger.maximumCostPerRunMicrosCny() - runConsumption.costMicrosCny()) {
            fail("CERTIFICATION_STAGE_RUN_COST_BUDGET_EXHAUSTED");
        }
        var reservationId = UUID.randomUUID();
        jdbcClient.sql("""
                        insert into certification_stage_call_reservation (
                            reservation_id, authorization_id, run_id, attempt_ordinal,
                            reserved_model_tokens, reserved_cost_micros_cny,
                            state, reserved_at
                        ) values (
                            :reservationId, :authorizationId, :runId, :attemptOrdinal,
                            :reservedModelTokens, :reservedCostMicrosCny,
                            'RESERVED', :reservedAt
                        )
                        """)
                .param("reservationId", reservationId)
                .param("authorizationId", authorizationId)
                .param("runId", runId)
                .param("attemptOrdinal", attemptOrdinal)
                .param("reservedModelTokens", maximumModelTokens)
                .param("reservedCostMicrosCny", maximumCostMicrosCny)
                .param("reservedAt", offset(reservedAt))
                .update();
        return new CertificationProviderCallPermit(
                reservationId, authorizationId, ledger.cycleId(), ledger.stage(), runId, run,
                attemptOrdinal, maximumModelTokens, maximumCostMicrosCny,
                ledger.expiresAt(), true);
    }

    @Override
    @Transactional
    public void settleCall(
            UUID reservationId,
            long actualModelTokens,
            long actualCostMicrosCny,
            Instant settledAt
    ) {
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(settledAt, "settledAt");
        if (actualModelTokens < 0 || actualCostMicrosCny < 0) {
            fail("CERTIFICATION_STAGE_SETTLEMENT_BOUNDS_INVALID");
        }
        var current = jdbcClient.sql("""
                        select reserved_model_tokens, actual_model_tokens,
                               reserved_cost_micros_cny, actual_cost_micros_cny, state
                        from certification_stage_call_reservation
                        where reservation_id = :reservationId
                        for update
                        """)
                .param("reservationId", reservationId)
                .query((resultSet, rowNumber) -> new ReservationState(
                        resultSet.getLong("reserved_model_tokens"),
                        resultSet.getObject("actual_model_tokens", Long.class),
                        resultSet.getLong("reserved_cost_micros_cny"),
                        resultSet.getObject("actual_cost_micros_cny", Long.class),
                        resultSet.getString("state")))
                .optional()
                .orElseThrow(() -> new CertificationStageLedgerViolation(
                        "CERTIFICATION_STAGE_RESERVATION_UNKNOWN"));
        if (actualModelTokens > current.reservedModelTokens()
                || actualCostMicrosCny > current.reservedCostMicrosCny()) {
            fail("CERTIFICATION_STAGE_SETTLEMENT_EXCEEDS_RESERVATION");
        }
        if ("SETTLED".equals(current.state())) {
            if (!Objects.equals(current.actualModelTokens(), actualModelTokens)
                    || !Objects.equals(current.actualCostMicrosCny(), actualCostMicrosCny)) {
                fail("CERTIFICATION_STAGE_SETTLEMENT_CONFLICT");
            }
            return;
        }
        jdbcClient.sql("""
                        update certification_stage_call_reservation
                        set actual_model_tokens = :actualModelTokens,
                            actual_cost_micros_cny = :actualCostMicrosCny,
                            state = 'SETTLED', settled_at = :settledAt
                        where reservation_id = :reservationId and state = 'RESERVED'
                        """)
                .param("actualModelTokens", actualModelTokens)
                .param("actualCostMicrosCny", actualCostMicrosCny)
                .param("settledAt", offset(settledAt))
                .param("reservationId", reservationId)
                .update();
    }

    @Override
    @Transactional
    public void close(String authorizationId, String reasonCode, Instant closedAt) {
        requireAuthorizationId(authorizationId);
        Objects.requireNonNull(closedAt, "closedAt");
        if (reasonCode == null || !reasonCode.matches("[A-Z][A-Z0-9_]{2,127}")) {
            fail("CERTIFICATION_STAGE_CLOSURE_REASON_INVALID");
        }
        var ledger = lockLedger(authorizationId);
        if (ledger.status() == CertificationStageLedgerStatus.CLOSED) {
            if (!reasonCode.equals(ledger.closureReason())) {
                fail("CERTIFICATION_STAGE_CLOSURE_CONFLICT");
            }
            return;
        }
        if (closedAt.isBefore(ledger.openedAt())) {
            fail("CERTIFICATION_STAGE_CLOSURE_TIME_INVALID");
        }
        jdbcClient.sql("""
                        update certification_stage_ledger
                        set status = 'CLOSED', closed_at = :closedAt,
                            closure_reason = :closureReason
                        where authorization_id = :authorizationId and status = 'OPEN'
                        """)
                .param("closedAt", offset(closedAt))
                .param("closureReason", reasonCode)
                .param("authorizationId", authorizationId)
                .update();
    }

    @Override
    public CertificationStageLedgerSnapshot snapshot(String authorizationId) {
        requireAuthorizationId(authorizationId);
        var ledger = readLedger(authorizationId, false);
        var runs = jdbcClient.sql("""
                        select count(*) from certification_stage_run
                        where authorization_id = :authorizationId
                        """)
                .param("authorizationId", authorizationId)
                .query(Long.class)
                .single();
        var consumed = consumption(authorizationId);
        return new CertificationStageLedgerSnapshot(
                ledger.authorizationId(), ledger.cycleId(), ledger.profileId(),
                ledger.profileSha256(), ledger.stage(), ledger.status(),
                ledger.maximumRuns(), Math.toIntExact(runs),
                ledger.maximumProviderCalls(), consumed.calls(),
                ledger.maximumModelTokens(), consumed.modelTokens(),
                ledger.maximumCostMicrosCny(), consumed.costMicrosCny(),
                ledger.maximumProviderCallsPerRun(), ledger.maximumCostPerRunMicrosCny(),
                consumed.unsettled(), ledger.effectiveAt(), ledger.expiresAt(),
                ledger.openedAt(), ledger.closedAt(), ledger.closureReason());
    }

    private LedgerState requireOpenLedger(String authorizationId, Instant now) {
        var ledger = lockLedger(authorizationId);
        if (ledger.status() != CertificationStageLedgerStatus.OPEN) {
            fail("CERTIFICATION_STAGE_LEDGER_NOT_OPEN");
        }
        requireWithinWindow(ledger.effectiveAt(), ledger.expiresAt(), now);
        return ledger;
    }

    private LedgerState lockLedger(String authorizationId) {
        return readLedger(authorizationId, true);
    }

    private LedgerState readLedger(String authorizationId, boolean forUpdate) {
        var sql = """
                select authorization_id, cycle_id, profile_id, profile_sha256, stage,
                       maximum_runs, maximum_provider_calls, maximum_model_tokens,
                       maximum_cost_micros_cny, maximum_provider_calls_per_run,
                       maximum_cost_per_run_micros_cny, effective_at, expires_at,
                       status, opened_at, closed_at, closure_reason
                from certification_stage_ledger
                where authorization_id = :authorizationId
                """ + (forUpdate ? " for update" : "");
        return jdbcClient.sql(sql)
                .param("authorizationId", authorizationId)
                .query((resultSet, rowNumber) -> new LedgerState(
                        resultSet.getString("authorization_id"),
                        resultSet.getObject("cycle_id", UUID.class),
                        resultSet.getString("profile_id"),
                        resultSet.getString("profile_sha256"),
                        CertificationStage.valueOf(resultSet.getString("stage")),
                        resultSet.getInt("maximum_runs"),
                        resultSet.getInt("maximum_provider_calls"),
                        resultSet.getLong("maximum_model_tokens"),
                        resultSet.getLong("maximum_cost_micros_cny"),
                        resultSet.getInt("maximum_provider_calls_per_run"),
                        resultSet.getLong("maximum_cost_per_run_micros_cny"),
                        resultSet.getObject("effective_at", OffsetDateTime.class).toInstant(),
                        resultSet.getObject("expires_at", OffsetDateTime.class).toInstant(),
                        CertificationStageLedgerStatus.valueOf(resultSet.getString("status")),
                        resultSet.getObject("opened_at", OffsetDateTime.class).toInstant(),
                        nullableInstant(resultSet.getObject("closed_at", OffsetDateTime.class)),
                        resultSet.getString("closure_reason")))
                .optional()
                .orElseThrow(() -> new CertificationStageLedgerViolation(
                        "CERTIFICATION_STAGE_LEDGER_UNKNOWN"));
    }

    private Consumption consumption(String authorizationId) {
        return jdbcClient.sql("""
                        select count(*) as calls,
                               coalesce(sum(coalesce(actual_model_tokens,
                                   reserved_model_tokens)), 0) as model_tokens,
                               coalesce(sum(coalesce(actual_cost_micros_cny,
                                   reserved_cost_micros_cny)), 0) as cost,
                               count(*) filter (where state = 'RESERVED') as unsettled
                        from certification_stage_call_reservation
                        where authorization_id = :authorizationId
                        """)
                .param("authorizationId", authorizationId)
                .query((resultSet, rowNumber) -> new Consumption(
                        resultSet.getInt("calls"), resultSet.getLong("model_tokens"),
                        resultSet.getLong("cost"), resultSet.getInt("unsettled")))
                .single();
    }

    private RunConsumption runConsumption(UUID runId) {
        return jdbcClient.sql("""
                        select count(*) as calls,
                               coalesce(sum(coalesce(actual_cost_micros_cny,
                                   reserved_cost_micros_cny)), 0) as cost
                        from certification_stage_call_reservation
                        where run_id = :runId
                        """)
                .param("runId", runId)
                .query((resultSet, rowNumber) -> new RunConsumption(
                        resultSet.getInt("calls"), resultSet.getLong("cost")))
                .single();
    }

    private static void requireWithinWindow(Instant effectiveAt, Instant expiresAt, Instant now) {
        if (now.isBefore(effectiveAt)) {
            fail("CERTIFICATION_STAGE_LEDGER_NOT_YET_EFFECTIVE");
        }
        if (!now.isBefore(expiresAt)) {
            fail("CERTIFICATION_STAGE_LEDGER_EXPIRED");
        }
    }

    private static void requireAuthorizationId(String authorizationId) {
        if (authorizationId == null
                || !authorizationId.matches("[a-z0-9][a-z0-9-]{2,95}")) {
            throw new IllegalArgumentException("authorizationId is invalid");
        }
    }

    private static Instant nullableInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime offset(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static void fail(String reasonCode) {
        throw new CertificationStageLedgerViolation(reasonCode);
    }

    private record LedgerState(
            String authorizationId,
            UUID cycleId,
            String profileId,
            String profileSha256,
            CertificationStage stage,
            int maximumRuns,
            int maximumProviderCalls,
            long maximumModelTokens,
            long maximumCostMicrosCny,
            int maximumProviderCallsPerRun,
            long maximumCostPerRunMicrosCny,
            Instant effectiveAt,
            Instant expiresAt,
            CertificationStageLedgerStatus status,
            Instant openedAt,
            Instant closedAt,
            String closureReason
    ) { }

    private record Consumption(
            int calls,
            long modelTokens,
            long costMicrosCny,
            int unsettled
    ) { }

    private record RunConsumption(int calls, long costMicrosCny) { }

    private record ReservationState(
            long reservedModelTokens,
            Long actualModelTokens,
            long reservedCostMicrosCny,
            Long actualCostMicrosCny,
            String state
    ) { }
}
