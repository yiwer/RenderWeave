package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.rendering.api.EvaluationStage;
import cn.hbads.renderweave.rendering.api.Evaluator;
import cn.hbads.renderweave.rendering.api.Evaluator.EvaluationCommand;
import cn.hbads.renderweave.rendering.api.Evaluator.EvaluationOutcome;
import cn.hbads.renderweave.rendering.api.Evaluator.RenderRequestId;
import cn.hbads.renderweave.rendering.api.RenderOutput;
import cn.hbads.renderweave.rendering.api.RenderingApplication;
import cn.hbads.renderweave.rendering.api.RenderingApplication.RenderOperationId;
import cn.hbads.renderweave.rendering.api.RenderingApplication.RenderOutcome;
import cn.hbads.renderweave.rendering.api.RenderingProblem;
import cn.hbads.renderweave.rendering.spi.RenderEngine;
import cn.hbads.renderweave.rendering.spi.RenderEngine.EngineOutcome;
import cn.hbads.renderweave.rendering.spi.RenderEngine.RendererCommand;
import cn.hbads.renderweave.rendering.spi.RendererProfileAuthority;
import cn.hbads.renderweave.rendering.spi.RenderingAuthority;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Pattern;

/** Rendering 产品操作的唯一授权、Profile、Evaluation、Engine 与结果释放编排。 */
final class CanonicalRenderingApplication implements RenderingApplication {

    private static final long TOTAL_DEADLINE_MILLIS = 60_000L;
    private static final String COMMAND_CONTRACT = "renderweave-render-command/1.0";
    private static final Pattern SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");

    private final Evaluator evaluator;
    private final RenderEngine engine;
    private final RenderingAuthority authority;
    private final RendererProfileAuthority profiles;
    private final Clock clock;
    private final long retryDelayNanos;

    CanonicalRenderingApplication(
            Evaluator evaluator,
            RenderEngine engine,
            RenderingAuthority authority,
            RendererProfileAuthority profiles,
            Clock clock,
            Duration retryDelay
    ) {
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.authority = Objects.requireNonNull(authority, "authority");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(retryDelay, "retryDelay");
        if (retryDelay.isNegative() || retryDelay.compareTo(Duration.ofSeconds(1)) > 0) {
            throw new IllegalArgumentException("retryDelay must be within 0..1 second");
        }
        this.retryDelayNanos = retryDelay.toNanos();
    }

    @Override
    public RenderOutcome render(RenderInvocationRef invocation, RenderCommand command) {
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(command, "command");
        var operationId = new RenderOperationId(UUID.randomUUID().toString());

        final long deadlineAt;
        try {
            deadlineAt = Math.addExact(clock.millis(), TOTAL_DEADLINE_MILLIS);
        } catch (ArithmeticException overflow) {
            return rejected(operationId, EvaluationStage.REQUEST_ADMISSION,
                    RenderingProblem.ProblemCode.RENDER_DEADLINE_EXCEEDED);
        }

        var authorization = authority.authorize(
                invocation,
                command.rootTemplateId(),
                command.purpose());
        if (authorization instanceof RenderingAuthority.Hidden) {
            return new RenderOutcome.NotFound(operationId);
        }
        if (authorization instanceof RenderingAuthority.Forbidden) {
            return new RenderOutcome.Forbidden(operationId);
        }
        if (authorization instanceof RenderingAuthority.Unavailable) {
            return new RenderOutcome.AuthorityUnavailable(operationId);
        }
        var authorized = (RenderingAuthority.Authorized) authorization;

        var profileSelection = profiles.select(command.outputSelection());
        if (profileSelection instanceof RendererProfileAuthority.Unavailable) {
            return release(
                    authorized,
                    new RenderOutcome.RendererUnavailable(operationId));
        }
        var available = (RendererProfileAuthority.Available) profileSelection;
        if (clock.millis() >= deadlineAt) {
            return release(authorized, rejected(
                    operationId,
                    EvaluationStage.REQUEST_ADMISSION,
                    RenderingProblem.ProblemCode.RENDER_DEADLINE_EXCEEDED));
        }

        var requestId = distinctRequestId(operationId);
        var evaluation = evaluator.evaluate(new EvaluationCommand(
                requestId,
                authorized.ownerScope(),
                command.rootTemplateId(),
                command.rawRenderInputUtf8(),
                command.outputSelection(),
                available.rendererProfile(),
                deadlineAt));
        if (evaluation instanceof EvaluationOutcome.Rejected rejected) {
            return release(authorized, new RenderOutcome.Rejected(
                    operationId,
                    rejected.problem()));
        }
        var sealed = (EvaluationOutcome.SealedDocument) evaluation;
        if (!sealed.renderRequestId().equals(requestId)
                || !sealed.outputSelection().equals(command.outputSelection())
                || !sealed.layoutProfile().equals(available.layoutProfile())
                || !SHA256.matcher(sealed.renderDocumentDigest()).matches()) {
            return release(authorized, rejected(
                    operationId,
                    EvaluationStage.DOCUMENT_SEAL,
                    RenderingProblem.ProblemCode.RENDER_INTERNAL_ERROR));
        }

        var rendererCommand = new RendererCommand(
                COMMAND_CONTRACT,
                requestId,
                available.rendererProfile(),
                deadlineAt,
                sealed.renderDocumentDigest(),
                sealed.renderDocumentCanonicalUtf8(),
                command.outputSelection(),
                false);

        while (clock.millis() < deadlineAt) {
            var engineOutcome = engine.execute(rendererCommand);
            if (engineOutcome instanceof EngineOutcome.Unknown
                    || isBusy(engineOutcome)) {
                waitBeforeRetry(deadlineAt);
                continue;
            }
            if (engineOutcome instanceof EngineOutcome.TerminalProblem terminal) {
                return release(authorized, new RenderOutcome.Rejected(
                        operationId,
                        terminal.problem()));
            }
            var output = outputOf(engineOutcome);
            if (output == null || !output.outputProfile().equals(command.outputSelection())) {
                return release(authorized, rejected(
                        operationId,
                        EvaluationStage.ENGINE,
                        RenderingProblem.ProblemCode.RENDER_INTERNAL_ERROR));
            }
            return release(authorized, new RenderOutcome.Rendered(operationId, output));
        }
        return release(authorized, rejected(
                operationId,
                EvaluationStage.ENGINE,
                RenderingProblem.ProblemCode.RENDER_DEADLINE_EXCEEDED));
    }

