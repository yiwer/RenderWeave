package cn.hbads.renderweave.rendering.spi;

import cn.hbads.renderweave.rendering.api.EvaluationStage;
import cn.hbads.renderweave.rendering.api.Evaluator.OutputSelection;
import cn.hbads.renderweave.rendering.api.Evaluator.RenderRequestId;
import cn.hbads.renderweave.rendering.api.RenderOutput;
import cn.hbads.renderweave.rendering.api.RenderingProblem;

import java.util.Objects;

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

        /** 终态失败；stage 固定为 {@link EvaluationStage#ENGINE}。 */
        record TerminalProblem(RenderingProblem problem) implements EngineOutcome {
            public TerminalProblem {
                Objects.requireNonNull(problem, "problem");
                if (problem.stage() != EvaluationStage.ENGINE) {
                    throw new IllegalArgumentException("terminal engine problem must carry ENGINE stage");
                }
            }
        }

        /** 传输结果不明：可按原 deadline 同 canonical Command 重发。 */
        record Unknown() implements EngineOutcome {
        }
    }
}
