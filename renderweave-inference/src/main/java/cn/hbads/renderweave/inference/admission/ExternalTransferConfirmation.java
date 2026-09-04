package cn.hbads.renderweave.inference.admission;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable per-run consent fact; it is never reusable as authority for another run. */
public record ExternalTransferConfirmation(
        UUID confirmationId,
        UUID runId,
        String requestFingerprint,
        String actorId,
        String requestId,
        String gatewayJti,
        String gatewayKeyId,
        InputProvenance inputProvenance,
        SensitivityClass sensitivityClass,
        String policyVersion,
        String policySha256,
        String providerContractId,
        String providerContractSha256,
        ExternalTransferNotice.Identity noticeIdentity,
        String provider,
        String model,
        String endpoint,
        String region,
        String profileId,
        String profileSha256,
        String manifestVersion,
        String manifestSha256,
        int maximumProviderCalls,
        long maximumCostMicrosCny,
        Instant confirmedAt,
        Instant dispatchNotAfter,
        Instant providerCallsNotAfter
) {
    public static final String FINGERPRINT_DOMAIN = "renderweave-live-admission-request/1.0";
    public static final Duration FIRST_DISPATCH_WINDOW = Duration.ofMinutes(15);
    public static final Duration PROVIDER_CALL_WINDOW = Duration.ofHours(2);

    public ExternalTransferConfirmation {
        Objects.requireNonNull(confirmationId, "confirmationId");
        Objects.requireNonNull(runId, "runId");
        requestFingerprint = ExternalTransferNotice.requireSha(requestFingerprint, "requestFingerprint");
        actorId = ExternalTransferNotice.requireText(actorId, "actorId", 192);
        requestId = ExternalTransferNotice.requireText(requestId, "requestId", 192);
        gatewayJti = ExternalTransferNotice.requireText(gatewayJti, "gatewayJti", 192);
        gatewayKeyId = ExternalTransferNotice.requireText(gatewayKeyId, "gatewayKeyId", 128);
        Objects.requireNonNull(inputProvenance, "inputProvenance");
        Objects.requireNonNull(sensitivityClass, "sensitivityClass");
        policyVersion = ExternalTransferNotice.requireText(policyVersion, "policyVersion", 128);
        policySha256 = ExternalTransferNotice.requireSha(policySha256, "policySha256");
        providerContractId = ExternalTransferNotice.requireText(
                providerContractId, "providerContractId", 192
        );
        providerContractSha256 = ExternalTransferNotice.requireSha(
                providerContractSha256, "providerContractSha256"
        );
        Objects.requireNonNull(noticeIdentity, "noticeIdentity");
        provider = ExternalTransferNotice.requireText(provider, "provider", 64);
        model = ExternalTransferNotice.requireText(model, "model", 128);
        endpoint = ExternalTransferNotice.requireText(endpoint, "endpoint", 512);
        region = ExternalTransferNotice.requireText(region, "region", 64);
        profileId = ExternalTransferNotice.requireText(profileId, "profileId", 128);
        profileSha256 = ExternalTransferNotice.requireSha(profileSha256, "profileSha256");
        if (!LiveInputManifest.VERSION.equals(manifestVersion)) {
            throw new IllegalArgumentException("manifestVersion is unsupported");
        }
        manifestSha256 = ExternalTransferNotice.requireSha(manifestSha256, "manifestSha256");
        if (maximumProviderCalls < 1 || maximumCostMicrosCny < 1) {
            throw new IllegalArgumentException("Provider caps must be positive");
        }
        Objects.requireNonNull(confirmedAt, "confirmedAt");
        Objects.requireNonNull(dispatchNotAfter, "dispatchNotAfter");
        Objects.requireNonNull(providerCallsNotAfter, "providerCallsNotAfter");
        if (!dispatchNotAfter.equals(confirmedAt.plus(FIRST_DISPATCH_WINDOW))
                || !providerCallsNotAfter.equals(confirmedAt.plus(PROVIDER_CALL_WINDOW))) {
            throw new IllegalArgumentException("Confirmation deadlines must use the exact 15-minute/2-hour windows");
        }
    }

    public static ExternalTransferConfirmation issue(
            UUID confirmationId,
            UUID runId,
            GatewayRequestIdentity identity,
            InputProvenance provenance,
            SensitivityClass sensitivity,
            LiveAdmissionConfiguration configuration,
            LiveInputManifest manifest,
            Instant confirmedAt
    ) {
        var notice = configuration.notice();
        var fingerprint = requestFingerprint(
                identity.actorId(), provenance, sensitivity, notice, manifest
        );
        return new ExternalTransferConfirmation(
                confirmationId, runId, fingerprint,
                identity.actorId(), identity.requestId(), identity.jti(), identity.keyId(),
                provenance, sensitivity, notice.policyVersion(), notice.policySha256(),
                notice.providerContractId(), notice.providerContractSha256(), notice.identity(),
                notice.provider(), notice.model(), notice.endpoint(), notice.region(),
                notice.profileId(), notice.profileSha256(), manifest.version(), manifest.sha256(),
                notice.maximumProviderCalls(), notice.maximumCostMicrosCny(), confirmedAt,
                confirmedAt.plus(FIRST_DISPATCH_WINDOW), confirmedAt.plus(PROVIDER_CALL_WINDOW)
        );
    }

    public static String requestFingerprint(
            String actorId,
            InputProvenance provenance,
            SensitivityClass sensitivity,
            ExternalTransferNotice notice,
            LiveInputManifest manifest
    ) {
        Objects.requireNonNull(provenance, "provenance");
        Objects.requireNonNull(sensitivity, "sensitivity");
        Objects.requireNonNull(notice, "notice");
        Objects.requireNonNull(manifest, "manifest");
        return AdmissionDigests.sha256(
                FINGERPRINT_DOMAIN,
                actorId, provenance.name(), sensitivity.name(),
                notice.version(), notice.locale(), notice.contentSha256(),
                notice.policyVersion(), notice.policySha256(),
                notice.providerContractId(), notice.providerContractSha256(),
                notice.provider(), notice.model(), notice.endpoint(), notice.region(),
                notice.profileId(), notice.profileSha256(),
                notice.maximumProviderCalls(), notice.maximumCostMicrosCny(),
                notice.localPayloadRetentionSeconds(), manifest.version(), manifest.sha256()
        );
    }
}
