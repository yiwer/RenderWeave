package cn.hbads.renderweave.rendering.spi;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Rendering capability 运行时 inbound seam：app（或测试）提供 Clock/Random demand 供给。
 * Rendering 内部持有 demand 记账与 capability result digest；本 seam 只负责按
 * capability/operation/位置供给具体值。供给不可用时失败封闭，Evaluation 拒绝。
 */
public interface RenderingCapabilityRuntime {

    CapabilityOutcome supply(String capability, String operation, byte[] callPosition);

    /** 本运行时声明的 exact capability contracts（canonical 标识，fingerprint 输入）。 */
    String capabilityContracts();

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