    private RenderOutcome release(
            RenderingAuthority.Authorized authorized,
            RenderOutcome authorizedOutcome
    ) {
        var current = authority.recheck(authorized.recheckIdentity());
        if (current instanceof RenderingAuthority.RecheckGranted granted) {
            return disclosed(authorizedOutcome, granted.disclosure());
        }
        if (current instanceof RenderingAuthority.RecheckHidden) {
            return new RenderOutcome.NotFound(authorizedOutcome.operationId());
        }
        if (current instanceof RenderingAuthority.RecheckForbidden) {
            return new RenderOutcome.Forbidden(authorizedOutcome.operationId());
        }
        return new RenderOutcome.AuthorityUnavailable(authorizedOutcome.operationId());
    }

    private static RenderOutcome disclosed(
            RenderOutcome outcome,
            RenderingAuthority.Disclosure disclosure
    ) {
        if (disclosure == RenderingAuthority.Disclosure.READABLE
                || !(outcome instanceof RenderOutcome.Rejected rejected)) {
            return outcome;
        }
        var problem = rejected.problem();
        return new RenderOutcome.Rejected(
                rejected.operationId(),
                new RenderingProblem(
                        problem.code(),
                        problem.stage(),
                        java.util.Optional.empty(),
                        problem.limitId()));
    }

    private void waitBeforeRetry(long deadlineAt) {
        if (retryDelayNanos == 0) {
            return;
        }
        long remainingMillis = deadlineAt - clock.millis();
        if (remainingMillis <= 0) {
            return;
        }
        long remainingNanos = remainingMillis > Long.MAX_VALUE / 1_000_000L
                ? Long.MAX_VALUE
                : remainingMillis * 1_000_000L;
        LockSupport.parkNanos(Math.min(retryDelayNanos, remainingNanos));
    }

    private static boolean isBusy(EngineOutcome outcome) {
        return outcome instanceof EngineOutcome.TerminalProblem terminal
                && terminal.problem().code()
                == RenderingProblem.ProblemCode.RENDER_ENGINE_BUSY;
    }

    private static RenderOutput outputOf(EngineOutcome outcome) {
        if (outcome instanceof EngineOutcome.SealedOutput sealed) {
            return sealed.output();
        }
        if (outcome instanceof EngineOutcome.Joined joined) {
            return joined.output();
        }
        if (outcome instanceof EngineOutcome.Replayed replayed) {
            return replayed.output();
        }
        return null;
    }

    private static RenderRequestId distinctRequestId(RenderOperationId operationId) {
        String value;
        do {
            value = UUID.randomUUID().toString();
        } while (value.equals(operationId.value()));
        return new RenderRequestId(value);
    }

    private static RenderOutcome.Rejected rejected(
            RenderOperationId operationId,
            EvaluationStage stage,
            RenderingProblem.ProblemCode code
    ) {
        return new RenderOutcome.Rejected(
                operationId,
                RenderingProblem.of(code, stage));
    }
}
