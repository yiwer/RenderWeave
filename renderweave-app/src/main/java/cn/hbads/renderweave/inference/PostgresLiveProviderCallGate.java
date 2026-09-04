package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.admission.ExternalTransferConfirmation;
import cn.hbads.renderweave.inference.admission.ExternalTransferConfirmationGuard;
import cn.hbads.renderweave.inference.admission.ImageOnlyAdmissionPolicy;
import cn.hbads.renderweave.inference.admission.ImageOnlyAdmissionPolicyStore;
import cn.hbads.renderweave.inference.admission.LiveAdmissionProblem;
import cn.hbads.renderweave.inference.admission.LiveAdmissionStore;
import cn.hbads.renderweave.inference.admission.LiveInputManifest;
import cn.hbads.renderweave.inference.admission.LiveNoticeAuthority;
import cn.hbads.renderweave.inference.admission.LiveProviderCallGate;
import cn.hbads.renderweave.inference.admission.ProviderCallAuthorizationCommand;
import cn.hbads.renderweave.inference.admission.ProviderCallOutcomeCommand;
import cn.hbads.renderweave.inference.admission.ProviderCallPermit;
import cn.hbads.renderweave.inference.admission.ProviderEgressPermit;
import cn.hbads.renderweave.inference.audit.LiveAdmissionAuditChain;
import cn.hbads.renderweave.inference.audit.LiveAdmissionAuditEvent;
import cn.hbads.renderweave.inference.input.InferenceMode;
import cn.hbads.renderweave.inference.provider.ProviderBudgetStore;
import cn.hbads.renderweave.inference.run.InferenceRunSnapshot;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * PostgreSQL-backed gate between the live worker and Provider egress. The dual switches, the
 * confirmation replay, the cost reservation, the call authorization fact and the audit event
 * commit as one transaction; the Provider permit exists only after that commit.
 */
@Repository
public class PostgresLiveProviderCallGate implements LiveProviderCallGate {
    private static final String WORKER_ACTOR_ID = "renderweave-live-worker";

    private final ImageOnlyAdmissionPolicyStore policyStore;
    private final ProviderEgressPermit egressPermit;
    private final PostgresLiveAuditStore auditStore;
    private final ProviderBudgetStore budgetStore;
    private final LiveAdmissionStore admissionStore;
    private final LiveNoticeAuthority noticeAuthority;
    private final JdbcClient jdbcClient;
    private final ExternalTransferConfirmationGuard confirmationGuard;
    private final Supplier<UUID> callAuthorizationIds;

    @org.springframework.beans.factory.annotation.Autowired
    public PostgresLiveProviderCallGate(
            ImageOnlyAdmissionPolicyStore policyStore,
            ProviderEgressPermit egressPermit,
            PostgresLiveAuditStore auditStore,
            ProviderBudgetStore budgetStore,
            LiveAdmissionStore admissionStore,
            JdbcClient jdbcClient
    ) {
        this(policyStore, egressPermit, auditStore, budgetStore, admissionStore,
                LiveNoticeAuthority.absent(), jdbcClient,
                new ExternalTransferConfirmationGuard(), UUID::randomUUID);
    }

    PostgresLiveProviderCallGate(
            ImageOnlyAdmissionPolicyStore policyStore,
            ProviderEgressPermit egressPermit,
            PostgresLiveAuditStore auditStore,
            ProviderBudgetStore budgetStore,
            LiveAdmissionStore admissionStore,
            LiveNoticeAuthority noticeAuthority,
            JdbcClient jdbcClient,
            ExternalTransferConfirmationGuard confirmationGuard,
            Supplier<UUID> callAuthorizationIds
    ) {
        this.policyStore = policyStore;
        this.egressPermit = egressPermit;
        this.auditStore = auditStore;
        this.budgetStore = budgetStore;
        this.admissionStore = admissionStore;
        this.noticeAuthority = noticeAuthority;
        this.jdbcClient = jdbcClient;
        this.confirmationGuard = confirmationGuard;
        this.callAuthorizationIds = callAuthorizationIds;
    }

    @Override
    public void requireDispatchEligible(InferenceRunSnapshot run) {
        requireSwitches(run.mode());
    }

