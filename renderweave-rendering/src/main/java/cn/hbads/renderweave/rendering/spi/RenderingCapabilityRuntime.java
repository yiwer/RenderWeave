package cn.hbads.renderweave.rendering.spi;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Rendering capability 运行时 inbound seam：app（或测试）按 Evaluation 建立运行时——每次
 * Evaluation 恰好一个 Clock snapshot 与一个 server-only Random nonce。Rendering 内部持有
 * demand 记账与 capability result digest；本 seam 只负责按 capability/operation/位置供给
 * 具体值。供给不可用时失败封闭，Evaluation 拒绝。
 */
public interface RenderingCapabilityRuntime {

    /** 为一次 Evaluation 建立运行时（单一 Clock snapshot + 单一 nonce）。 */
    Runtime establish();

    /** 本部署声明的 exact capability contracts（canonical 标识，fingerprint 输入）。 */
    String capabilityContracts();

    interface Runtime {
        CapabilityOutcome supply(String capability, String operation, byte[] callPosition);
    }

    sealed interface CapabilityOutcome permits Supplied, ProviderUnavailable {
    }

    record Supplied(CapabilityValue value) implements CapabilityOutcome {
        public Supplied {
            Objects.requireNonNull(value, "value");
        }
    }

    record ProviderUnavailable() implements CapabilityOutcome {
    }

    sealed interface CapabilityValue permits
            TextResult,
            DecimalResult,
            DateResult,
            TimeResult {
    }

    record TextResult(String value) implements CapabilityValue {
        public TextResult {
            Objects.requireNonNull(value, "value");
        }
    }

    record DecimalResult(BigDecimal value) implements CapabilityValue {
        public DecimalResult {
            Objects.requireNonNull(value, "value");
        }
    }

    record DateResult(String value) implements CapabilityValue {
        public DateResult {
            Objects.requireNonNull(value, "value");
        }
    }

    record TimeResult(String value) implements CapabilityValue {
        public TimeResult {
            Objects.requireNonNull(value, "value");
        }
    }
}
