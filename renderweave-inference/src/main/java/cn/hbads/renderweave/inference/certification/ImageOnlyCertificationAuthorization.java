package cn.hbads.renderweave.inference.certification;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Exact human J1 envelope. Only OPEN records can pass the separate preflight. */
public record ImageOnlyCertificationAuthorization(
        String version,
        String authorizationId,
        AuthorizationStatus status,
        UUID cycleId,
        CertificationStage stage,
        String profileId,
        String profileSha256,
        String manifestIdentity,
        String evaluatorIdentity,
        String provider,
        String model,
        String providerBaseUrl,
        String inputProvenance,
        String dataClassification,
        List<AuthorizedCertificationCase> cases,
        int maximumRuns,
        int maximumProviderCalls,
        long maximumModelTokens,
        long maximumCostMicrosCny,
        Instant effectiveAt,
        Instant expiresAt,
        String approvedBy,
        Instant approvedAt,
        String approvalScope,
        Instant closedAt,
        String closureReason
) {
    public static final String VERSION =
            "renderweave-image-only-certification-authorization/1.0";

    public ImageOnlyCertificationAuthorization {
        if (!VERSION.equals(version)
                || authorizationId == null || !authorizationId.matches("[a-z0-9][a-z0-9-]{2,95}")) {
            throw new IllegalArgumentException("CERTIFICATION_AUTHORIZATION_IDENTITY_INVALID");
        }
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(cycleId, "cycleId");
        Objects.requireNonNull(stage, "stage");
        requireText(profileId, "profileId");
        CertificationCanaryCase.requireSha(profileSha256);
        requireText(manifestIdentity, "manifestIdentity");
        requireText(evaluatorIdentity, "evaluatorIdentity");
        requireText(provider, "provider");
        requireText(model, "model");
        requireText(providerBaseUrl, "providerBaseUrl");
        requireText(inputProvenance, "inputProvenance");
        requireText(dataClassification, "dataClassification");
        cases = List.copyOf(Objects.requireNonNull(cases, "cases"));
        if (cases.isEmpty() || cases.size() > 60
                || new HashSet<>(cases.stream().map(AuthorizedCertificationCase::caseId).toList()).size()
                != cases.size()) {
            throw new IllegalArgumentException("CERTIFICATION_AUTHORIZATION_CASES_INVALID");
        }
        if (maximumRuns < 1 || maximumRuns > 60
                || maximumProviderCalls < 1 || maximumProviderCalls > 720
                || maximumModelTokens < 1 || maximumModelTokens > 10_000_000
                || maximumCostMicrosCny < 1 || maximumCostMicrosCny > 360_000_000L) {
            throw new IllegalArgumentException("CERTIFICATION_AUTHORIZATION_BOUNDS_INVALID");
        }
        Objects.requireNonNull(effectiveAt, "effectiveAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        requireText(approvedBy, "approvedBy");
        Objects.requireNonNull(approvedAt, "approvedAt");
        requireText(approvalScope, "approvalScope");
        if (status == AuthorizationStatus.CLOSED) {
            Objects.requireNonNull(closedAt, "closedAt");
            requireText(closureReason, "closureReason");
        } else if (closedAt != null || closureReason != null) {
            throw new IllegalArgumentException("CERTIFICATION_AUTHORIZATION_CLOSURE_SHAPE_INVALID");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 256) {
            throw new IllegalArgumentException("CERTIFICATION_AUTHORIZATION_" + name + "_INVALID");
        }
    }
}
