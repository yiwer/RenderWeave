package cn.hbads.renderweave.rendering.spi;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Rendering capability 运行时 inbound seam：app（或测试）按完整 closure 的 exact 声明集合
 * 选择性建立组件——CLOCK 对应单一 snapshot，RANDOM 对应单一 server-only nonce；未声明组件
 * 不读取、不进入 opaque state。Rendering 内部持有 demand 记账与 capability result digest；
 * 本 seam 只负责按 capability/operation/位置供给具体值。供给不可用时失败封闭，Evaluation 拒绝。
 */
public interface RenderingCapabilityRuntime {

    /** 只建立完整 closure 实际声明的组件；调用前全部早期 admission 已成功。 */
    Established establish(CapabilityRequirements requirements);

    /** 恢复 exact required components；state 与 requirement 不一致必须失败封闭。 */
    Runtime restore(CapabilityRequirements requirements, byte[] sealedState);

    /** 本部署声明支持的 exact capability contracts。 */
    Set<CapabilityContract> supportedContracts();

    enum CapabilityContract {
        CLOCK_1_0("renderweave-capability-clock/1.0"),
        RANDOM_1_0("renderweave-capability-random/1.0");

        private final String contractId;

        CapabilityContract(String contractId) {
            this.contractId = contractId;
        }

        public String contractId() {
            return contractId;
        }
    }

    /** Non-empty, immutable component set selected from the frozen closure. */
    record CapabilityRequirements(Set<CapabilityContract> contracts) {
        public CapabilityRequirements {
            Objects.requireNonNull(contracts, "contracts");
            if (contracts.isEmpty()) {
                throw new IllegalArgumentException("capability requirements must not be empty");
            }
            var copy = EnumSet.noneOf(CapabilityContract.class);
            copy.addAll(contracts);
            contracts = Set.copyOf(copy);
        }

        public boolean requires(CapabilityContract contract) {
            return contracts.contains(Objects.requireNonNull(contract, "contract"));
        }
    }

    /** Runtime plus opaque, store-ready state. The bytes are never exposed outside trusted adapters. */
    record Established(Runtime runtime, byte[] sealedState) {
        public Established {
            Objects.requireNonNull(runtime, "runtime");
            Objects.requireNonNull(sealedState, "sealedState");
            if (sealedState.length == 0) {
                throw new IllegalArgumentException("sealedState must not be empty");
            }
            sealedState = sealedState.clone();
        }

        public byte[] sealedState() {
            return sealedState.clone();
        }
    }

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
