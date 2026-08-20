package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.rendering.api.EvaluationStage;
import cn.hbads.renderweave.rendering.api.Evaluator;
import cn.hbads.renderweave.rendering.api.RenderingProblem;
import cn.hbads.renderweave.rendering.api.RenderingProblem.LimitId;
import cn.hbads.renderweave.rendering.api.RenderingProblem.ProblemCode;
import cn.hbads.renderweave.rendering.spi.AssetResolutionPort;
import cn.hbads.renderweave.template.api.DesignDslAuthority;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority;
import cn.hbads.renderweave.template.api.TemplateClosureAuthority;
import cn.hbads.renderweave.template.api.TemplateClosureAuthority.ClosureOutcome;
import cn.hbads.renderweave.validation.ValidationTargetResolver;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/**
 * Rendering 唯一动态语义权威（ADR-0044 §2）：单一窄 {@code evaluate} 按 first-fail 串行
 * stage 1–8——REQUEST_ADMISSION envelope、TEMPLATE_CLOSURE 冻结、INPUT_ADMISSION typed view、
 * ASSET_ADMISSION/MATERIALIZATION/ASSET_RESOLUTION 物化、DOCUMENT_SEAL 原子封存。失败无
 * partial output；内部违约折叠 RENDER_INTERNAL_ERROR。Engine 执行（stage 9）随 Renderer
 * 实现票接线，本票 evaluate 终止于 SealedDocument。
 */
final class CanonicalEvaluator implements Evaluator {

    private static final long RENDER_DEADLINE_MILLIS = 60_000L;

    private final TemplateClosureAuthority closureAuthority;
    private final DesignSemanticAuthority semantics;
    private final DesignDslAuthority dslAuthority;
    private final AssetResolutionPort assets;
    private final cn.hbads.renderweave.rendering.spi.RenderingCapabilityRuntime capabilities;
    private final ValidationTargetResolver validationResolver;
    private final Clock clock;

