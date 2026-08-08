package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.eval.LiveEvaluationCase;
import cn.hbads.renderweave.inference.eval.LiveEvaluationCorpus;
import cn.hbads.renderweave.inference.profile.InferenceProfileRegistry;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Versioned human authorization envelope. PROPOSED and CLOSED are deliberately non-executable. */
record LiveCertificationAuthorization(
        String authorizationVersion,
        String authorizationId,
        String status,
        String inputClassification,
        String corpusVersion,
        String evaluationIdentity,
        List<String> profileIds,
        int maximumProviderAttempts,
        long maximumCostMicrosCny,
        int maximumCasesPerBatch,
        String approvedBy,
        String approvedAt,
        String expiresAt,
        String approvalScope
) {
    static final String VERSION = "renderweave-live-certification-authorization/1.1";
    static final String INPUT_CLASSIFICATION = "REPOSITORY_SYNTHETIC_ONLY";
    static final String PENDING_EVALUATION_IDENTITY = "PENDING_PRELIVE_COMMIT";
    static final String FLASH_PROFILE = "dashscope-qwen37-flash-v1";
    static final String MAX_PROFILE = "dashscope-qwen38-max-v1";
    private static final int ABSOLUTE_MAXIMUM_ATTEMPTS = 360;
    private static final long ABSOLUTE_MAXIMUM_COST_MICROS_CNY = 54_000_000L;

    LiveCertificationAuthorization {
        profileIds = List.copyOf(Objects.requireNonNull(profileIds, "profileIds"));
        if (!VERSION.equals(authorizationVersion)
                || authorizationId == null
                || !authorizationId.matches("[a-z0-9][a-z0-9-]{0,95}")) {
            throw new IllegalArgumentException("Certification authorization identity is invalid");
        }
        if (!List.of("PROPOSED", "OPEN", "CLOSED").contains(status)) {
            throw new IllegalArgumentException("Certification authorization status is invalid");
        }
        if (!INPUT_CLASSIFICATION.equals(inputClassification)
                || !LiveEvaluationCorpus.VERSION.equals(corpusVersion)) {
            throw new IllegalArgumentException("Certification authorization data scope is invalid");
        }
        var pendingIdentity = PENDING_EVALUATION_IDENTITY.equals(evaluationIdentity);
        if (evaluationIdentity == null || !evaluationIdentity.matches(
                "renderweave-repository-tree-sha256/1:[0-9a-f]{64}"
        ) && !("PROPOSED".equals(status) && pendingIdentity)) {
            throw new IllegalArgumentException("Certification evaluation identity is invalid");
        }
        if (profileIds.isEmpty() || profileIds.size() > 2
                || new HashSet<>(profileIds).size() != profileIds.size()
                || profileIds.stream().anyMatch(id -> !List.of(FLASH_PROFILE, MAX_PROFILE).contains(id))) {
            throw new IllegalArgumentException("Certification authorization profiles are invalid");
        }
        if (maximumProviderAttempts < 1 || maximumProviderAttempts > designedMaximumAttempts(profileIds)
                || maximumProviderAttempts > ABSOLUTE_MAXIMUM_ATTEMPTS
                || maximumCostMicrosCny < 1 || maximumCostMicrosCny > designedMaximumCost(profileIds)
                || maximumCostMicrosCny > ABSOLUTE_MAXIMUM_COST_MICROS_CNY
                || maximumCasesPerBatch < 1 || maximumCasesPerBatch > 5) {
            throw new IllegalArgumentException("Certification authorization budget is invalid");
        }
    }

    static LiveCertificationAuthorization load(Path path, ObjectMapper json) {
        try {
            return json.readValue(Files.readString(path), LiveCertificationAuthorization.class);
        } catch (IOException failure) {
            throw new IllegalStateException("Certification authorization cannot be loaded", failure);
        }
    }

    void requireOpen(Instant now) {
        Objects.requireNonNull(now, "now");
        if (!"OPEN".equals(status)) {
            throw new IllegalStateException("LIVE_CERTIFICATION_AUTHORIZATION_NOT_OPEN");
        }
        if (approvedBy == null || approvedBy.isBlank() || approvalScope == null || approvalScope.isBlank()
                || approvedAt == null || expiresAt == null) {
            throw new IllegalStateException("LIVE_CERTIFICATION_APPROVAL_INCOMPLETE");
        }
        final Instant approved;
        final Instant expires;
        try {
            approved = Instant.parse(approvedAt);
            expires = Instant.parse(expiresAt);
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("LIVE_CERTIFICATION_APPROVAL_TIME_INVALID", invalid);
        }
        if (approved.isAfter(now) || !expires.isAfter(now) || !expires.isAfter(approved)) {
            throw new IllegalStateException("LIVE_CERTIFICATION_AUTHORIZATION_EXPIRED");
        }
    }

    void requireEvaluationIdentity(String actualIdentity) {
        if (!Objects.equals(evaluationIdentity, actualIdentity)) {
            throw new IllegalStateException("LIVE_CERTIFICATION_EVALUATION_IDENTITY_MISMATCH");
        }
    }

    List<Assignment> assignments(LiveEvaluationCorpus corpus) {
        var result = new ArrayList<Assignment>();
        for (var profileId : profileIds) {
            for (var item : corpus.cases()) result.add(new Assignment(profileId, item));
        }
        return List.copyOf(result);
    }

    int assignmentCount() {
        return Math.multiplyExact(profileIds.size(), 60);
    }

    private static int designedMaximumAttempts(List<String> profileIds) {
        var profiles = new InferenceProfileRegistry();
        return profileIds.stream().map(profiles::require)
                .mapToInt(item -> Math.multiplyExact(60, item.profile().maximumTotalCalls())).sum();
    }

    private static long designedMaximumCost(List<String> profileIds) {
        var profiles = new InferenceProfileRegistry();
        return profileIds.stream().map(profiles::require).mapToLong(item -> Math.multiplyExact(
                60L,
                Math.multiplyExact(
                        item.profile().maximumTotalCalls(),
                        item.profile().maximumEstimatedCostMicrosCny()
                )
        )).sum();
    }

    record Assignment(String profileId, LiveEvaluationCase evaluationCase) {
        String key() {
            return profileId + "|" + evaluationCase.caseId();
        }
    }
}
