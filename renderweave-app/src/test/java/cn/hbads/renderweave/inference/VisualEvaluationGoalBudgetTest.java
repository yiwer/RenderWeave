package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.eval.visual.VisualStageCorpus;
import cn.hbads.renderweave.inference.profile.InferenceProfileRegistry;
import cn.hbads.renderweave.inference.provider.InferenceProvider;
import cn.hbads.renderweave.inference.provider.ProviderImage;
import cn.hbads.renderweave.inference.provider.ProviderInferenceRequest;
import cn.hbads.renderweave.inference.provider.ProviderInferenceResponse;
import cn.hbads.renderweave.inference.provider.ProviderUsage;
import cn.hbads.renderweave.inference.run.InferenceStage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisualEvaluationGoalBudgetTest {
    private static final Instant NOW = Instant.parse("2026-08-10T10:00:00Z");
    private static final String PROFILE_ID = "dashscope-qwen37-plus-product-v4";
    private final VisualStageCorpus corpus = new VisualStageCorpus();
    private final InferenceProfileRegistry.ProfileResource profile =
            new InferenceProfileRegistry().require(PROFILE_ID);

    @Test
    void strictAuthorizationBindsCorpusProfileAndGoalCaps(@TempDir Path directory) throws Exception {
        var authorization = authorization("visual-baseline-plus", 24, 500_000, 4_000_000);
        authorization.requireOpen(NOW);
        authorization.requireCorpus(corpus);
        authorization.requireProfileSnapshot(sha256(profile.snapshotJson()));

        assertThrows(IllegalArgumentException.class,
                () -> authorization("too-many-tokens", 24, 500_001, 4_000_000));
        assertThrows(IllegalArgumentException.class,
                () -> authorization("too-much-cost", 24, 500_000, 4_000_001));

        var json = JsonMapper.builder().build();
        var path = directory.resolve("authorization.json");
        Files.writeString(path, json.writeValueAsString(authorization), StandardCharsets.UTF_8);
        assertEquals(authorization, VisualEvaluationAuthorization.load(path, json));
        Files.writeString(path, json.writeValueAsString(authorization).replaceFirst(
                "\\{", "{\"unknown\":true,"), StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, () -> VisualEvaluationAuthorization.load(path, json));

        var original = json.writeValueAsString(authorization);
        Files.writeString(path, original.replace(
                "Synthetic visual stage evaluation", "candidateJson"), StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, () -> VisualEvaluationAuthorization.load(path, json));
        Files.writeString(path, original.replaceFirst(
                "(\"status\"\\s*:\\s*\"OPEN\")", "$1,\"status\":\"CLOSED\""));
        assertThrows(IllegalStateException.class, () -> VisualEvaluationAuthorization.load(path, json));
        Files.writeString(path, original + "\n{}");
        assertThrows(IllegalStateException.class, () -> VisualEvaluationAuthorization.load(path, json));
        Files.writeString(path, original.replaceFirst(
                "(\"maximumTotalTokens\"\\s*:\\s*)500000", "$1\"500000\""));
        assertThrows(IllegalStateException.class, () -> VisualEvaluationAuthorization.load(path, json));
        Files.writeString(path, original.replace(
                "\"maximumCostMicrosCny\":4000000", "\"maximumCostMicrosCny\":1.5"));
        assertThrows(IllegalStateException.class, () -> VisualEvaluationAuthorization.load(path, json));
    }

    @Test
    void proposedExpiredAndProfileModelDriftNeverReachTheBudget(@TempDir Path directory) {
        var open = authorization("visual-authorization-lifecycle", 24, 500_000, 4_000_000);
        var proposed = new VisualEvaluationAuthorization(
                open.authorizationVersion(), open.authorizationId(), "PROPOSED", open.phase(),
                open.inputClassification(), open.corpusVersion(), open.corpusSourceSha256(),
                VisualEvaluationAuthorization.PENDING_IDENTITY, open.profileId(),
                VisualEvaluationAuthorization.PENDING_PROFILE_SNAPSHOT, open.model(), open.caseIds(),
                open.maximumProviderAttempts(), open.maximumTotalTokens(), open.maximumCostMicrosCny(),
                open.maximumCasesPerBatch(), null, null, null, null
        );
        assertThrows(IllegalStateException.class, () -> proposed.requireOpen(NOW));

        var expired = new VisualEvaluationAuthorization(
                open.authorizationVersion(), open.authorizationId(), "OPEN", open.phase(),
                open.inputClassification(), open.corpusVersion(), open.corpusSourceSha256(),
                open.evaluationIdentity(), open.profileId(), open.profileSnapshotSha256(), open.model(),
                open.caseIds(), open.maximumProviderAttempts(), open.maximumTotalTokens(),
                open.maximumCostMicrosCny(), open.maximumCasesPerBatch(), "yiwer",
                NOW.minusSeconds(120).toString(), NOW.minusSeconds(1).toString(), open.approvalScope()
        );
        assertThrows(IllegalStateException.class, () -> expired.requireOpen(NOW));
        assertThrows(IllegalArgumentException.class, () -> new VisualEvaluationAuthorization(
                open.authorizationVersion(), open.authorizationId(), "OPEN", open.phase(),
                open.inputClassification(), open.corpusVersion(), open.corpusSourceSha256(),
                open.evaluationIdentity(), "dashscope-qwen38-max-product-v4",
                open.profileSnapshotSha256(), "qwen3.7-plus", open.caseIds(),
                open.maximumProviderAttempts(), open.maximumTotalTokens(), open.maximumCostMicrosCny(),
                open.maximumCasesPerBatch(), open.approvedBy(), open.approvedAt(), open.expiresAt(),
                open.approvalScope()
        ));
        assertTrue(Files.notExists(directory.resolve("goal-budget.json")));
    }

    @Test
    void settledUsageReplacesReservationWhileUnsettledUsageRemainsWorstCase(@TempDir Path directory) {
        var authorization = authorization("visual-budget-settle", 24, 500_000, 4_000_000);
        var budget = new VisualEvaluationGoalBudget(directory, JsonMapper.builder().build(), NOW);
        var request = request(0);
        var reservation = budget.reserve(authorization, request, NOW);
        var before = budget.snapshot("qwen3.7-plus", authorization.authorizationId());
        assertEquals(1, before.goal().attempts());
        assertTrue(before.goal().tokens() > 20_000);

        budget.settle(UUID.fromString(reservation.reservationId()), new ProviderUsage(120, 80),
                1_000, NOW.plusSeconds(1));
        var after = budget.snapshot("qwen3.7-plus", authorization.authorizationId());
        assertEquals(200, after.goal().tokens());
        assertEquals(1_000, after.goal().costMicrosCny());
        assertEquals("SETTLED", budget.reservations().getFirst().state());
    }

    @Test
    void aggregateTokenLimitStopsBeforeNineteenthIrreversibleCall(@TempDir Path directory) {
        var authorization = authorization("visual-budget-cap", 24, 500_000, 4_000_000);
        var budget = new VisualEvaluationGoalBudget(directory, JsonMapper.builder().build(), NOW);
        for (var ordinal = 0; ordinal < 18; ordinal++) {
            budget.reserve(authorization, request(ordinal), NOW.plusSeconds(ordinal));
        }
        assertThrows(IllegalStateException.class,
                () -> budget.reserve(authorization, request(18), NOW.plusSeconds(19)));
        assertEquals(18, budget.reservations().size());
        assertTrue(budget.snapshot("qwen3.7-plus", authorization.authorizationId()).goal().tokens()
                <= 500_000);
    }

    @Test
    void underestimatedReservationIsPersistedAsBreachAndPermanentlyStopsNewCalls(@TempDir Path directory) {
        var authorization = authorization("visual-budget-breach", 24, 500_000, 4_000_000);
        var budget = new VisualEvaluationGoalBudget(directory, JsonMapper.builder().build(), NOW);
        var reservation = budget.reserve(authorization, request(0), NOW);
        assertThrows(IllegalStateException.class, () -> budget.settle(
                UUID.fromString(reservation.reservationId()), new ProviderUsage(600_000, 1),
                4_100_000, NOW.plusSeconds(1)
        ));
        var snapshot = budget.snapshot("qwen3.7-plus", authorization.authorizationId());
        assertTrue(snapshot.breached());
        assertEquals("BREACHED", budget.reservations().getFirst().state());
        assertThrows(IllegalStateException.class,
                () -> budget.reserve(authorization, request(1), NOW.plusSeconds(2)));
    }

    @Test
    void providerDecoratorReservesBeforeDelegateAndRetainsWorstCaseOnFailure(@TempDir Path directory) {
        var authorization = authorization("visual-provider-wrapper", 24, 500_000, 4_000_000);
        var budget = new VisualEvaluationGoalBudget(directory, JsonMapper.builder().build(), NOW);
        var calls = new AtomicInteger();
        InferenceProvider success = request -> {
            calls.incrementAndGet();
            assertEquals(1, budget.reservations().size());
            return new ProviderInferenceResponse(
                    "{}", "synthetic-request", request.profile().model(),
                    new ProviderUsage(100, 50), "stop"
            );
        };
        var provider = new GoalBudgetInferenceProvider(
                authorization, budget, success, Clock.fixed(NOW, ZoneOffset.UTC)
        );
        provider.complete(request(0));
        assertEquals(1, calls.get());
        assertEquals("SETTLED", budget.reservations().getFirst().state());

        InferenceProvider failure = request -> {
            assertEquals(2, budget.reservations().size());
            throw new IllegalStateException("synthetic failure");
        };
        var failingProvider = new GoalBudgetInferenceProvider(
                authorization, budget, failure, Clock.fixed(NOW.plusSeconds(2), ZoneOffset.UTC)
        );
        assertThrows(IllegalStateException.class, () -> failingProvider.complete(request(1)));
        assertEquals("RESERVED", budget.reservations().get(1).state());
    }

    @Test
    void guardTamperingAfterConstructionStopsTheLongRunningBudget(@TempDir Path directory)
            throws Exception {
        var authorization = authorization("visual-budget-live-guard", 24, 500_000, 4_000_000);
        var budget = new VisualEvaluationGoalBudget(directory, JsonMapper.builder().build(), NOW);
        var guardFile = directory.resolve("goal-budget.guard.json");
        var originalGuard = Files.readString(guardFile, StandardCharsets.UTF_8);
        Files.writeString(guardFile, "{}", StandardCharsets.UTF_8);

        assertThrows(IllegalStateException.class,
                () -> budget.reserve(authorization, request(0), NOW.plusSeconds(1)));
        Files.writeString(guardFile, originalGuard, StandardCharsets.UTF_8);
        assertTrue(budget.reservations().isEmpty());
    }

    private VisualEvaluationAuthorization authorization(
            String id,
            int maximumAttempts,
            long maximumTokens,
            long maximumCost
    ) {
        return new VisualEvaluationAuthorization(
                VisualEvaluationAuthorization.VERSION, id, "OPEN", "BASELINE",
                VisualEvaluationAuthorization.INPUT_CLASSIFICATION,
                VisualStageCorpus.VERSION, corpus.sourceSha256(),
                VisualEvaluationIdentity.VERSION + ":" + "b".repeat(64),
                PROFILE_ID, sha256(profile.snapshotJson()), "qwen3.7-plus",
                corpus.cases().stream().limit(3).map(VisualStageCorpus.EvaluationCase::caseId).toList(),
                maximumAttempts, maximumTokens, maximumCost, 3,
                "yiwer", NOW.minusSeconds(60).toString(), NOW.plusSeconds(43_200).toString(),
                "Synthetic visual stage evaluation"
        );
    }

    private ProviderInferenceRequest request(int runIndex) {
        return new ProviderInferenceRequest(
                UUID.nameUUIDFromBytes(("run-" + runIndex).getBytes(StandardCharsets.UTF_8)),
                0, InferenceStage.OBSERVE, profile.profile(),
                "Return one bounded JSON object.", "{}",
                List.of(new ProviderImage("c".repeat(64), "image/png", new byte[]{1}))
        );
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
