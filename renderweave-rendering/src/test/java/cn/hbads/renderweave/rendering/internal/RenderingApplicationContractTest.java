package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.rendering.api.EvaluationStage;
import cn.hbads.renderweave.rendering.api.Evaluator;
import cn.hbads.renderweave.rendering.api.Evaluator.EvaluationCommand;
import cn.hbads.renderweave.rendering.api.Evaluator.EvaluationOutcome;
import cn.hbads.renderweave.rendering.api.Evaluator.OutputSelection;
import cn.hbads.renderweave.rendering.api.RenderOutput;
import cn.hbads.renderweave.rendering.api.RenderingApplication;
import cn.hbads.renderweave.rendering.api.RenderingApplication.RenderCommand;
import cn.hbads.renderweave.rendering.api.RenderingApplication.RenderInvocationRef;
import cn.hbads.renderweave.rendering.api.RenderingApplication.RenderOutcome;
import cn.hbads.renderweave.rendering.api.RenderingApplication.RenderPurpose;
import cn.hbads.renderweave.rendering.api.RenderingProblem;
import cn.hbads.renderweave.rendering.spi.RenderEngine;
import cn.hbads.renderweave.rendering.spi.RenderEngine.EngineOutcome;
import cn.hbads.renderweave.rendering.spi.RenderEngine.RendererCommand;
import cn.hbads.renderweave.rendering.spi.RendererProfileAuthority;
import cn.hbads.renderweave.rendering.spi.RenderingAuthority;
import cn.hbads.renderweave.template.api.TemplateApplication.TemplateId;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderingApplicationContractTest {

    private static final RenderInvocationRef INVOCATION =
            RenderInvocationRef.serverCreated("rendering-contract");
    private static final TemplateId ROOT_TEMPLATE =
            TemplateId.of("00000000-0000-4000-8000-0000000000a1");
    private static final OutputSelection OUTPUT = OutputSelection.defaultPng();
    private static final long NOW = 1_787_673_600_000L;

    @Test
    void successfulFormalRenderUsesOneDeadlineOneEvaluationAndDistinctPublicIdentity() {
        var evaluator = new ScriptedEvaluator();
        var engine = new ScriptedEngine(new EngineOutcome.SealedOutput(output(OUTPUT)));
        var authority = grantedAuthority(RenderPurpose.FORMAL_OUTPUT);
        var application = application(evaluator, engine, authority, availableProfiles());

        var outcome = application.render(INVOCATION, command(RenderPurpose.FORMAL_OUTPUT));

        var rendered = assertInstanceOf(RenderOutcome.Rendered.class, outcome);
        assertArrayEquals(new byte[] { 1, 2, 3 }, rendered.output().sealedImageBytes());
        assertEquals(1, evaluator.seen.size());
        assertEquals(1, engine.seen.size());
        assertEquals(1, authority.recheckCalls);
        assertEquals(RenderPurpose.FORMAL_OUTPUT, authority.seenPurpose);

        var evaluation = evaluator.seen.get(0);
        var engineCommand = engine.seen.get(0);
        assertNotEquals(rendered.operationId().value(), evaluation.renderRequestId().value());
        assertEquals(evaluation.renderRequestId(), engineCommand.renderRequestId());
        assertEquals(NOW + 60_000L, evaluation.deadlineAtEpochMilli());
        assertEquals(evaluation.deadlineAtEpochMilli(), engineCommand.deadlineAtEpochMilli());
        assertEquals("renderweave-renderer/1.0", evaluation.rendererProfile());
        assertEquals(evaluation.rendererProfile(), engineCommand.rendererProfile());
        assertArrayEquals(
                evaluator.sealedDocumentBytes,
                engineCommand.renderDocumentCanonicalUtf8());
    }

    @Test
    void profileUnavailabilityStopsBeforeEvaluationAndStillRechecksAuthority() {
        var evaluator = new ScriptedEvaluator();
        var engine = new ScriptedEngine(new EngineOutcome.SealedOutput(output(OUTPUT)));
        var authority = grantedAuthority(RenderPurpose.AUTHORITATIVE_PREVIEW);
        var profiles = new ScriptedProfiles(new RendererProfileAuthority.Unavailable());
        var application = application(evaluator, engine, authority, profiles);

        var outcome = application.render(
                INVOCATION,
                command(RenderPurpose.AUTHORITATIVE_PREVIEW));

        assertInstanceOf(RenderOutcome.RendererUnavailable.class, outcome);
        assertEquals(RenderPurpose.AUTHORITATIVE_PREVIEW, authority.seenPurpose);
        assertEquals(1, authority.recheckCalls);
        assertEquals(1, profiles.calls);
        assertEquals(0, evaluator.seen.size());
        assertEquals(0, engine.seen.size());
    }

    @Test
    void initialAuthorizationOutcomesDoNotProbeProfileOrPayload() {
        for (var decision : List.<RenderingAuthority.AuthorizationDecision>of(
                new RenderingAuthority.Hidden(),
                new RenderingAuthority.Forbidden(),
                new RenderingAuthority.Unavailable())) {
            var evaluator = new ScriptedEvaluator();
            var engine = new ScriptedEngine(new EngineOutcome.SealedOutput(output(OUTPUT)));
            var authority = new ScriptedAuthority(
                    decision,
                    new RenderingAuthority.RecheckGranted(
                            RenderingAuthority.Disclosure.READABLE));
            var profiles = availableProfiles();
            var application = application(evaluator, engine, authority, profiles);

            var outcome = application.render(INVOCATION, command(RenderPurpose.FORMAL_OUTPUT));

            if (decision instanceof RenderingAuthority.Hidden) {
                assertInstanceOf(RenderOutcome.NotFound.class, outcome);
            } else if (decision instanceof RenderingAuthority.Forbidden) {
                assertInstanceOf(RenderOutcome.Forbidden.class, outcome);
            } else {
                assertInstanceOf(RenderOutcome.AuthorityUnavailable.class, outcome);
            }
            assertEquals(0, authority.recheckCalls);
            assertEquals(0, profiles.calls);
            assertEquals(0, evaluator.seen.size());
            assertEquals(0, engine.seen.size());
        }
    }

    @Test
    void unknownAndBusyResendTheSameCommandWithoutReevaluation() {
        var evaluator = new ScriptedEvaluator();
        var busy = new EngineOutcome.TerminalProblem(RenderingProblem.of(
                RenderingProblem.ProblemCode.RENDER_ENGINE_BUSY,
                EvaluationStage.ENGINE));
        var engine = new ScriptedEngine(
                new EngineOutcome.Unknown(),
                busy,
                new EngineOutcome.Joined(output(OUTPUT)));
        var authority = grantedAuthority(RenderPurpose.FORMAL_OUTPUT);
        var application = application(evaluator, engine, authority, availableProfiles());

        var outcome = application.render(INVOCATION, command(RenderPurpose.FORMAL_OUTPUT));

        assertInstanceOf(RenderOutcome.Rendered.class, outcome);
        assertEquals(1, evaluator.seen.size());
        assertEquals(3, engine.seen.size());
        assertSame(engine.seen.get(0), engine.seen.get(1));
        assertSame(engine.seen.get(0), engine.seen.get(2));
        assertEquals(1, authority.recheckCalls);
    }

    @Test
    void evaluationRejectionIsReleasedOnlyAfterLatestAuthorityDecision() {
        var evaluator = new ScriptedEvaluator();
        evaluator.outcome = new EvaluationOutcome.Rejected(
                EvaluationStage.INPUT_ADMISSION,
                RenderingProblem.of(
                        RenderingProblem.ProblemCode.EVALUATION_FAILED,
                        EvaluationStage.INPUT_ADMISSION));
        var engine = new ScriptedEngine(new EngineOutcome.SealedOutput(output(OUTPUT)));
        var authority = new ScriptedAuthority(
                granted(RenderPurpose.FORMAL_OUTPUT),
                new RenderingAuthority.RecheckForbidden());
        var application = application(evaluator, engine, authority, availableProfiles());

        var outcome = application.render(INVOCATION, command(RenderPurpose.FORMAL_OUTPUT));

        assertInstanceOf(RenderOutcome.Forbidden.class, outcome);
        assertEquals(1, evaluator.seen.size());
        assertEquals(0, engine.seen.size());
        assertEquals(1, authority.recheckCalls);
    }

    @Test
    void sealedOutputIsDiscardedWhenAuthorityDriftsBeforeRelease() {
        var evaluator = new ScriptedEvaluator();
        var engine = new ScriptedEngine(new EngineOutcome.Replayed(output(OUTPUT)));
        var authority = new ScriptedAuthority(
                granted(RenderPurpose.FORMAL_OUTPUT),
                new RenderingAuthority.RecheckHidden());
        var application = application(evaluator, engine, authority, availableProfiles());

        var outcome = application.render(INVOCATION, command(RenderPurpose.FORMAL_OUTPUT));

        assertInstanceOf(RenderOutcome.NotFound.class, outcome);
        assertEquals(1, engine.seen.size());
        assertEquals(1, authority.recheckCalls);
    }

    @Test
    void engineTerminalProblemAndEvaluationProblemRemainClosed() {
        var problem = RenderingProblem.of(
                RenderingProblem.ProblemCode.RENDER_DEADLINE_EXCEEDED,
                EvaluationStage.ENGINE);
        var application = application(
                new ScriptedEvaluator(),
                new ScriptedEngine(new EngineOutcome.TerminalProblem(problem)),
                grantedAuthority(RenderPurpose.FORMAL_OUTPUT),
                availableProfiles());

        var outcome = application.render(INVOCATION, command(RenderPurpose.FORMAL_OUTPUT));

        var rejected = assertInstanceOf(RenderOutcome.Rejected.class, outcome);
        assertEquals(problem, rejected.problem());
    }

    @Test
    void opaqueFormalRenderRemovesAuthorLocationFromProblems() {
        var evaluator = new ScriptedEvaluator();
        evaluator.outcome = new EvaluationOutcome.Rejected(
                EvaluationStage.INPUT_ADMISSION,
                RenderingProblem.ofLocation(
                        RenderingProblem.ProblemCode.EVALUATION_FAILED,
                        EvaluationStage.INPUT_ADMISSION,
                        "/private-author-location"));
        var application = application(
                evaluator,
                new ScriptedEngine(new EngineOutcome.SealedOutput(output(OUTPUT))),
                grantedAuthority(RenderPurpose.FORMAL_OUTPUT),
                availableProfiles());

        var outcome = application.render(INVOCATION, command(RenderPurpose.FORMAL_OUTPUT));

        var rejected = assertInstanceOf(RenderOutcome.Rejected.class, outcome);
        assertEquals(RenderingProblem.ProblemCode.EVALUATION_FAILED,
                rejected.problem().code());
        assertEquals(EvaluationStage.INPUT_ADMISSION, rejected.problem().stage());
        assertTrue(rejected.problem().safeLocation().isEmpty());
    }

    @Test
    void repeatedUnknownStopsAtTheOriginalDeadlineWithoutReevaluation() {
        var evaluator = new ScriptedEvaluator();
        var engine = new ScriptedEngine(new EngineOutcome.Unknown());
        var authority = grantedAuthority(RenderPurpose.FORMAL_OUTPUT);
        var application = new CanonicalRenderingApplication(
                evaluator,
                engine,
                authority,
                availableProfiles(),
                new AdvancingClock(NOW, 20_000L),
                Duration.ZERO);

        var outcome = application.render(INVOCATION, command(RenderPurpose.FORMAL_OUTPUT));

        var rejected = assertInstanceOf(RenderOutcome.Rejected.class, outcome);
        assertEquals(RenderingProblem.ProblemCode.RENDER_DEADLINE_EXCEEDED,
                rejected.problem().code());
        assertEquals(EvaluationStage.ENGINE, rejected.problem().stage());
        assertEquals(1, evaluator.seen.size());
        assertEquals(NOW + 60_000L, evaluator.seen.get(0).deadlineAtEpochMilli());
        assertEquals(1, engine.seen.size());
        assertEquals(1, authority.recheckCalls);
    }

    @Test
    void selectedLayoutOrEngineOutputDriftFoldsToInternalProblem() {
        var mismatchedLayoutEvaluator = new ScriptedEvaluator();
        mismatchedLayoutEvaluator.layoutProfile = "renderweave-layout/2.0";
        var firstEngine = new ScriptedEngine(new EngineOutcome.SealedOutput(output(OUTPUT)));
        var first = application(
                mismatchedLayoutEvaluator,
                firstEngine,
                grantedAuthority(RenderPurpose.FORMAL_OUTPUT),
                availableProfiles());

        var layoutOutcome = first.render(INVOCATION, command(RenderPurpose.FORMAL_OUTPUT));

        var layoutRejected = assertInstanceOf(RenderOutcome.Rejected.class, layoutOutcome);
        assertEquals(RenderingProblem.ProblemCode.RENDER_INTERNAL_ERROR,
                layoutRejected.problem().code());
        assertEquals(EvaluationStage.DOCUMENT_SEAL, layoutRejected.problem().stage());
        assertEquals(0, firstEngine.seen.size());

        var wrongOutput = OutputSelection.defaultJpeg();
        var second = application(
                new ScriptedEvaluator(),
                new ScriptedEngine(new EngineOutcome.SealedOutput(output(wrongOutput))),
                grantedAuthority(RenderPurpose.FORMAL_OUTPUT),
                availableProfiles());

        var outputOutcome = second.render(INVOCATION, command(RenderPurpose.FORMAL_OUTPUT));

        var outputRejected = assertInstanceOf(RenderOutcome.Rejected.class, outputOutcome);
        assertEquals(RenderingProblem.ProblemCode.RENDER_INTERNAL_ERROR,
                outputRejected.problem().code());
        assertEquals(EvaluationStage.ENGINE, outputRejected.problem().stage());

        var third = application(
                new ScriptedEvaluator(),
                new ScriptedEngine(new EngineOutcome.SealedOutput(output(
                        OUTPUT,
                        "renderweave-renderer/2.0",
                        "renderweave-layout/1.0"))),
                grantedAuthority(RenderPurpose.FORMAL_OUTPUT),
                availableProfiles());

        var profileOutcome = third.render(INVOCATION, command(RenderPurpose.FORMAL_OUTPUT));

        var profileRejected = assertInstanceOf(RenderOutcome.Rejected.class, profileOutcome);
        assertEquals(RenderingProblem.ProblemCode.RENDER_INTERNAL_ERROR,
                profileRejected.problem().code());
        assertEquals(EvaluationStage.ENGINE, profileRejected.problem().stage());
    }

    private static RenderingApplication application(
            ScriptedEvaluator evaluator,
            ScriptedEngine engine,
            ScriptedAuthority authority,
            ScriptedProfiles profiles
    ) {
        return new CanonicalRenderingApplication(
                evaluator,
                engine,
                authority,
                profiles,
                Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC),
                Duration.ZERO);
    }

    private static RenderCommand command(RenderPurpose purpose) {
        return new RenderCommand(
                ROOT_TEMPLATE,
                "{\"rootDocument\":{}}".getBytes(StandardCharsets.UTF_8),
                OUTPUT,
                purpose);
    }

    private static ScriptedProfiles availableProfiles() {
        return new ScriptedProfiles(new RendererProfileAuthority.Available(
                "renderweave-renderer/1.0",
                "renderweave-layout/1.0"));
    }

    private static ScriptedAuthority grantedAuthority(RenderPurpose purpose) {
        return new ScriptedAuthority(
                granted(purpose),
                new RenderingAuthority.RecheckGranted(
                        purpose == RenderPurpose.AUTHORITATIVE_PREVIEW
                                ? RenderingAuthority.Disclosure.READABLE
                                : RenderingAuthority.Disclosure.OPAQUE));
    }

    private static RenderingAuthority.Authorized granted(RenderPurpose purpose) {
        return new RenderingAuthority.Authorized(
                new Evaluator.OwnerScope("owner-a"),
                new RenderingAuthority.RecheckIdentity("recheck-1"),
                purpose == RenderPurpose.AUTHORITATIVE_PREVIEW
                        ? RenderingAuthority.Disclosure.READABLE
                        : RenderingAuthority.Disclosure.OPAQUE);
    }

    private static RenderOutput output(OutputSelection selection) {
        return output(
                selection,
                "renderweave-renderer/1.0",
                "renderweave-layout/1.0");
    }

    private static RenderOutput output(
            OutputSelection selection,
            String rendererProfile,
            String layoutProfile
    ) {
        var bytes = new byte[] { 1, 2, 3 };
        var png = selection instanceof OutputSelection.Png;
        var dpi = png
                ? ((OutputSelection.Png) selection).dpi()
                : ((OutputSelection.Jpeg) selection).dpi();
        var quality = png
                ? OptionalInt.empty()
                : OptionalInt.of(((OutputSelection.Jpeg) selection).quality());
        return new RenderOutput(
                bytes,
                "renderweave-render-result/1.0",
                rendererProfile,
                "renderweave-render/1.0",
                layoutProfile,
                png ? "renderweave-output-png/1.0" : "renderweave-output-jpeg/1.0",
                png ? "PNG" : "JPEG",
                png ? "image/png" : "image/jpeg",
                10,
                20,
                dpi,
                quality,
                bytes.length,
                sha256(bytes));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static final class ScriptedEvaluator implements Evaluator {
        private final List<EvaluationCommand> seen = new ArrayList<>();
        private final byte[] sealedDocumentBytes = "{\"canvas\":{}}"
                .getBytes(StandardCharsets.UTF_8);
        private EvaluationOutcome outcome;
        private String layoutProfile = "renderweave-layout/1.0";

        @Override
        public EvaluationOutcome evaluate(EvaluationCommand command) {
            seen.add(command);
            if (outcome != null) {
                return outcome;
            }
            return new EvaluationOutcome.SealedDocument(
                    command.renderRequestId(),
                    sealedDocumentBytes,
                    "sha256:" + "a".repeat(64),
                    "sha256:" + "b".repeat(64),
                    layoutProfile,
                    command.outputSelection());
        }
    }

    private static final class ScriptedEngine implements RenderEngine {
        private final ArrayDeque<EngineOutcome> outcomes;
        private final List<RendererCommand> seen = new ArrayList<>();

        private ScriptedEngine(EngineOutcome... outcomes) {
            this.outcomes = new ArrayDeque<>(List.of(outcomes));
        }

        @Override
        public EngineOutcome execute(RendererCommand command) {
            seen.add(command);
            return outcomes.removeFirst();
        }
    }

    private static final class ScriptedAuthority implements RenderingAuthority {
        private final AuthorizationDecision authorization;
        private final RecheckDecision recheck;
        private RenderPurpose seenPurpose;
        private int recheckCalls;

        private ScriptedAuthority(
                AuthorizationDecision authorization,
                RecheckDecision recheck
        ) {
            this.authorization = authorization;
            this.recheck = recheck;
        }

        @Override
        public AuthorizationDecision authorize(
                RenderInvocationRef invocation,
                TemplateId rootTemplateId,
                RenderPurpose purpose
        ) {
            seenPurpose = purpose;
            return authorization;
        }

        @Override
        public RecheckDecision recheck(RecheckIdentity identity) {
            recheckCalls++;
            return recheck;
        }
    }

    private static final class ScriptedProfiles implements RendererProfileAuthority {
        private final Selection selection;
        private int calls;

        private ScriptedProfiles(Selection selection) {
            this.selection = selection;
        }

        @Override
        public Selection select(OutputSelection outputSelection) {
            calls++;
            return selection;
        }
    }

    private static final class AdvancingClock extends Clock {
        private long currentMillis;
        private final long stepMillis;

        private AdvancingClock(long currentMillis, long stepMillis) {
            this.currentMillis = currentMillis;
            this.stepMillis = stepMillis;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis());
        }

        @Override
        public long millis() {
            long result = currentMillis;
            currentMillis += stepMillis;
            return result;
        }
    }
}
