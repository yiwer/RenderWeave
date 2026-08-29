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
import cn.hbads.renderweave.template.api.DesignInputExpressionCapacityAuthority;
import cn.hbads.renderweave.template.api.DesignDslAuthority;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority;
import cn.hbads.renderweave.template.api.TemplateClosureAuthority;
import cn.hbads.renderweave.template.api.TemplateClosureAuthority.ClosureOutcome;
import cn.hbads.renderweave.validation.ValidationTargetResolver;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Rendering 唯一动态语义权威（ADR-0044 §2）：单一窄 {@code evaluate} 按 first-fail 串行
 * stage 1–8——REQUEST_ADMISSION envelope、TEMPLATE_CLOSURE 冻结、INPUT_ADMISSION typed view、
 * ASSET_ADMISSION、CAPABILITY_STATE、MATERIALIZATION/ASSET_RESOLUTION 物化、DOCUMENT_SEAL
 * 原子封存。失败无 partial output；内部违约折叠 RENDER_INTERNAL_ERROR。Engine 执行（stage 9）
 * 由 RenderingApplication 接线，本 evaluate 终止于 SealedDocument。
 */
final class CanonicalEvaluator implements Evaluator {

    private static final RenderingPipelineCapacityGuard CAPACITY_GUARD =
            new RenderingPipelineCapacityGuard();
    private static final RenderingPipelineCapacityGuard.Limit
            ADMISSION_AND_CLOSURE_DEADLINE_LIMIT =
            RenderingPipelineCapacityGuard.Limit
                    .DEADLINE_AND_RETENTION_ADMISSION_AND_CLOSURE_MILLIS;
    private static final RenderingPipelineCapacityGuard.Limit
            EVALUATION_AND_DOCUMENT_SEAL_DEADLINE_LIMIT =
            RenderingPipelineCapacityGuard.Limit
                    .DEADLINE_AND_RETENTION_EVALUATION_AND_DOCUMENT_SEAL_MILLIS;
    private static final RenderingPipelineCapacityGuard.Limit
            CAPABILITY_AND_RESOLVER_RECOVERY_RETENTION_LIMIT =
            RenderingPipelineCapacityGuard.Limit
                    .DEADLINE_AND_RETENTION_CAPABILITY_AND_RESOLVER_RECOVERY_RETENTION_AFTER_DEADLINE_MILLIS;
    private static final RenderingPipelineCapacityGuard.Limit TOTAL_DEADLINE_LIMIT =
            RenderingPipelineCapacityGuard.Limit
                    .DEADLINE_AND_RETENTION_TOTAL_DEADLINE_MILLIS;

    private final TemplateClosureAuthority closureAuthority;
    private final DesignInputExpressionCapacityAuthority capacityAuthority;
    private final DesignSemanticAuthority semantics;
    private final DesignDslAuthority dslAuthority;
    private final AssetResolutionPort assets;
    private final RenderingCapabilityRuntime capabilities;
    private final CapabilityStateStore capabilityStates;
    private final String effectiveBudgetVector;
    private final CapabilityBudget capabilityBudget;
    private final ValidationTargetResolver validationResolver;
    private final Clock clock;
    private final LongSupplier monotonicNanos;

    CanonicalEvaluator(
            TemplateClosureAuthority closureAuthority,
            DesignInputExpressionCapacityAuthority capacityAuthority,
            DesignSemanticAuthority semantics,
            DesignDslAuthority dslAuthority,
            AssetResolutionPort assets,
            RenderingCapabilityRuntime capabilities,
            CapabilityStateStore capabilityStates,
            String effectiveBudgetVector,
            ValidationTargetResolver validationResolver,
            Clock clock
    ) {
        this(closureAuthority, capacityAuthority, semantics, dslAuthority, assets, capabilities,
                capabilityStates, effectiveBudgetVector, validationResolver, clock,
                System::nanoTime);
    }

