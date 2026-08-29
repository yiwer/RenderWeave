package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.rendering.api.EvaluationStage;
import cn.hbads.renderweave.rendering.api.Evaluator;
import cn.hbads.renderweave.rendering.api.Evaluator.EvaluationCommand;
import cn.hbads.renderweave.rendering.api.Evaluator.EvaluationOutcome;
import cn.hbads.renderweave.rendering.api.Evaluator.ExternalAssetReadAuthorization;
import cn.hbads.renderweave.rendering.api.Evaluator.OutputSelection;
import cn.hbads.renderweave.rendering.api.Evaluator.OwnerScope;
import cn.hbads.renderweave.rendering.api.Evaluator.RenderRequestId;
import cn.hbads.renderweave.rendering.api.RenderingProblem;
import cn.hbads.renderweave.rendering.spi.AssetResolutionPort;
import cn.hbads.renderweave.rendering.spi.CapabilityStateStore;
import cn.hbads.renderweave.rendering.spi.RenderingCapabilityRuntime;
import cn.hbads.renderweave.rendering.spi.RenderingCapabilityRuntime.CapabilityContract;
import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.schema.identity.SchemaKey;
import cn.hbads.renderweave.schema.identity.VersionTag;
import cn.hbads.renderweave.template.api.DesignDslAuthority;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority;
import cn.hbads.renderweave.template.api.TemplateApplication;
import cn.hbads.renderweave.template.api.TemplateClosureAuthority;
import cn.hbads.renderweave.template.api.TemplateClosureAuthority.ClosureEdge;
import cn.hbads.renderweave.template.api.TemplateClosureAuthority.ClosureOutcome;
import cn.hbads.renderweave.template.api.TemplateClosureAuthority.ClosureSnapshot;
import cn.hbads.renderweave.template.api.TemplateClosureAuthority.TemplateSnapshot;
import cn.hbads.renderweave.template.internal.TemplateModule;
import cn.hbads.renderweave.validation.ResolvedSchema;
import cn.hbads.renderweave.validation.ResolvedSchemaIdentity;
import cn.hbads.renderweave.validation.ResolvedValidationTarget;
import cn.hbads.renderweave.validation.ValidationTarget;
import cn.hbads.renderweave.validation.ValidationTargetResolver;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluatorContractTest {

    private static final StaticSchemaRef SCHEMA = new StaticSchemaRef(
            SchemaKey.systemProvided("system-empty"), VersionTag.of("v1"));
    private static final String ROOT_ID = "00000000-0000-4000-8000-0000000000a1";
    private static final String CHILD_ID = "00000000-0000-4000-8000-0000000000c1";
    private static final String AUTH_DIGEST = "sha256:" + "5".repeat(64);
    private static final String DEFAULT_BUDGET_VECTOR = budgetVector(
            4_096, 8_192, 4_096, 4_096, 2_048, 16_777_216, 1_048_576,
            16_777_216);
    private static final String PUBLIC_IMAGE_DEFINITION =
            "00000000-0000-4000-8000-0000000000d1";
    private static final String DEFAULT_IMAGE =
            "00000000-0000-4000-8000-0000000000a1";
    private static final String OVERRIDE_IMAGE_LOSER =
            "00000000-0000-4000-8000-0000000000a3";
    private static final String OVERRIDE_IMAGE =
            "00000000-0000-4000-8000-0000000000a2";

    @Test
    void closureWithoutCapabilityDeclarationsDoesNoStateWork() {
        var stateStore = new RecordingCapabilityStateStore();
        var runtime = new RecordingCapabilityRuntime();
        var evaluator = evaluator(closureWith(canvasWithRect()), resolver(), stateStore, runtime);

        assertInstanceOf(EvaluationOutcome.SealedDocument.class,
                evaluator.evaluate(command("{\"rootDocument\":{}}")));

        assertEquals(0, runtime.establishCalls);
        assertEquals(0, runtime.restoreCalls);
        assertEquals(0, stateStore.loadCalls);
        assertEquals(0, stateStore.saveCalls);
    }

    @Test
    void unsupportedExactContractRejectsBeforeStateStoreWork() {
        var stateStore = new RecordingCapabilityStateStore();
        var runtime = new RequirementCheckingRuntime(
                Set.of(CapabilityContract.CLOCK_1_0),
                Set.of(CapabilityContract.RANDOM_1_0));
        var evaluator = evaluator(
                closureWith(withUnusedClock(canvasWithRect())), resolver(), stateStore, runtime);

        var rejected = assertInstanceOf(EvaluationOutcome.Rejected.class,
                evaluator.evaluate(command("{\"rootDocument\":{}}")));

        assertEquals(EvaluationStage.CAPABILITY_STATE, rejected.stage());
        assertEquals(RenderingProblem.ProblemCode.CAPABILITY_PROFILE_UNAVAILABLE,
                rejected.problem().code());
        assertEquals(0, stateStore.loadCalls);
        assertEquals(0, stateStore.saveCalls);
    }

    @Test
    void staticCapabilitySourceOverBudgetRejectsBeforeCapabilityStateWork() {
        var stateStore = new RecordingCapabilityStateStore();
        var runtime = new RecordingCapabilityRuntime();
        var evaluator = evaluator(
                closureWith(withUnusedClock(canvasWithRect())),
                resolver(),
                stateStore,
                runtime,
                budgetVector(0, 8, 4, 4, 2_048, 16_777_216, 1_048_576,
                        16_777_216));

        var rejected = assertInstanceOf(EvaluationOutcome.Rejected.class,
                evaluator.evaluate(command("{\"rootDocument\":{}}")));

        assertEquals(EvaluationStage.TEMPLATE_CLOSURE, rejected.stage());
        assertEquals(RenderingProblem.ProblemCode.CAPABILITY_BUDGET_EXCEEDED,
                rejected.problem().code());
        assertEquals("capabilityRuntime.staticCapabilitySources",
                rejected.problem().limitId().orElseThrow().value());
        assertEquals(0, runtime.establishCalls);
        assertEquals(0, stateStore.loadCalls);
        assertEquals(0, stateStore.saveCalls);
    }

    @Test
    void explicitRoundingScaleOverBudgetRejectsAtClosureBeforeAllDownstreamWork() {
        var stateStore = new RecordingCapabilityStateStore();
        var runtime = new RecordingCapabilityRuntime();
        var inputResolutions = new AtomicInteger();
        var targetResolver = resolver();
        ValidationTargetResolver recordingResolver = target -> {
            inputResolutions.incrementAndGet();
            return targetResolver.resolve(target);
        };
        var evaluator = evaluator(
                closureWith(withUnusedRandomScaleOverflow(canvasWithRect())),
                recordingResolver,
                stateStore,
                runtime);

        var rejected = assertInstanceOf(EvaluationOutcome.Rejected.class,
                evaluator.evaluate(command("{\"rootDocument\":{}}")));

        assertEquals(EvaluationStage.TEMPLATE_CLOSURE, rejected.stage());
        assertEquals(RenderingProblem.ProblemCode.EXPRESSION_LIMIT_EXCEEDED,
                rejected.problem().code());
        assertEquals("expression.explicitRoundingScaleMax",
                rejected.problem().limitId().orElseThrow().value());
        assertEquals(0, inputResolutions.get());
        assertEquals(0, runtime.establishCalls);
        assertEquals(0, runtime.restoreCalls);
        assertEquals(0, stateStore.loadCalls);
        assertEquals(0, stateStore.saveCalls);
    }

    @Test
    void sourceUtf8OverBudgetRejectsAtClosureBeforeAllDownstreamWork() {
        var stateStore = new RecordingCapabilityStateStore();
        var runtime = new RecordingCapabilityRuntime();
        var inputResolutions = new AtomicInteger();
        var targetResolver = resolver();
        ValidationTargetResolver recordingResolver = target -> {
            inputResolutions.incrementAndGet();
            return targetResolver.resolve(target);
        };
        var evaluator = evaluator(
                closureWith(withUnusedRandomSourceOverflow(canvasWithRect())),
                recordingResolver,
                stateStore,
                runtime);

        var rejected = assertInstanceOf(EvaluationOutcome.Rejected.class,
                evaluator.evaluate(command("{\"rootDocument\":{}}")));

        assertEquals(EvaluationStage.TEMPLATE_CLOSURE, rejected.stage());
        assertEquals(RenderingProblem.ProblemCode.EXPRESSION_LIMIT_EXCEEDED,
                rejected.problem().code());
        assertEquals("expression.sourceUtf8BytesPerExpression",
                rejected.problem().limitId().orElseThrow().value());
        assertEquals(0, inputResolutions.get());
        assertEquals(0, runtime.establishCalls);
        assertEquals(0, runtime.restoreCalls);
        assertEquals(0, stateStore.loadCalls);
        assertEquals(0, stateStore.saveCalls);
    }

    @Test
    void randomDemandOverBudgetRejectsBeforeCallingProviderForTheNextPosition() {
        var runtime = new SupplyingCapabilityRuntime();
        var evaluator = evaluator(
                closureWith(loopRandomCapabilityDocument("[\"a\",\"b\"]")),
                resolver(),
                new RecordingCapabilityStateStore(),
                runtime,
                budgetVector(1, 8, 4, 1, 2_048, 16_777_216, 1_048_576,
                        16_777_216));

        var rejected = assertInstanceOf(EvaluationOutcome.Rejected.class,
                evaluator.evaluate(command("{\"rootDocument\":{}}")));

        assertEquals(EvaluationStage.MATERIALIZATION, rejected.stage());
        assertEquals(RenderingProblem.ProblemCode.CAPABILITY_BUDGET_EXCEEDED,
                rejected.problem().code());
        assertEquals("capabilityRuntime.randomDemands",
                rejected.problem().limitId().orElseThrow().value());
        assertEquals(1, runtime.supplyCalls);
    }

    @Test
    void totalDemandBudgetPrecedesTheSecondProviderCallAcrossDistinctAliases() {
        var runtime = new SupplyingCapabilityRuntime();
        var evaluator = evaluator(
                closureWith(twoRandomAliasCapabilityDocument()),
                resolver(),
                new RecordingCapabilityStateStore(),
                runtime,
                budgetVector(2, 1, 4, 2, 2_048, 16_777_216, 1_048_576,
                        16_777_216));

        var rejected = assertInstanceOf(EvaluationOutcome.Rejected.class,
                evaluator.evaluate(command("{\"rootDocument\":{}}")));

        assertEquals(EvaluationStage.MATERIALIZATION, rejected.stage());
        assertEquals(RenderingProblem.ProblemCode.CAPABILITY_BUDGET_EXCEEDED,
                rejected.problem().code());
        assertEquals("capabilityRuntime.totalDemands",
                rejected.problem().limitId().orElseThrow().value());
        assertEquals(1, runtime.supplyCalls);
    }

    @Test
    void clockDemandBudgetCountsDistinctAliasesEvenWhenTheSnapshotValueMatches() {
        var runtime = new SupplyingCapabilityRuntime();
        var evaluator = evaluator(
                closureWith(twoClockAliasCapabilityDocument()),
                resolver(),
                new RecordingCapabilityStateStore(),
                runtime,
                budgetVector(2, 8, 1, 4, 2_048, 16_777_216, 1_048_576,
                        16_777_216));

        var rejected = assertInstanceOf(EvaluationOutcome.Rejected.class,
                evaluator.evaluate(command("{\"rootDocument\":{}}")));

        assertEquals(EvaluationStage.MATERIALIZATION, rejected.stage());
        assertEquals(RenderingProblem.ProblemCode.CAPABILITY_BUDGET_EXCEEDED,
                rejected.problem().code());
        assertEquals("capabilityRuntime.clockDemands",
                rejected.problem().limitId().orElseThrow().value());
        assertEquals(1, runtime.supplyCalls);
    }

    @Test
    void positionPerDemandBudgetRejectsBeforeProviderWork() {
        assertFirstDemandByteLimit(
                budgetVector(2, 2, 2, 2, 1, 16_777_216, 1_048_576, 16_777_216),
                "capabilityRuntime.positionCanonicalBytesPerDemand",
                0);
    }

    @Test
    void positionTotalBudgetRejectsBeforeProviderWork() {
        assertFirstDemandByteLimit(
                budgetVector(2, 2, 2, 2, 2_048, 1, 1_048_576, 16_777_216),
                "capabilityRuntime.positionCanonicalBytesTotal",
                0);
    }

    @Test
    void resultDigestStreamingBudgetRejectsBeforeReturningTheProviderResult() {
        assertFirstDemandByteLimit(
                budgetVector(2, 2, 2, 2, 2_048, 16_777_216, 1_048_576, 1),
                "capabilityRuntime.resultDigestStreamingBytes",
                1);
    }

    @Test
    void unusedCapabilitySourceConsumesNoDemandBudget() {
        var runtime = new SupplyingCapabilityRuntime();
        var evaluator = evaluator(
                closureWith(withUnusedRandom(canvasWithRect())),
                resolver(),
                new RecordingCapabilityStateStore(),
                runtime,
                budgetVector(1, 0, 0, 0, 0, 0, 1_048_576, 0));

        assertInstanceOf(EvaluationOutcome.SealedDocument.class,
                evaluator.evaluate(command("{\"rootDocument\":{}}")));
        assertEquals(0, runtime.supplyCalls);
    }

    @Test
    void invocationMemoAcrossLoopConsumersReservesOnlyOneDemand() {
        var runtime = new SupplyingCapabilityRuntime();
        var evaluator = evaluator(
                closureWith(invocationRandomCapabilityDocument("[\"a\",\"b\"]")),
                resolver(),
                new RecordingCapabilityStateStore(),
                runtime,
                budgetVector(1, 1, 0, 1, 2_048, 2_048, 1_048_576, 2_048));

        assertInstanceOf(EvaluationOutcome.SealedDocument.class,
                evaluator.evaluate(command("{\"rootDocument\":{}}")));
        assertEquals(1, runtime.supplyCalls);
    }

    @Test
    void randomRejectionExhaustionFailsClosedAtTheFirstDemand() {
        var runtime = new RejectionExhaustedCapabilityRuntime();
        var evaluator = evaluator(
                closureWith(twoRandomAliasCapabilityDocument()),
                resolver(),
                new RecordingCapabilityStateStore(),
                runtime);

        var rejected = assertInstanceOf(EvaluationOutcome.Rejected.class,
                evaluator.evaluate(command("{\"rootDocument\":{}}")));

        assertEquals(EvaluationStage.MATERIALIZATION, rejected.stage());
        assertEquals(RenderingProblem.ProblemCode.CAPABILITY_RESULT_INVALID,
                rejected.problem().code());
        assertEquals("capabilityRuntime.randomRejectionAttempts",
                rejected.problem().limitId().orElseThrow().value());
        assertEquals(1, runtime.supplyCalls);
    }

    @Test
    void randomRejectionAttemptProfileRejectsNonExactValues() {
        for (long value : List.of(127L, 129L)) {
            var vector = budgetVector(
                    1, 8, 4, 4, 2_048, 16_777_216, 1_048_576,
                    16_777_216, 3, value);
            assertThrows(IllegalArgumentException.class, () -> evaluator(
                    closureWith(withUnusedRandom(canvasWithRect())),
                    resolver(),
                    new RecordingCapabilityStateStore(),
                    new RecordingCapabilityRuntime(),
                    vector));
        }
    }

    private static void assertFirstDemandByteLimit(
            String effectiveBudgetVector,
            String expectedLimitId,
            int expectedSupplyCalls
    ) {
        var runtime = new SupplyingCapabilityRuntime();
        var evaluator = evaluator(
                closureWith(twoRandomAliasCapabilityDocument()),
                resolver(),
                new RecordingCapabilityStateStore(),
                runtime,
                effectiveBudgetVector);

        var rejected = assertInstanceOf(EvaluationOutcome.Rejected.class,
                evaluator.evaluate(command("{\"rootDocument\":{}}")));

        assertEquals(EvaluationStage.MATERIALIZATION, rejected.stage());
        assertEquals(RenderingProblem.ProblemCode.CAPABILITY_BUDGET_EXCEEDED,
                rejected.problem().code());
        assertEquals(expectedLimitId, rejected.problem().limitId().orElseThrow().value());
        assertEquals(expectedSupplyCalls, runtime.supplyCalls);
    }

    @Test
    void sameRequestAndFingerprintRestoresCommittedCapabilityStateWithoutResampling() {
        var stateStore = new RecordingCapabilityStateStore();
        var runtime = new RecordingCapabilityRuntime();
        var evaluator = evaluator(closureWith(canvasWithUnusedRandom()), resolver(), stateStore, runtime);

        assertInstanceOf(EvaluationOutcome.SealedDocument.class,
                evaluator.evaluate(command("{\"rootDocument\":{}}")));
        assertInstanceOf(EvaluationOutcome.SealedDocument.class,
                evaluator.evaluate(command("{\"rootDocument\":{}}")));

        assertEquals(1, runtime.establishCalls);
        assertEquals(1, runtime.restoreCalls);
        assertEquals(1, stateStore.saveCalls);
        assertEquals(2, stateStore.loadCalls);
    }

    @Test
    void capabilityStateRecordOverBudgetRejectsBeforeStoreCommit() {
        var stateStore = new RecordingCapabilityStateStore();
        var runtime = new RecordingCapabilityRuntime();
        var evaluator = evaluator(
                closureWith(withUnusedClock(canvasWithRect())),
                resolver(),
                stateStore,
                runtime,
                budgetVector(1, 8, 4, 4, 2_048, 16_777_216, 2, 16_777_216));

        var rejected = assertInstanceOf(EvaluationOutcome.Rejected.class,
                evaluator.evaluate(command("{\"rootDocument\":{}}")));

        assertEquals(EvaluationStage.CAPABILITY_STATE, rejected.stage());
        assertEquals(RenderingProblem.ProblemCode.CAPABILITY_BUDGET_EXCEEDED,
                rejected.problem().code());
        assertEquals("capabilityRuntime.capabilityStateRecordBytes",
                rejected.problem().limitId().orElseThrow().value());
        assertEquals(1, runtime.establishCalls);
        assertEquals(1, stateStore.loadCalls);
        assertEquals(0, stateStore.saveCalls);
    }

    @Test
    void capabilityStateRecordAtLimitCommitsNormally() {
        var stateStore = new RecordingCapabilityStateStore();
        var runtime = new RecordingCapabilityRuntime();
        var evaluator = evaluator(
                closureWith(withUnusedClock(canvasWithRect())),
                resolver(),
                stateStore,
                runtime,
                budgetVector(1, 8, 4, 4, 2_048, 16_777_216, 3, 16_777_216));

        assertInstanceOf(EvaluationOutcome.SealedDocument.class,
                evaluator.evaluate(command("{\"rootDocument\":{}}")));

        assertEquals(1, runtime.establishCalls);
        assertEquals(1, stateStore.loadCalls);
        assertEquals(1, stateStore.saveCalls);
    }

    @Test
    void transientInitializationFailureCanSucceedOnTheThirdFrozenAttempt() {
        var stateStore = new RecordingCapabilityStateStore();
        var runtime = new TransientEstablishRuntime(2);
        var evaluator = evaluator(
                closureWith(withUnusedClock(canvasWithRect())), resolver(), stateStore, runtime);

        assertInstanceOf(EvaluationOutcome.SealedDocument.class,
                evaluator.evaluate(command("{\"rootDocument\":{}}")));

        assertEquals(3, runtime.establishCalls);
        assertEquals(1, stateStore.saveCalls);
    }

    @Test
    void initializationAttemptProfileRejectsNonExactValues() {
        for (long value : List.of(2L, 4L)) {
            var vector = budgetVector(
                    1, 8, 4, 4, 2_048, 16_777_216, 1_048_576,
                    16_777_216, value);
            assertThrows(IllegalArgumentException.class, () -> evaluator(
                    closureWith(withUnusedClock(canvasWithRect())),
                    resolver(),
                    new RecordingCapabilityStateStore(),
                    new RecordingCapabilityRuntime(),
                    vector));
        }
    }

    @Test
    void initializationAttemptLimitRejectsBeforeFourthFrozenAttempt() {
        var stateStore = new RecordingCapabilityStateStore();
        var runtime = new TransientEstablishRuntime(Integer.MAX_VALUE);
        var evaluator = evaluator(
                closureWith(withUnusedClock(canvasWithRect())),
                resolver(),
                stateStore,
                runtime);

        var rejected = assertInstanceOf(EvaluationOutcome.Rejected.class,
                evaluator.evaluate(command("{\"rootDocument\":{}}")));

        assertEquals(EvaluationStage.CAPABILITY_STATE, rejected.stage());
        assertEquals(RenderingProblem.ProblemCode.CAPABILITY_STATE_UNAVAILABLE,
                rejected.problem().code());
        assertEquals("capabilityRuntime.initializationAttempts",
                rejected.problem().limitId().orElseThrow().value());
        assertEquals(3, runtime.establishCalls);
        assertEquals(0, stateStore.saveCalls);
    }

    @Test
    void unknownSaveThatCommittedIsQueriedAndRestoredWithoutResampling() {
        var stateStore = new CommittedUnknownCapabilityStateStore();
        var runtime = new RecordingCapabilityRuntime();
        var evaluator = evaluator(
                closureWith(withUnusedClock(canvasWithRect())), resolver(), stateStore, runtime);

        assertInstanceOf(EvaluationOutcome.SealedDocument.class,
                evaluator.evaluate(command("{\"rootDocument\":{}}")));

        assertEquals(1, runtime.establishCalls);
        assertEquals(1, runtime.restoreCalls);
        assertEquals(1, stateStore.saveCalls);
        assertEquals(2, stateStore.loadCalls);
    }

    @Test
    void unknownSaveRetriesOnlyAfterTheRecordIsConfirmedMissing() {
        var stateStore = new MissingUnknownThenStoredCapabilityStateStore();
        var runtime = new RecordingCapabilityRuntime();
        var evaluator = evaluator(
                closureWith(withUnusedClock(canvasWithRect())), resolver(), stateStore, runtime);

        assertInstanceOf(EvaluationOutcome.SealedDocument.class,
                evaluator.evaluate(command("{\"rootDocument\":{}}")));

        assertEquals(2, runtime.establishCalls);
        assertEquals(0, runtime.restoreCalls);
        assertEquals(2, stateStore.saveCalls);
        assertEquals(2, stateStore.loadCalls);
    }

    @Test
    void initializationThatReachesTheDeadlineDoesNotCommitState() {
        var clock = new MutableClock(1_000L);
        var ticker = new MutableTicker(0L);
        var stateStore = new RecordingCapabilityStateStore();
        var runtime = new DeadlineAdvancingRuntime(ticker, 1_000L);
        var evaluator = evaluator(
                closureWith(withUnusedClock(canvasWithRect())),
                resolver(),
                stateStore,
                runtime,
                DEFAULT_BUDGET_VECTOR,
                clock,
                ticker);

        var rejected = assertInstanceOf(EvaluationOutcome.Rejected.class,
                evaluator.evaluate(command(
                        "{\"rootDocument\":{}}", 2_000L, 1_000L)));

        assertEquals(EvaluationStage.CAPABILITY_STATE, rejected.stage());
        assertEquals(RenderingProblem.ProblemCode.CAPABILITY_DEADLINE_EXCEEDED,
                rejected.problem().code());
        assertTrue(rejected.problem().limitId().isEmpty());
        assertEquals(1, runtime.establishCalls);
        assertEquals(0, stateStore.saveCalls);
    }

    @Test
    void stateCommitThatReachesTheDeadlineDoesNotEnterTheRootFrame() {
        var clock = new MutableClock(1_000L);
        var ticker = new MutableTicker(0L);
        var stateStore = new DeadlineAdvancingCapabilityStateStore(ticker, 1_000L);
        var runtime = new RecordingCapabilityRuntime();
        var evaluator = evaluator(
                closureWith(withUnusedClock(canvasWithRect())),
                resolver(),
                stateStore,
                runtime,
                DEFAULT_BUDGET_VECTOR,
                clock,
                ticker);

        var rejected = assertInstanceOf(EvaluationOutcome.Rejected.class,
                evaluator.evaluate(command(
                        "{\"rootDocument\":{}}", 2_000L, 1_000L)));

        assertEquals(EvaluationStage.CAPABILITY_STATE, rejected.stage());
        assertEquals(RenderingProblem.ProblemCode.CAPABILITY_DEADLINE_EXCEEDED,
                rejected.problem().code());
        assertEquals(1, runtime.establishCalls);
        assertEquals(1, stateStore.saveCalls);
    }

    @Test
    void stateExpiryIsFixedAcrossAConfirmedMissingRetry() {
        var clock = new MutableClock(1_000L);
        var stateStore = new ExpiryRecordingCapabilityStateStore(clock);
        var evaluator = evaluator(
                closureWith(withUnusedClock(canvasWithRect())),
                resolver(),
                stateStore,
                new RecordingCapabilityRuntime(),
                DEFAULT_BUDGET_VECTOR,
                clock);

        assertInstanceOf(EvaluationOutcome.SealedDocument.class,
                evaluator.evaluate(command("{\"rootDocument\":{}}", 61_123L)));

        assertEquals(2, stateStore.requests.size());
        assertEquals(1_000L, stateStore.requests.get(0).issuedAtEpochMilli());
        assertEquals(1_000L, stateStore.requests.get(1).issuedAtEpochMilli());
        assertEquals(361_123L, stateStore.requests.get(0).expiresAtEpochMilli());
        assertEquals(361_123L, stateStore.requests.get(1).expiresAtEpochMilli());
    }

    @Test
    void capabilityRecoveryExpiryOverflowFailsClosedBeforeSampling() {
        var stateStore = new RecordingCapabilityStateStore();
        var runtime = new RecordingCapabilityRuntime();
        var evaluator = evaluator(
                closureWith(withUnusedClock(canvasWithRect())),
                resolver(),
                stateStore,
                runtime,
                DEFAULT_BUDGET_VECTOR,
                new MutableClock(1_000L));

        var rejected = assertInstanceOf(EvaluationOutcome.Rejected.class,
                evaluator.evaluate(command("{\"rootDocument\":{}}", Long.MAX_VALUE)));

        assertEquals(EvaluationStage.CAPABILITY_STATE, rejected.stage());
        assertEquals(RenderingProblem.ProblemCode.CAPABILITY_STATE_UNAVAILABLE,
                rejected.problem().code());
        assertEquals(0, runtime.establishCalls);
        assertEquals(0, stateStore.saveCalls);
    }

    @Test
    void loadedStateThatReachesTheDeadlineIsNotRestored() {
        var clock = new MutableClock(1_000L);
        var ticker = new MutableTicker(0L);
        var stateStore = new DeadlineLoadedCapabilityStateStore(ticker, 1_000L);
        var runtime = new RecordingCapabilityRuntime();
        var evaluator = evaluator(
                closureWith(withUnusedClock(canvasWithRect())),
                resolver(),
                stateStore,
                runtime,
                DEFAULT_BUDGET_VECTOR,
                clock,
                ticker);

        var rejected = assertInstanceOf(EvaluationOutcome.Rejected.class,
                evaluator.evaluate(command(
                        "{\"rootDocument\":{}}", 2_000L, 1_000L)));

        assertEquals(EvaluationStage.CAPABILITY_STATE, rejected.stage());
        assertEquals(RenderingProblem.ProblemCode.CAPABILITY_DEADLINE_EXCEEDED,
                rejected.problem().code());
        assertEquals(0, runtime.establishCalls);
        assertEquals(0, runtime.restoreCalls);
    }

    @Test
    void unknownCommitResolvedAtTheDeadlineIsNotRestored() {
        var clock = new MutableClock(1_000L);
        var ticker = new MutableTicker(0L);
        var stateStore = new UnknownCommittedDeadlineStateStore(ticker, 1_000L);
        var runtime = new RecordingCapabilityRuntime();
        var evaluator = evaluator(
                closureWith(withUnusedClock(canvasWithRect())),
                resolver(),
                stateStore,
                runtime,
                DEFAULT_BUDGET_VECTOR,
                clock,
                ticker);

        var rejected = assertInstanceOf(EvaluationOutcome.Rejected.class,
                evaluator.evaluate(command(
                        "{\"rootDocument\":{}}", 2_000L, 1_000L)));

        assertEquals(EvaluationStage.CAPABILITY_STATE, rejected.stage());
        assertEquals(RenderingProblem.ProblemCode.CAPABILITY_DEADLINE_EXCEEDED,
                rejected.problem().code());
        assertEquals(1, runtime.establishCalls);
        assertEquals(0, runtime.restoreCalls);
        assertEquals(2, stateStore.loadCalls);
    }

    @Test
    void clockOnlyDeclarationEstablishesOnlyTheClockComponent() {
        var evaluator = evaluator(
                closureWith(withUnusedClock(canvasWithRect())),
                resolver(),
                new RecordingCapabilityStateStore(),
                new RequirementCheckingRuntime(Set.of(
                        RenderingCapabilityRuntime.CapabilityContract.CLOCK_1_0)));

        assertInstanceOf(EvaluationOutcome.SealedDocument.class,
                evaluator.evaluate(command("{\"rootDocument\":{}}")));
    }

    @Test
    void sameRequestWithDifferentAuthorizationFingerprintRejectsAtCapabilityState() {
        var stateStore = new RecordingCapabilityStateStore();
        var evaluator = evaluator(
                closureWith(canvasWithUnusedRandom()), resolver(), stateStore,
                new RecordingCapabilityRuntime());
        assertInstanceOf(EvaluationOutcome.SealedDocument.class,
                evaluator.evaluate(command("{\"rootDocument\":{}}")));

        var changed = command("{\"rootDocument\":{}}", 61_000L, "sha256:" + "6".repeat(64));
        var rejected = assertInstanceOf(EvaluationOutcome.Rejected.class,
                evaluator.evaluate(changed));

        assertEquals(EvaluationStage.CAPABILITY_STATE, rejected.stage());
        assertEquals(RenderingProblem.ProblemCode.CAPABILITY_STATE_CONFLICT,
                rejected.problem().code());
    }

    @Test
    void capabilityStateDependencyUnavailableFailsBeforeMaterialization() {
        var unavailable = new RecordingCapabilityStateStore();
        unavailable.unavailable = true;
        var evaluator = evaluator(
                closureWith(canvasWithUnusedRandom()), resolver(), unavailable,
                new RecordingCapabilityRuntime());

        var rejected = assertInstanceOf(EvaluationOutcome.Rejected.class,
                evaluator.evaluate(command("{\"rootDocument\":{}}")));

        assertEquals(EvaluationStage.CAPABILITY_STATE, rejected.stage());
        assertEquals(RenderingProblem.ProblemCode.CAPABILITY_STATE_UNAVAILABLE,
                rejected.problem().code());
    }

    @Test
    void assetAdmissionFailurePrecedesCapabilityStateInitialization() {
        var evaluator = new CanonicalEvaluator(
                scriptedClosure(closureWith(withUnusedRandom(canvasWithImage()))),
                TemplateModule.designSemanticAuthority(),
                TemplateModule.designDslAuthority(),
                new RejectingAssetPort(),
                new RenderingCapabilityRuntime() {
                    @Override
                    public Established establish(CapabilityRequirements requirements) {
                        throw new IllegalStateException("capability state must not be initialized");
                    }

                    @Override
                    public Runtime restore(CapabilityRequirements requirements, byte[] sealedState) {
                        throw new IllegalStateException("capability state must not be restored");
                    }

                    @Override
                    public Set<CapabilityContract> supportedContracts() {
                        return Set.of(CapabilityContract.RANDOM_1_0);
                    }
                },
                new RecordingCapabilityStateStore(),
                DEFAULT_BUDGET_VECTOR,
                resolver(),
                Clock.fixed(Instant.ofEpochMilli(1_000L), ZoneOffset.UTC));

        var rejected = assertInstanceOf(EvaluationOutcome.Rejected.class,
                evaluator.evaluate(command("{\"rootDocument\":{}}")));

        assertEquals(EvaluationStage.ASSET_ADMISSION, rejected.stage());
        assertEquals(RenderingProblem.ProblemCode.ASSET_NOT_FOUND,
                rejected.problem().code());
    }

    @Test
    void externalPublicAssetOverrideWithoutReadIsHiddenBeforeCapabilityState() {
        var stateStore = new RecordingCapabilityStateStore();
        var runtime = new RecordingCapabilityRuntime();
        var assets = new RecordingAssetPort();
        var evaluator = evaluator(
                closureWith(withUnusedRandom(canvasWithPublicImageCustom())),
                resolver(), assets, stateStore, runtime);

        var rejected = assertInstanceOf(EvaluationOutcome.Rejected.class,
                evaluator.evaluate(command(externalImageEnvelope(),
                        ExternalAssetReadAuthorization.DENIED)));

        assertEquals(EvaluationStage.ASSET_ADMISSION, rejected.stage());
        assertEquals(RenderingProblem.ProblemCode.ASSET_NOT_FOUND,
                rejected.problem().code());
        assertEquals(List.of(DEFAULT_IMAGE), assets.precheckedAssetIds);
        assertEquals(0, runtime.establishCalls);
        assertEquals(0, stateStore.loadCalls);
        assertEquals(0, stateStore.saveCalls);
    }

    @Test
    void externalPublicAssetOverrideWithReadIsAdmittedAfterAuthoredDefault() {
        var assets = new RecordingAssetPort();
        var evaluator = evaluator(
                closureWith(canvasWithPublicImageCustom()),
                resolver(), assets, new RecordingCapabilityStateStore(), scriptedRuntime());

        assertInstanceOf(EvaluationOutcome.SealedDocument.class,
                evaluator.evaluate(command(externalImageEnvelope(),
                        ExternalAssetReadAuthorization.GRANTED)));

        assertEquals(List.of(DEFAULT_IMAGE, OVERRIDE_IMAGE), assets.precheckedAssetIds);
    }

    @Test
    void externalAssetAuthorityUnavailabilityDoesNotProbeOverrideOrInitializeCapabilities() {
        var stateStore = new RecordingCapabilityStateStore();
        var runtime = new RecordingCapabilityRuntime();
        var assets = new RecordingAssetPort();
        var evaluator = evaluator(
                closureWith(withUnusedRandom(canvasWithPublicImageCustom())),
                resolver(), assets, stateStore, runtime);

        var rejected = assertInstanceOf(EvaluationOutcome.Rejected.class,
                evaluator.evaluate(command(externalImageEnvelope(),
                        ExternalAssetReadAuthorization.UNAVAILABLE)));

        assertEquals(EvaluationStage.ASSET_ADMISSION, rejected.stage());
        assertEquals(RenderingProblem.ProblemCode.EVALUATION_FAILED,
                rejected.problem().code());
        assertEquals(List.of(DEFAULT_IMAGE), assets.precheckedAssetIds);
        assertEquals(0, runtime.establishCalls);
        assertEquals(0, stateStore.loadCalls);
        assertEquals(0, stateStore.saveCalls);
    }

    @Test
    void authoredAssetDoesNotRequireCallerAssetRead() {
        var assets = new CapturingAssetPort();
        var evaluator = new CanonicalEvaluator(
                scriptedClosure(closureWith(canvasWithImage())),
                TemplateModule.designSemanticAuthority(),
                TemplateModule.designDslAuthority(),
                assets,
                scriptedRuntime(),
                new RecordingCapabilityStateStore(),
                DEFAULT_BUDGET_VECTOR,
                resolver(),
                Clock.fixed(Instant.ofEpochMilli(1_000L), ZoneOffset.UTC));

        assertInstanceOf(EvaluationOutcome.SealedDocument.class,
                evaluator.evaluate(command("{\"rootDocument\":{}}",
                        ExternalAssetReadAuthorization.DENIED)));
        assertTrue(assets.lastRequest != null);
    }

    @Test
    void ignoredUnknownOverrideDoesNotRequireCallerAssetRead() {
        var evaluator = evaluator(closureWith(canvasWithRect()), resolver());
        var envelope = "{\"rootDocument\":{},\"customValues\":[{"
                + "\"definitionId\":\"00000000-0000-4000-8000-0000000000f9\","
                + "\"value\":{\"assetId\":\"" + OVERRIDE_IMAGE + "\"}}]}";

        assertInstanceOf(EvaluationOutcome.SealedDocument.class,
                evaluator.evaluate(command(
                        envelope,
                        ExternalAssetReadAuthorization.DENIED)));
    }

    @Test
    void actualTemplateInvocationAboveLimitRejectsBeforeTheNextChildFrame() {
        var evaluator = evaluator(closureWithTemplateUseChildren(256), resolver());

        var rejected = assertInstanceOf(EvaluationOutcome.Rejected.class,
                evaluator.evaluate(command("{\"rootDocument\":{}}")));

        assertEquals(EvaluationStage.MATERIALIZATION, rejected.stage());
        assertEquals(RenderingProblem.ProblemCode.EVALUATION_BUDGET_EXCEEDED,
                rejected.problem().code());
        assertEquals("closureAndExpansion.actualTemplateInvocations",
                rejected.problem().limitId().orElseThrow().value());
    }

    @Test
    void actualTemplateInvocationBelowLimitIsAccepted() {
        var evaluator = evaluator(closureWithTemplateUseChildren(254), resolver());

        assertInstanceOf(EvaluationOutcome.SealedDocument.class,
                evaluator.evaluate(command("{\"rootDocument\":{}}")));
    }

    @Test
    void actualTemplateInvocationAtLimitIsAccepted() {
        var evaluator = evaluator(closureWithTemplateUseChildren(255), resolver());

        assertInstanceOf(EvaluationOutcome.SealedDocument.class,
                evaluator.evaluate(command("{\"rootDocument\":{}}")));
    }

    @Test
    void skippedTemplateUsesDoNotConsumeActualInvocationBudget() {
        var evaluator = evaluator(closureWithSkippedTemplateUses(256), resolver());
        var outcome = evaluator.evaluate(command("{\"rootDocument\":{}}"));

        assertInstanceOf(EvaluationOutcome.SealedDocument.class,
                outcome, outcome::toString);
    }

    @Test
    void invocationDepthAboveLimitRejectsBeforeTheNextChildFrame() {
        var evaluator = evaluator(closureWithInvocationDepth(17), resolver());

        var rejected = assertInstanceOf(EvaluationOutcome.Rejected.class,
                evaluator.evaluate(command("{\"rootDocument\":{}}")));

        assertEquals(EvaluationStage.MATERIALIZATION, rejected.stage());
        assertEquals(RenderingProblem.ProblemCode.EVALUATION_BUDGET_EXCEEDED,
                rejected.problem().code());
        assertEquals("closureAndExpansion.invocationDepth",
                rejected.problem().limitId().orElseThrow().value());
    }

    @Test
    void invocationDepthBelowLimitIsAccepted() {
        var evaluator = evaluator(closureWithInvocationDepth(15), resolver());

        assertInstanceOf(EvaluationOutcome.SealedDocument.class,
                evaluator.evaluate(command("{\"rootDocument\":{}}")));
    }

    @Test
    void invocationDepthAtLimitIsAccepted() {
        var evaluator = evaluator(closureWithInvocationDepth(16), resolver());

        assertInstanceOf(EvaluationOutcome.SealedDocument.class,
                evaluator.evaluate(command("{\"rootDocument\":{}}")));
    }

    @Test
    void repeatCollectionAboveLimitRejectsBeforeTheFirstLoopFrame() {
        var runtime = new SupplyingCapabilityRuntime();
        var evaluator = evaluator(
                closureWith(loopRandomCapabilityDocument(repeatedTextItems(1_001))),
                resolver(),
                new RecordingCapabilityStateStore(),
                runtime);

        var rejected = assertInstanceOf(EvaluationOutcome.Rejected.class,
                evaluator.evaluate(command("{\"rootDocument\":{}}")));

        assertEquals(EvaluationStage.MATERIALIZATION, rejected.stage());
        assertEquals(RenderingProblem.ProblemCode.EVALUATION_BUDGET_EXCEEDED,
                rejected.problem().code());
        assertEquals("closureAndExpansion.repeatCollectionItemsPerOccurrence",
                rejected.problem().limitId().orElseThrow().value());
        assertEquals(0, runtime.supplyCalls);
    }

    @Test
    void repeatCollectionBelowLimitIsAccepted() {
        assertRepeatCollectionAccepted(999);
    }

    @Test
    void repeatCollectionAtLimitIsAccepted() {
        assertRepeatCollectionAccepted(1_000);
    }

    private static void assertRepeatCollectionAccepted(int itemCount) {
        var runtime = new SupplyingCapabilityRuntime();
        var evaluator = evaluator(
                closureWith(loopRandomCapabilityDocument(repeatedTextItems(itemCount))),
                resolver(),
                new RecordingCapabilityStateStore(),
                runtime);

        assertInstanceOf(EvaluationOutcome.SealedDocument.class,
                evaluator.evaluate(command("{\"rootDocument\":{}}")));
        assertEquals(itemCount, runtime.supplyCalls);
    }

    @Test
    void repeatNestingAboveLimitRejectsBeforeTheNinthLoopFrame() {
        assertRepeatNestingRejected(closureWith(nestedRepeatDocument(9)));
    }

    @Test
    void repeatNestingBelowLimitIsAccepted() {
        assertRepeatNestingAccepted(7);
    }

    @Test
    void repeatNestingAtLimitIsAccepted() {
        assertRepeatNestingAccepted(8);
    }

    @Test
    void repeatNestingIsPathLocalAcrossSiblings() {
        var document = canvasWithChildren(
                nestedRepeatNode(8, 0, -1) + "," + nestedRepeatNode(8, 1_000, -1));

        assertInstanceOf(EvaluationOutcome.SealedDocument.class,
                evaluator(closureWith(document), resolver())
                        .evaluate(command("{\"rootDocument\":{}}")));
    }

    @Test
    void renderFalseRepeatDoesNotEnterTheNinthDepth() {
        var document = canvasWithChildren(nestedRepeatNode(9, 0, 9));

        assertInstanceOf(EvaluationOutcome.SealedDocument.class,
                evaluator(closureWith(document), resolver())
                        .evaluate(command("{\"rootDocument\":{}}")));
    }

    @Test
    void templateUseCannotResetActiveRepeatNestingDepth() {
        assertRepeatNestingRejected(closureWithRepeatAcrossTemplateDepth(9));
    }

    private static void assertRepeatNestingAccepted(int repeatDepth) {
        assertInstanceOf(EvaluationOutcome.SealedDocument.class,
                evaluator(closureWith(nestedRepeatDocument(repeatDepth)), resolver())
                        .evaluate(command("{\"rootDocument\":{}}")));
    }

    private static void assertRepeatNestingRejected(ClosureSnapshot closure) {
        var evaluator = evaluator(closure, resolver());

        var rejected = assertInstanceOf(EvaluationOutcome.Rejected.class,
                evaluator.evaluate(command("{\"rootDocument\":{}}")));

        assertEquals(EvaluationStage.MATERIALIZATION, rejected.stage());
        assertEquals(RenderingProblem.ProblemCode.EVALUATION_BUDGET_EXCEEDED,
                rejected.problem().code());
        assertEquals("closureAndExpansion.repeatNestingDepth",
                rejected.problem().limitId().orElseThrow().value());
    }

    @Test
    void loopFramesAboveLimitRejectsBeforeTheTenThousandAndFirstFrame() {
        var runtime = new SupplyingCapabilityRuntime();
        var evaluator = evaluator(
                closureWith(loopFrameCapacityDocument(10_001, true)),
                resolver(),
                new RecordingCapabilityStateStore(),
                runtime);

        assertLoopFrameRejected(evaluator);
        assertEquals(0, runtime.supplyCalls);
    }

    @Test
    void loopFramesBelowLimitIsAccepted() {
        assertLoopFramesAccepted(loopFrameCapacityDocument(9_999, false));
    }

    @Test
    void loopFramesAtLimitIsAccepted() {
        assertLoopFramesAccepted(loopFrameCapacityDocument(10_000, false));
    }

    @Test
    void zeroCollectionDoesNotConsumeALoopFrame() {
        assertLoopFramesAccepted(loopFrameCapacityDocument(10_000, false, true));
    }

    @Test
    void nestedLoopFramesShareTheRequestTotalAndNeverReturnAPrefix() {
        assertLoopFrameRejected(evaluator(
                closureWith(nestedLoopFrameOverflowDocument()), resolver()));
    }

    private static void assertLoopFramesAccepted(String document) {
        assertInstanceOf(EvaluationOutcome.SealedDocument.class,
                evaluator(closureWith(document), resolver())
                        .evaluate(command("{\"rootDocument\":{}}")));
    }

    private static void assertLoopFrameRejected(CanonicalEvaluator evaluator) {
        var rejected = assertInstanceOf(EvaluationOutcome.Rejected.class,
                evaluator.evaluate(command("{\"rootDocument\":{}}")));
        assertEquals(EvaluationStage.MATERIALIZATION, rejected.stage());
        assertEquals(RenderingProblem.ProblemCode.EVALUATION_BUDGET_EXCEEDED,
                rejected.problem().code());
        assertEquals("closureAndExpansion.loopFramesTotal",
                rejected.problem().limitId().orElseThrow().value());
    }

    @Test
    void evaluateSealsDocumentEndToEnd() {
        var evaluator = evaluator(closureWith(canvasWithRect()), resolver());

        var outcome = evaluator.evaluate(command(
                "{\"rootDocument\":{}}"));

        var sealed = assertInstanceOf(EvaluationOutcome.SealedDocument.class, outcome);
        var document = new String(
                sealed.renderDocumentCanonicalUtf8(), StandardCharsets.UTF_8);
        assertTrue(document.contains("\"dslVersion\":\"renderweave-render/1.0\""));
        assertTrue(document.contains("\"layoutProfile\":\"renderweave-layout/1.0\""));
        assertTrue(document.contains("rwocc_0000000000000000"));
        assertTrue(sealed.renderDocumentDigest().startsWith("sha256:"));
        assertTrue(sealed.evaluationResultDigest().startsWith("sha256:"));
        assertEquals(OutputSelection.defaultPng(), sealed.outputSelection());
    }

    @Test
    void wallClockJumpAfterAdmissionCannotExpireEvaluation() {
        var wallClockAfterJump = new MutableClock(100_000L);
        var evaluator = evaluator(
                closureWith(canvasWithRect()),
                resolver(),
                new RecordingCapabilityStateStore(),
                scriptedRuntime(),
                DEFAULT_BUDGET_VECTOR,
                wallClockAfterJump,
                () -> 0L);

        var outcome = evaluator.evaluate(command(
                "{\"rootDocument\":{}}", 61_000L, 1_000L));

        assertInstanceOf(EvaluationOutcome.SealedDocument.class, outcome);
    }

    @Test
    void expiredTotalDeadlineUsesFrozenTaxonomyWhenStageControlIsStillOpen() {
        var evaluator = evaluator(
                closureWith(canvasWithRect()),
                resolver(),
                new RecordingCapabilityStateStore(),
                scriptedRuntime(),
                DEFAULT_BUDGET_VECTOR,
                new MutableClock(1_000L),
                () -> 1_000L);

        var outcome = evaluator.evaluate(command(
                "{\"rootDocument\":{}}", 61_000L, 1_000L, 2_000L));

        var rejected = assertInstanceOf(EvaluationOutcome.Rejected.class, outcome);
        assertEquals(EvaluationStage.ENGINE, rejected.stage());
        assertEquals(RenderingProblem.ProblemCode.RENDER_DEADLINE_EXCEEDED,
                rejected.problem().code());
        assertEquals("deadlineAndRetention.totalDeadlineMillis",
                rejected.problem().limitId().orElseThrow().value());
    }

    @Test
    void closureReceivesTheAdmissionAndClosureDeadlineControl() {
        var ticker = new MutableTicker(1_000L);
        var frozenClosure = closureWith(canvasWithRect());
        TemplateClosureAuthority controlledClosure =
                (renderRequestId, rootTemplateId, control) -> {
                    ticker.setNanos(5_000L);
                    return control.deadlineExceeded()
                            ? new TemplateClosureAuthority.ClosureDeadlineExceeded()
                            : new TemplateClosureAuthority.ClosureFrozen(frozenClosure);
                };
        var evaluator = new CanonicalEvaluator(
                controlledClosure,
                TemplateModule.designSemanticAuthority(),
                TemplateModule.designDslAuthority(),
                null,
                scriptedRuntime(),
                new RecordingCapabilityStateStore(),
                DEFAULT_BUDGET_VECTOR,
                ignored -> {
                    throw new AssertionError("input admission must not run after closure expiry");
                },
                new MutableClock(1_000L),
                ticker);

        var outcome = evaluator.evaluate(command(
                "{\"rootDocument\":{}}", 61_000L, 60_000L, 5_000L));

        var rejected = assertInstanceOf(EvaluationOutcome.Rejected.class, outcome);
        assertEquals(EvaluationStage.TEMPLATE_CLOSURE, rejected.stage());
        assertEquals(RenderingProblem.ProblemCode.RENDER_DEADLINE_EXCEEDED,
                rejected.problem().code());
        assertEquals("deadlineAndRetention.admissionAndClosureMillis",
                rejected.problem().limitId().orElseThrow().value());
    }

    @Test
    void evaluatorRejectsAClosureThatReturnsAfterDeadlineWithoutObservingControl() {
        var ticker = new MutableTicker(1_000L);
        var frozenClosure = closureWith(canvasWithRect());
        TemplateClosureAuthority nonCooperativeClosure =
                (renderRequestId, rootTemplateId, control) -> {
                    ticker.setNanos(5_000L);
                    return new TemplateClosureAuthority.ClosureFrozen(frozenClosure);
                };
        var evaluator = new CanonicalEvaluator(
                nonCooperativeClosure,
                TemplateModule.designSemanticAuthority(),
                TemplateModule.designDslAuthority(),
                null,
                scriptedRuntime(),
                new RecordingCapabilityStateStore(),
                DEFAULT_BUDGET_VECTOR,
                ignored -> {
                    throw new AssertionError("input admission must not run after closure expiry");
                },
                new MutableClock(1_000L),
                ticker);

        var outcome = evaluator.evaluate(command(
                "{\"rootDocument\":{}}", 61_000L, 60_000L, 5_000L));

        var rejected = assertInstanceOf(EvaluationOutcome.Rejected.class, outcome);
        assertEquals(EvaluationStage.TEMPLATE_CLOSURE, rejected.stage());
        assertEquals("deadlineAndRetention.admissionAndClosureMillis",
                rejected.problem().limitId().orElseThrow().value());
    }

    @Test
    void admissionAndClosureDeadlineStopsApplyingAfterClosureFreezes() {
        var ticker = new MutableTicker(1_000L);
        var targetResolver = resolver();
        ValidationTargetResolver inputAdvancesPastStageDeadline = target -> {
            ticker.setNanos(5_000L);
            return targetResolver.resolve(target);
        };
        var evaluator = new CanonicalEvaluator(
                scriptedClosure(closureWith(canvasWithRect())),
                TemplateModule.designSemanticAuthority(),
                TemplateModule.designDslAuthority(),
                null,
                scriptedRuntime(),
                new RecordingCapabilityStateStore(),
                DEFAULT_BUDGET_VECTOR,
                inputAdvancesPastStageDeadline,
                new MutableClock(1_000L),
                ticker);

        var outcome = evaluator.evaluate(command(
                "{\"rootDocument\":{}}", 61_000L, 60_000L, 5_000L));

        assertInstanceOf(EvaluationOutcome.SealedDocument.class, outcome);
    }

    @Test
    void inputAdmissionThatConsumesTheEvaluationWindowStopsBeforeAssetAdmission() {
        var ticker = new MutableTicker(0L);
        var frozenClosure = closureWith(canvasWithImage());
        TemplateClosureAuthority closureCompletesAtFourSeconds =
                (renderRequestId, rootTemplateId, control) -> {
                    ticker.setNanos(4_000_000_000L);
                    return new TemplateClosureAuthority.ClosureFrozen(frozenClosure);
                };
        var targetResolver = resolver();
        ValidationTargetResolver inputConsumesFifteenSeconds = target -> {
            ticker.setNanos(19_000_000_000L);
            return targetResolver.resolve(target);
        };
        var assets = new CapturingAssetPort();
        var evaluator = new CanonicalEvaluator(
                closureCompletesAtFourSeconds,
                TemplateModule.designSemanticAuthority(),
                TemplateModule.designDslAuthority(),
                assets,
                scriptedRuntime(),
                new RecordingCapabilityStateStore(),
                DEFAULT_BUDGET_VECTOR,
                inputConsumesFifteenSeconds,
                new MutableClock(1_000L),
                ticker);

        var outcome = evaluator.evaluate(command(
                "{\"rootDocument\":{}}",
                61_000L,
                60_000_000_000L,
                5_000_000_000L));

        var rejected = assertInstanceOf(EvaluationOutcome.Rejected.class, outcome);
        assertEquals(EvaluationStage.DOCUMENT_SEAL, rejected.stage());
        assertEquals(RenderingProblem.ProblemCode.RENDER_DEADLINE_EXCEEDED,
                rejected.problem().code());
        assertEquals("deadlineAndRetention.evaluationAndDocumentSealMillis",
                rejected.problem().limitId().orElseThrow().value());
        assertNull(assets.lastRequest);
    }

    @Test
    void evaluationWindowStartsAtClosureFreezeAndAllowsFourteenPointNineNineNineSeconds() {
        var ticker = new MutableTicker(0L);
        var frozenClosure = closureWith(canvasWithRect());
        TemplateClosureAuthority closureCompletesAtFourSeconds =
                (renderRequestId, rootTemplateId, control) -> {
                    ticker.setNanos(4_000_000_000L);
                    return new TemplateClosureAuthority.ClosureFrozen(frozenClosure);
                };
        var targetResolver = resolver();
        ValidationTargetResolver inputCompletesOneMillisecondInsideWindow = target -> {
            ticker.setNanos(18_999_000_000L);
            return targetResolver.resolve(target);
        };
        var evaluator = new CanonicalEvaluator(
                closureCompletesAtFourSeconds,
                TemplateModule.designSemanticAuthority(),
                TemplateModule.designDslAuthority(),
                null,
                scriptedRuntime(),
                new RecordingCapabilityStateStore(),
                DEFAULT_BUDGET_VECTOR,
                inputCompletesOneMillisecondInsideWindow,
                new MutableClock(1_000L),
                ticker);

        var outcome = evaluator.evaluate(command(
                "{\"rootDocument\":{}}",
                61_000L,
                60_000_000_000L,
                5_000_000_000L));

        assertInstanceOf(EvaluationOutcome.SealedDocument.class, outcome);
    }

    @Test
    void assetAdmissionThatConsumesTheEvaluationWindowStopsBeforeResolution() {
        var ticker = new MutableTicker(0L);
        var frozenClosure = closureWith(canvasWithImage());
        TemplateClosureAuthority closureCompletesAtFourSeconds =
                (renderRequestId, rootTemplateId, control) -> {
                    ticker.setNanos(4_000_000_000L);
                    return new TemplateClosureAuthority.ClosureFrozen(frozenClosure);
                };
        var resolveCalls = new AtomicInteger();
        AssetResolutionPort assets = new AssetResolutionPort() {
            @Override
            public PrecheckOutcome precheckAdmission(
                    cn.hbads.renderweave.asset.api.AssetApplication.OwnerScope ownerScope,
                    cn.hbads.renderweave.asset.api.AssetApplication.AssetId assetId,
                    cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.AssetKind expectedKind
            ) {
                ticker.setNanos(19_000_000_000L);
                return new PrecheckOutcome.PrecheckPassed();
            }

            @Override
            public ResolveOutcome resolve(ResolveRequest request) {
                resolveCalls.incrementAndGet();
                return new CapturingAssetPort().resolve(request);
            }
        };
        var evaluator = new CanonicalEvaluator(
                closureCompletesAtFourSeconds,
                TemplateModule.designSemanticAuthority(),
                TemplateModule.designDslAuthority(),
                assets,
                scriptedRuntime(),
                new RecordingCapabilityStateStore(),
                DEFAULT_BUDGET_VECTOR,
                resolver(),
                new MutableClock(1_000L),
                ticker);

        var outcome = evaluator.evaluate(command(
                "{\"rootDocument\":{}}",
                61_000L,
                60_000_000_000L,
                5_000_000_000L));

        var rejected = assertInstanceOf(EvaluationOutcome.Rejected.class, outcome);
        assertEquals(EvaluationStage.DOCUMENT_SEAL, rejected.stage());
        assertEquals("deadlineAndRetention.evaluationAndDocumentSealMillis",
                rejected.problem().limitId().orElseThrow().value());
        assertEquals(0, resolveCalls.get());
    }

    @Test
    void assetAdmissionStopsBetweenSuccessfulPrechecksWhenTheWindowExpires() {
        var ticker = new MutableTicker(0L);
        var frozenClosure = closureWith(canvasWithTwoImages());
        TemplateClosureAuthority closureCompletesAtFourSeconds =
                (renderRequestId, rootTemplateId, control) -> {
                    ticker.setNanos(4_000_000_000L);
                    return new TemplateClosureAuthority.ClosureFrozen(frozenClosure);
                };
        var precheckCalls = new AtomicInteger();
        AssetResolutionPort assets = new AssetResolutionPort() {
            @Override
            public PrecheckOutcome precheckAdmission(
                    cn.hbads.renderweave.asset.api.AssetApplication.OwnerScope ownerScope,
                    cn.hbads.renderweave.asset.api.AssetApplication.AssetId assetId,
                    cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.AssetKind expectedKind
            ) {
                if (precheckCalls.incrementAndGet() == 1) {
                    ticker.setNanos(19_000_000_000L);
                }
                return new PrecheckOutcome.PrecheckPassed();
            }

            @Override
            public ResolveOutcome resolve(ResolveRequest request) {
                throw new AssertionError("resolution must not begin after admission deadline");
            }
        };
        var evaluator = new CanonicalEvaluator(
                closureCompletesAtFourSeconds,
                TemplateModule.designSemanticAuthority(),
                TemplateModule.designDslAuthority(),
                assets,
                scriptedRuntime(),
                new RecordingCapabilityStateStore(),
                DEFAULT_BUDGET_VECTOR,
                resolver(),
                new MutableClock(1_000L),
                ticker);

        var outcome = evaluator.evaluate(command(
                "{\"rootDocument\":{}}",
                61_000L,
                60_000_000_000L,
                5_000_000_000L));

        var rejected = assertInstanceOf(EvaluationOutcome.Rejected.class, outcome);
        assertEquals(EvaluationStage.DOCUMENT_SEAL, rejected.stage());
        assertEquals("deadlineAndRetention.evaluationAndDocumentSealMillis",
                rejected.problem().limitId().orElseThrow().value());
        assertEquals(1, precheckCalls.get());
    }

    @Test
    void assetRejectionObservedAtTheWindowBoundaryKeepsItsSpecificFailure() {
        var ticker = new MutableTicker(0L);
        var frozenClosure = closureWith(canvasWithImage());
        TemplateClosureAuthority closureCompletesAtFourSeconds =
                (renderRequestId, rootTemplateId, control) -> {
                    ticker.setNanos(4_000_000_000L);
                    return new TemplateClosureAuthority.ClosureFrozen(frozenClosure);
                };
        AssetResolutionPort assets = new AssetResolutionPort() {
            @Override
            public PrecheckOutcome precheckAdmission(
                    cn.hbads.renderweave.asset.api.AssetApplication.OwnerScope ownerScope,
                    cn.hbads.renderweave.asset.api.AssetApplication.AssetId assetId,
                    cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.AssetKind expectedKind
            ) {
                ticker.setNanos(19_000_000_000L);
                return new PrecheckOutcome.PrecheckRejected(AdmissionRejection.NOT_FOUND);
            }

            @Override
            public ResolveOutcome resolve(ResolveRequest request) {
                throw new AssertionError("rejected admission must stop resolution");
            }
        };
        var evaluator = new CanonicalEvaluator(
                closureCompletesAtFourSeconds,
                TemplateModule.designSemanticAuthority(),
                TemplateModule.designDslAuthority(),
                assets,
                scriptedRuntime(),
                new RecordingCapabilityStateStore(),
                DEFAULT_BUDGET_VECTOR,
                resolver(),
                new MutableClock(1_000L),
                ticker);

        var outcome = evaluator.evaluate(command(
                "{\"rootDocument\":{}}",
                61_000L,
                60_000_000_000L,
                5_000_000_000L));

        var rejected = assertInstanceOf(EvaluationOutcome.Rejected.class, outcome);
        assertEquals(EvaluationStage.ASSET_ADMISSION, rejected.stage());
        assertEquals(RenderingProblem.ProblemCode.ASSET_NOT_FOUND,
                rejected.problem().code());
        assertTrue(rejected.problem().limitId().isEmpty());
    }

    @Test
    void capabilityInitializationThatConsumesTheEvaluationWindowDoesNotCommitState() {
        var ticker = new MutableTicker(0L);
        var frozenClosure = closureWith(withUnusedClock(canvasWithRect()));
        TemplateClosureAuthority closureCompletesAtFourSeconds =
                (renderRequestId, rootTemplateId, control) -> {
                    ticker.setNanos(4_000_000_000L);
                    return new TemplateClosureAuthority.ClosureFrozen(frozenClosure);
                };
        var stateStore = new RecordingCapabilityStateStore();
        var runtime = new DeadlineAdvancingRuntime(ticker, 19_000_000_000L);
        var evaluator = new CanonicalEvaluator(
                closureCompletesAtFourSeconds,
                TemplateModule.designSemanticAuthority(),
                TemplateModule.designDslAuthority(),
                null,
                runtime,
                stateStore,
                DEFAULT_BUDGET_VECTOR,
                resolver(),
                new MutableClock(1_000L),
                ticker);

        var outcome = evaluator.evaluate(command(
                "{\"rootDocument\":{}}",
                61_000L,
                60_000_000_000L,
                5_000_000_000L));

        var rejected = assertInstanceOf(EvaluationOutcome.Rejected.class, outcome);
        assertEquals(EvaluationStage.DOCUMENT_SEAL, rejected.stage());
        assertEquals(RenderingProblem.ProblemCode.RENDER_DEADLINE_EXCEEDED,
                rejected.problem().code());
        assertEquals("deadlineAndRetention.evaluationAndDocumentSealMillis",
                rejected.problem().limitId().orElseThrow().value());
        assertEquals(1, runtime.establishCalls);
        assertEquals(0, stateStore.saveCalls);
    }

    @Test
    void successfulAssetResolveThatConsumesTheEvaluationWindowStopsMaterialization() {
        var ticker = new MutableTicker(0L);
        var frozenClosure = closureWith(canvasWithTwoImages());
        TemplateClosureAuthority closureCompletesAtFourSeconds =
                (renderRequestId, rootTemplateId, control) -> {
                    ticker.setNanos(4_000_000_000L);
                    return new TemplateClosureAuthority.ClosureFrozen(frozenClosure);
                };
        var resolveCalls = new AtomicInteger();
        AssetResolutionPort assets = new AssetResolutionPort() {
            @Override
            public PrecheckOutcome precheckAdmission(
                    cn.hbads.renderweave.asset.api.AssetApplication.OwnerScope ownerScope,
                    cn.hbads.renderweave.asset.api.AssetApplication.AssetId assetId,
                    cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.AssetKind expectedKind
            ) {
                return new PrecheckOutcome.PrecheckPassed();
            }

            @Override
            public ResolveOutcome resolve(ResolveRequest request) {
                if (resolveCalls.incrementAndGet() == 1) {
                    ticker.setNanos(19_000_000_000L);
                }
                return new CapturingAssetPort().resolve(request);
            }
        };
        var evaluator = new CanonicalEvaluator(
                closureCompletesAtFourSeconds,
                TemplateModule.designSemanticAuthority(),
                TemplateModule.designDslAuthority(),
                assets,
                scriptedRuntime(),
                new RecordingCapabilityStateStore(),
                DEFAULT_BUDGET_VECTOR,
                resolver(),
                new MutableClock(1_000L),
                ticker);

        var outcome = evaluator.evaluate(command(
                "{\"rootDocument\":{}}",
                61_000L,
                60_000_000_000L,
                5_000_000_000L));

        var rejected = assertInstanceOf(EvaluationOutcome.Rejected.class, outcome);
        assertEquals(EvaluationStage.DOCUMENT_SEAL, rejected.stage());
        assertEquals("deadlineAndRetention.evaluationAndDocumentSealMillis",
                rejected.problem().limitId().orElseThrow().value());
        assertEquals(1, resolveCalls.get());
    }

    @Test
    void successfulCapabilityDemandThatConsumesTheWindowStopsBeforeTheNextDemand() {
        var ticker = new MutableTicker(0L);
        var frozenClosure = closureWith(twoRandomAliasCapabilityDocument());
        TemplateClosureAuthority closureCompletesAtFourSeconds =
                (renderRequestId, rootTemplateId, control) -> {
                    ticker.setNanos(4_000_000_000L);
                    return new TemplateClosureAuthority.ClosureFrozen(frozenClosure);
                };
        var supplyCalls = new AtomicInteger();
        RenderingCapabilityRuntime runtime = new RenderingCapabilityRuntime() {
            @Override
            public Established establish(CapabilityRequirements requirements) {
                return new Established(provider(), new byte[]{1});
            }

            @Override
            public Runtime restore(CapabilityRequirements requirements, byte[] sealedState) {
                return provider();
            }

            @Override
            public Set<CapabilityContract> supportedContracts() {
                return Set.of(CapabilityContract.RANDOM_1_0);
            }

            private Runtime provider() {
                return (capability, operation, callPosition) -> {
                    if (supplyCalls.incrementAndGet() == 1) {
                        ticker.setNanos(19_000_000_000L);
                    }
                    return new Supplied(new DecimalResult(new BigDecimal("0.5")));
                };
            }
        };
        var evaluator = new CanonicalEvaluator(
                closureCompletesAtFourSeconds,
                TemplateModule.designSemanticAuthority(),
                TemplateModule.designDslAuthority(),
                null,
                runtime,
                new RecordingCapabilityStateStore(),
                DEFAULT_BUDGET_VECTOR,
                resolver(),
                new MutableClock(1_000L),
                ticker);

        var outcome = evaluator.evaluate(command(
                "{\"rootDocument\":{}}",
                61_000L,
                60_000_000_000L,
                5_000_000_000L));

        var rejected = assertInstanceOf(EvaluationOutcome.Rejected.class, outcome);
        assertEquals(EvaluationStage.DOCUMENT_SEAL, rejected.stage());
        assertEquals("deadlineAndRetention.evaluationAndDocumentSealMillis",
                rejected.problem().limitId().orElseThrow().value());
        assertEquals(1, supplyCalls.get());
    }

    @Test
    void declarationScanThatConsumesTheEvaluationWindowStopsBeforeInputAdmission() {
        var ticker = new MutableTicker(0L);
        var frozenClosure = closureWith(canvasWithRect());
        TemplateClosureAuthority closureCompletesAtFourSeconds =
                (renderRequestId, rootTemplateId, control) -> {
                    ticker.setNanos(4_000_000_000L);
                    return new TemplateClosureAuthority.ClosureFrozen(frozenClosure);
                };
        var semanticDelegate = TemplateModule.designSemanticAuthority();
        DesignSemanticAuthority declarationConsumesFifteenSeconds = bytes -> {
            ticker.setNanos(19_000_000_000L);
            return semanticDelegate.interpret(bytes);
        };
        var inputResolutions = new AtomicInteger();
        var targetResolver = resolver();
        ValidationTargetResolver recordingResolver = target -> {
            inputResolutions.incrementAndGet();
            return targetResolver.resolve(target);
        };
        var evaluator = new CanonicalEvaluator(
                closureCompletesAtFourSeconds,
                declarationConsumesFifteenSeconds,
                TemplateModule.designDslAuthority(),
                null,
                scriptedRuntime(),
                new RecordingCapabilityStateStore(),
                DEFAULT_BUDGET_VECTOR,
                recordingResolver,
                new MutableClock(1_000L),
                ticker);

        var outcome = evaluator.evaluate(command(
                "{\"rootDocument\":{}}",
                61_000L,
                60_000_000_000L,
                5_000_000_000L));

        var rejected = assertInstanceOf(EvaluationOutcome.Rejected.class, outcome);
        assertEquals(EvaluationStage.DOCUMENT_SEAL, rejected.stage());
        assertEquals("deadlineAndRetention.evaluationAndDocumentSealMillis",
                rejected.problem().limitId().orElseThrow().value());
        assertEquals(0, inputResolutions.get());
    }

    @Test
    void declarationScanStopsBetweenSnapshotsWhenTheEvaluationWindowExpires() {
        var ticker = new MutableTicker(0L);
        var frozenClosure = closureWithTemplateUseChildren(1);
        TemplateClosureAuthority closureCompletesAtFourSeconds =
                (renderRequestId, rootTemplateId, control) -> {
                    ticker.setNanos(4_000_000_000L);
                    return new TemplateClosureAuthority.ClosureFrozen(frozenClosure);
                };
        var interpretations = new AtomicInteger();
        var semanticDelegate = TemplateModule.designSemanticAuthority();
        DesignSemanticAuthority firstSnapshotConsumesTheWindow = bytes -> {
            var outcome = semanticDelegate.interpret(bytes);
            if (interpretations.incrementAndGet() == 1) {
                ticker.setNanos(19_000_000_000L);
            }
            return outcome;
        };
        var inputResolutions = new AtomicInteger();
        var targetResolver = resolver();
        ValidationTargetResolver recordingResolver = target -> {
            inputResolutions.incrementAndGet();
            return targetResolver.resolve(target);
        };
        var evaluator = new CanonicalEvaluator(
                closureCompletesAtFourSeconds,
                firstSnapshotConsumesTheWindow,
                TemplateModule.designDslAuthority(),
                null,
                scriptedRuntime(),
                new RecordingCapabilityStateStore(),
                DEFAULT_BUDGET_VECTOR,
                recordingResolver,
                new MutableClock(1_000L),
                ticker);

        var outcome = evaluator.evaluate(command(
                "{\"rootDocument\":{}}",
                61_000L,
                60_000_000_000L,
                5_000_000_000L));

        var rejected = assertInstanceOf(EvaluationOutcome.Rejected.class, outcome);
        assertEquals(EvaluationStage.DOCUMENT_SEAL, rejected.stage());
        assertEquals("deadlineAndRetention.evaluationAndDocumentSealMillis",
                rejected.problem().limitId().orElseThrow().value());
        assertEquals(1, interpretations.get());
        assertEquals(0, inputResolutions.get());
    }

    @Test
    void malformedEnvelopeRejectsAtRequestAdmission() {
        var evaluator = evaluator(closureWith(canvasWithRect()), resolver());

        var outcome = evaluator.evaluate(command("{\"nope\":1}"));

        var rejected = assertInstanceOf(EvaluationOutcome.Rejected.class, outcome);
        assertEquals(EvaluationStage.REQUEST_ADMISSION, rejected.stage());
    }

    @Test
    void ownerScopeMismatchRejectsAtRequestAdmission() {
        var closure = closureWith(canvasWithRect());
        var evaluator = new cn.hbads.renderweave.rendering.internal.CanonicalEvaluator(
                scriptedClosure(closure),
                TemplateModule.designSemanticAuthority(),
                TemplateModule.designDslAuthority(),
                null,
                scriptedRuntime(),
                new RecordingCapabilityStateStore(),
                DEFAULT_BUDGET_VECTOR,
                resolver(),
                Clock.fixed(Instant.ofEpochMilli(1_000L), ZoneOffset.UTC));

        var outcome = evaluator.evaluate(new EvaluationCommand(
                new RenderRequestId("00000000-0000-4000-8000-000000000001"),
                new OwnerScope("intruder-scope"),
                AUTH_DIGEST,
                ExternalAssetReadAuthorization.GRANTED,
                new TemplateApplication.TemplateId(ROOT_ID),
                "{\"rootDocument\":{}}".getBytes(StandardCharsets.UTF_8),
                OutputSelection.defaultPng(),
                "renderweave-renderer/1.0",
                61_000L,
                System.nanoTime() + 60_000_000_000L,
                System.nanoTime() + 60_000_000_000L));

        var rejected = assertInstanceOf(EvaluationOutcome.Rejected.class, outcome);
        assertEquals(EvaluationStage.REQUEST_ADMISSION, rejected.stage());
    }

    @Test
    void unstableClosureRejectsWithFrozenCode() {
        var evaluator = new cn.hbads.renderweave.rendering.internal.CanonicalEvaluator(
                (renderRequestId, rootTemplateId, control) ->
                        new TemplateClosureAuthority.ClosureUnstable(),
                TemplateModule.designSemanticAuthority(),
                TemplateModule.designDslAuthority(),
                null,
                scriptedRuntime(),
                new RecordingCapabilityStateStore(),
                DEFAULT_BUDGET_VECTOR,
                resolver(),
                Clock.fixed(Instant.ofEpochMilli(1_000L), ZoneOffset.UTC));

        var outcome = evaluator.evaluate(command("{\"rootDocument\":{}}"));

        var rejected = assertInstanceOf(EvaluationOutcome.Rejected.class, outcome);
        assertEquals(EvaluationStage.TEMPLATE_CLOSURE, rejected.stage());
        assertEquals(RenderingProblem.ProblemCode.TEMPLATE_CLOSURE_UNSTABLE,
                rejected.problem().code());
    }

    @Test
    void closureCapacityRejectsWithFullMachineLimitId() {
        var evaluator = evaluator(new TemplateClosureAuthority.ClosureLimitExceeded(
                new TemplateClosureAuthority.LimitId("closureCanonicalDesignBytes")
        ));

        var rejected = assertInstanceOf(EvaluationOutcome.Rejected.class,
                evaluator.evaluate(command("{\"rootDocument\":{}}")));

        assertEquals(EvaluationStage.TEMPLATE_CLOSURE, rejected.stage());
        assertEquals(RenderingProblem.ProblemCode.TEMPLATE_CLOSURE_LIMIT_EXCEEDED,
                rejected.problem().code());
        assertEquals("closureAndExpansion.closureCanonicalDesignBytes",
                rejected.problem().limitId().orElseThrow().value());
    }

    @Test
    void missingRootTemplatePreservesTemplateDomainCode() {
        var evaluator = evaluator(new TemplateClosureAuthority.ClosureNotFound());

        var rejected = assertInstanceOf(EvaluationOutcome.Rejected.class,
                evaluator.evaluate(command("{\"rootDocument\":{}}")));

        assertEquals(EvaluationStage.TEMPLATE_CLOSURE, rejected.stage());
        assertEquals(RenderingProblem.ProblemCode.TEMPLATE_NOT_FOUND,
                rejected.problem().code());
    }

    @Test
    void deletedRootTemplatePreservesTemplateDomainCode() {
        var evaluator = evaluator(new TemplateClosureAuthority.ClosureDeleted());

        var rejected = assertInstanceOf(EvaluationOutcome.Rejected.class,
                evaluator.evaluate(command("{\"rootDocument\":{}}")));

        assertEquals(EvaluationStage.TEMPLATE_CLOSURE, rejected.stage());
        assertEquals(RenderingProblem.ProblemCode.TEMPLATE_DELETED,
                rejected.problem().code());
    }

    @Test
    void invalidClosureDependencyPreservesTemplateDomainCode() {
        var evaluator = evaluator(new TemplateClosureAuthority.ClosureDependencyInvalid());

        var rejected = assertInstanceOf(EvaluationOutcome.Rejected.class,
                evaluator.evaluate(command("{\"rootDocument\":{}}")));

        assertEquals(EvaluationStage.TEMPLATE_CLOSURE, rejected.stage());
        assertEquals(RenderingProblem.ProblemCode.TEMPLATE_DEPENDENCY_ERROR,
                rejected.problem().code());
    }

    @Test
    void unavailableClosureAuthorityPreservesTemplateDomainCode() {
        var evaluator = evaluator(new TemplateClosureAuthority.ClosureUnavailable());

        var rejected = assertInstanceOf(EvaluationOutcome.Rejected.class,
                evaluator.evaluate(command("{\"rootDocument\":{}}")));

        assertEquals(EvaluationStage.TEMPLATE_CLOSURE, rejected.stage());
        assertEquals(RenderingProblem.ProblemCode.TEMPLATE_AUTHORITY_UNAVAILABLE,
                rejected.problem().code());
    }

    @Test
    void closureIntegrityViolationRemainsAnInternalError() {
        var evaluator = evaluator(new TemplateClosureAuthority.ClosureIntegrityViolation());

        var rejected = assertInstanceOf(EvaluationOutcome.Rejected.class,
                evaluator.evaluate(command("{\"rootDocument\":{}}")));

        assertEquals(EvaluationStage.TEMPLATE_CLOSURE, rejected.stage());
        assertEquals(RenderingProblem.ProblemCode.RENDER_INTERNAL_ERROR,
                rejected.problem().code());
    }

    @Test
    void missingRootDocumentRejectsAtEnvelopeStage() {
        var evaluator = evaluator(closureWith(canvasWithRect()), resolver());

        var outcome = evaluator.evaluate(command("{\"customValues\":[]}"));

        var rejected = assertInstanceOf(EvaluationOutcome.Rejected.class, outcome);
        // envelope 结构拒绝属于 stage 1（REQUEST_ADMISSION）。
        assertEquals(EvaluationStage.REQUEST_ADMISSION, rejected.stage());
    }

    @Test
    void evaluationUsesTheApplicationFrozenAbsoluteRenderDeadline() {
        var now = Instant.parse("2026-08-20T08:00:00Z");
        var assets = new CapturingAssetPort();
        var evaluator = new CanonicalEvaluator(
                scriptedClosure(closureWith(canvasWithImage())),
                TemplateModule.designSemanticAuthority(),
                TemplateModule.designDslAuthority(),
                assets,
                scriptedRuntime(),
                new RecordingCapabilityStateStore(),
                DEFAULT_BUDGET_VECTOR,
                resolver(),
                Clock.fixed(now, ZoneOffset.UTC));

        var deadline = now.plusSeconds(60).toEpochMilli();
        var outcome = evaluator.evaluate(command("{\"rootDocument\":{}}", deadline));

        assertInstanceOf(EvaluationOutcome.SealedDocument.class, outcome);
        assertEquals(deadline, assets.lastRequest.deadlineEpochMilli());
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static EvaluationCommand command(String envelope) {
        return command(envelope, 61_000L);
    }

    private static EvaluationCommand command(
            String envelope,
            ExternalAssetReadAuthorization externalAssetReadAuthorization
    ) {
        return command(envelope, 61_000L, AUTH_DIGEST, externalAssetReadAuthorization);
    }

    private static EvaluationCommand command(String envelope, long deadlineAtEpochMilli) {
        return command(envelope, deadlineAtEpochMilli, AUTH_DIGEST,
                ExternalAssetReadAuthorization.GRANTED);
    }

    private static EvaluationCommand command(
            String envelope,
            long deadlineAtEpochMilli,
            long deadlineAtMonotonicNanos
    ) {
        return command(envelope, deadlineAtEpochMilli, deadlineAtMonotonicNanos,
                deadlineAtMonotonicNanos,
                AUTH_DIGEST, ExternalAssetReadAuthorization.GRANTED);
    }

    private static EvaluationCommand command(
            String envelope,
            long deadlineAtEpochMilli,
            long deadlineAtMonotonicNanos,
            long admissionAndClosureDeadlineAtMonotonicNanos
    ) {
        return command(envelope, deadlineAtEpochMilli, deadlineAtMonotonicNanos,
                admissionAndClosureDeadlineAtMonotonicNanos,
                AUTH_DIGEST, ExternalAssetReadAuthorization.GRANTED);
    }

    private static EvaluationCommand command(
            String envelope, long deadlineAtEpochMilli, String authorizationDigest) {
        return command(envelope, deadlineAtEpochMilli, authorizationDigest,
                ExternalAssetReadAuthorization.GRANTED);
    }

    private static EvaluationCommand command(
            String envelope,
            long deadlineAtEpochMilli,
            String authorizationDigest,
            ExternalAssetReadAuthorization externalAssetReadAuthorization
    ) {
        return command(
                envelope,
                deadlineAtEpochMilli,
                System.nanoTime() + 60_000_000_000L,
                System.nanoTime() + 60_000_000_000L,
                authorizationDigest,
                externalAssetReadAuthorization);
    }

    private static EvaluationCommand command(
            String envelope,
            long deadlineAtEpochMilli,
            long deadlineAtMonotonicNanos,
            long admissionAndClosureDeadlineAtMonotonicNanos,
            String authorizationDigest,
            ExternalAssetReadAuthorization externalAssetReadAuthorization
    ) {
        return new EvaluationCommand(
                new RenderRequestId("00000000-0000-4000-8000-000000000001"),
                new OwnerScope("owner-a"),
                authorizationDigest,
                externalAssetReadAuthorization,
                new TemplateApplication.TemplateId(ROOT_ID),
                envelope.getBytes(StandardCharsets.UTF_8),
                OutputSelection.defaultPng(),
                "renderweave-renderer/1.0",
                deadlineAtEpochMilli,
                deadlineAtMonotonicNanos,
                admissionAndClosureDeadlineAtMonotonicNanos);
    }

    private static cn.hbads.renderweave.rendering.internal.CanonicalEvaluator evaluator(
            ClosureSnapshot closure, ValidationTargetResolver resolver) {
        return evaluator(closure, resolver, new RecordingCapabilityStateStore(), scriptedRuntime());
    }

    private static cn.hbads.renderweave.rendering.internal.CanonicalEvaluator evaluator(
            ClosureOutcome closureOutcome) {
        return new cn.hbads.renderweave.rendering.internal.CanonicalEvaluator(
                (renderRequestId, rootTemplateId, control) -> closureOutcome,
                TemplateModule.designSemanticAuthority(),
                TemplateModule.designDslAuthority(),
                null,
                scriptedRuntime(),
                new RecordingCapabilityStateStore(),
                DEFAULT_BUDGET_VECTOR,
                resolver(),
                Clock.fixed(Instant.ofEpochMilli(1_000L), ZoneOffset.UTC));
    }

    private static CanonicalEvaluator evaluator(
            ClosureSnapshot closure,
            ValidationTargetResolver resolver,
            AssetResolutionPort assets,
            CapabilityStateStore stateStore,
            RenderingCapabilityRuntime runtime
    ) {
        return new CanonicalEvaluator(
                scriptedClosure(closure),
                TemplateModule.designSemanticAuthority(),
                TemplateModule.designDslAuthority(),
                assets,
                runtime,
                stateStore,
                DEFAULT_BUDGET_VECTOR,
                resolver,
                Clock.fixed(Instant.ofEpochMilli(1_000L), ZoneOffset.UTC));
    }

    private static cn.hbads.renderweave.rendering.internal.CanonicalEvaluator evaluator(
            ClosureSnapshot closure,
            ValidationTargetResolver resolver,
            CapabilityStateStore stateStore,
            RenderingCapabilityRuntime runtime) {
        return evaluator(closure, resolver, stateStore, runtime, DEFAULT_BUDGET_VECTOR);
    }

    private static cn.hbads.renderweave.rendering.internal.CanonicalEvaluator evaluator(
            ClosureSnapshot closure,
            ValidationTargetResolver resolver,
            CapabilityStateStore stateStore,
            RenderingCapabilityRuntime runtime,
            String effectiveBudgetVector) {
        return evaluator(
                closure,
                resolver,
                stateStore,
                runtime,
                effectiveBudgetVector,
                Clock.fixed(Instant.ofEpochMilli(1_000L), ZoneOffset.UTC));
    }

    private static cn.hbads.renderweave.rendering.internal.CanonicalEvaluator evaluator(
            ClosureSnapshot closure,
            ValidationTargetResolver resolver,
            CapabilityStateStore stateStore,
            RenderingCapabilityRuntime runtime,
            String effectiveBudgetVector,
            Clock clock) {
        return evaluator(closure, resolver, stateStore, runtime,
                effectiveBudgetVector, clock, System::nanoTime);
    }

    private static cn.hbads.renderweave.rendering.internal.CanonicalEvaluator evaluator(
            ClosureSnapshot closure,
            ValidationTargetResolver resolver,
            CapabilityStateStore stateStore,
            RenderingCapabilityRuntime runtime,
            String effectiveBudgetVector,
            Clock clock,
            LongSupplier monotonicNanos) {
        return new cn.hbads.renderweave.rendering.internal.CanonicalEvaluator(
                scriptedClosure(closure),
                TemplateModule.designSemanticAuthority(),
                TemplateModule.designDslAuthority(),
                null,
                runtime,
                stateStore,
                effectiveBudgetVector,
                resolver,
                clock,
                monotonicNanos);
    }

    private static String budgetVector(
            long staticSources,
            long totalDemands,
            long clockDemands,
            long randomDemands,
            long positionBytesPerDemand,
            long positionBytesTotal,
            long capabilityStateRecordBytes,
            long resultDigestStreamingBytes
    ) {
        return budgetVector(
                staticSources,
                totalDemands,
                clockDemands,
                randomDemands,
                positionBytesPerDemand,
                positionBytesTotal,
                capabilityStateRecordBytes,
                resultDigestStreamingBytes,
                3,
                128);
    }

    private static String budgetVector(
            long staticSources,
            long totalDemands,
            long clockDemands,
            long randomDemands,
            long positionBytesPerDemand,
            long positionBytesTotal,
            long capabilityStateRecordBytes,
            long resultDigestStreamingBytes,
            long initializationAttempts
    ) {
        return budgetVector(
                staticSources,
                totalDemands,
                clockDemands,
                randomDemands,
                positionBytesPerDemand,
                positionBytesTotal,
                capabilityStateRecordBytes,
                resultDigestStreamingBytes,
                initializationAttempts,
                128);
    }

    private static String budgetVector(
            long staticSources,
            long totalDemands,
            long clockDemands,
            long randomDemands,
            long positionBytesPerDemand,
            long positionBytesTotal,
            long capabilityStateRecordBytes,
            long resultDigestStreamingBytes,
            long initializationAttempts,
            long randomRejectionAttempts
    ) {
        return "{\"groups\":{\"capabilityRuntime\":{\"limits\":{"
                + "\"staticCapabilitySources\":" + staticSources + ","
                + "\"totalDemands\":" + totalDemands + ","
                + "\"clockDemands\":" + clockDemands + ","
                + "\"randomDemands\":" + randomDemands + ","
                + "\"positionCanonicalBytesPerDemand\":" + positionBytesPerDemand + ","
                + "\"positionCanonicalBytesTotal\":" + positionBytesTotal + ","
                + "\"capabilityStateRecordBytes\":" + capabilityStateRecordBytes + ","
                + "\"resultDigestStreamingBytes\":" + resultDigestStreamingBytes + ","
                + "\"initializationAttempts\":" + initializationAttempts + ","
                + "\"randomRejectionAttempts\":" + randomRejectionAttempts
                + "}}}}";
    }

    private static final class RecordingCapabilityRuntime implements RenderingCapabilityRuntime {
        private int establishCalls;
        private int restoreCalls;

        @Override
        public Established establish(CapabilityRequirements requirements) {
            establishCalls++;
            return new Established(scriptedProvider(), new byte[]{1, 2, 3});
        }

        @Override
        public Runtime restore(CapabilityRequirements requirements, byte[] sealedState) {
            restoreCalls++;
            return scriptedProvider();
        }

        @Override
        public Set<CapabilityContract> supportedContracts() {
            return Set.of(CapabilityContract.CLOCK_1_0, CapabilityContract.RANDOM_1_0);
        }

        private static Runtime scriptedProvider() {
            return (capability, operation, callPosition) -> new ProviderUnavailable();
        }
    }

    private static final class TransientEstablishRuntime implements RenderingCapabilityRuntime {
        private final int failuresBeforeSuccess;
        private int establishCalls;

        private TransientEstablishRuntime(int failuresBeforeSuccess) {
            this.failuresBeforeSuccess = failuresBeforeSuccess;
        }

        @Override
        public Established establish(CapabilityRequirements requirements) {
            establishCalls++;
            if (establishCalls <= failuresBeforeSuccess) {
                throw new IllegalStateException("transient capability initialization failure");
            }
            return new Established(RecordingCapabilityRuntime.scriptedProvider(),
                    new byte[]{1, 2, 3});
        }

        @Override
        public Runtime restore(CapabilityRequirements requirements, byte[] sealedState) {
            return RecordingCapabilityRuntime.scriptedProvider();
        }

        @Override
        public Set<CapabilityContract> supportedContracts() {
            return Set.of(CapabilityContract.CLOCK_1_0, CapabilityContract.RANDOM_1_0);
        }
    }

    private static final class DeadlineAdvancingRuntime implements RenderingCapabilityRuntime {
        private final MutableTicker ticker;
        private final long deadlineNanos;
        private int establishCalls;

        private DeadlineAdvancingRuntime(MutableTicker ticker, long deadlineNanos) {
            this.ticker = ticker;
            this.deadlineNanos = deadlineNanos;
        }

        @Override
        public Established establish(CapabilityRequirements requirements) {
            establishCalls++;
            ticker.setNanos(deadlineNanos);
            return new Established(RecordingCapabilityRuntime.scriptedProvider(),
                    new byte[]{1, 2, 3});
        }

        @Override
        public Runtime restore(CapabilityRequirements requirements, byte[] sealedState) {
            return RecordingCapabilityRuntime.scriptedProvider();
        }

        @Override
        public Set<CapabilityContract> supportedContracts() {
            return Set.of(CapabilityContract.CLOCK_1_0, CapabilityContract.RANDOM_1_0);
        }
    }

    private static final class MutableClock extends Clock {
        private long epochMillis;

        private MutableClock(long epochMillis) {
            this.epochMillis = epochMillis;
        }

        private void setMillis(long epochMillis) {
            this.epochMillis = epochMillis;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("only UTC is supported");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(epochMillis);
        }

        @Override
        public long millis() {
            return epochMillis;
        }
    }

    private static final class MutableTicker implements LongSupplier {
        private long nanos;

        private MutableTicker(long nanos) {
            this.nanos = nanos;
        }

        private void setNanos(long nanos) {
            this.nanos = nanos;
        }

        @Override
        public long getAsLong() {
            return nanos;
        }
    }

    private static final class RequirementCheckingRuntime implements RenderingCapabilityRuntime {
        private final Set<CapabilityContract> expected;
        private final Set<CapabilityContract> supported;

        private RequirementCheckingRuntime(Set<CapabilityContract> expected) {
            this(expected, Set.of(CapabilityContract.CLOCK_1_0, CapabilityContract.RANDOM_1_0));
        }

        private RequirementCheckingRuntime(
                Set<CapabilityContract> expected,
                Set<CapabilityContract> supported
        ) {
            this.expected = Set.copyOf(expected);
            this.supported = Set.copyOf(supported);
        }

        @Override
        public Established establish(CapabilityRequirements requirements) {
            if (!requirements.contracts().equals(expected)) {
                throw new IllegalStateException("unexpected capability requirements");
            }
            return new Established(provider(), new byte[]{1});
        }

        @Override
        public Runtime restore(CapabilityRequirements requirements, byte[] sealedState) {
            if (!requirements.contracts().equals(expected)) {
                throw new IllegalStateException("unexpected capability requirements");
            }
            return provider();
        }

        @Override
        public Set<CapabilityContract> supportedContracts() {
            return supported;
        }

        private static Runtime provider() {
            return (capability, operation, callPosition) -> new ProviderUnavailable();
        }
    }

    private static final class SupplyingCapabilityRuntime implements RenderingCapabilityRuntime {
        private int supplyCalls;

        @Override
        public Established establish(CapabilityRequirements requirements) {
            return new Established(provider(), new byte[]{1});
        }

        @Override
        public Runtime restore(CapabilityRequirements requirements, byte[] sealedState) {
            return provider();
        }

        @Override
        public Set<CapabilityContract> supportedContracts() {
            return Set.of(CapabilityContract.CLOCK_1_0, CapabilityContract.RANDOM_1_0);
        }

        private Runtime provider() {
            return (capability, operation, callPosition) -> {
                supplyCalls++;
                return switch (capability + "/" + operation) {
                    case "CLOCK/UTC_DATE" -> new Supplied(new DateResult("2026-08-28"));
                    case "CLOCK/UTC_TIME" -> new Supplied(new TimeResult("22:00:00"));
                    case "RANDOM/UNIFORM_DECIMAL_0_1" ->
                            new Supplied(new DecimalResult(new BigDecimal("5")));
                    default -> new ProviderUnavailable();
                };
            };
        }
    }

    private static final class RejectionExhaustedCapabilityRuntime
            implements RenderingCapabilityRuntime {
        private int supplyCalls;

        @Override
        public Established establish(CapabilityRequirements requirements) {
            return new Established(provider(), new byte[]{1});
        }

        @Override
        public Runtime restore(CapabilityRequirements requirements, byte[] sealedState) {
            return provider();
        }

        @Override
        public Set<CapabilityContract> supportedContracts() {
            return Set.of(CapabilityContract.RANDOM_1_0);
        }

        private Runtime provider() {
            return (capability, operation, callPosition) -> {
                supplyCalls++;
                return new RandomRejectionExhausted();
            };
        }
    }

    private static final class RecordingCapabilityStateStore implements CapabilityStateStore {
        private SaveRequest committed;
        private boolean unavailable;
        private int saveCalls;
        private int loadCalls;

        @Override
        public SaveOutcome save(SaveRequest request) {
            saveCalls++;
            if (unavailable) {
                return new SaveOutcome.SaveUnavailable();
            }
            committed = request;
            return new SaveOutcome.Stored(new CapabilityStateId("state-1"));
        }

        @Override
        public LoadOutcome load(RenderRequestId requestId, String evaluationFingerprint) {
            loadCalls++;
            if (unavailable) {
                return new LoadOutcome.LoadUnavailable();
            }
            if (committed == null) {
                return new LoadOutcome.Missing();
            }
            if (!committed.evaluationFingerprint().equals(evaluationFingerprint)) {
                return new LoadOutcome.LoadFingerprintConflict();
            }
            return new LoadOutcome.Loaded(committed.sealedState(), committed.expiresAtEpochMilli());
        }
    }

    private static final class CommittedUnknownCapabilityStateStore
            implements CapabilityStateStore {
        private SaveRequest committed;
        private int saveCalls;
        private int loadCalls;

        @Override
        public SaveOutcome save(SaveRequest request) {
            saveCalls++;
            committed = request;
            return new SaveOutcome.SaveUnavailable();
        }

        @Override
        public LoadOutcome load(RenderRequestId requestId, String evaluationFingerprint) {
            loadCalls++;
            if (committed == null) {
                return new LoadOutcome.Missing();
            }
            return new LoadOutcome.Loaded(
                    committed.sealedState(), committed.expiresAtEpochMilli());
        }
    }

    private static final class MissingUnknownThenStoredCapabilityStateStore
            implements CapabilityStateStore {
        private int saveCalls;
        private int loadCalls;

        @Override
        public SaveOutcome save(SaveRequest request) {
            saveCalls++;
            if (saveCalls == 1) {
                return new SaveOutcome.SaveUnavailable();
            }
            return new SaveOutcome.Stored(new CapabilityStateId("state-2"));
        }

        @Override
        public LoadOutcome load(RenderRequestId requestId, String evaluationFingerprint) {
            loadCalls++;
            return new LoadOutcome.Missing();
        }
    }

    private static final class DeadlineAdvancingCapabilityStateStore
            implements CapabilityStateStore {
        private final MutableTicker ticker;
        private final long deadlineNanos;
        private int saveCalls;

        private DeadlineAdvancingCapabilityStateStore(
                MutableTicker ticker, long deadlineNanos) {
            this.ticker = ticker;
            this.deadlineNanos = deadlineNanos;
        }

        @Override
        public SaveOutcome save(SaveRequest request) {
            saveCalls++;
            ticker.setNanos(deadlineNanos);
            return new SaveOutcome.Stored(new CapabilityStateId("state-deadline"));
        }

        @Override
        public LoadOutcome load(RenderRequestId requestId, String evaluationFingerprint) {
            return new LoadOutcome.Missing();
        }
    }

    private static final class ExpiryRecordingCapabilityStateStore
            implements CapabilityStateStore {
        private final MutableClock clock;
        private final List<SaveRequest> requests = new java.util.ArrayList<>();

        private ExpiryRecordingCapabilityStateStore(MutableClock clock) {
            this.clock = clock;
        }

        @Override
        public SaveOutcome save(SaveRequest request) {
            requests.add(request);
            if (requests.size() == 1) {
                clock.setMillis(5_000L);
                return new SaveOutcome.SaveUnavailable();
            }
            return new SaveOutcome.Stored(new CapabilityStateId("state-fixed-expiry"));
        }

        @Override
        public LoadOutcome load(RenderRequestId requestId, String evaluationFingerprint) {
            return new LoadOutcome.Missing();
        }
    }

    private static final class DeadlineLoadedCapabilityStateStore
            implements CapabilityStateStore {
        private final MutableTicker ticker;
        private final long deadlineNanos;

        private DeadlineLoadedCapabilityStateStore(
                MutableTicker ticker, long deadlineNanos) {
            this.ticker = ticker;
            this.deadlineNanos = deadlineNanos;
        }

        @Override
        public SaveOutcome save(SaveRequest request) {
            throw new AssertionError("loaded state must not be saved again");
        }

        @Override
        public LoadOutcome load(RenderRequestId requestId, String evaluationFingerprint) {
            ticker.setNanos(deadlineNanos);
            return new LoadOutcome.Loaded(new byte[]{1, 2, 3}, 361_000L);
        }
    }

    private static final class UnknownCommittedDeadlineStateStore
            implements CapabilityStateStore {
        private final MutableTicker ticker;
        private final long deadlineNanos;
        private SaveRequest committed;
        private int loadCalls;

        private UnknownCommittedDeadlineStateStore(
                MutableTicker ticker, long deadlineNanos) {
            this.ticker = ticker;
            this.deadlineNanos = deadlineNanos;
        }

        @Override
        public SaveOutcome save(SaveRequest request) {
            committed = request;
            return new SaveOutcome.SaveUnavailable();
        }

        @Override
        public LoadOutcome load(RenderRequestId requestId, String evaluationFingerprint) {
            loadCalls++;
            if (committed == null) {
                return new LoadOutcome.Missing();
            }
            ticker.setNanos(deadlineNanos);
            return new LoadOutcome.Loaded(
                    committed.sealedState(), committed.expiresAtEpochMilli());
        }
    }

    private static TemplateClosureAuthority scriptedClosure(ClosureSnapshot closure) {
        return (renderRequestId, rootTemplateId, control) ->
                new TemplateClosureAuthority.ClosureFrozen(closure);
    }

    private static RenderingCapabilityRuntime scriptedRuntime() {
        return new RenderingCapabilityRuntime() {
            @Override
            public Established establish(CapabilityRequirements requirements) {
                return new Established(provider(), new byte[]{1});
            }

            @Override
            public Runtime restore(CapabilityRequirements requirements, byte[] sealedState) {
                return provider();
            }

            @Override
            public Set<CapabilityContract> supportedContracts() {
                return Set.of(CapabilityContract.CLOCK_1_0, CapabilityContract.RANDOM_1_0);
            }

            private Runtime provider() {
                return (capability, operation, callPosition) -> new ProviderUnavailable();
            }
        };
    }

    private static ClosureSnapshot closureWith(String designDocument) {
        var admission = TemplateModule.designDslAuthority()
                .admit(designDocument.getBytes(StandardCharsets.UTF_8));
        var admitted = (DesignDslAuthority.Admitted) admission;
        var snapshot = new TemplateSnapshot(
                new TemplateApplication.TemplateId(ROOT_ID),
                1,
                new TemplateClosureAuthority.OwnerScope("owner-a"),
                SCHEMA,
                "renderweave-design/1.0",
                "renderweave-expression/1.0",
                admitted.canonicalUtf8(),
                admitted.contentHash());
        return new ClosureSnapshot(
                new TemplateClosureAuthority.OwnerScope("owner-a"),
                snapshot.templateId(),
                1,
                List.of(snapshot),
                List.of());
    }

    private static ClosureSnapshot closureWithTemplateUseChildren(int childCount) {
        var uses = new ArrayList<String>(childCount);
        var edges = new ArrayList<ClosureEdge>(childCount);
        for (var index = 0; index < childCount; index++) {
            var nodeId = capacityUuid(1, index);
            var useId = capacityUuid(2, index);
            uses.add("{\"nodeId\":\"" + nodeId + "\",\"kind\":\"templateUse\","
                    + "\"bindings\":[],\"useId\":\"" + useId + "\","
                    + "\"templateRef\":{\"templateId\":\"" + CHILD_ID + "\"},"
                    + "\"contextSelector\":{\"kind\":\"empty\"},\"fills\":[],"
                    + "\"placement\":{\"type\":\"ABSOLUTE\",\"xMm\":0,\"yMm\":0,"
                    + "\"widthMode\":\"FIXED\",\"widthMm\":10,"
                    + "\"heightMode\":\"FIXED\",\"heightMm\":10}}");
            edges.add(new ClosureEdge(
                    new TemplateApplication.TemplateId(ROOT_ID),
                    1,
                    useId,
                    new TemplateApplication.TemplateId(CHILD_ID),
                    1));
        }
        var rootSnapshot = snapshot(ROOT_ID, canvasWithChildren(String.join(",", uses)));
        var childSnapshot = snapshot(CHILD_ID, canvasWithChildren(""));
        return new ClosureSnapshot(
                new TemplateClosureAuthority.OwnerScope("owner-a"),
                rootSnapshot.templateId(),
                1,
                List.of(rootSnapshot, childSnapshot),
                List.copyOf(edges));
    }

    private static ClosureSnapshot closureWithSkippedTemplateUses(int skippedCount) {
        var skippedUseId = capacityUuid(4, 1);
        var actualUseId = capacityUuid(4, 2);
        var items = String.join(",",
                java.util.Collections.nCopies(skippedCount, "\"x\""));
        var skippedUse = "{\"nodeId\":\"" + capacityUuid(3, 2)
                + "\",\"kind\":\"templateUse\",\"bindings\":[],"
                + "\"useId\":\"" + skippedUseId + "\","
                + "\"templateRef\":{\"templateId\":\"" + CHILD_ID + "\"},"
                + "\"contextSelector\":{\"kind\":\"context\","
                + "\"domain\":{\"kind\":\"invocation\"},\"pointer\":\"/missing\","
                + "\"contextAbsentPolicy\":\"SKIP\"},\"fills\":[],"
                + "\"placement\":{\"type\":\"PACK\",\"widthMode\":\"FIXED\","
                + "\"widthMm\":10,\"heightMode\":\"FIXED\",\"heightMm\":10}}";
        var repeat = "{\"nodeId\":\"" + capacityUuid(3, 1)
                + "\",\"kind\":\"repeat\",\"bindings\":[],"
                + "\"placement\":{\"type\":\"ABSOLUTE\",\"xMm\":0,\"yMm\":0,"
                + "\"widthMode\":\"FIXED\",\"widthMm\":10,"
                + "\"heightMode\":\"FIXED\",\"heightMm\":10},"
                + "\"loopId\":\"" + capacityUuid(5, 1) + "\","
                + "\"absentPolicy\":\"ERROR\",\"items\":{\"kind\":\"literal\","
                + "\"valueType\":{\"type\":\"list\",\"items\":\"text\"},"
                + "\"value\":[" + items + "]},"
                + "\"itemLayout\":{\"kind\":\"STACK\",\"direction\":\"ROW\"},"
                + "\"instanceLayout\":{\"kind\":\"STACK\",\"direction\":\"ROW\"},"
                + "\"children\":[" + skippedUse + "]}";
        var actualUse = "{\"nodeId\":\"" + capacityUuid(3, 3)
                + "\",\"kind\":\"templateUse\",\"bindings\":[],"
                + "\"useId\":\"" + actualUseId + "\","
                + "\"templateRef\":{\"templateId\":\"" + CHILD_ID + "\"},"
                + "\"contextSelector\":{\"kind\":\"empty\"},\"fills\":[],"
                + "\"placement\":{\"type\":\"ABSOLUTE\",\"xMm\":0,\"yMm\":0,"
                + "\"widthMode\":\"FIXED\",\"widthMm\":10,"
                + "\"heightMode\":\"FIXED\",\"heightMm\":10}}";
        var rootSnapshot = snapshot(
                ROOT_ID, canvasWithChildren(repeat + "," + actualUse));
        var childSnapshot = snapshot(CHILD_ID, canvasWithChildren(""));
        var rootTemplateId = new TemplateApplication.TemplateId(ROOT_ID);
        var childTemplateId = new TemplateApplication.TemplateId(CHILD_ID);
        return new ClosureSnapshot(
                new TemplateClosureAuthority.OwnerScope("owner-a"),
                rootTemplateId,
                1,
                List.of(rootSnapshot, childSnapshot),
                List.of(
                        new ClosureEdge(rootTemplateId, 1, skippedUseId, childTemplateId, 1),
                        new ClosureEdge(rootTemplateId, 1, actualUseId, childTemplateId, 1)));
    }

    private static ClosureSnapshot closureWithInvocationDepth(int invocationDepth) {
        if (invocationDepth < 1) {
            throw new IllegalArgumentException("invocationDepth must be positive");
        }
        var snapshots = new ArrayList<TemplateSnapshot>(invocationDepth);
        var edges = new ArrayList<ClosureEdge>(Math.max(0, invocationDepth - 1));
        var currentTemplateId = ROOT_ID;
        for (var depth = 1; depth <= invocationDepth; depth++) {
            var leaf = depth == invocationDepth;
            var childTemplateId = leaf ? null : capacityUuid(6, depth);
            var useId = leaf ? null : capacityUuid(7, depth);
            var children = leaf ? "" : templateUse(childTemplateId, useId, depth);
            snapshots.add(snapshot(currentTemplateId, canvasWithChildren(children)));
            if (!leaf) {
                edges.add(new ClosureEdge(
                        new TemplateApplication.TemplateId(currentTemplateId),
                        1,
                        useId,
                        new TemplateApplication.TemplateId(childTemplateId),
                        1));
                currentTemplateId = childTemplateId;
            }
        }
        return new ClosureSnapshot(
                new TemplateClosureAuthority.OwnerScope("owner-a"),
                new TemplateApplication.TemplateId(ROOT_ID),
                1,
                List.copyOf(snapshots),
                List.copyOf(edges));
    }

    private static ClosureSnapshot closureWithRepeatAcrossTemplateDepth(int repeatDepth) {
        if (repeatDepth < 1) {
            throw new IllegalArgumentException("repeatDepth must be positive");
        }
        var snapshots = new ArrayList<TemplateSnapshot>(repeatDepth);
        var edges = new ArrayList<ClosureEdge>(Math.max(0, repeatDepth - 1));
        var currentTemplateId = ROOT_ID;
        for (var depth = 1; depth <= repeatDepth; depth++) {
            var leaf = depth == repeatDepth;
            var childTemplateId = leaf ? null : capacityUuid(6, depth);
            var useId = leaf ? null : capacityUuid(7, depth);
            var child = leaf
                    ? packedCapacityRect(capacityUuid(3, depth))
                    : packedTemplateUse(childTemplateId, useId, depth);
            snapshots.add(snapshot(
                    currentTemplateId,
                    canvasWithChildren(repeatNode(depth, child, false, true))));
            if (!leaf) {
                edges.add(new ClosureEdge(
                        new TemplateApplication.TemplateId(currentTemplateId),
                        1,
                        useId,
                        new TemplateApplication.TemplateId(childTemplateId),
                        1));
                currentTemplateId = childTemplateId;
            }
        }
        return new ClosureSnapshot(
                new TemplateClosureAuthority.OwnerScope("owner-a"),
                new TemplateApplication.TemplateId(ROOT_ID),
                1,
                List.copyOf(snapshots),
                List.copyOf(edges));
    }

    private static String templateUse(String childTemplateId, String useId, int depth) {
        return "{\"nodeId\":\"" + capacityUuid(8, depth)
                + "\",\"kind\":\"templateUse\",\"bindings\":[],"
                + "\"useId\":\"" + useId + "\","
                + "\"templateRef\":{\"templateId\":\"" + childTemplateId + "\"},"
                + "\"contextSelector\":{\"kind\":\"empty\"},\"fills\":[],"
                + "\"placement\":{\"type\":\"ABSOLUTE\",\"xMm\":0,\"yMm\":0,"
                + "\"widthMode\":\"FIXED\",\"widthMm\":10,"
                + "\"heightMode\":\"FIXED\",\"heightMm\":10}}";
    }

    private static TemplateSnapshot snapshot(String templateId, String designDocument) {
        var admission = TemplateModule.designDslAuthority()
                .admit(designDocument.getBytes(StandardCharsets.UTF_8));
        if (!(admission instanceof DesignDslAuthority.Admitted admitted)) {
            throw new AssertionError("capacity fixture must be admitted: " + admission);
        }
        return new TemplateSnapshot(
                new TemplateApplication.TemplateId(templateId),
                1,
                new TemplateClosureAuthority.OwnerScope("owner-a"),
                SCHEMA,
                "renderweave-design/1.0",
                "renderweave-expression/1.0",
                admitted.canonicalUtf8(),
                admitted.contentHash());
    }

    private static String canvasWithChildren(String children) {
        return "{\"dslVersion\":\"renderweave-design/1.0\","
                + "\"expressionProfile\":\"renderweave-expression/1.0\","
                + "\"displayName\":\"R\",\"definitions\":[],"
                + "\"designRoot\":{\"nodeId\":\"00000000-0000-4000-8000-000000000001\","
                + "\"kind\":\"canvas\",\"widthMm\":210,\"heightMm\":297,"
                + "\"bindings\":[],\"children\":[" + children + "]}}";
    }

    private static String capacityUuid(int namespace, int ordinal) {
        return String.format(Locale.ROOT,
                "%d0000000-0000-4000-8000-%012x", namespace, ordinal);
    }

    private static String nestedRepeatDocument(int repeatDepth) {
        if (repeatDepth < 1) {
            throw new IllegalArgumentException("repeatDepth must be positive");
        }
        return canvasWithChildren(nestedRepeatNode(repeatDepth, 0, -1));
    }

    private static String nestedRepeatNode(
            int repeatDepth, int identityOffset, int renderFalseDepth) {
        var child = packedCapacityRect(capacityUuid(3, identityOffset + 999));
        for (var depth = repeatDepth; depth >= 1; depth--) {
            var identity = identityOffset + depth;
            child = repeatNode(
                    identity,
                    child,
                    depth == renderFalseDepth,
                    depth == 1);
        }
        return child;
    }

    private static String repeatNode(
            int identity, String child, boolean renderFalse, boolean absolutePlacement) {
        return "{\"nodeId\":\"" + capacityUuid(9, identity)
                + "\",\"kind\":\"repeat\",\"bindings\":[],"
                + (renderFalse ? "\"render\":false," : "")
                + "\"placement\":"
                + (absolutePlacement ? capacityAbsolutePlacement() : capacityPackPlacement())
                + ",\"loopId\":\"" + capacityUuid(5, identity) + "\","
                + "\"absentPolicy\":\"ERROR\",\"items\":{\"kind\":\"literal\","
                + "\"valueType\":{\"type\":\"list\",\"items\":\"text\"},"
                + "\"value\":[\"x\"]},"
                + "\"itemLayout\":{\"kind\":\"STACK\",\"direction\":\"ROW\"},"
                + "\"instanceLayout\":{\"kind\":\"STACK\",\"direction\":\"ROW\"},"
                + "\"children\":[" + child + "]}";
    }

    private static String packedTemplateUse(
            String childTemplateId, String useId, int identity) {
        return "{\"nodeId\":\"" + capacityUuid(8, identity)
                + "\",\"kind\":\"templateUse\",\"bindings\":[],"
                + "\"useId\":\"" + useId + "\","
                + "\"templateRef\":{\"templateId\":\"" + childTemplateId + "\"},"
                + "\"contextSelector\":{\"kind\":\"empty\"},\"fills\":[],"
                + "\"placement\":" + capacityPackPlacement() + "}";
    }

    private static String packedCapacityRect(String nodeId) {
        return "{\"nodeId\":\"" + nodeId + "\",\"kind\":\"rect\",\"bindings\":[],"
                + "\"placement\":" + capacityPackPlacement() + ","
                + "\"fill\":{\"color\":\"#FF000000\"}}";
    }

    private static String capacityAbsolutePlacement() {
        return "{\"type\":\"ABSOLUTE\",\"xMm\":0,\"yMm\":0,"
                + "\"widthMode\":\"FIXED\",\"widthMm\":10,"
                + "\"heightMode\":\"FIXED\",\"heightMm\":10}";
    }

    private static String capacityPackPlacement() {
        return "{\"type\":\"PACK\",\"widthMode\":\"FIXED\",\"widthMm\":10,"
                + "\"heightMode\":\"FIXED\",\"heightMm\":10}";
    }

    private static String loopFrameCapacityDocument(
            int totalFrames, boolean demandInLastFrame) {
        return loopFrameCapacityDocument(totalFrames, demandInLastFrame, false);
    }

    private static String loopFrameCapacityDocument(
            int totalFrames, boolean demandInLastFrame, boolean includeZeroPrefix) {
        if (totalFrames < 1) {
            throw new IllegalArgumentException("totalFrames must be positive");
        }
        var repeatCount = Math.ceilDiv(totalFrames, 1_000);
        var finalLoopId = capacityUuid(5, repeatCount);
        var definitionId = capacityUuid(7, 999);
        var repeats = new ArrayList<String>(repeatCount + (includeZeroPrefix ? 1 : 0));
        if (includeZeroPrefix) {
            repeats.add(loopFrameRepeat(900, 0, prunedCapacityRect(900)));
        }
        var remaining = totalFrames;
        for (var identity = 1; identity <= repeatCount; identity++) {
            var itemCount = Math.min(1_000, remaining);
            var finalRepeat = identity == repeatCount;
            var child = demandInLastFrame && finalRepeat
                    ? demandedCapacityRect(identity, definitionId)
                    : prunedCapacityRect(identity);
            repeats.add(loopFrameRepeat(identity, itemCount, child));
            remaining -= itemCount;
        }
        var document = canvasWithChildren(String.join(",", repeats));
        if (!demandInLastFrame) {
            return document;
        }
        var definition = "{\"definitionId\":\"" + definitionId
                + "\",\"kind\":\"expression\",\"displayName\":\"Final draw\","
                + "\"domain\":{\"kind\":\"loop\",\"loopId\":\"" + finalLoopId
                + "\"},\"output\":\"decimal\",\"inputs\":[{\"alias\":\"draw\","
                + "\"source\":{\"kind\":\"capability\",\"capability\":\"RANDOM\","
                + "\"operation\":\"UNIFORM_DECIMAL_0_1\"}}],\"source\":\"input.draw\"}";
        return document.replace("\"definitions\":[]", "\"definitions\":[" + definition + "]");
    }

    private static String nestedLoopFrameOverflowDocument() {
        var inner = loopFrameRepeat(2, 10, prunedCapacityRect(3), false);
        var outer = loopFrameRepeat(1, 1_000, inner, true);
        return canvasWithChildren(outer);
    }

    private static String loopFrameRepeat(int identity, int itemCount, String child) {
        return loopFrameRepeat(identity, itemCount, child, true);
    }

    private static String loopFrameRepeat(
            int identity, int itemCount, String child, boolean absolutePlacement) {
        return "{\"nodeId\":\"" + capacityUuid(4, identity)
                + "\",\"kind\":\"repeat\",\"bindings\":[],"
                + "\"placement\":"
                + (absolutePlacement ? capacityAbsolutePlacement() : capacityPackPlacement()) + ","
                + "\"loopId\":\"" + capacityUuid(5, identity) + "\","
                + "\"absentPolicy\":\"ERROR\",\"items\":{\"kind\":\"literal\","
                + "\"valueType\":{\"type\":\"list\",\"items\":\"text\"},"
                + "\"value\":" + repeatedTextItems(itemCount) + "},"
                + "\"itemLayout\":{\"kind\":\"STACK\",\"direction\":\"ROW\"},"
                + "\"instanceLayout\":{\"kind\":\"STACK\",\"direction\":\"ROW\"},"
                + "\"children\":[" + child + "]}";
    }

    private static String prunedCapacityRect(int identity) {
        return "{\"nodeId\":\"" + capacityUuid(3, identity)
                + "\",\"kind\":\"rect\",\"render\":false,\"bindings\":[],"
                + "\"placement\":" + capacityPackPlacement() + ","
                + "\"fill\":{\"color\":\"#FF000000\"}}";
    }

    private static String demandedCapacityRect(int identity, String definitionId) {
        return "{\"nodeId\":\"" + capacityUuid(3, identity)
                + "\",\"kind\":\"rect\",\"bindings\":[{\"bindingId\":\""
                + capacityUuid(8, identity) + "\",\"targetPropertyRef\":{"
                + "\"rootPropertyId\":\"placement\",\"selectors\":[{\"kind\":\"member\","
                + "\"name\":\"widthMm\"}]},\"source\":{\"kind\":\"definition\","
                + "\"definitionId\":\"" + definitionId + "\"}}],"
                + "\"placement\":" + capacityPackPlacement() + ","
                + "\"fill\":{\"color\":\"#FF000000\"}}";
    }

    private static String canvasWithRect() {
        return "{\"dslVersion\":\"renderweave-design/1.0\","
                + "\"expressionProfile\":\"renderweave-expression/1.0\","
                + "\"displayName\":\"R\",\"definitions\":[],"
                + "\"designRoot\":{\"nodeId\":\"00000000-0000-4000-8000-000000000001\","
                + "\"kind\":\"canvas\",\"widthMm\":210,\"heightMm\":297,\"bindings\":[],"
                + "\"children\":[{\"nodeId\":\"00000000-0000-4000-8000-000000000011\","
                + "\"kind\":\"rect\",\"bindings\":[],\"placement\":{\"type\":\"ABSOLUTE\","
                + "\"xMm\":0,\"yMm\":0,\"widthMode\":\"FIXED\",\"widthMm\":10,"
                + "\"heightMode\":\"FIXED\",\"heightMm\":10},"
                + "\"fill\":{\"color\":\"#FF000000\"}}]}}";
    }

    private static String canvasWithImage() {
        return "{\"dslVersion\":\"renderweave-design/1.0\","
                + "\"expressionProfile\":\"renderweave-expression/1.0\","
                + "\"displayName\":\"R\",\"definitions\":[],"
                + "\"designRoot\":{\"nodeId\":\"00000000-0000-4000-8000-000000000001\","
                + "\"kind\":\"canvas\",\"widthMm\":210,\"heightMm\":297,\"bindings\":[],"
                + "\"children\":[{\"nodeId\":\"00000000-0000-4000-8000-000000000011\","
                + "\"kind\":\"image\",\"bindings\":[],\"placement\":{\"type\":\"ABSOLUTE\","
                + "\"xMm\":0,\"yMm\":0,\"widthMode\":\"FIXED\",\"widthMm\":10,"
                + "\"heightMode\":\"FIXED\",\"heightMm\":10},"
                + "\"imageRef\":{\"assetId\":\"00000000-0000-4000-8000-0000000000aa\"}}]}}";
    }

    private static String canvasWithTwoImages() {
        return canvasWithChildren(
                imageNode("00000000-0000-4000-8000-000000000011",
                        "00000000-0000-4000-8000-0000000000aa")
                        + ","
                        + imageNode("00000000-0000-4000-8000-000000000012",
                        "00000000-0000-4000-8000-0000000000ab"));
    }

    private static String imageNode(String nodeId, String assetId) {
        return "{\"nodeId\":\"" + nodeId + "\",\"kind\":\"image\",\"bindings\":[],"
                + "\"placement\":{\"type\":\"ABSOLUTE\",\"xMm\":0,\"yMm\":0,"
                + "\"widthMode\":\"FIXED\",\"widthMm\":10,"
                + "\"heightMode\":\"FIXED\",\"heightMm\":10},"
                + "\"imageRef\":{\"assetId\":\"" + assetId + "\"}}";
    }

    private static String canvasWithPublicImageCustom() {
        return canvasWithRect().replace("\"definitions\":[]", "\"definitions\":[{"
                + "\"definitionId\":\"" + PUBLIC_IMAGE_DEFINITION + "\","
                + "\"kind\":\"custom\",\"displayName\":\"Logo\","
                + "\"exposure\":\"PUBLIC\",\"valueType\":\"imageRef\","
                + "\"defaultValue\":{\"assetId\":\"" + DEFAULT_IMAGE + "\"}}]");
    }

    private static String externalImageEnvelope() {
        return "{\"rootDocument\":{},\"customValues\":["
                + "{\"definitionId\":\"" + PUBLIC_IMAGE_DEFINITION
                + "\",\"value\":{\"assetId\":\"" + OVERRIDE_IMAGE_LOSER + "\"}},"
                + "{\"definitionId\":\"" + PUBLIC_IMAGE_DEFINITION
                + "\",\"value\":{\"assetId\":\"" + OVERRIDE_IMAGE + "\"}}]}";
    }

    private static String canvasWithUnusedRandom() {
        return withUnusedRandom(canvasWithRect());
    }

    private static String withUnusedClock(String designDocument) {
        return designDocument.replace("\"definitions\":[]", "\"definitions\":[{"
                + "\"definitionId\":\"00000000-0000-4000-8000-0000000000e2\","
                + "\"kind\":\"expression\",\"displayName\":\"Today\","
                + "\"domain\":\"invocation\",\"output\":\"date\","
                + "\"inputs\":[{\"alias\":\"today\",\"source\":{\"kind\":\"capability\","
                + "\"capability\":\"CLOCK\",\"operation\":\"UTC_DATE\"}}],"
                + "\"source\":\"input.today\"}]");
    }

    private static String withUnusedRandom(String designDocument) {
        return designDocument.replace("\"definitions\":[]", "\"definitions\":[{"
                + "\"definitionId\":\"00000000-0000-4000-8000-0000000000e1\","
                + "\"kind\":\"expression\",\"displayName\":\"Draw\","
                + "\"domain\":\"invocation\",\"output\":\"text\","
                + "\"inputs\":[{\"alias\":\"draw\",\"source\":{\"kind\":\"capability\","
                + "\"capability\":\"RANDOM\",\"operation\":\"UNIFORM_DECIMAL_0_1\"}}],"
                + "\"source\":\"if(input.draw < 0.5, 'A', 'B')\"}]");
    }

    private static String withUnusedRandomScaleOverflow(String designDocument) {
        return designDocument.replace("\"definitions\":[]", "\"definitions\":[{"
                + "\"definitionId\":\"00000000-0000-4000-8000-0000000000e6\","
                + "\"kind\":\"expression\",\"displayName\":\"Overflow\","
                + "\"domain\":\"invocation\",\"output\":\"decimal\","
                + "\"inputs\":[{\"alias\":\"draw\",\"source\":{\"kind\":\"capability\","
                + "\"capability\":\"RANDOM\",\"operation\":\"UNIFORM_DECIMAL_0_1\"}}],"
                + "\"source\":\"round(input.draw, 65, 'HALF_UP')\"}]");
    }

    private static String withUnusedRandomSourceOverflow(String designDocument) {
        var prefix = "if(input.draw < 0.5, '";
        var suffix = "', 'B')";
        var source = prefix + "a".repeat(65_537 - prefix.length() - suffix.length()) + suffix;
        assertEquals(65_537, source.getBytes(StandardCharsets.UTF_8).length);
        return designDocument.replace("\"definitions\":[]", "\"definitions\":[{"
                + "\"definitionId\":\"00000000-0000-4000-8000-0000000000e7\","
                + "\"kind\":\"expression\",\"displayName\":\"Overflow\","
                + "\"domain\":\"invocation\",\"output\":\"text\","
                + "\"inputs\":[{\"alias\":\"draw\",\"source\":{\"kind\":\"capability\","
                + "\"capability\":\"RANDOM\",\"operation\":\"UNIFORM_DECIMAL_0_1\"}}],"
                + "\"source\":\"" + source + "\"}]");
    }

    private static String loopRandomCapabilityDocument(String items) {
        var definitionId = "00000000-0000-4000-8000-0000000000e3";
        var loopId = "00000000-0000-4000-8000-0000000000b1";
        return "{\"dslVersion\":\"renderweave-design/1.0\","
                + "\"expressionProfile\":\"renderweave-expression/1.0\","
                + "\"displayName\":\"Capability budget\",\"definitions\":[{"
                + "\"definitionId\":\"" + definitionId + "\",\"kind\":\"expression\","
                + "\"displayName\":\"Draw\",\"domain\":{\"kind\":\"loop\","
                + "\"loopId\":\"" + loopId + "\"},\"output\":\"decimal\","
                + "\"inputs\":[{\"alias\":\"draw\",\"source\":{\"kind\":\"capability\","
                + "\"capability\":\"RANDOM\",\"operation\":\"UNIFORM_DECIMAL_0_1\"}}],"
                + "\"source\":\"input.draw\"}],\"designRoot\":{"
                + "\"nodeId\":\"00000000-0000-4000-8000-000000000001\","
                + "\"kind\":\"canvas\",\"widthMm\":210,\"heightMm\":297,\"bindings\":[],"
                + "\"children\":[{\"nodeId\":\"00000000-0000-4000-8000-000000000092\","
                + "\"kind\":\"repeat\",\"bindings\":[],\"placement\":{\"type\":\"ABSOLUTE\","
                + "\"xMm\":0,\"yMm\":0,\"widthMode\":\"FIXED\",\"widthMm\":20,"
                + "\"heightMode\":\"FIXED\",\"heightMm\":20},"
                + "\"loopId\":\"" + loopId + "\",\"absentPolicy\":\"ERROR\","
                + "\"items\":{\"kind\":\"literal\",\"valueType\":{\"type\":\"list\","
                + "\"items\":\"text\"},\"value\":" + items + "},"
                + "\"itemLayout\":{\"kind\":\"STACK\",\"direction\":\"ROW\"},"
                + "\"instanceLayout\":{\"kind\":\"STACK\",\"direction\":\"ROW\"},"
                + "\"children\":[{\"nodeId\":\"00000000-0000-4000-8000-000000000093\","
                + "\"kind\":\"rect\",\"bindings\":[{"
                + "\"bindingId\":\"00000000-0000-4000-8000-0000000000f1\","
                + "\"targetPropertyRef\":{\"rootPropertyId\":\"placement\","
                + "\"selectors\":[{\"kind\":\"member\",\"name\":\"widthMm\"}]},"
                + "\"source\":{\"kind\":\"definition\",\"definitionId\":\""
                + definitionId + "\"}}],\"placement\":{\"type\":\"PACK\","
                + "\"widthMode\":\"FIXED\",\"widthMm\":10,\"heightMode\":\"FIXED\","
                + "\"heightMm\":10},\"fill\":{\"color\":\"#FF000000\"}}]}]}}";
    }

    private static String repeatedTextItems(int count) {
        return "[" + String.join(",",
                java.util.Collections.nCopies(count, "\"x\"")) + "]";
    }

    private static String invocationRandomCapabilityDocument(String items) {
        return loopRandomCapabilityDocument(items).replace(
                "\"domain\":{\"kind\":\"loop\","
                        + "\"loopId\":\"00000000-0000-4000-8000-0000000000b1\"}",
                "\"domain\":\"invocation\"");
    }

    private static String twoRandomAliasCapabilityDocument() {
        var definitionId = "00000000-0000-4000-8000-0000000000e4";
        return canvasWithRect()
                .replace("\"definitions\":[]", "\"definitions\":[{"
                        + "\"definitionId\":\"" + definitionId + "\","
                        + "\"kind\":\"expression\",\"displayName\":\"Two draws\","
                        + "\"domain\":\"invocation\",\"output\":\"decimal\","
                        + "\"inputs\":["
                        + "{\"alias\":\"first\",\"source\":{\"kind\":\"capability\","
                        + "\"capability\":\"RANDOM\","
                        + "\"operation\":\"UNIFORM_DECIMAL_0_1\"}},"
                        + "{\"alias\":\"second\",\"source\":{\"kind\":\"capability\","
                        + "\"capability\":\"RANDOM\","
                        + "\"operation\":\"UNIFORM_DECIMAL_0_1\"}}],"
                        + "\"source\":\"input.first + input.second\"}]")
                .replace("\"kind\":\"rect\",\"bindings\":[]", "\"kind\":\"rect\","
                        + "\"bindings\":[{"
                        + "\"bindingId\":\"00000000-0000-4000-8000-0000000000f4\","
                        + "\"targetPropertyRef\":{\"rootPropertyId\":\"placement\","
                        + "\"selectors\":[{\"kind\":\"member\","
                        + "\"name\":\"widthMm\"}]},"
                        + "\"source\":{\"kind\":\"definition\","
                        + "\"definitionId\":\"" + definitionId + "\"}}]");
    }

    private static String twoClockAliasCapabilityDocument() {
        var definitionId = "00000000-0000-4000-8000-0000000000e5";
        return canvasWithRect()
                .replace("\"definitions\":[]", "\"definitions\":[{"
                        + "\"definitionId\":\"" + definitionId + "\","
                        + "\"kind\":\"expression\",\"displayName\":\"Two dates\","
                        + "\"domain\":\"invocation\",\"output\":\"decimal\","
                        + "\"inputs\":["
                        + "{\"alias\":\"first\",\"source\":{\"kind\":\"capability\","
                        + "\"capability\":\"CLOCK\",\"operation\":\"UTC_DATE\"}},"
                        + "{\"alias\":\"second\",\"source\":{\"kind\":\"capability\","
                        + "\"capability\":\"CLOCK\",\"operation\":\"UTC_DATE\"}}],"
                        + "\"source\":\"if(input.first == input.second, 10, 10)\"}]")
                .replace("\"kind\":\"rect\",\"bindings\":[]", "\"kind\":\"rect\","
                        + "\"bindings\":[{"
                        + "\"bindingId\":\"00000000-0000-4000-8000-0000000000f5\","
                        + "\"targetPropertyRef\":{\"rootPropertyId\":\"placement\","
                        + "\"selectors\":[{\"kind\":\"member\","
                        + "\"name\":\"widthMm\"}]},"
                        + "\"source\":{\"kind\":\"definition\","
                        + "\"definitionId\":\"" + definitionId + "\"}}]");
    }

    private static final class RejectingAssetPort implements AssetResolutionPort {
        @Override
        public PrecheckOutcome precheckAdmission(
                cn.hbads.renderweave.asset.api.AssetApplication.OwnerScope ownerScope,
                cn.hbads.renderweave.asset.api.AssetApplication.AssetId assetId,
                cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.AssetKind expectedKind
        ) {
            return new PrecheckOutcome.PrecheckRejected(AdmissionRejection.NOT_FOUND);
        }

        @Override
        public ResolveOutcome resolve(ResolveRequest request) {
            throw new IllegalStateException("resolution must not follow rejected admission");
        }
    }

    private static final class RecordingAssetPort implements AssetResolutionPort {
        private final java.util.ArrayList<String> precheckedAssetIds = new java.util.ArrayList<>();

        @Override
        public PrecheckOutcome precheckAdmission(
                cn.hbads.renderweave.asset.api.AssetApplication.OwnerScope ownerScope,
                cn.hbads.renderweave.asset.api.AssetApplication.AssetId assetId,
                cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.AssetKind expectedKind
        ) {
            precheckedAssetIds.add(assetId.value());
            return new PrecheckOutcome.PrecheckPassed();
        }

        @Override
        public ResolveOutcome resolve(ResolveRequest request) {
            throw new AssertionError("unused CustomDefinition must not resolve an Asset");
        }
    }

    private static final class CapturingAssetPort implements AssetResolutionPort {
        private ResolveRequest lastRequest;

        @Override
        public PrecheckOutcome precheckAdmission(
                cn.hbads.renderweave.asset.api.AssetApplication.OwnerScope ownerScope,
                cn.hbads.renderweave.asset.api.AssetApplication.AssetId assetId,
                cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.AssetKind expectedKind
        ) {
            return new PrecheckOutcome.PrecheckPassed();
        }

        @Override
        public ResolveOutcome resolve(ResolveRequest request) {
            lastRequest = request;
            return new ResolveOutcome.Resolved(new ResolvedAssetFact(
                    "0",
                    "b".repeat(64),
                    "image/png",
                    128,
                    "renderweave-asset-acceptance/1.0",
                    new cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.ImageDescriptor(
                            1, 1,
                            cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.Orientation.IDENTITY,
                            1, 1, 1,
                            cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.ColorEncoding.SRGB_8BIT),
                    "https://assets.internal/fetch/lease",
                    request.deadlineEpochMilli() / 1_000));
        }
    }

    private static ValidationTargetResolver resolver() {
        var rootSchema = new ResolvedSchema(
                new ResolvedSchemaIdentity.StaticIdentity(SCHEMA),
                new cn.hbads.renderweave.schema.definition.SchemaDefinition(
                        cn.hbads.renderweave.schema.definition.SchemaDefinition.DSL_VERSION,
                        "Empty",
                        java.util.Optional.empty(),
                        List.of()));
        var target = new ResolvedValidationTarget(
                new ResolvedSchemaIdentity.StaticIdentity(SCHEMA),
                Map.of(),
                Map.of(SCHEMA, rootSchema));
        return ignored -> target;
    }
}
