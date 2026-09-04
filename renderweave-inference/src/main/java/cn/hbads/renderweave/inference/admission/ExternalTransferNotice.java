package cn.hbads.renderweave.inference.admission;

import java.net.URI;
import java.util.Objects;

/** Server-owned immutable content displayed before an IMAGE_ONLY external transfer. */
public record ExternalTransferNotice(
        String version,
        String locale,
        String contentSha256,
        String providerLegalEntity,
        String provider,
        String model,
        String endpoint,
        String region,
        String processingPurpose,
        String providerRetentionStatement,
        String providerSecondaryUseStatement,
        String providerHumanAccessStatement,
        String profileId,
        String profileSha256,
        int maximumProviderCalls,
        long maximumCostMicrosCny,
        long localPayloadRetentionSeconds,
        String policyVersion,
        String policySha256,
        String providerContractId,
        String providerContractSha256
) {
    public static final String DIGEST_DOMAIN = "renderweave-external-transfer-notice/1.0";

    public ExternalTransferNotice {
        version = requireText(version, "version", 128);
        locale = requireText(locale, "locale", 32);
        contentSha256 = requireSha(contentSha256, "contentSha256");
        providerLegalEntity = requireText(providerLegalEntity, "providerLegalEntity", 256);
        provider = requireText(provider, "provider", 64);
        model = requireText(model, "model", 128);
        endpoint = requireHttpsEndpoint(endpoint);
        region = requireText(region, "region", 64);
        processingPurpose = requireText(processingPurpose, "processingPurpose", 1024);
        providerRetentionStatement = requireText(
                providerRetentionStatement, "providerRetentionStatement", 1024
        );
        providerSecondaryUseStatement = requireText(
                providerSecondaryUseStatement, "providerSecondaryUseStatement", 1024
        );
        providerHumanAccessStatement = requireText(
                providerHumanAccessStatement, "providerHumanAccessStatement", 1024
        );
        profileId = requireText(profileId, "profileId", 128);
        profileSha256 = requireSha(profileSha256, "profileSha256");
        if (maximumProviderCalls < 1 || maximumProviderCalls > 100) {
            throw new IllegalArgumentException("maximumProviderCalls must be 1..100");
        }
        if (maximumCostMicrosCny < 1) {
            throw new IllegalArgumentException("maximumCostMicrosCny must be positive");
        }
        if (localPayloadRetentionSeconds < 1 || localPayloadRetentionSeconds > 7L * 24 * 60 * 60) {
            throw new IllegalArgumentException("localPayloadRetentionSeconds must be within seven days");
        }
        policyVersion = requireText(policyVersion, "policyVersion", 128);
        policySha256 = requireSha(policySha256, "policySha256");
        providerContractId = requireText(providerContractId, "providerContractId", 192);
        providerContractSha256 = requireSha(providerContractSha256, "providerContractSha256");

        var expected = digest(
                version, locale, providerLegalEntity, provider, model, endpoint, region,
                processingPurpose, providerRetentionStatement, providerSecondaryUseStatement,
                providerHumanAccessStatement, profileId, profileSha256, maximumProviderCalls,
                maximumCostMicrosCny, localPayloadRetentionSeconds, policyVersion, policySha256,
                providerContractId, providerContractSha256
        );
        if (!expected.equals(contentSha256)) {
            throw new IllegalArgumentException("contentSha256 does not identify the exact notice content");
        }
    }

    public static ExternalTransferNotice issue(
            String version,
            String locale,
            String providerLegalEntity,
            String provider,
            String model,
            String endpoint,
            String region,
            String processingPurpose,
            String providerRetentionStatement,
            String providerSecondaryUseStatement,
            String providerHumanAccessStatement,
            String profileId,
            String profileSha256,
            int maximumProviderCalls,
            long maximumCostMicrosCny,
            long localPayloadRetentionSeconds,
            String policyVersion,
            String policySha256,
            String providerContractId,
            String providerContractSha256
    ) {
        var digest = digest(
                version, locale, providerLegalEntity, provider, model, endpoint, region,
                processingPurpose, providerRetentionStatement, providerSecondaryUseStatement,
                providerHumanAccessStatement, profileId, profileSha256, maximumProviderCalls,
                maximumCostMicrosCny, localPayloadRetentionSeconds, policyVersion, policySha256,
                providerContractId, providerContractSha256
        );
        return new ExternalTransferNotice(
                version, locale, digest, providerLegalEntity, provider, model, endpoint, region,
                processingPurpose, providerRetentionStatement, providerSecondaryUseStatement,
                providerHumanAccessStatement, profileId, profileSha256, maximumProviderCalls,
                maximumCostMicrosCny, localPayloadRetentionSeconds, policyVersion, policySha256,
                providerContractId, providerContractSha256
        );
    }

    public Identity identity() {
        return new Identity(version, locale, contentSha256);
    }

    private static String digest(Object... fields) {
        return AdmissionDigests.sha256(DIGEST_DOMAIN, fields);
    }

    private static String requireHttpsEndpoint(String value) {
        value = requireText(value, "endpoint", 512);
        final URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("endpoint must be an absolute HTTPS URI", invalid);
        }
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getFragment() != null || uri.getQuery() != null) {
            throw new IllegalArgumentException("endpoint must be an absolute HTTPS URI without query or fragment");
        }
        return value;
    }

    static String requireText(String value, String name, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must contain 1.." + maximumLength + " characters");
        }
        return value;
    }

    static String requireSha(String value, String name) {
        if (value == null || !value.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException(name + " must be a SHA-256 hex digest");
        }
        return value;
    }

    public record Identity(String version, String locale, String contentSha256) {
        public Identity {
            version = requireText(version, "version", 128);
            locale = requireText(locale, "locale", 32);
            contentSha256 = requireSha(contentSha256, "contentSha256");
        }
    }
}