    @Override
    @Transactional
    public ProviderCallPermit authorizeCall(ProviderCallAuthorizationCommand command) {
        requireSwitches(command.mode());
        requireChainHealthy(command.runId());
        var confirmation = admissionStore.findConfirmation(command.runId());
        var manifest = confirmation.isPresent()
                ? loadPersistedManifest(command.runId()) : Optional.<LiveInputManifest>empty();
        var actorId = confirmation.map(ExternalTransferConfirmation::actorId).orElse(WORKER_ACTOR_ID);
        if (confirmation.isPresent()) {
            replayConfirmation(command, confirmation.orElseThrow(), manifest);
        }
        var reservation = budgetStore.reserve(
                command.budgetKey(), command.runId(), command.attemptOrdinal(),
                command.maximumRequestCostMicrosCny(), command.runCostLimitMicrosCny(),
                command.now()
        );
        var authorizationId = callAuthorizationIds.get();
        var auditEvent = auditStore.append(new LiveAdmissionAuditEvent(
                command.runId(), 1, "CALL_AUTHORIZED", actorId,
                confirmation.map(ExternalTransferConfirmation::confirmationId).orElse(null),
                reservation.reservationId(), authorizationId, command.attemptOrdinal(),
                command.inputFingerprint(), command.profileId(), command.profileSha256(),
                null, null, null, null, command.now(), "", ""
        ));
        var policy = policyStore.current();
        var egress = egressPermit.snapshot();
        var callsNotAfter = confirmation
                .map(ExternalTransferConfirmation::providerCallsNotAfter)
                .orElse(command.now().plusSeconds(2 * 60 * 60));
        jdbcClient.sql("""
                        insert into provider_call_authorization (
                            call_authorization_id, run_id, attempt_ordinal, confirmation_id,
                            policy_version, egress_permit_identity,
                            profile_id, profile_sha256, endpoint, manifest_sha256,
                            input_fingerprint, reservation_id, audit_sequence,
                            authorized_at, provider_calls_not_after
                        ) values (
                            :authorizationId, :runId, :attemptOrdinal, :confirmationId,
                            :policyVersion, :egressPermitIdentity,
                            :profileId, :profileSha256, :endpoint, :manifestSha256,
                            :inputFingerprint, :reservationId, :auditSequence,
                            :authorizedAt, :providerCallsNotAfter
                        )
                        """)
                .param("authorizationId", authorizationId)
                .param("runId", command.runId())
                .param("attemptOrdinal", command.attemptOrdinal())
                .param("confirmationId", confirmation
                        .map(ExternalTransferConfirmation::confirmationId).orElse(null))
                .param("policyVersion", policy.version())
                .param("egressPermitIdentity", egress.identity())
                .param("profileId", command.profileId())
                .param("profileSha256", command.profileSha256())
                .param("endpoint", command.endpoint())
                .param("manifestSha256", manifest.map(LiveInputManifest::sha256).orElse(null))
                .param("inputFingerprint", command.inputFingerprint())
                .param("reservationId", reservation.reservationId())
                .param("auditSequence", auditEvent.sequence())
                .param("authorizedAt", offset(command.now()))
                .param("providerCallsNotAfter", offset(callsNotAfter))
                .update();
        return new ProviderCallPermit(
                authorizationId, reservation.reservationId(), command.runId(),
                command.attemptOrdinal(), policy.version(), egress.identity(),
                command.now(), callsNotAfter
        );
    }

    @Override
    @Transactional
    public void recordDispatchOutcome(ProviderCallOutcomeCommand command) {
        var permit = command.permit();
        if (command.outcome() == ProviderCallOutcomeCommand.Outcome.DISPATCH_SUCCEEDED) {
            budgetStore.settle(permit.reservationId(), command.actualCostMicrosCny(), command.now());
        }
        auditStore.append(new LiveAdmissionAuditEvent(
                permit.runId(), 1,
                command.outcome() == ProviderCallOutcomeCommand.Outcome.DISPATCH_SUCCEEDED
                        ? "CALL_DISPATCH_SUCCEEDED" : "CALL_DISPATCH_FAILED",
                null, null, permit.reservationId(), permit.callAuthorizationId(),
                permit.attemptOrdinal(), null, null, null,
                command.outcome() == ProviderCallOutcomeCommand.Outcome.DISPATCH_FAILED
                        ? command.failureCode() : null,
                command.usageInputTokens(), command.usageOutputTokens(),
                command.actualCostMicrosCny(), command.now(), "", ""
        ));
    }

    /** Appends the drain decision for a run that must reach a stable terminal without dispatch. */
    @Transactional
    public void recordDrain(UUID runId, String drainEventCode, Instant now) {
        requireDrainCode(drainEventCode);
        auditStore.append(new LiveAdmissionAuditEvent(
                runId, 1, drainEventCode, null, null, null, null, null,
                null, null, null, drainReason(drainEventCode),
                null, null, null, now, "", ""
        ));
    }