    CanonicalEvaluator(
            TemplateClosureAuthority closureAuthority,
            DesignSemanticAuthority semantics,
            DesignDslAuthority dslAuthority,
            AssetResolutionPort assets,
            cn.hbads.renderweave.rendering.spi.RenderingCapabilityRuntime capabilities,
            ValidationTargetResolver validationResolver,
            Clock clock
    ) {
        this.closureAuthority = Objects.requireNonNull(closureAuthority, "closureAuthority");
        this.semantics = Objects.requireNonNull(semantics, "semantics");
        this.dslAuthority = Objects.requireNonNull(dslAuthority, "dslAuthority");
        this.assets = assets;
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.validationResolver = Objects.requireNonNull(validationResolver, "validationResolver");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public EvaluationOutcome evaluate(EvaluationCommand command) {
        Objects.requireNonNull(command, "command");

        final long deadlineEpochMilli;
        try {
            deadlineEpochMilli = Math.addExact(clock.millis(), RENDER_DEADLINE_MILLIS);
        } catch (ArithmeticException unavailableDeadline) {
            return rejected(EvaluationStage.REQUEST_ADMISSION,
                    ProblemCode.RENDER_DEADLINE_EXCEEDED, null);
        }

        var closureOutcome = closureAuthority.freezeClosure(
                new TemplateClosureAuthority.RenderRequestId(command.renderRequestId().value()),
                command.rootTemplateId());
        if (closureOutcome instanceof TemplateClosureAuthority.ClosureNotFound
                || closureOutcome instanceof TemplateClosureAuthority.ClosureDeleted
                || closureOutcome instanceof TemplateClosureAuthority.ClosureDependencyInvalid) {
            return rejected(EvaluationStage.TEMPLATE_CLOSURE, ProblemCode.EVALUATION_FAILED, null);
        }
        if (closureOutcome instanceof TemplateClosureAuthority.ClosureIntegrityViolation) {
            return rejected(EvaluationStage.TEMPLATE_CLOSURE,
                    ProblemCode.RENDER_INTERNAL_ERROR, null);
        }
        if (closureOutcome instanceof TemplateClosureAuthority.ClosureUnstable) {
            return rejected(EvaluationStage.TEMPLATE_CLOSURE,
                    ProblemCode.TEMPLATE_CLOSURE_UNSTABLE, null);
        }
        if (closureOutcome instanceof TemplateClosureAuthority.ClosureLimitExceeded limited) {
            return new EvaluationOutcome.Rejected(EvaluationStage.TEMPLATE_CLOSURE,
                    new RenderingProblem(
                            ProblemCode.TEMPLATE_CLOSURE_LIMIT_EXCEEDED,
                            EvaluationStage.TEMPLATE_CLOSURE,
                            Optional.empty(),
                            Optional.of(new LimitId(limited.limitId().value()))));
        }
        if (!(closureOutcome instanceof TemplateClosureAuthority.ClosureFrozen frozen)) {
            return rejected(EvaluationStage.TEMPLATE_CLOSURE,
                    ProblemCode.RENDER_INTERNAL_ERROR, null);
        }
        var closure = frozen.closure();
        if (!closure.ownerScope().value().equals(command.ownerScope().value())) {
            return rejected(EvaluationStage.REQUEST_ADMISSION,
                    ProblemCode.EVALUATION_FAILED, null);
        }
        var rootSnapshot = closure.snapshots().stream()
                .filter(snapshot -> snapshot.templateId().equals(closure.rootTemplateId()))
                .findFirst()
                .orElse(null);
        if (rootSnapshot == null) {
            return rejected(EvaluationStage.TEMPLATE_CLOSURE,
                    ProblemCode.RENDER_INTERNAL_ERROR, null);
        }

        var admission = InputAdmission.admit(
                command.rawRenderInputUtf8(), rootSnapshot, validationResolver);
        if (admission instanceof InputAdmission.AdmissionRejected admissionRejected) {
            return new EvaluationOutcome.Rejected(
                    admissionRejected.problems().get(0).stage(),
                    admissionRejected.problems().get(0));
        }
        if (admission instanceof InputAdmission.AdmissionUnavailable) {
            return rejected(EvaluationStage.INPUT_ADMISSION,
                    ProblemCode.EVALUATION_FAILED, null);
        }
        if (!(admission instanceof InputAdmission.AdmissionAdmitted admitted)) {
            return rejected(EvaluationStage.INPUT_ADMISSION,
                    ProblemCode.RENDER_INTERNAL_ERROR, null);
        }

        var runtime = CapabilityValues.wrapping(capabilities.establish());
        var audience = new AssetResolutionPort.RendererAudience("renderweave-renderer/1.0");
        var materialization = Materializer.materialize(
                closure,
                semantics,
                dslAuthority,
                assets,
                runtime.provider(),
                admitted.input(),
                command.renderRequestId(),
                audience,
                deadlineEpochMilli);
        if (materialization instanceof Materializer.MaterializationFailed failed) {
            return new EvaluationOutcome.Rejected(failed.stage(), failed.problem());
        }
        if (!(materialization instanceof Materializer.Materialized materialized)) {
            return rejected(EvaluationStage.MATERIALIZATION,
                    ProblemCode.RENDER_INTERNAL_ERROR, null);
        }

        var seal = Sealer.seal(
                closure,
                admitted.input(),
                materialized.tree(),
                runtime.capabilityResultDigest());
        // capabilityContracts 进入 fingerprint（S8 已实现）；此处 runtime 只承载 demand 供给。
        if (seal instanceof Sealer.SealRejected sealRejected) {
            return new EvaluationOutcome.Rejected(EvaluationStage.DOCUMENT_SEAL,
                    new RenderingProblem(
                            ProblemCode.RENDER_DOCUMENT_LIMIT_EXCEEDED,
                            EvaluationStage.DOCUMENT_SEAL,
                            Optional.empty(),
                            Optional.of(new LimitId(sealRejected.limitId()))));
        }
        var sealed = ((Sealer.Sealed) seal).evaluation();
        return new EvaluationOutcome.SealedDocument(
                command.renderRequestId(),
                sealed.renderDocumentCanonicalUtf8(),
                sealed.renderDocumentDigest(),
                sealed.evaluationResultDigest(),
                command.outputSelection());
    }

    private static EvaluationOutcome.Rejected rejected(
            EvaluationStage stage, ProblemCode code, String limitId) {
        return new EvaluationOutcome.Rejected(stage, new RenderingProblem(
                code, stage, Optional.empty(),
                limitId == null ? Optional.empty() : Optional.of(new LimitId(limitId))));
    }

}
