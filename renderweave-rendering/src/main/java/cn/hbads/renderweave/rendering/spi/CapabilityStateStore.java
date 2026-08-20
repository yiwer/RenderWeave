package cn.hbads.renderweave.rendering.spi;

import cn.hbads.renderweave.rendering.api.Evaluator.RenderRequestId;

import java.util.Objects;

/**
 * CapabilityState 短期存储 port（ADR-0044 §5）：closed 三操作
 * save / load(fingerprint replay | conflict) / 固定 TTL 过期（不续期）。
 *
 * <p>app Adapter 加密落盘（server-only 秘密不明文入库）；Rendering 不碰 JDBC/加密实现。
 * state 由 Rendering.internal 序列化为 canonical sealed bytes，store 视为不透明载荷。
 */
public interface CapabilityStateStore {

    SaveOutcome save(SaveRequest request);

    LoadOutcome load(CapabilityStateId id, String evaluationFingerprint);

    /** store 返回的不透明短期记录身份。 */
    record CapabilityStateId(String value) {
        public CapabilityStateId {
            Objects.requireNonNull(value, "value");
            if (value.isBlank() || value.length() > 256) {
                throw new IllegalArgumentException("capabilityStateId must be non-blank and at most 256 chars");
            }
        }
    }

    /**
     * 线性化创建提交：短期记录绑定 renderRequestId 与 evaluationFingerprint。
     * 同 key 同 fingerprint → 重放；异 fingerprint → 冲突。expiresAt 覆盖 Render deadline +
     * 固定重试裕量，一经签发永不续期。
     */
    record SaveRequest(
            RenderRequestId renderRequestId,
            String evaluationFingerprint,
            byte[] sealedState,
            long issuedAtEpochSecond,
            long expiresAtEpochSecond
    ) {
        public SaveRequest {
            Objects.requireNonNull(renderRequestId, "renderRequestId");
            Objects.requireNonNull(evaluationFingerprint, "evaluationFingerprint");
            Objects.requireNonNull(sealedState, "sealedState");
            if (evaluationFingerprint.isBlank() || evaluationFingerprint.length() > 256) {
                throw new IllegalArgumentException("evaluationFingerprint must be non-blank and at most 256 chars");
            }
            if (sealedState.length == 0) {
                throw new IllegalArgumentException("sealedState must not be empty");
            }
            if (expiresAtEpochSecond <= issuedAtEpochSecond) {
                throw new IllegalArgumentException("expiresAt must be after issuedAt");
            }
            sealedState = sealedState.clone();
        }

        public byte[] sealedState() {
            return sealedState.clone();
        }
    }

    sealed interface SaveOutcome
            permits SaveOutcome.Stored, SaveOutcome.Replayed, SaveOutcome.FingerprintConflict,
                    SaveOutcome.SaveUnavailable {

        /** 新记录落盘成功。 */
        record Stored(CapabilityStateId id) implements SaveOutcome {
            public Stored {
                Objects.requireNonNull(id, "id");
            }
        }

        /** 同 key 同 fingerprint 的幂等重放：返回既有记录身份，绝不重新采样。 */
        record Replayed(CapabilityStateId id) implements SaveOutcome {
            public Replayed {
                Objects.requireNonNull(id, "id");
            }
        }

        /** 同 key 异 fingerprint：恢复冲突，本次 Evaluation 必须失败。 */
        record FingerprintConflict() implements SaveOutcome {
        }

        record SaveUnavailable() implements SaveOutcome {
        }
    }

    sealed interface LoadOutcome
            permits LoadOutcome.Loaded, LoadOutcome.LoadFingerprintConflict, LoadOutcome.Missing,
                    LoadOutcome.LoadUnavailable {

        record Loaded(byte[] sealedState, long expiresAtEpochSecond) implements LoadOutcome {
            public Loaded {
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

        record LoadFingerprintConflict() implements LoadOutcome {
        }

        /** 记录不存在或已按固定 TTL 过期（过期等价不存在）。 */
        record Missing() implements LoadOutcome {
        }

        record LoadUnavailable() implements LoadOutcome {
        }
    }
}