    CanonicalEvaluator(
            TemplateClosureAuthority closureAuthority,
            DesignInputExpressionCapacityAuthority capacityAuthority,
            DesignSemanticAuthority semantics,
            DesignDslAuthority dslAuthority,
            AssetResolutionPort assets,
            RenderingCapabilityRuntime capabilities,
            CapabilityStateStore capabilityStates,
            String effectiveBudgetVector,
            ValidationTargetResolver validationResolver,
            Clock clock,
            LongSupplier monotonicNanos
    ) {
        this.closureAuthority = Objects.requireNonNull(closureAuthority, "closureAuthority");
        this.capacityAuthority = Objects.requireNonNull(capacityAuthority, "capacityAuthority");
        this.semantics = Objects.requireNonNull(semantics, "semantics");
        this.dslAuthority = Objects.requireNonNull(dslAuthority, "dslAuthority");
        this.assets = assets;
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.capabilityStates = Objects.requireNonNull(capabilityStates, "capabilityStates");
        this.effectiveBudgetVector = Objects.requireNonNull(effectiveBudgetVector, "effectiveBudgetVector");
        this.capabilityBudget = CapabilityBudget.fromEffectiveVector(effectiveBudgetVector);
        this.validationResolver = Objects.requireNonNull(validationResolver, "validationResolver");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.monotonicNanos = Objects.requireNonNull(monotonicNanos, "monotonicNanos");
    }

