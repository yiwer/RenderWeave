package cn.hbads.renderweave.rendering.spi;

import cn.hbads.renderweave.rendering.api.Evaluator.OutputSelection;
import cn.hbads.renderweave.rendering.api.Evaluator.RenderRequestId;
import cn.hbads.renderweave.rendering.api.RenderOutput;
import cn.hbads.renderweave.rendering.api.RenderingProblem;
import cn.hbads.renderweave.rendering.api.RenderingProblem.LimitId;
import cn.hbads.renderweave.rendering.api.RenderingProblem.ProblemCode;

import java.util.Objects;
import java.util.Optional;

/**
 * RenderEngine outbound port（ADR-0044 §6）：单次 {@code execute} + closed 五态。
 *
 * <p>生产使用 app process Adapter（随 Rust Renderer 实现票），测试使用 scripted Adapter；
 * cancel/join/replay/registry 是 Engine 侧语义，不暴露为 Java 子接口。deadline 由服务端按部署
 * 配置在 Command 中冻结，不可延长。Windows/WSL/scripted 结果永不升级 Renderer READY。
 */
public interface RenderEngine {

    EngineOutcome execute(RendererCommand command);

    /**
     * 一次内部、closed、可 canonical 化的执行请求（{@code renderweave-render-command/1.0}）：
     * 一个 RenderDocument、不可延长 deadline、exact Renderer/Output Profile 与一张图片的有效
     * 输出参数。RenderDocument 以 canonical bytes 交接——app Adapter 只按协议分帧，不解释内容。
     */
    record RendererCommand(
            String contractVersion,
            RenderRequestId renderRequestId,
            String rendererProfile,
            long deadlineAtEpochMilli,
            String renderDocumentDigest,
            byte[] renderDocumentCanonicalUtf8,
            OutputSelection outputSelection,
            boolean layoutTraceRequested
    ) {
        public RendererCommand {
            Objects.requireNonNull(contractVersion, "contractVersion");
            Objects.requireNonNull(renderRequestId, "renderRequestId");
            Objects.requireNonNull(rendererProfile, "rendererProfile");
            Objects.requireNonNull(renderDocumentDigest, "renderDocumentDigest");
            Objects.requireNonNull(renderDocumentCanonicalUtf8, "renderDocumentCanonicalUtf8");
            Objects.requireNonNull(outputSelection, "outputSelection");
            if (!"renderweave-render-command/1.0".equals(contractVersion)) {
                throw new IllegalArgumentException("contractVersion must be renderweave-render-command/1.0");
            }
            if (rendererProfile.isBlank() || rendererProfile.length() > 256) {
                throw new IllegalArgumentException("rendererProfile must be non-blank and at most 256 chars");
            }
            if (renderDocumentCanonicalUtf8.length == 0) {
                throw new IllegalArgumentException("renderDocumentCanonicalUtf8 must not be empty");
            }
            renderDocumentCanonicalUtf8 = renderDocumentCanonicalUtf8.clone();
        }

        public byte[] renderDocumentCanonicalUtf8() {
            return renderDocumentCanonicalUtf8.clone();
        }
    }

    /** Closed Engine-internal execution stage retained until Rendering projects diagnostics. */
    enum EngineProblemStage {
        COMMAND_ADMISSION,
        REQUEST_CONTROL,
        DOCUMENT_ADMISSION,
        OUTPUT_PREFLIGHT,
        RESOURCE_PREPARATION,
        LAYOUT,
        SHAPING,
        RASTERIZATION,
        ENCODING,
        TRACE_PROJECTION,
        OUTPUT_SEAL
    }

