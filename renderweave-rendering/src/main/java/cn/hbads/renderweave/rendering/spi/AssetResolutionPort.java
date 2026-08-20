package cn.hbads.renderweave.rendering.spi;

import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.AssetKind;
import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.TechnicalDescriptor;
import cn.hbads.renderweave.asset.api.AssetApplication.AssetId;
import cn.hbads.renderweave.asset.api.AssetApplication.OwnerScope;
import cn.hbads.renderweave.rendering.api.Evaluator.RenderRequestId;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Rendering-owned consumer seam：Evaluator 对 Asset 侧事实的唯一消费面（ADR-0041 consumer-owned
 * seam 模式，方向对偶于 T12b 的 Asset-owned {@code AssetReferencePort}）。
 *
 * <p>输入成分与 ADR-0043 冻结的 {@code AssetResolver} 对齐；生产 provider 与 app bridge 随
 * Ticket 13 物化，本票无生产 bridge 时含 Asset 的 Evaluation 以依赖不可用 fail-closed。
 * 预准入只检查 same ownerScope/存在/ACTIVE/immutable kind——不读用户 metadata、不 pin
 * contentVersion、不建立 Asset snapshot/锁/长期 lease。
 */
public interface AssetResolutionPort {

    PrecheckOutcome precheckAdmission(OwnerScope ownerScope, AssetId assetId, AssetKind expectedKind);

    ResolveOutcome resolve(ResolveRequest request);

    /** closed 预准入拒绝理由；产品语义统一折叠为 NOT_FOUND。 */
    enum AdmissionRejection {
        SCOPE_MISMATCH,
        NOT_FOUND,
        NOT_ACTIVE,
        KIND_MISMATCH
    }

    sealed interface PrecheckOutcome
            permits PrecheckOutcome.PrecheckPassed, PrecheckOutcome.PrecheckRejected,
                    PrecheckOutcome.PrecheckUnavailable {

        record PrecheckPassed() implements PrecheckOutcome {
        }

        record PrecheckRejected(AdmissionRejection reason) implements PrecheckOutcome {
            public PrecheckRejected {
                Objects.requireNonNull(reason, "reason");
            }
        }

        record PrecheckUnavailable() implements PrecheckOutcome {
        }
    }

    /**
     * 串行 resolve 请求：仅对真正流入物化 Node property 的 AssetRef occurrence 发起。
     *
     * @param resourceId       Rendering 按 occurrence 预分配的 {@code rwres_} 身份
     * @param rendererAudience 服务端冻结的 Renderer 受众身份（lease 作用域）
     * @param deadlineEpochMilli Render deadline（lease 签发的时间上界）
     */
    record ResolveRequest(
            RenderRequestId renderRequestId,
            OwnerScope ownerScope,
            ResourceId resourceId,
            AssetId assetId,
            AssetKind expectedKind,
            RendererAudience rendererAudience,
            long deadlineEpochMilli
    ) {
        public ResolveRequest {
            Objects.requireNonNull(renderRequestId, "renderRequestId");
            Objects.requireNonNull(ownerScope, "ownerScope");
            Objects.requireNonNull(resourceId, "resourceId");
            Objects.requireNonNull(assetId, "assetId");
            Objects.requireNonNull(expectedKind, "expectedKind");
            Objects.requireNonNull(rendererAudience, "rendererAudience");
            if (deadlineEpochMilli <= 0) {
                throw new IllegalArgumentException("deadlineEpochMilli must be positive");
            }
        }
    }

    /** 一对一 per occurrence 的已解析资源事实；同 assetId/contentVersion 也绝不合并。 */
    record ResolvedAssetFact(
            String contentVersion,
            String sha256,
            String mediaType,
            long byteLength,
            String acceptanceProfileId,
            TechnicalDescriptor technicalDescriptor,
            String fetchUrl,
            long leaseExpiresAtEpochSecond
    ) {
        public ResolvedAssetFact {
            Objects.requireNonNull(contentVersion, "contentVersion");
            Objects.requireNonNull(sha256, "sha256");
            Objects.requireNonNull(mediaType, "mediaType");
            Objects.requireNonNull(acceptanceProfileId, "acceptanceProfileId");
            Objects.requireNonNull(technicalDescriptor, "technicalDescriptor");
            Objects.requireNonNull(fetchUrl, "fetchUrl");
            if (contentVersion.isBlank() || sha256.isBlank() || mediaType.isBlank()
                    || acceptanceProfileId.isBlank() || fetchUrl.isBlank()) {
                throw new IllegalArgumentException("resolved asset facts must carry non-blank identities");
            }
            if (byteLength <= 0) {
                throw new IllegalArgumentException("byteLength must be positive");
            }
        }
    }

    sealed interface ResolveOutcome
            permits ResolveOutcome.Resolved, ResolveOutcome.ResolveRejected,
                    ResolveOutcome.ResolveConflict, ResolveOutcome.ResolveTimedOut,
                    ResolveOutcome.ResolveUnavailable {

        record Resolved(ResolvedAssetFact fact) implements ResolveOutcome {
            public Resolved {
                Objects.requireNonNull(fact, "fact");
            }
        }

        record ResolveRejected(AdmissionRejection reason) implements ResolveOutcome {
            public ResolveRejected {
                Objects.requireNonNull(reason, "reason");
            }
        }

        record ResolveConflict() implements ResolveOutcome {
        }

        record ResolveTimedOut() implements ResolveOutcome {
        }

        record ResolveUnavailable() implements ResolveOutcome {
        }
    }

    /** 请求级资源身份：{@code rwres_} + 64 位小写十六进制 SHA-256。 */
    record ResourceId(String value) {
        private static final Pattern FORMAT = Pattern.compile("^rwres_[0-9a-f]{64}$");

        public ResourceId {
            Objects.requireNonNull(value, "value");
            if (!FORMAT.matcher(value).matches()) {
                throw new IllegalArgumentException(
                        "resourceId must be rwres_ + 64 lowercase hex chars");
            }
        }
    }

    /** 服务端冻结的 Renderer 受众身份（lease 作用域成分）。 */
    record RendererAudience(String value) {
        public RendererAudience {
            Objects.requireNonNull(value, "value");
            if (value.isBlank() || value.length() > 256) {
                throw new IllegalArgumentException("rendererAudience must be non-blank and at most 256 chars");
            }
        }
    }
}