    @Override
    public EvaluationOutcome evaluate(EvaluationCommand command) {
        Objects.requireNonNull(command, "command");

        if (deadlineExpired(command.admissionAndClosureDeadlineAtMonotonicNanos())) {
            return deadlineRejected(ADMISSION_AND_CLOSURE_DEADLINE_LIMIT);
        }
        if (deadlineExpired(command.deadlineAtMonotonicNanos())) {
            return deadlineRejected(TOTAL_DEADLINE_LIMIT);
        }

        var closureOutcome = closureAuthority.freezeClosure(
                new TemplateClosureAuthority.RenderRequestId(command.renderRequestId().value()),
                command.rootTemplateId(),
                () -> deadlineExpired(
                        command.admissionAndClosureDeadlineAtMonotonicNanos()));
        if (deadlineExpired(command.admissionAndClosureDeadlineAtMonotonicNanos())) {
            return deadlineRejected(ADMISSION_AND_CLOSURE_DEADLINE_LIMIT);
        }
        if (closureOutcome instanceof TemplateClosureAuthority.ClosureDeadlineExceeded) {
            return deadlineRejected(ADMISSION_AND_CLOSURE_DEADLINE_LIMIT);
        }
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
                            Optional.of(new LimitId(
                                    "closureAndExpansion." + limited.limitId().value()
                            ))));
        }
        if (closureOutcome instanceof TemplateClosureAuthority.ClosureUnavailable) {
            return rejected(EvaluationStage.TEMPLATE_CLOSURE,
                    ProblemCode.TEMPLATE_AUTHORITY_UNAVAILABLE, null);
        }
        if (!(closureOutcome instanceof TemplateClosureAuthority.ClosureFrozen frozen)) {
            return rejected(EvaluationStage.TEMPLATE_CLOSURE,
                    ProblemCode.RENDER_INTERNAL_ERROR, null);
        }
        var evaluationControl = EvaluationStageControl.start(
                monotonicNanos,
                CAPACITY_GUARD.exactValue(EVALUATION_AND_DOCUMENT_SEAL_DEADLINE_LIMIT));
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
        var expressionCapacity = ExpressionCapacityAdmission.admit(
                closure, semantics, dslAuthority, capacityAuthority, evaluationControl);
        if (expressionCapacity instanceof ExpressionCapacityAdmission.Rejected limited) {
            return new EvaluationOutcome.Rejected(
                    limited.problem().stage(), limited.problem());
        }
        if (expressionCapacity instanceof ExpressionCapacityAdmission.Fault) {
            return rejected(EvaluationStage.TEMPLATE_CLOSURE,
                    ProblemCode.RENDER_INTERNAL_ERROR, null);
        }
        if (expressionCapacity instanceof ExpressionCapacityAdmission.DeadlineExceeded) {
            return deadlineRejected(EVALUATION_AND_DOCUMENT_SEAL_DEADLINE_LIMIT);
        }
        var declarationOutcome = CapabilityDeclarations.scan(
                closure, semantics, capabilityBudget, evaluationControl);
        if (declarationOutcome instanceof CapabilityDeclarations.DeclarationFault) {
            return rejected(EvaluationStage.TEMPLATE_CLOSURE,
                    ProblemCode.RENDER_INTERNAL_ERROR, null);
        }
        if (declarationOutcome
                instanceof CapabilityDeclarations.DeclarationCapacityExceeded exceeded) {
            return new EvaluationOutcome.Rejected(
                    exceeded.problem().stage(), exceeded.problem());
        }
        if (declarationOutcome instanceof CapabilityDeclarations.DeclarationDeadlineExceeded) {
            return deadlineRejected(EVALUATION_AND_DOCUMENT_SEAL_DEADLINE_LIMIT);
        }
        var declarations = (CapabilityDeclarations.Declared) declarationOutcome;
        if (evaluationControl.deadlineExceeded()) {
            return deadlineRejected(EVALUATION_AND_DOCUMENT_SEAL_DEADLINE_LIMIT);
        }

        var admission = InputAdmission.admit(
                command.rawRenderInputUtf8(),
                rootSnapshot,
                capacityAuthority,
                validationResolver,
                evaluationControl);
        if (admission instanceof InputAdmission.AdmissionRejected admissionRejected) {
            return new EvaluationOutcome.Rejected(
                    admissionRejected.problems().get(0).stage(),
                    admissionRejected.problems().get(0));
        }
        if (admission instanceof InputAdmission.AdmissionUnavailable) {
            return rejected(EvaluationStage.INPUT_ADMISSION,
                    ProblemCode.EVALUATION_FAILED, null);
        }
        if (admission instanceof InputAdmission.AdmissionDeadlineExceeded) {
            return deadlineRejected(EVALUATION_AND_DOCUMENT_SEAL_DEADLINE_LIMIT);
        }
        if (!(admission instanceof InputAdmission.AdmissionAdmitted admitted)) {
            return rejected(EvaluationStage.INPUT_ADMISSION,
                    ProblemCode.RENDER_INTERNAL_ERROR, null);
        }
        if (evaluationControl.deadlineExceeded()) {
            return deadlineRejected(EVALUATION_AND_DOCUMENT_SEAL_DEADLINE_LIMIT);
        }

        var assetAdmission = AssetAdmission.admit(
                closure,
                semantics,
                assets,
                admitted.input(),
                command.externalAssetReadAuthorization(),
                evaluationControl);
        if (assetAdmission instanceof AssetAdmission.Rejected rejected) {
            return new EvaluationOutcome.Rejected(rejected.stage(), rejected.problem());
        }
        if (assetAdmission instanceof AssetAdmission.AdmissionDeadlineExceeded) {
            return deadlineRejected(EVALUATION_AND_DOCUMENT_SEAL_DEADLINE_LIMIT);
        }
        var admittedAssets = (AssetAdmission.Admitted) assetAdmission;
        if (evaluationControl.deadlineExceeded()) {
            return deadlineRejected(EVALUATION_AND_DOCUMENT_SEAL_DEADLINE_LIMIT);
        }

        var runtimeOutcome = capabilityRuntime(
                command, closure, admitted.input(), declarations, evaluationControl);
        if (runtimeOutcome instanceof CapabilityRuntimeRejected rejected) {
            return rejected(EvaluationStage.CAPABILITY_STATE, rejected.code(), rejected.limitId());
        }
        if (runtimeOutcome instanceof CapabilityRuntimeStageDeadlineExceeded) {
            return deadlineRejected(EVALUATION_AND_DOCUMENT_SEAL_DEADLINE_LIMIT);
        }
        var runtime = CapabilityValues.wrapping(
                ((CapabilityRuntimeReady) runtimeOutcome).runtime(),
                capabilityBudget.newTracker(),
                evaluationControl);
        if (evaluationControl.deadlineExceeded()) {
            return deadlineRejected(EVALUATION_AND_DOCUMENT_SEAL_DEADLINE_LIMIT);
        }
        var audience = new AssetResolutionPort.RendererAudience(command.rendererProfile());
        var materialization = Materializer.materialize(
                admittedAssets,
                closure,
                semantics,
                dslAuthority,
                capacityAuthority,
                assets,
                runtime.provider(),
                admitted.input(),
                command.renderRequestId(),
                audience,
                command.deadlineAtEpochMilli(),
                evaluationControl);
        if (materialization instanceof Materializer.MaterializationFailed failed) {
            return new EvaluationOutcome.Rejected(failed.stage(), failed.problem());
        }
        if (materialization instanceof Materializer.MaterializationDeadlineExceeded) {
            return deadlineRejected(EVALUATION_AND_DOCUMENT_SEAL_DEADLINE_LIMIT);
        }
        if (!(materialization instanceof Materializer.Materialized materialized)) {
            return rejected(EvaluationStage.MATERIALIZATION,
                    ProblemCode.RENDER_INTERNAL_ERROR, null);
        }
        if (evaluationControl.deadlineExceeded()) {
            return deadlineRejected(EVALUATION_AND_DOCUMENT_SEAL_DEADLINE_LIMIT);
        }

        final String capabilityResultDigest;
        try {
            capabilityResultDigest = runtime.capabilityResultDigest();
        } catch (EvaluationStageControl.DeadlineExceeded ignored) {
            return deadlineRejected(EVALUATION_AND_DOCUMENT_SEAL_DEADLINE_LIMIT);
        }
        var seal = Sealer.seal(
                closure,
                admitted.input(),
                materialized.tree(),
                capabilityResultDigest,
                evaluationControl);
        // Capability state is established or restored before materialization can demand it.
        if (seal instanceof Sealer.SealRejected sealRejected) {
            return new EvaluationOutcome.Rejected(
                    sealRejected.problem().stage(), sealRejected.problem());
        }
        if (seal instanceof Sealer.SealDeadlineExceeded) {
            return deadlineRejected(EVALUATION_AND_DOCUMENT_SEAL_DEADLINE_LIMIT);
        }
        var sealed = ((Sealer.Sealed) seal).evaluation();
        if (evaluationControl.deadlineExceeded()) {
            return deadlineRejected(EVALUATION_AND_DOCUMENT_SEAL_DEADLINE_LIMIT);
        }
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
            CapabilityDeclarations.Declared declarations,
            EvaluationStageControl evaluationControl
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
        if (evaluationControl.deadlineExceeded()) {
            return new CapabilityRuntimeStageDeadlineExceeded();
        }
        var loaded = capabilityStates.load(command.renderRequestId(), fingerprint);
        if (loaded instanceof CapabilityStateStore.LoadOutcome.Loaded state) {
            return restoreCapabilityRuntime(
                    requirements,
                    state,
                    command.deadlineAtMonotonicNanos(),
                    evaluationControl);
        }
        if (loaded instanceof CapabilityStateStore.LoadOutcome.LoadFingerprintConflict) {
            return new CapabilityRuntimeRejected(ProblemCode.CAPABILITY_STATE_CONFLICT);
        }
        if (loaded instanceof CapabilityStateStore.LoadOutcome.LoadUnavailable) {
            return new CapabilityRuntimeRejected(ProblemCode.CAPABILITY_STATE_UNAVAILABLE);
        }
        if (evaluationControl.deadlineExceeded()) {
            return new CapabilityRuntimeStageDeadlineExceeded();
        }
        var issuedAt = clock.millis();
        var deadlineMillis = command.deadlineAtEpochMilli();
        var deadlineAtMonotonicNanos = command.deadlineAtMonotonicNanos();
        final long expiresAt;
        try {
            expiresAt = Math.addExact(
                    deadlineMillis,
                    CAPACITY_GUARD.exactValue(
                            CAPABILITY_AND_RESOLVER_RECOVERY_RETENTION_LIMIT));
        } catch (ArithmeticException overflow) {
            return new CapabilityRuntimeRejected(ProblemCode.CAPABILITY_STATE_UNAVAILABLE);
        }
        if (expiresAt <= issuedAt) {
            return new CapabilityRuntimeRejected(ProblemCode.CAPABILITY_STATE_UNAVAILABLE);
        }
        var initializationAttempts = capabilityBudget.newInitializationAttempts();
        while (true) {
            if (deadlineExpired(deadlineAtMonotonicNanos)) {
                return new CapabilityRuntimeRejected(ProblemCode.CAPABILITY_DEADLINE_EXCEEDED);
            }
            if (evaluationControl.deadlineExceeded()) {
                return new CapabilityRuntimeStageDeadlineExceeded();
            }
            var attemptLimit = initializationAttempts.reserve();
            if (attemptLimit != null) {
                return new CapabilityRuntimeRejected(
                        ProblemCode.CAPABILITY_STATE_UNAVAILABLE, attemptLimit.limitId());
            }
            final RenderingCapabilityRuntime.Established established;
            try {
                established = Objects.requireNonNull(
                        capabilities.establish(requirements), "established");
            } catch (RuntimeException unavailable) {
                // No state can have committed before establish returns; a bounded resample is safe.
                continue;
            }
            if (deadlineExpired(deadlineAtMonotonicNanos)) {
                return new CapabilityRuntimeRejected(ProblemCode.CAPABILITY_DEADLINE_EXCEEDED);
            }
            if (evaluationControl.deadlineExceeded()) {
                return new CapabilityRuntimeStageDeadlineExceeded();
            }
            var stateRecordLimit = capabilityBudget.admitStateRecord(
                    established.sealedState().length);
            if (stateRecordLimit != null) {
                return new CapabilityRuntimeRejected(
                        ProblemCode.CAPABILITY_BUDGET_EXCEEDED, stateRecordLimit.limitId());
            }
            if (evaluationControl.deadlineExceeded()) {
                return new CapabilityRuntimeStageDeadlineExceeded();
            }
            var saved = capabilityStates.save(new CapabilityStateStore.SaveRequest(
                    command.renderRequestId(), fingerprint, established.sealedState(),
                    issuedAt, expiresAt));
            if (saved instanceof CapabilityStateStore.SaveOutcome.Stored) {
                if (deadlineExpired(deadlineAtMonotonicNanos)) {
                    return new CapabilityRuntimeRejected(
                            ProblemCode.CAPABILITY_DEADLINE_EXCEEDED);
                }
                if (evaluationControl.deadlineExceeded()) {
                    return new CapabilityRuntimeStageDeadlineExceeded();
                }
                return new CapabilityRuntimeReady(established.runtime());
            }
            if (saved instanceof CapabilityStateStore.SaveOutcome.FingerprintConflict) {
                return new CapabilityRuntimeRejected(ProblemCode.CAPABILITY_STATE_CONFLICT);
            }
            if (saved instanceof CapabilityStateStore.SaveOutcome.Replayed
                    || saved instanceof CapabilityStateStore.SaveOutcome.SaveUnavailable) {
                var replay = capabilityStates.load(command.renderRequestId(), fingerprint);
                if (replay instanceof CapabilityStateStore.LoadOutcome.Loaded state) {
                    return restoreCapabilityRuntime(
                            requirements,
                            state,
                            deadlineAtMonotonicNanos,
                            evaluationControl);
                }
                if (replay instanceof CapabilityStateStore.LoadOutcome.LoadFingerprintConflict) {
                    return new CapabilityRuntimeRejected(ProblemCode.CAPABILITY_STATE_CONFLICT);
                }
                if (saved instanceof CapabilityStateStore.SaveOutcome.SaveUnavailable
                        && replay instanceof CapabilityStateStore.LoadOutcome.Missing) {
                    if (deadlineExpired(deadlineAtMonotonicNanos)) {
                        return new CapabilityRuntimeRejected(
                                ProblemCode.CAPABILITY_DEADLINE_EXCEEDED);
                    }
                    if (evaluationControl.deadlineExceeded()) {
                        return new CapabilityRuntimeStageDeadlineExceeded();
                    }
                    continue;
                }
                return new CapabilityRuntimeRejected(ProblemCode.CAPABILITY_STATE_UNAVAILABLE);
            }
            return new CapabilityRuntimeRejected(ProblemCode.CAPABILITY_STATE_UNAVAILABLE);
        }
    }

    private CapabilityRuntimeOutcome restoreCapabilityRuntime(
            CapabilityRequirements requirements,
            CapabilityStateStore.LoadOutcome.Loaded state,
            long deadlineAtMonotonicNanos,
            EvaluationStageControl evaluationControl
    ) {
        if (deadlineExpired(deadlineAtMonotonicNanos)) {
            return new CapabilityRuntimeRejected(ProblemCode.CAPABILITY_DEADLINE_EXCEEDED);
        }
        if (evaluationControl.deadlineExceeded()) {
            return new CapabilityRuntimeStageDeadlineExceeded();
        }
        try {
            var restored = Objects.requireNonNull(
                    capabilities.restore(requirements, state.sealedState()), "restored");
            if (deadlineExpired(deadlineAtMonotonicNanos)) {
                return new CapabilityRuntimeRejected(ProblemCode.CAPABILITY_DEADLINE_EXCEEDED);
            }
            if (evaluationControl.deadlineExceeded()) {
                return new CapabilityRuntimeStageDeadlineExceeded();
            }
            return new CapabilityRuntimeReady(restored);
        } catch (RuntimeException invalid) {
            return new CapabilityRuntimeRejected(ProblemCode.CAPABILITY_STATE_UNAVAILABLE);
        }
    }

    private boolean deadlineExpired(long deadlineAtMonotonicNanos) {
        // Signed subtraction is wrap-safe for System.nanoTime intervals below 2^63 ns.
        return deadlineAtMonotonicNanos - monotonicNanos.getAsLong() <= 0;
    }

    private static EvaluationOutcome.Rejected deadlineRejected(
            RenderingPipelineCapacityGuard.Limit limit
    ) {
        var problem = CAPACITY_GUARD.rejection(limit);
        return new EvaluationOutcome.Rejected(problem.stage(), problem);
    }

    private sealed interface CapabilityRuntimeOutcome permits CapabilityRuntimeReady,
            CapabilityRuntimeRejected, CapabilityRuntimeStageDeadlineExceeded { }
    private record CapabilityRuntimeReady(
            RenderingCapabilityRuntime.Runtime runtime)
            implements CapabilityRuntimeOutcome { }
    private record CapabilityRuntimeRejected(
            ProblemCode code, String limitId) implements CapabilityRuntimeOutcome {
        private CapabilityRuntimeRejected(ProblemCode code) {
            this(code, null);
        }
    }
    private record CapabilityRuntimeStageDeadlineExceeded()
            implements CapabilityRuntimeOutcome { }

    private static EvaluationOutcome.Rejected rejected(
            EvaluationStage stage, ProblemCode code, String limitId) {
        return new EvaluationOutcome.Rejected(stage, new RenderingProblem(
                code, stage, Optional.empty(),
                limitId == null ? Optional.empty() : Optional.of(new LimitId(limitId))));
    }

}
