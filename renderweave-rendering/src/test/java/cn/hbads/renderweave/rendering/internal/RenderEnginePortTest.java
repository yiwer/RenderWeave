package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.rendering.api.EvaluationStage;
import cn.hbads.renderweave.rendering.api.Evaluator.OutputSelection;
import cn.hbads.renderweave.rendering.api.Evaluator.RenderRequestId;
import cn.hbads.renderweave.rendering.api.RenderOutput;
import cn.hbads.renderweave.rendering.api.RenderingProblem;
import cn.hbads.renderweave.rendering.spi.RenderEngine;
import cn.hbads.renderweave.rendering.spi.RenderEngine.EngineOutcome;
import cn.hbads.renderweave.rendering.spi.RenderEngine.RendererCommand;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RenderEngine port 合同（ADR-0044 §6）：closed 五态；TerminalProblem 固定 ENGINE stage；
 * Unknown → 同 canonical Command 在原 deadline 内重发（不重新 seal、不延长 deadline）。
 * 生产 process adapter 随 Rust Renderer 实现票；本测试使用 scripted adapter。
 */
class RenderEnginePortTest {

    private static RendererCommand command() {
        return new RendererCommand(
                "renderweave-render-command/1.0",
                new RenderRequestId("00000000-0000-4000-8000-000000000001"),
                "renderweave-renderer/1.0",
                1_000L,
                "sha256:" + "a".repeat(64),
                "{\"canvas\":{}}".getBytes(StandardCharsets.UTF_8),
                OutputSelection.defaultPng(),
                false);
    }

    private static RenderOutput output() {
        return new RenderOutput(
                new byte[] { 1, 2, 3 },
                OutputSelection.defaultPng(),
                10,
                20,
                3);
    }

    @Test
    void fiveClosedOutcomesAreDistinguishable() {
        var sealed = new EngineOutcome.SealedOutput(output());
        var joined = new EngineOutcome.Joined(output());
        var replayed = new EngineOutcome.Replayed(output());
        var terminal = new EngineOutcome.TerminalProblem(RenderingProblem.of(
                RenderingProblem.ProblemCode.RENDER_DEADLINE_EXCEEDED, EvaluationStage.ENGINE));
        var unknown = new EngineOutcome.Unknown();

        assertInstanceOf(EngineOutcome.class, sealed);
        assertInstanceOf(EngineOutcome.class, joined);
        assertInstanceOf(EngineOutcome.class, replayed);
        assertInstanceOf(EngineOutcome.class, terminal);
        assertInstanceOf(EngineOutcome.class, unknown);
    }

    @Test
    void terminalProblemMustCarryEngineStage() {
        assertThrows(IllegalArgumentException.class, () -> new EngineOutcome.TerminalProblem(
                RenderingProblem.of(
                        RenderingProblem.ProblemCode.RENDER_INTERNAL_ERROR,
                        EvaluationStage.MATERIALIZATION)));
    }

    @Test
    void unknownResendReusesSameCanonicalCommandAndDeadline() {
        var seen = new ArrayList<RendererCommand>();
        var scripted = new ScriptedEngine(seen, 1);
        var cmd = command();

        var first = scripted.execute(cmd);
        assertInstanceOf(EngineOutcome.Unknown.class, first);
        var second = scripted.execute(cmd);
        assertInstanceOf(EngineOutcome.SealedOutput.class, second);

        assertEquals(2, seen.size());
        // 重发使用同一 canonical Command bytes 与原 deadline。
        assertArrayEquals(
                seen.get(0).renderDocumentCanonicalUtf8(),
                seen.get(1).renderDocumentCanonicalUtf8());
        assertEquals(seen.get(0).deadlineAtEpochMilli(), seen.get(1).deadlineAtEpochMilli());
        assertEquals(seen.get(0).renderRequestId(), seen.get(1).renderRequestId());
    }

    @Test
    void commandContractVersionIsFrozen() {
        assertThrows(IllegalArgumentException.class, () -> new RendererCommand(
                "renderweave-render-command/2.0",
                new RenderRequestId("00000000-0000-4000-8000-000000000001"),
                "renderweave-renderer/1.0",
                1_000L,
                "sha256:" + "a".repeat(64),
                "{}".getBytes(StandardCharsets.UTF_8),
                OutputSelection.defaultPng(),
                false));
    }

    @Test
    void commandCopiesDocumentBytes() {
        var bytes = "{}".getBytes(StandardCharsets.UTF_8);
        var cmd = new RendererCommand(
                "renderweave-render-command/1.0",
                new RenderRequestId("00000000-0000-4000-8000-000000000001"),
                "renderweave-renderer/1.0",
                1_000L,
                "sha256:" + "a".repeat(64),
                bytes,
                OutputSelection.defaultPng(),
                false);
        bytes[0] = 'X';
        assertTrue(cmd.renderDocumentCanonicalUtf8()[0] == '{');
    }

    /** 前 unknownCount 次返回 Unknown，之后封存输出。 */
    static final class ScriptedEngine implements RenderEngine {
        private final List<RendererCommand> seen;
        private final int unknownCount;
        private int calls;

        ScriptedEngine(List<RendererCommand> seen, int unknownCount) {
            this.seen = seen;
            this.unknownCount = unknownCount;
        }

        @Override
        public EngineOutcome execute(RendererCommand cmd) {
            seen.add(cmd);
            calls++;
            if (calls <= unknownCount) {
                return new EngineOutcome.Unknown();
            }
            return new EngineOutcome.SealedOutput(output());
        }
    }
}
