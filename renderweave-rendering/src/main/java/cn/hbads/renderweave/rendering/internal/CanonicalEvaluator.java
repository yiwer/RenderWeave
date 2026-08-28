package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.rendering.api.EvaluationStage;
import cn.hbads.renderweave.rendering.api.Evaluator;
import cn.hbads.renderweave.rendering.api.RenderingProblem;
import cn.hbads.renderweave.rendering.api.RenderingProblem.LimitId;
import cn.hbads.renderweave.rendering.api.RenderingProblem.ProblemCode;
import cn.hbads.renderweave.rendering.spi.AssetResolutionPort;
import cn.hbads.renderweave.rendering.spi.CapabilityStateStore;
import cn.hbads.renderweave.rendering.spi.RenderingCapabilityRuntime;
import cn.hbads.renderweave.rendering.spi.RenderingCapabilityRuntime.CapabilityRequirements;
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
 * ASSET_ADMISSION、CAPABILITY_STATE、MATERIALIZATION/ASSET_RESOLUTION 物化、DOCUMENT_SEAL
 * 原子封存。失败无 partial output；内部违约折叠 RENDER_INTERNAL_ERROR。Engine 执行（stage 9）
 * 由 RenderingApplication 接线，本 evaluate 终止于 SealedDocument。
 */
final class CanonicalEvaluator implements Evaluator {

    private final TemplateClosureAuthority closureAuthority;
    private final DesignSemanticAuthority semantics;
    private final DesignDslAuthority dslAuthority;
    private final AssetResolutionPort assets;
    private final RenderingCapabilityRuntime capabilities;
    private final CapabilityStateStore capabilityStates;
    private final String effectiveBudgetVector;
    private final ValidationTargetResolver validationResolver;
    private final Clock clock;

