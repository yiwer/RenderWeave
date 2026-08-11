package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.eval.visual.VisualStageCorpus;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.EnumFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** One-model, one-slice human authorization. Only OPEN is executable. */
record VisualEvaluationAuthorization(
        String authorizationVersion,
        String authorizationId,
        String status,
        String phase,
        String inputClassification,
        String corpusVersion,
        String corpusSourceSha256,
        String evaluationIdentity,
        String profileId,
        String profileSnapshotSha256,
        String model,
        List<String> caseIds,
        int maximumProviderAttempts,
        long maximumTotalTokens,
        long maximumCostMicrosCny,
        int maximumCasesPerBatch,
        String approvedBy,
        String approvedAt,
        String expiresAt,
        String approvalScope
) {
    static final String VERSION = "renderweave-visual-evaluation-authorization/1.0";
    static final String INPUT_CLASSIFICATION = "REPOSITORY_SYNTHETIC_ONLY";
    static final String PENDING_IDENTITY = "PENDING_PRELIVE_COMMIT";
    static final String PENDING_PROFILE_SNAPSHOT = "PENDING_PROFILE_SNAPSHOT";
    static final long GOAL_MAXIMUM_TOKENS_PER_MODEL = 1_500_000L;
    static final long MAXIMUM_TOKENS_PER_AUTHORIZATION = 500_000L;
    static final int GOAL_MAXIMUM_ATTEMPTS_PER_MODEL = 180;
    static final Map<String, Long> GOAL_MAXIMUM_COST_MICROS_CNY = Map.of(
            "qwen3.8-max", 18_000_000L,
            "qwen3.7-plus", 4_000_000L,
            "qwen3.7-flash", 400_000L
    );
    private static final Set<String> APPROVED_MODELS = Set.of(
            "qwen3.8-max",
            "qwen3.7-plus",
            "qwen3.7-flash",
            "qwen3.7-flash-2026-07-15"
    );
    private static final Duration MAXIMUM_AUTHORIZATION_WINDOW = Duration.ofHours(168);

    VisualEvaluationAuthorization {
        caseIds = List.copyOf(Objects.requireNonNull(caseIds, "caseIds"));
        if (!VERSION.equals(authorizationVersion) || authorizationId == null
                || !authorizationId.matches("[a-z0-9][a-z0-9-]{0,95}")) {
            throw new IllegalArgumentException("Visual evaluation authorization identity is invalid");
        }
        if (!List.of("PROPOSED", "OPEN", "CLOSED").contains(status)
                || !List.of("BASELINE", "ABLATION", "CANARY", "FINAL").contains(phase)) {
            throw new IllegalArgumentException("Visual evaluation authorization lifecycle is invalid");
        }
        if (!INPUT_CLASSIFICATION.equals(inputClassification)
                || !VisualStageCorpus.VERSION.equals(corpusVersion)
                || corpusSourceSha256 == null || !corpusSourceSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Visual evaluation authorization corpus is invalid");
        }
        if (!validEvaluationIdentity(evaluationIdentity, status)
                || !validProfileSnapshot(profileSnapshotSha256, status)) {
            throw new IllegalArgumentException("Visual evaluation authorization snapshot is invalid");
        }
        if (profileId == null || !profileId.matches("[a-z0-9][a-z0-9-]{0,127}")
                || !APPROVED_MODELS.contains(model)
                || !profileMatchesModel(profileId, model)) {
            throw new IllegalArgumentException("Visual evaluation authorization profile is invalid");
        }
        if (caseIds.isEmpty() || caseIds.size() > 60 || new HashSet<>(caseIds).size() != caseIds.size()
                || caseIds.stream().anyMatch(item -> item == null
                || !item.matches("[a-z][a-z0-9-]{0,127}"))) {
            throw new IllegalArgumentException("Visual evaluation authorization assignments are invalid");
        }
        if (maximumProviderAttempts < 1
                || maximumProviderAttempts > GOAL_MAXIMUM_ATTEMPTS_PER_MODEL
                || maximumProviderAttempts > Math.multiplyExact(caseIds.size(), 8)
                || maximumTotalTokens < 1 || maximumTotalTokens > MAXIMUM_TOKENS_PER_AUTHORIZATION
                || maximumCostMicrosCny < 1
                || maximumCostMicrosCny > goalMaximumCostMicrosCny(model)
                || maximumCasesPerBatch < 1 || maximumCasesPerBatch > 5) {
            throw new IllegalArgumentException("Visual evaluation authorization budget is invalid");
        }
    }

    static VisualEvaluationAuthorization load(Path path, ObjectMapper objectMapper) {
        try {
            var strict = Objects.requireNonNull(objectMapper, "objectMapper").rebuild()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                    .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                    .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                    .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                    .build();
            var raw = Files.readString(path, StandardCharsets.UTF_8);
            PayloadFreeLiveEvidenceGuard.requirePayloadFree(raw);
            return strict.readValue(raw, VisualEvaluationAuthorization.class);
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException("Visual evaluation authorization cannot be loaded", failure);
        }
    }

    void requireOpen(Instant now) {
        Objects.requireNonNull(now, "now");
        if (!"OPEN".equals(status)) throw new IllegalStateException("VISUAL_EVALUATION_AUTHORIZATION_NOT_OPEN");
        if (approvedBy == null || approvedBy.isBlank() || approvalScope == null || approvalScope.isBlank()
                || approvedAt == null || expiresAt == null) {
            throw new IllegalStateException("VISUAL_EVALUATION_APPROVAL_INCOMPLETE");
        }
        try {
            var approved = Instant.parse(approvedAt);
            var expires = Instant.parse(expiresAt);
            if (approved.isAfter(now) || !expires.isAfter(now) || !expires.isAfter(approved)) {
                throw new IllegalStateException("VISUAL_EVALUATION_AUTHORIZATION_EXPIRED");
            }
            if (Duration.between(approved, expires).compareTo(MAXIMUM_AUTHORIZATION_WINDOW) > 0) {
                throw new IllegalStateException("VISUAL_EVALUATION_AUTHORIZATION_WINDOW_EXCEEDED");
            }
        } catch (IllegalStateException expected) {
            throw expected;
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("VISUAL_EVALUATION_APPROVAL_TIME_INVALID", invalid);
        }
    }

    void requireClosed() {
        if (!"CLOSED".equals(status)) {
            throw new IllegalStateException("VISUAL_EVALUATION_AUTHORIZATION_NOT_CLOSED");
        }
        if (approvedBy == null || approvedBy.isBlank() || approvalScope == null || approvalScope.isBlank()
                || approvedAt == null || expiresAt == null) {
            throw new IllegalStateException("VISUAL_EVALUATION_APPROVAL_INCOMPLETE");
        }
        try {
            var approved = Instant.parse(approvedAt);
            var expires = Instant.parse(expiresAt);
            if (!expires.isAfter(approved)
                    || Duration.between(approved, expires).compareTo(MAXIMUM_AUTHORIZATION_WINDOW) > 0) {
                throw new IllegalStateException("VISUAL_EVALUATION_APPROVAL_TIME_INVALID");
            }
        } catch (IllegalStateException expected) {
            throw expected;
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("VISUAL_EVALUATION_APPROVAL_TIME_INVALID", invalid);
        }
    }

    void requireCorpus(VisualStageCorpus corpus) {
        Objects.requireNonNull(corpus, "corpus");
        if (!Objects.equals(corpusSourceSha256, corpus.sourceSha256())) {
            throw new IllegalStateException("VISUAL_EVALUATION_CORPUS_IDENTITY_MISMATCH");
        }
        for (var caseId : caseIds) corpus.require(caseId);
    }

    void requireEvaluationIdentity(String actual) {
        if (!Objects.equals(evaluationIdentity, actual)) {
            throw new IllegalStateException("VISUAL_EVALUATION_IDENTITY_MISMATCH");
        }
    }

    void requireProfileSnapshot(String actual) {
        if (!Objects.equals(profileSnapshotSha256, actual)) {
            throw new IllegalStateException("VISUAL_EVALUATION_PROFILE_SNAPSHOT_MISMATCH");
        }
    }

    private static boolean validEvaluationIdentity(String value, String status) {
        return value != null && (value.matches(
                "renderweave-visual-evaluation-tree-sha256/2:[0-9a-f]{64}")
                || "CLOSED".equals(status) && value.matches(
                "renderweave-visual-evaluation-tree-sha256/1:[0-9a-f]{64}")
                || "PROPOSED".equals(status) && PENDING_IDENTITY.equals(value));
    }

    private static boolean validProfileSnapshot(String value, String status) {
        return value != null && (value.matches("[0-9a-f]{64}")
                || "PROPOSED".equals(status) && PENDING_PROFILE_SNAPSHOT.equals(value));
    }

    static String goalModel(String model) {
        if (!isApprovedModel(model)) {
            throw new IllegalArgumentException("Visual evaluation model is invalid");
        }
        return "qwen3.7-flash-2026-07-15".equals(model) ? "qwen3.7-flash" : model;
    }

    static boolean isApprovedModel(String model) {
        return APPROVED_MODELS.contains(model);
    }

    static long goalMaximumCostMicrosCny(String model) {
        return GOAL_MAXIMUM_COST_MICROS_CNY.get(goalModel(model));
    }

    private static boolean profileMatchesModel(String profileId, String model) {
        return switch (model) {
            case "qwen3.8-max" -> profileId.startsWith("dashscope-qwen38-max-");
            case "qwen3.7-plus" -> profileId.startsWith("dashscope-qwen37-plus-");
            case "qwen3.7-flash" -> profileId.startsWith("dashscope-qwen37-flash-")
                    && !profileId.startsWith("dashscope-qwen37-flash-20260715-");
            case "qwen3.7-flash-2026-07-15" ->
                    profileId.startsWith("dashscope-qwen37-flash-20260715-");
            default -> false;
        };
    }
}
