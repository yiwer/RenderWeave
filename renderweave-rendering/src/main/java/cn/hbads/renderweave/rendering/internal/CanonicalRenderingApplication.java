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
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/** Rendering 产品操作的唯一授权、Profile、Evaluation、Engine 与结果释放编排。 */
final class CanonicalRenderingApplication implements RenderingApplication {

    private static final RenderingPipelineCapacityGuard CAPACITY_GUARD =
            new RenderingPipelineCapacityGuard();
    private static final RenderingPipelineCapacityGuard.Limit
            ADMISSION_AND_CLOSURE_DEADLINE_LIMIT =
            RenderingPipelineCapacityGuard.Limit
                    .DEADLINE_AND_RETENTION_ADMISSION_AND_CLOSURE_MILLIS;
    private static final RenderingPipelineCapacityGuard.Limit TOTAL_DEADLINE_LIMIT =
            RenderingPipelineCapacityGuard.Limit
                    .DEADLINE_AND_RETENTION_TOTAL_DEADLINE_MILLIS;
    private static final String COMMAND_CONTRACT = "renderweave-render-command/1.0";
    private static final Pattern SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");

    private final Evaluator evaluator;
    private final RenderEngine engine;
    private final RenderingAuthority authority;
    private final RendererProfileAuthority profiles;
    private final Clock clock;
    private final long retryDelayNanos;
    private final LongSupplier monotonicNanos;

    CanonicalRenderingApplication(
            Evaluator evaluator,
            RenderEngine engine,
            RenderingAuthority authority,
            RendererProfileAuthority profiles,
            Clock clock,
            Duration retryDelay
    ) {
        this(evaluator, engine, authority, profiles, clock, retryDelay, System::nanoTime);
    }

    CanonicalRenderingApplication(
            Evaluator evaluator,
            RenderEngine engine,
            RenderingAuthority authority,
            RendererProfileAuthority profiles,
            Clock clock,
            Duration retryDelay,
            LongSupplier monotonicNanos
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
        this.monotonicNanos = Objects.requireNonNull(monotonicNanos, "monotonicNanos");
    }