    CanonicalEvaluator(
            TemplateClosureAuthority closureAuthority,
            DesignSemanticAuthority semantics,
            DesignDslAuthority dslAuthority,
            AssetResolutionPort assets,
            RenderingCapabilityRuntime capabilities,
            CapabilityStateStore capabilityStates,
            String effectiveBudgetVector,
            ValidationTargetResolver validationResolver,
            Clock clock
    ) {
        this.closureAuthority = Objects.requireNonNull(closureAuthority, "closureAuthority");
        this.semantics = Objects.requireNonNull(semantics, "semantics");
        this.dslAuthority = Objects.requireNonNull(dslAuthority, "dslAuthority");
        this.assets = assets;
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.capabilityStates = Objects.requireNonNull(capabilityStates, "capabilityStates");
        this.effectiveBudgetVector = Objects.requireNonNull(effectiveBudgetVector, "effectiveBudgetVector");
        this.validationResolver = Objects.requireNonNull(validationResolver, "validationResolver");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public EvaluationOutcome evaluate(EvaluationCommand command) {
        Objects.requireNonNull(command, "command");

        if (clock.millis() >= command.deadlineAtEpochMilli()) {
            return rejected(EvaluationStage.REQUEST_ADMISSION,
                    ProblemCode.RENDER_DEADLINE_EXCEEDED, null);
        }

        var closureOutcome = closureAuthority.freezeClosure(
                new TemplateClosureAuthority.RenderRequestId(command.renderRequestId().value()),
                command.rootTemplateId());
        if (closureOutcome instanceof TemplateClosureAuthority.ClosureNotFound) {
            return rejected(EvaluationStage.TEMPLATE_CLOSURE,
                    ProblemCode.TEMPLATE_NOT_FOUND, null);
        }
        if (closureOutcome instanceof TemplateClosureAuthority.ClosureDeleted) {
            return rejected(EvaluationStage.TEMPLATE_CLOSURE,
                    ProblemCode.TEMPLATE_DELETED, null);
        }
        if (closureOutcome instanceof TemplateClosureAuthority.ClosureDependencyInvalid) {
            return rejected(EvaluationStage.TEMPLATE_CLOSURE,
                    ProblemCode.TEMPLATE_DEPENDENCY_ERROR, null);
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
        if (closureOutcome instanceof TemplateClosureAuthority.ClosureUnavailable) {
            return rejected(EvaluationStage.TEMPLATE_CLOSURE,
                    ProblemCode.TEMPLATE_AUTHORITY_UNAVAILABLE, null);
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
        var declarationOutcome = CapabilityDeclarations.scan(closure, semantics);
        if (declarationOutcome instanceof CapabilityDeclarations.DeclarationFault) {
            return rejected(EvaluationStage.TEMPLATE_CLOSURE,
                    ProblemCode.RENDER_INTERNAL_ERROR, null);
        }
        var declarations = (CapabilityDeclarations.Declared) declarationOutcome;

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

        var assetAdmission = AssetAdmission.admit(
                closure, semantics, assets, admitted.input());
        if (assetAdmission instanceof AssetAdmission.Rejected rejected) {
            return new EvaluationOutcome.Rejected(rejected.stage(), rejected.problem());
        }
        var admittedAssets = (AssetAdmission.Admitted) assetAdmission;

        var runtimeOutcome = capabilityRuntime(command, closure, admitted.input(), declarations);
        if (runtimeOutcome instanceof CapabilityRuntimeRejected rejected) {
            return rejected(EvaluationStage.CAPABILITY_STATE, rejected.code(), null);
        }
        var runtime = CapabilityValues.wrapping(
                ((CapabilityRuntimeReady) runtimeOutcome).runtime());
        var audience = new AssetResolutionPort.RendererAudience(command.rendererProfile());
        var materialization = Materializer.materialize(
                admittedAssets,
                closure,
                semantics,
                dslAuthority,
                assets,
                runtime.provider(),
                admitted.input(),
                command.renderRequestId(),
                audience,
                command.deadlineAtEpochMilli());
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
        // Capability state is established or restored before materialization can demand it.
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
                Sealer.LAYOUT_PROFILE,
                command.outputSelection());
    }

    private CapabilityRuntimeOutcome capabilityRuntime(
            EvaluationCommand command,
            TemplateClosureAuthority.ClosureSnapshot closure,
            AdmittedRenderInput admittedInput,
            CapabilityDeclarations.Declared declarations
    ) {
        var declaredContracts = declarations.contracts();
        if (declaredContracts.isEmpty()) {
            return new CapabilityRuntimeReady((capability, operation, position) ->
                    new RenderingCapabilityRuntime.ProviderUnavailable());
        }
        var requirements = new CapabilityRequirements(declaredContracts);
        if (!capabilities.supportedContracts().containsAll(requirements.contracts())) {
            return new CapabilityRuntimeRejected(ProblemCode.CAPABILITY_PROFILE_UNAVAILABLE);
        }
        var contractIdentity = declarations.canonicalContractIdentity();
        var fingerprint = CapabilityValues.evaluationFingerprint(
                command.ownerScope().value(),
                command.authorizationContextDigest(),
                ClosureManifests.digest(closure),
                AdmittedInputCanonicalizer.digest(admittedInput),
                Sealer.RENDER_DSL_VERSION,
                Sealer.LAYOUT_PROFILE,
                contractIdentity,
                "renderweave-asset-acceptance/1.0",
                effectiveBudgetVector);
        var loaded = capabilityStates.load(command.renderRequestId(), fingerprint);
        if (loaded instanceof CapabilityStateStore.LoadOutcome.Loaded state) {
            try {
                return new CapabilityRuntimeReady(capabilities.restore(requirements, state.sealedState()));
            } catch (RuntimeException invalid) {
                return new CapabilityRuntimeRejected(ProblemCode.CAPABILITY_STATE_UNAVAILABLE);
            }
        }
        if (loaded instanceof CapabilityStateStore.LoadOutcome.LoadFingerprintConflict) {
            return new CapabilityRuntimeRejected(ProblemCode.CAPABILITY_STATE_CONFLICT);
        }
        if (loaded instanceof CapabilityStateStore.LoadOutcome.LoadUnavailable) {
            return new CapabilityRuntimeRejected(ProblemCode.CAPABILITY_STATE_UNAVAILABLE);
        }
        final RenderingCapabilityRuntime.Established established;
        try {
            established = capabilities.establish(requirements);
        } catch (RuntimeException unavailable) {
            return new CapabilityRuntimeRejected(ProblemCode.CAPABILITY_STATE_UNAVAILABLE);
        }
        var issuedAt = clock.instant().getEpochSecond();
        var deadlineMillis = command.deadlineAtEpochMilli();
        var deadlineSecond = Math.floorDiv(deadlineMillis, 1_000L)
                + (Math.floorMod(deadlineMillis, 1_000L) == 0L ? 0L : 1L);
        var expiresAt = Math.max(issuedAt + 1L, deadlineSecond + 300L);
        var saved = capabilityStates.save(new CapabilityStateStore.SaveRequest(
                command.renderRequestId(), fingerprint, established.sealedState(), issuedAt, expiresAt));
        if (saved instanceof CapabilityStateStore.SaveOutcome.Stored) {
            return new CapabilityRuntimeReady(established.runtime());
        }
        if (saved instanceof CapabilityStateStore.SaveOutcome.FingerprintConflict) {
            return new CapabilityRuntimeRejected(ProblemCode.CAPABILITY_STATE_CONFLICT);
        }
        if (saved instanceof CapabilityStateStore.SaveOutcome.Replayed) {
            var replay = capabilityStates.load(command.renderRequestId(), fingerprint);
            if (replay instanceof CapabilityStateStore.LoadOutcome.Loaded state) {
                try {
                    return new CapabilityRuntimeReady(capabilities.restore(requirements, state.sealedState()));
                } catch (RuntimeException invalid) {
                    return new CapabilityRuntimeRejected(ProblemCode.CAPABILITY_STATE_UNAVAILABLE);
                }
            }
        }
        return new CapabilityRuntimeRejected(ProblemCode.CAPABILITY_STATE_UNAVAILABLE);
    }

    private sealed interface CapabilityRuntimeOutcome permits CapabilityRuntimeReady, CapabilityRuntimeRejected { }
    private record CapabilityRuntimeReady(
            RenderingCapabilityRuntime.Runtime runtime)
            implements CapabilityRuntimeOutcome { }
    private record CapabilityRuntimeRejected(ProblemCode code) implements CapabilityRuntimeOutcome { }

    private static EvaluationOutcome.Rejected rejected(
            EvaluationStage stage, ProblemCode code, String limitId) {
        return new EvaluationOutcome.Rejected(stage, new RenderingProblem(
                code, stage, Optional.empty(),
                limitId == null ? Optional.empty() : Optional.of(new LimitId(limitId))));
    }

}