    private void requireSwitches(InferenceMode mode) {
        var policy = policyStore.current();
        if (mode == InferenceMode.IMAGE_ONLY && !policy.enabled()) {
            throw new LiveAdmissionProblem(
                    ImageOnlyAdmissionPolicy.DISABLED_REASON_CODE,
                    "IMAGE_ONLY live admission is closed by the persisted application policy."
            );
        }
        var egress = egressPermit.snapshot();
        if (!egress.enabled()) {
            throw new LiveAdmissionProblem(
                    ProviderEgressPermit.DISABLED_REASON_CODE,
                    "Provider egress is closed by the external orchestrator authority."
            );
        }
    }

    private void requireChainHealthy(UUID runId) {
        var verdict = LiveAdmissionAuditChain.verify(auditStore.eventsForRun(runId));
        if (verdict != LiveAdmissionAuditChain.Verdict.OK) {
            throw new LiveAdmissionProblem(
                    "AUDIT_INTEGRITY_UNAVAILABLE",
                    "The Live Admission Audit chain cannot be verified for this run."
            );
        }
    }

    private void replayConfirmation(
            ProviderCallAuthorizationCommand command,
            ExternalTransferConfirmation confirmation,
            Optional<LiveInputManifest> manifest
    ) {
        if (manifest.isEmpty()) {
            throw new LiveAdmissionProblem(
                    "LIVE_INPUT_MANIFEST_MISMATCH",
                    "The confirmed run no longer carries its exact normalized input manifest."
            );
        }
        var firstDispatchPending = command.attemptOrdinal() == 0;
        var currentNotice = noticeAuthority.currentNotice(
                confirmation.profileId(), confirmation.noticeIdentity().locale()
        );
        if (firstDispatchPending && currentNotice.isEmpty()) {
            throw new LiveAdmissionProblem(
                    "LIVE_TRANSFER_NOTICE_STALE",
                    "The current external-transfer notice identity cannot be established."
            );
        }
        confirmationGuard.authorizeProviderRequest(
                confirmation,
                currentNotice.orElse(confirmation.noticeIdentity()),
                command.profileSha256(),
                manifest.orElseThrow(),
                !firstDispatchPending,
                ExternalTransferConfirmationGuard.ProviderAttemptKnowledge.CLEAR,
                command.now()
        );
    }

    private Optional<LiveInputManifest> loadPersistedManifest(UUID runId) {
        var header = jdbcClient.sql("""
                        select manifest_version, manifest_sha256, aggregate_normalized_bytes
                        from live_input_manifest
                        where run_id = :runId
                        """)
                .param("runId", runId)
                .query((resultSet, rowNumber) -> new Object[] {
                        resultSet.getString("manifest_version"),
                        resultSet.getString("manifest_sha256").trim(),
                        resultSet.getLong("aggregate_normalized_bytes")
                })
                .optional();
        if (header.isEmpty()) return Optional.empty();
        var items = jdbcClient.sql("""
                        select input_ordinal, artifact_id, media_type, byte_length, width, height
                        from live_input_manifest_item
                        where run_id = :runId
                        order by input_ordinal
                        """)
                .param("runId", runId)
                .query((resultSet, rowNumber) -> new LiveInputManifest.Item(
                        resultSet.getInt("input_ordinal"),
                        resultSet.getString("artifact_id").trim(),
                        resultSet.getString("media_type"),
                        resultSet.getLong("byte_length"),
                        resultSet.getInt("width"),
                        resultSet.getInt("height")
                ))
                .list();
        var row = header.orElseThrow();
        return Optional.of(new LiveInputManifest(
                (String) row[0], (String) row[1], (Long) row[2], new ArrayList<>(items)
        ));
    }

    private static void requireDrainCode(String drainEventCode) {
        if (!"RUN_DRAINED_POLICY".equals(drainEventCode) && !"RUN_DRAINED_EGRESS".equals(drainEventCode)) {
            throw new IllegalArgumentException("Drain event code is not in the closed set");
        }
    }

    private static String drainReason(String drainEventCode) {
        return "RUN_DRAINED_POLICY".equals(drainEventCode)
                ? ImageOnlyAdmissionPolicy.DISABLED_REASON_CODE
                : ProviderEgressPermit.DISABLED_REASON_CODE;
    }

    private static OffsetDateTime offset(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
