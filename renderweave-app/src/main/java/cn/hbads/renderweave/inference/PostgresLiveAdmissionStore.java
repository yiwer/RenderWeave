package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.admission.ExternalTransferConfirmation;
import cn.hbads.renderweave.inference.admission.ExternalTransferNotice;
import cn.hbads.renderweave.inference.admission.InputProvenance;
import cn.hbads.renderweave.inference.admission.LiveAdmissionProblem;
import cn.hbads.renderweave.inference.admission.LiveAdmissionStore;
import cn.hbads.renderweave.inference.admission.NewLiveInferenceRun;
import cn.hbads.renderweave.inference.admission.SensitivityClass;
import cn.hbads.renderweave.inference.run.InferenceIdempotencyConflictException;
import cn.hbads.renderweave.inference.run.InferenceRunStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PostgresLiveAdmissionStore implements LiveAdmissionStore {
    private final JdbcClient jdbcClient;
    private final InferenceRunStore runStore;
    private final PostgresPayloadLifecycleStore payloadLifecycle;
    private final PostgresLiveAuditStore auditStore;

    public PostgresLiveAdmissionStore(
            JdbcClient jdbcClient,
            InferenceRunStore runStore,
            PostgresPayloadLifecycleStore payloadLifecycle,
            PostgresLiveAuditStore auditStore
    ) {
        this.jdbcClient = jdbcClient;
        this.runStore = runStore;
        this.payloadLifecycle = payloadLifecycle;
        this.auditStore = auditStore;
    }

    @Override
    @Transactional
    public Result admit(NewLiveInferenceRun command) {
        var deletionReadiness = payloadLifecycle.snapshot();
        if (!deletionReadiness.healthy()) {
            throw new LiveAdmissionProblem(
                    deletionReadiness.reasonCode(),
                    "Live payload admission is closed while deletion exceeds its hard SLO."
            );
        }
        upsertAndVerifyNotice(command.notice(), command.confirmation().confirmedAt());

        final InferenceRunStore.CreationResult run;
        try {
            run = runStore.create(command.run());
        } catch (InferenceIdempotencyConflictException conflict) {
            throw new LiveAdmissionProblem(
                    "LIVE_IDEMPOTENCY_CONFLICT",
                    "The idempotency key is already bound to different live admission facts."
            );
        }

        if (!run.created()) {
            var persisted = findConfirmation(run.run().runId()).orElseThrow(() -> new LiveAdmissionProblem(
                    "LIVE_IDEMPOTENCY_CONFLICT",
                    "The idempotency key belongs to a non-live or incomplete admission."
            ));
            if (!persisted.requestFingerprint().equals(command.run().requestFingerprint())
                    || !persisted.manifestVersion().equals(command.manifest().version())
                    || !persisted.manifestSha256().equals(command.manifest().sha256())) {
                throw new LiveAdmissionProblem(
                        "LIVE_IDEMPOTENCY_CONFLICT",
                        "The idempotency key is already bound to different live admission facts."
                );
            }
            payloadLifecycle.finishAdmissionReplay(
                    persisted.runId(),
                    command.manifest().items().stream()
                            .map(item -> item.artifactSha256())
                            .toList(),
                    command.confirmation().confirmedAt()
            );
            return result(persisted, false);
        }

        insertManifest(command);
        insertConfirmation(command.confirmation());
        payloadLifecycle.registerFreshAdmission(command);
        auditStore.append(new cn.hbads.renderweave.inference.audit.LiveAdmissionAuditEvent(
                command.confirmation().runId(), 1, "LIVE_RUN_ADMITTED",
                command.confirmation().actorId(),
                command.confirmation().confirmationId(), null, null, null,
                command.manifest().sha256(),
                command.confirmation().profileId(), command.confirmation().profileSha256(),
                null, null, null, null, command.confirmation().confirmedAt(), "", ""
        ));
        return result(command.confirmation(), true);
    }

    @Override
    public Optional<ExternalTransferConfirmation> findConfirmation(UUID runId) {
        return jdbcClient.sql("""
                        select confirmation_id, run_id, request_fingerprint,
                               actor_id, request_id, gateway_jti, gateway_key_id,
                               input_provenance, sensitivity_class,
                               policy_version, policy_sha256,
                               provider_contract_id, provider_contract_sha256,
                               notice_version, notice_locale, notice_content_sha256,
                               provider, model, endpoint, region,
                               profile_id, profile_sha256,
                               manifest_version, manifest_sha256,
                               maximum_provider_calls, maximum_cost_micros_cny,
                               confirmed_at, dispatch_not_after, provider_calls_not_after
                        from external_transfer_confirmation
                        where run_id = :runId
                        """)
                .param("runId", runId)
                .query(PostgresLiveAdmissionStore::mapConfirmation)
                .optional();
    }

    private void upsertAndVerifyNotice(ExternalTransferNotice notice, java.time.Instant createdAt) {
        jdbcClient.sql("""
                        insert into external_transfer_notice (
                            notice_version, locale, content_sha256,
                            provider_legal_entity, provider, model, endpoint, region,
                            processing_purpose, provider_retention_statement,
                            provider_secondary_use_statement, provider_human_access_statement,
                            profile_id, profile_sha256, maximum_provider_calls,
                            maximum_cost_micros_cny, local_payload_retention_seconds,
                            policy_version, policy_sha256,
                            provider_contract_id, provider_contract_sha256, created_at
                        ) values (
                            :version, :locale, :contentSha256,
                            :providerLegalEntity, :provider, :model, :endpoint, :region,
                            :processingPurpose, :providerRetentionStatement,
                            :providerSecondaryUseStatement, :providerHumanAccessStatement,
                            :profileId, :profileSha256, :maximumProviderCalls,
                            :maximumCostMicrosCny, :localPayloadRetentionSeconds,
                            :policyVersion, :policySha256,
                            :providerContractId, :providerContractSha256, :createdAt
                        )
                        on conflict (notice_version, locale) do nothing
                        """)
                .param("version", notice.version())
                .param("locale", notice.locale())
                .param("contentSha256", notice.contentSha256())
                .param("providerLegalEntity", notice.providerLegalEntity())
                .param("provider", notice.provider())
                .param("model", notice.model())
                .param("endpoint", notice.endpoint())
                .param("region", notice.region())
                .param("processingPurpose", notice.processingPurpose())
                .param("providerRetentionStatement", notice.providerRetentionStatement())
                .param("providerSecondaryUseStatement", notice.providerSecondaryUseStatement())
                .param("providerHumanAccessStatement", notice.providerHumanAccessStatement())
                .param("profileId", notice.profileId())
                .param("profileSha256", notice.profileSha256())
                .param("maximumProviderCalls", notice.maximumProviderCalls())
                .param("maximumCostMicrosCny", notice.maximumCostMicrosCny())
                .param("localPayloadRetentionSeconds", notice.localPayloadRetentionSeconds())
                .param("policyVersion", notice.policyVersion())
                .param("policySha256", notice.policySha256())
                .param("providerContractId", notice.providerContractId())
                .param("providerContractSha256", notice.providerContractSha256())
                .param("createdAt", offset(createdAt))
                .update();

        var persisted = jdbcClient.sql("""
                        select notice_version, locale, content_sha256,
                               provider_legal_entity, provider, model, endpoint, region,
                               processing_purpose, provider_retention_statement,
                               provider_secondary_use_statement, provider_human_access_statement,
                               profile_id, profile_sha256, maximum_provider_calls,
                               maximum_cost_micros_cny, local_payload_retention_seconds,
                               policy_version, policy_sha256,
                               provider_contract_id, provider_contract_sha256
                        from external_transfer_notice
                        where notice_version = :version and locale = :locale
                        """)
                .param("version", notice.version())
                .param("locale", notice.locale())
                .query(PostgresLiveAdmissionStore::mapNotice)
                .single();
        if (!persisted.equals(notice)) {
            throw new LiveAdmissionProblem(
                    "LIVE_TRANSFER_NOTICE_IDENTITY_CONFLICT",
                    "An immutable notice version/locale already identifies different content."
            );
        }
    }

    private void insertManifest(NewLiveInferenceRun command) {
        var manifest = command.manifest();
        jdbcClient.sql("""
                        insert into live_input_manifest (
                            run_id, manifest_version, manifest_sha256,
                            aggregate_normalized_bytes, artifact_count, created_at
                        ) values (
                            :runId, :version, :sha256, :aggregateBytes, :artifactCount, :createdAt
                        )
                        """)
                .param("runId", command.run().runId())
                .param("version", manifest.version())
                .param("sha256", manifest.sha256())
                .param("aggregateBytes", manifest.aggregateNormalizedBytes())
                .param("artifactCount", manifest.items().size())
                .param("createdAt", offset(command.confirmation().confirmedAt()))
                .update();
        for (var item : manifest.items()) {
            jdbcClient.sql("""
                            insert into live_input_manifest_item (
                                run_id, input_ordinal, artifact_id, media_type,
                                byte_length, width, height
                            ) values (
                                :runId, :ordinal, :artifactId, :mediaType,
                                :byteLength, :width, :height
                            )
                            """)
                    .param("runId", command.run().runId())
                    .param("ordinal", item.ordinal())
                    .param("artifactId", item.artifactSha256())
                    .param("mediaType", item.mediaType())
                    .param("byteLength", item.byteLength())
                    .param("width", item.width())
                    .param("height", item.height())
                    .update();
        }
    }

    private void insertConfirmation(ExternalTransferConfirmation value) {
        jdbcClient.sql("""
                        insert into external_transfer_confirmation (
                            confirmation_id, run_id, request_fingerprint,
                            actor_id, request_id, gateway_jti, gateway_key_id,
                            input_provenance, sensitivity_class,
                            policy_version, policy_sha256,
                            provider_contract_id, provider_contract_sha256,
                            notice_version, notice_locale, notice_content_sha256,
                            provider, model, endpoint, region,
                            profile_id, profile_sha256,
                            manifest_version, manifest_sha256,
                            maximum_provider_calls, maximum_cost_micros_cny,
                            confirmed_at, dispatch_not_after, provider_calls_not_after
                        ) values (
                            :confirmationId, :runId, :requestFingerprint,
                            :actorId, :requestId, :gatewayJti, :gatewayKeyId,
                            :inputProvenance, :sensitivityClass,
                            :policyVersion, :policySha256,
                            :providerContractId, :providerContractSha256,
                            :noticeVersion, :noticeLocale, :noticeContentSha256,
                            :provider, :model, :endpoint, :region,
                            :profileId, :profileSha256,
                            :manifestVersion, :manifestSha256,
                            :maximumProviderCalls, :maximumCostMicrosCny,
                            :confirmedAt, :dispatchNotAfter, :providerCallsNotAfter
                        )
                        """)
                .param("confirmationId", value.confirmationId())
                .param("runId", value.runId())
                .param("requestFingerprint", value.requestFingerprint())
                .param("actorId", value.actorId())
                .param("requestId", value.requestId())
                .param("gatewayJti", value.gatewayJti())
                .param("gatewayKeyId", value.gatewayKeyId())
                .param("inputProvenance", value.inputProvenance().name())
                .param("sensitivityClass", value.sensitivityClass().name())
                .param("policyVersion", value.policyVersion())
                .param("policySha256", value.policySha256())
                .param("providerContractId", value.providerContractId())
                .param("providerContractSha256", value.providerContractSha256())
                .param("noticeVersion", value.noticeIdentity().version())
                .param("noticeLocale", value.noticeIdentity().locale())
                .param("noticeContentSha256", value.noticeIdentity().contentSha256())
                .param("provider", value.provider())
                .param("model", value.model())
                .param("endpoint", value.endpoint())
                .param("region", value.region())
                .param("profileId", value.profileId())
                .param("profileSha256", value.profileSha256())
                .param("manifestVersion", value.manifestVersion())
                .param("manifestSha256", value.manifestSha256())
                .param("maximumProviderCalls", value.maximumProviderCalls())
                .param("maximumCostMicrosCny", value.maximumCostMicrosCny())
                .param("confirmedAt", offset(value.confirmedAt()))
                .param("dispatchNotAfter", offset(value.dispatchNotAfter()))
                .param("providerCallsNotAfter", offset(value.providerCallsNotAfter()))
                .update();
    }

    private static ExternalTransferNotice mapNotice(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ExternalTransferNotice(
                resultSet.getString("notice_version"),
                resultSet.getString("locale"),
                resultSet.getString("content_sha256"),
                resultSet.getString("provider_legal_entity"),
                resultSet.getString("provider"),
                resultSet.getString("model"),
                resultSet.getString("endpoint"),
                resultSet.getString("region"),
                resultSet.getString("processing_purpose"),
                resultSet.getString("provider_retention_statement"),
                resultSet.getString("provider_secondary_use_statement"),
                resultSet.getString("provider_human_access_statement"),
                resultSet.getString("profile_id"),
                resultSet.getString("profile_sha256"),
                resultSet.getInt("maximum_provider_calls"),
                resultSet.getLong("maximum_cost_micros_cny"),
                resultSet.getLong("local_payload_retention_seconds"),
                resultSet.getString("policy_version"),
                resultSet.getString("policy_sha256"),
                resultSet.getString("provider_contract_id"),
                resultSet.getString("provider_contract_sha256")
        );
    }

    private static ExternalTransferConfirmation mapConfirmation(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new ExternalTransferConfirmation(
                resultSet.getObject("confirmation_id", UUID.class),
                resultSet.getObject("run_id", UUID.class),
                resultSet.getString("request_fingerprint"),
                resultSet.getString("actor_id"),
                resultSet.getString("request_id"),
                resultSet.getString("gateway_jti"),
                resultSet.getString("gateway_key_id"),
                InputProvenance.valueOf(resultSet.getString("input_provenance")),
                SensitivityClass.valueOf(resultSet.getString("sensitivity_class")),
                resultSet.getString("policy_version"),
                resultSet.getString("policy_sha256"),
                resultSet.getString("provider_contract_id"),
                resultSet.getString("provider_contract_sha256"),
                new ExternalTransferNotice.Identity(
                        resultSet.getString("notice_version"),
                        resultSet.getString("notice_locale"),
                        resultSet.getString("notice_content_sha256")
                ),
                resultSet.getString("provider"),
                resultSet.getString("model"),
                resultSet.getString("endpoint"),
                resultSet.getString("region"),
                resultSet.getString("profile_id"),
                resultSet.getString("profile_sha256"),
                resultSet.getString("manifest_version"),
                resultSet.getString("manifest_sha256"),
                resultSet.getInt("maximum_provider_calls"),
                resultSet.getLong("maximum_cost_micros_cny"),
                instant(resultSet, "confirmed_at"),
                instant(resultSet, "dispatch_not_after"),
                instant(resultSet, "provider_calls_not_after")
        );
    }

    private static Result result(ExternalTransferConfirmation value, boolean created) {
        return new Result(
                value.runId(), value.confirmationId(),
                value.manifestVersion() + ":" + value.manifestSha256(), created
        );
    }

    private static OffsetDateTime offset(java.time.Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static java.time.Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }
}