    @Override
    public RenderOutcome render(RenderInvocationRef invocation, RenderCommand command) {
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(command, "command");
        var operationId = new RenderOperationId(UUID.randomUUID().toString());

        final RequestDeadline deadline;
        try {
            deadline = RequestDeadline.start(
                    clock.millis(),
                    monotonicNanos.getAsLong(),
                    CAPACITY_GUARD.exactValue(TOTAL_DEADLINE_LIMIT),
                    CAPACITY_GUARD.exactValue(ADMISSION_AND_CLOSURE_DEADLINE_LIMIT));
        } catch (ArithmeticException overflow) {
            return deadlineExceeded(operationId);
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
        if (deadline.admissionAndClosureExpired(monotonicNanos)) {
            return release(authorized, admissionAndClosureDeadlineExceeded(operationId));
        }

        var profileSelection = profiles.select(command.outputSelection());
        if (profileSelection instanceof RendererProfileAuthority.Unavailable) {
            return release(
                    authorized,
                    new RenderOutcome.RendererUnavailable(operationId));
        }
        var available = (RendererProfileAuthority.Available) profileSelection;
        if (deadline.admissionAndClosureExpired(monotonicNanos)) {
            return release(authorized, admissionAndClosureDeadlineExceeded(operationId));
        }
        if (deadline.expired(monotonicNanos)) {
            return release(authorized, deadlineExceeded(operationId));
        }

        var requestId = distinctRequestId(operationId);
        var evaluation = evaluator.evaluate(new EvaluationCommand(
                requestId,
                authorized.ownerScope(),
                authorized.authorizationContextDigest(),
                authorized.externalAssetReadAuthorization(),
                command.rootTemplateId(),
                command.rawRenderInputUtf8(),
                command.outputSelection(),
                available.rendererProfile(),
                deadline.deadlineAtEpochMilli(),
                deadline.monotonicDeadlineNanos(),
                deadline.admissionAndClosureDeadlineAtMonotonicNanos()));
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
                deadline.deadlineAtEpochMilli(),
                sealed.renderDocumentDigest(),
                sealed.renderDocumentCanonicalUtf8(),
                command.outputSelection(),
                false);

        while (!deadline.expired(monotonicNanos)) {
            var engineOutcome = engine.execute(rendererCommand);
            if (engineOutcome instanceof EngineOutcome.Unknown
                    || isBusy(engineOutcome)) {
                waitBeforeRetry(deadline);
                continue;
            }
            if (engineOutcome instanceof EngineOutcome.TerminalProblem terminal) {
                return release(authorized, new RenderOutcome.Rejected(
                        operationId,
                        terminal.problem()));
            }
            var output = outputOf(engineOutcome);
            if (output == null
                    || !output.outputSelection().equals(command.outputSelection())
                    || !output.rendererProfile().equals(available.rendererProfile())
                    || !output.layoutProfile().equals(available.layoutProfile())) {
                return release(authorized, rejected(
                        operationId,
                        EvaluationStage.ENGINE,
                        RenderingProblem.ProblemCode.RENDER_INTERNAL_ERROR));
            }
            return release(authorized, new RenderOutcome.Rendered(operationId, output));
        }
        return release(authorized, deadlineExceeded(operationId));
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

    private void waitBeforeRetry(RequestDeadline deadline) {
        if (retryDelayNanos == 0) {
            return;
        }
        long remainingNanos = deadline.remainingNanos(monotonicNanos);
        if (remainingNanos == 0) {
            return;
        }
        LockSupport.parkNanos(Math.min(retryDelayNanos, remainingNanos));
    }

    private static RenderOutcome.Rejected deadlineExceeded(RenderOperationId operationId) {
        return new RenderOutcome.Rejected(
                operationId,
                CAPACITY_GUARD.rejection(TOTAL_DEADLINE_LIMIT));
    }

    private static RenderOutcome.Rejected admissionAndClosureDeadlineExceeded(
            RenderOperationId operationId
    ) {
        return new RenderOutcome.Rejected(
                operationId,
                CAPACITY_GUARD.rejection(ADMISSION_AND_CLOSURE_DEADLINE_LIMIT));
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

    private record RequestDeadline(
            long deadlineAtEpochMilli,
            long monotonicDeadlineNanos,
            long admissionAndClosureDeadlineAtMonotonicNanos
    ) {
        private static RequestDeadline start(
                long admittedAtEpochMilli,
                long admittedAtMonotonicNanos,
                long totalDeadlineMillis,
                long admissionAndClosureDeadlineMillis
        ) {
            if (totalDeadlineMillis <= 0) {
                throw new IllegalArgumentException("totalDeadlineMillis must be positive");
            }
            if (admissionAndClosureDeadlineMillis <= 0
                    || admissionAndClosureDeadlineMillis > totalDeadlineMillis) {
                throw new IllegalArgumentException(
                        "admissionAndClosureDeadlineMillis must be within total deadline");
            }
            var totalDeadlineNanos = Math.multiplyExact(
                    totalDeadlineMillis,
                    1_000_000L);
            var admissionAndClosureDeadlineNanos = Math.multiplyExact(
                    admissionAndClosureDeadlineMillis,
                    1_000_000L);
            return new RequestDeadline(
                    Math.addExact(admittedAtEpochMilli, totalDeadlineMillis),
                    admittedAtMonotonicNanos + totalDeadlineNanos,
                    admittedAtMonotonicNanos + admissionAndClosureDeadlineNanos);
        }

        private boolean admissionAndClosureExpired(LongSupplier monotonicNanos) {
            return admissionAndClosureDeadlineAtMonotonicNanos
                    - monotonicNanos.getAsLong() <= 0;
        }

        private boolean expired(LongSupplier monotonicNanos) {
            return remainingNanos(monotonicNanos) == 0;
        }

        private long remainingNanos(LongSupplier monotonicNanos) {
            // Signed subtraction is wrap-safe for System.nanoTime intervals below 2^63 ns.
            var remaining = monotonicDeadlineNanos - monotonicNanos.getAsLong();
            return Math.max(0L, remaining);
        }
    }
}
