package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.eval.visual.VisualStageCorpus;
import cn.hbads.renderweave.inference.profile.InferenceProfileRegistry;
import cn.hbads.renderweave.inference.profile.InferenceProfileRegistry.ProfileResource;
import cn.hbads.renderweave.inference.provider.InferenceProvider;
import cn.hbads.renderweave.inference.provider.ProviderCostEstimator;
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
        assertEquals(1_500_000L, VisualEvaluationAuthorization.GOAL_MAXIMUM_TOKENS_PER_MODEL);

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
    void aggregateTokenLimitStopsBeforeTheNextIrreversibleCallAcrossLedgers(@TempDir Path directory) {
        var authorizations = List.of(
                authorization("visual-budget-cap-a", 24, 500_000, 4_000_000),
                authorization("visual-budget-cap-b", 24, 500_000, 4_000_000),
                authorization("visual-budget-cap-c", 24, 500_000, 4_000_000),
                authorization("visual-budget-cap-d", 24, 500_000, 4_000_000)
        );
        var budget = new VisualEvaluationGoalBudget(directory, JsonMapper.builder().build(), NOW);
        var tokensPerRequest = ProviderCostEstimator.maximumRequestTokens(request(0));
        var perLedger = (int) Math.min(24,
                VisualEvaluationAuthorization.MAXIMUM_TOKENS_PER_AUTHORIZATION / tokensPerRequest);
        var maximumCalls = (int) (VisualEvaluationAuthorization.GOAL_MAXIMUM_TOKENS_PER_MODEL
                / tokensPerRequest);
        for (var ordinal = 0; ordinal < maximumCalls; ordinal++) {
            var reservation = budget.reserve(authorizations.get(ordinal / perLedger), request(ordinal),
                    NOW.plusSeconds(ordinal));
            budget.settle(UUID.fromString(reservation.reservationId()),
                    new ProviderUsage(reservation.reservedTokens(), 0), 0,
                    NOW.plusSeconds(ordinal + 1L));
        }
        assertThrows(IllegalStateException.class,
                () -> budget.reserve(authorizations.get(maximumCalls / perLedger),
                        request(maximumCalls), NOW.plusSeconds(maximumCalls + 1L)));
        assertEquals(maximumCalls, budget.reservations().size());
        var goalTokens = budget.snapshot(
                "qwen3.7-plus", authorizations.getFirst().authorizationId()
        ).goal().tokens();
        assertTrue(goalTokens <= VisualEvaluationAuthorization.GOAL_MAXIMUM_TOKENS_PER_MODEL);
        assertTrue(goalTokens + tokensPerRequest
                > VisualEvaluationAuthorization.GOAL_MAXIMUM_TOKENS_PER_MODEL);
    }

    @Test
    void legacyV1GuardMigratesWithoutResetAndPinnedFlashSharesHistoricalSlot(@TempDir Path directory)
            throws Exception {
        var json = JsonMapper.builder().build();
        var registry = new InferenceProfileRegistry();
        var historicalProfile = registry.require("dashscope-qwen37-flash-product-v12-generic");
        var pinnedProfile = registry.require(
                "dashscope-qwen37-flash-20260715-product-v13-generic"
        );
        var historical = authorization(
                "visual-flash-historical", historicalProfile, "qwen3.7-flash",
                8, 500_000, 400_000
        );
        var original = new VisualEvaluationGoalBudget(directory, json, NOW);
        var historicalReservation = original.reserve(
                historical, request(100, historicalProfile), NOW
        );
        original.settle(UUID.fromString(historicalReservation.reservationId()),
                new ProviderUsage(120, 80), 100, NOW.plusSeconds(1));

        Files.writeString(directory.resolve("goal-budget.guard.json"),
                json.writeValueAsString(VisualEvaluationGoalBudget.Guard.legacy()),
                StandardCharsets.UTF_8);
        var migrated = new VisualEvaluationGoalBudget(directory, json, NOW.plusSeconds(2));
        assertEquals(VisualEvaluationGoalBudget.Guard.expected(), json.readValue(
                Files.readString(directory.resolve("goal-budget.guard.json"), StandardCharsets.UTF_8),
                VisualEvaluationGoalBudget.Guard.class
        ));
        assertEquals(historicalReservation.reservationId(), migrated.reservations().getFirst().reservationId());
        assertEquals(200, migrated.snapshot(
                "qwen3.7-flash-2026-07-15", "unrelated-authorization"
        ).goal().tokens());

        var pinned = authorization(
                "visual-flash-pinned", pinnedProfile, "qwen3.7-flash-2026-07-15",
                8, 500_000, 400_000
        );
        var pinnedReservation = migrated.reserve(pinned, request(101, pinnedProfile), NOW.plusSeconds(3));
        migrated.settle(UUID.fromString(pinnedReservation.reservationId()),
                new ProviderUsage(180, 120), 150, NOW.plusSeconds(4));
        var pinnedSnapshot = migrated.snapshot(pinned.model(), pinned.authorizationId());
        assertEquals(2, pinnedSnapshot.goal().attempts());
        assertEquals(500, pinnedSnapshot.goal().tokens());
        assertEquals(1, pinnedSnapshot.authorization().attempts());
        assertEquals(300, pinnedSnapshot.authorization().tokens());
        assertEquals(500, migrated.snapshot(
                historical.model(), historical.authorizationId()
        ).goal().tokens());
    }

    @Test
    void previousV2GuardMigratesWithoutChangingReservations(@TempDir Path directory) throws Exception {
        var json = JsonMapper.builder().build();
        var authorization = authorization("visual-v2-migration", 24, 500_000, 4_000_000);
        var original = new VisualEvaluationGoalBudget(directory, json, NOW);
        var reservation = original.reserve(authorization, request(102), NOW);
        original.settle(UUID.fromString(reservation.reservationId()),
                new ProviderUsage(240, 160), 2_000, NOW.plusSeconds(1));
        var stateBefore = Files.readString(directory.resolve("goal-budget.json"), StandardCharsets.UTF_8);

        Files.writeString(directory.resolve("goal-budget.guard.json"),
                json.writeValueAsString(VisualEvaluationGoalBudget.Guard.previous()),
                StandardCharsets.UTF_8);
        var migrated = new VisualEvaluationGoalBudget(directory, json, NOW.plusSeconds(2));

        assertEquals(VisualEvaluationGoalBudget.Guard.expected(), json.readValue(
                Files.readString(directory.resolve("goal-budget.guard.json"), StandardCharsets.UTF_8),
                VisualEvaluationGoalBudget.Guard.class
        ));
        assertEquals(stateBefore,
                Files.readString(directory.resolve("goal-budget.json"), StandardCharsets.UTF_8));
        assertEquals(reservation.reservationId(), migrated.reservations().getFirst().reservationId());
        assertEquals(400, migrated.snapshot(authorization.model(), authorization.authorizationId())
                .goal().tokens());
    }

    @Test
    void inexactPreviousGuardCannotUseTheMigrationPath(@TempDir Path directory) throws Exception {
        var json = JsonMapper.builder().build();
        new VisualEvaluationGoalBudget(directory, json, NOW);
        var tampered = json.writeValueAsString(VisualEvaluationGoalBudget.Guard.previous())
                .replace("\"maximumTokensPerModel\":1000000",
                        "\"maximumTokensPerModel\":1500000");
        Files.writeString(directory.resolve("goal-budget.guard.json"), tampered,
                StandardCharsets.UTF_8);

        assertThrows(IllegalStateException.class,
                () -> new VisualEvaluationGoalBudget(directory, json, NOW.plusSeconds(1)));
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
        return authorization(id, profile, "qwen3.7-plus", maximumAttempts, maximumTokens, maximumCost);
    }

    private VisualEvaluationAuthorization authorization(
            String id,
            ProfileResource selectedProfile,
            String model,
            int maximumAttempts,
            long maximumTokens,
            long maximumCost
    ) {
        return new VisualEvaluationAuthorization(
                VisualEvaluationAuthorization.VERSION, id, "OPEN", "BASELINE",
                VisualEvaluationAuthorization.INPUT_CLASSIFICATION,
                VisualStageCorpus.VERSION, corpus.sourceSha256(),
                VisualEvaluationIdentity.VERSION + ":" + "b".repeat(64),
                selectedProfile.profile().profileId(), sha256(selectedProfile.snapshotJson()), model,
                corpus.cases().stream().limit(3).map(VisualStageCorpus.EvaluationCase::caseId).toList(),
                maximumAttempts, maximumTokens, maximumCost, 3,
                "yiwer", NOW.minusSeconds(60).toString(), NOW.plusSeconds(43_200).toString(),
                "Synthetic visual stage evaluation"
        );
    }

    private ProviderInferenceRequest request(int runIndex) {
        return request(runIndex, profile);
    }

    private ProviderInferenceRequest request(int runIndex, ProfileResource selectedProfile) {
        return new ProviderInferenceRequest(
                UUID.nameUUIDFromBytes(("run-" + runIndex).getBytes(StandardCharsets.UTF_8)),
                0, InferenceStage.OBSERVE, selectedProfile.profile(),
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