    /**
     * Engine terminal problem before authorization-aware projection. Opaque locator identities
     * deliberately remain separate: a resource problem may carry both the consuming occurrence
     * and the exact resource occurrence.
     */
    record EngineProblem(
            ProblemCode code,
            EngineProblemStage engineStage,
            Optional<String> occurrenceId,
            Optional<String> resourceId,
            Optional<LimitId> limitId
    ) {
        public EngineProblem {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(engineStage, "engineStage");
            Objects.requireNonNull(occurrenceId, "occurrenceId");
            Objects.requireNonNull(resourceId, "resourceId");
            Objects.requireNonNull(limitId, "limitId");
            occurrenceId.ifPresent(value -> requireOpaqueLocator(value, "occurrenceId"));
            resourceId.ifPresent(value -> requireOpaqueLocator(value, "resourceId"));
            var engineCode = switch (code) {
                case RESOURCE_BUDGET_EXCEEDED,
                        RESOURCE_LEASE_EXPIRED,
                        FETCH_FAILED,
                        LENGTH_MISMATCH,
                        HASH_MISMATCH,
                        MEDIA_MISMATCH,
                        DECODE_FAILED,
                        FONT_GLYPH_MISSING,
                        RASTER_BUDGET_EXCEEDED,
                        OUTPUT_BUDGET_EXCEEDED,
                        RENDER_REQUEST_STATE_LOST,
                        RENDER_REQUEST_CONFLICT,
                        RENDER_ENGINE_BUSY,
                        RENDER_CANCELLED,
                        RENDER_DEADLINE_EXCEEDED,
                        RENDER_LAYOUT_TRACE_LIMIT_EXCEEDED,
                        RENDER_INTERNAL_ERROR -> true;
                case RENDER_INPUT_LIMIT_EXCEEDED,
                        RENDER_INPUT_CONTENT_ENCODING_UNSUPPORTED,
                        TEMPLATE_NOT_FOUND,
                        TEMPLATE_DELETED,
                        TEMPLATE_DEPENDENCY_ERROR,
                        TEMPLATE_AUTHORITY_UNAVAILABLE,
                        TEMPLATE_CLOSURE_LIMIT_EXCEEDED,
                        TEMPLATE_CLOSURE_UNSTABLE,
                        DESIGN_DSL_LIMIT_EXCEEDED,
                        ASSET_BUDGET_EXCEEDED,
                        ASSET_NOT_FOUND,
                        ASSET_RESOLVE_NOT_FOUND,
                        ASSET_RESOLVE_DELETED,
                        ASSET_RESOLVE_KIND_MISMATCH,
                        ASSET_RESOLVE_UNAVAILABLE,
                        ASSET_RESOLVE_TIMEOUT,
                        CAPABILITY_BUDGET_EXCEEDED,
                        CAPABILITY_STATE_CONFLICT,
                        CAPABILITY_STATE_UNAVAILABLE,
                        CAPABILITY_RESULT_INVALID,
                        CAPABILITY_CLOCK_UNAVAILABLE,
                        CAPABILITY_ENTROPY_UNAVAILABLE,
                        CAPABILITY_PROFILE_UNAVAILABLE,
                        CAPABILITY_DEADLINE_EXCEEDED,
                        CAPABILITY_CANCELLED,
                        EXPRESSION_LIMIT_EXCEEDED,
                        EVALUATION_BUDGET_EXCEEDED,
                        EVALUATION_FAILED,
                        RENDER_DOCUMENT_LIMIT_EXCEEDED,
                        RENDER_DIAGNOSTIC_LIMIT_EXCEEDED -> false;
            };
            if (!engineCode) {
                throw new IllegalArgumentException("problem code is outside the Engine catalog");
            }
        }

        public static EngineProblem of(ProblemCode code, EngineProblemStage engineStage) {
            return new EngineProblem(
                    code, engineStage, Optional.empty(), Optional.empty(), Optional.empty());
        }

        private static void requireOpaqueLocator(String value, String name) {
            if (value.isBlank() || value.length() > 256) {
                throw new IllegalArgumentException(
                        name + " must be non-blank and at most 256 chars");
            }
        }
    }

    /**
     * closed 五态：Java 侧对 {@link Unknown}（传输结果不明）在原 absolute deadline 与全部 lease
     * 有效期内用同一 canonical Command 重发——不重新 seal、不续期 lease、不重新 resolve current、
     * 不重建 CapabilityState、不延长 deadline；registry 状态丢失必须失败，新请求重新 Evaluation。
     */
    sealed interface EngineOutcome
            permits EngineOutcome.SealedOutput, EngineOutcome.Joined, EngineOutcome.Replayed,
                    EngineOutcome.TerminalProblem, EngineOutcome.Unknown {

        /** Engine 原子封存后的正式输出。 */
        record SealedOutput(RenderOutput output) implements EngineOutcome {
            public SealedOutput {
                Objects.requireNonNull(output, "output");
            }
        }

        /** 同 canonical Command identity 加入活跃执行（Engine 侧语义）。 */
        record Joined(RenderOutput output) implements EngineOutcome {
            public Joined {
                Objects.requireNonNull(output, "output");
            }
        }

        /** 同 Command exact replay 的已授权终态（Engine 侧语义）。 */
        record Replayed(RenderOutput output) implements EngineOutcome {
            public Replayed {
                Objects.requireNonNull(output, "output");
            }
        }

        /** 终态失败；Rendering 在授权重检后将 opaque locator 投影为 public problem。 */
        record TerminalProblem(EngineProblem problem) implements EngineOutcome {
            public TerminalProblem {
                Objects.requireNonNull(problem, "problem");
            }
        }

        /** 传输结果不明：可按原 deadline 同 canonical Command 重发。 */
        record Unknown() implements EngineOutcome {
        }
    }
}
