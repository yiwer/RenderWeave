package cn.hbads.renderweave.asset.api;

import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.AssetKind;
import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.TechnicalDescriptor;
import cn.hbads.renderweave.asset.api.AssetApplication.AssetId;
import cn.hbads.renderweave.asset.api.AssetApplication.OwnerScope;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;

/** Rendering-only Asset current-selection and exact fetch-lease authority. */
public interface AssetResolver {

    String ACCEPTANCE_PROFILE_ID = "renderweave-asset-acceptance/1.0";

    PrecheckOutcome precheck(PrecheckRequest request);

    ResolveOutcome resolve(ResolveRequest request);

    record PrecheckRequest(OwnerScope ownerScope, AssetId assetId, AssetKind expectedKind) {
        public PrecheckRequest {
            Objects.requireNonNull(ownerScope, "ownerScope");
            Objects.requireNonNull(assetId, "assetId");
            Objects.requireNonNull(expectedKind, "expectedKind");
        }
    }

    record ResolveRequest(
            String renderRequestId,
            OwnerScope ownerScope,
            String resourceId,
            AssetId assetId,
            AssetKind expectedKind,
            String rendererAudience,
            long renderDeadlineEpochMilli
    ) {
        private static final Pattern RESOURCE_ID = Pattern.compile("^rwres_[0-9a-f]{64}$");

        public ResolveRequest {
            renderRequestId = requireBounded(renderRequestId, 256, "renderRequestId");
            Objects.requireNonNull(ownerScope, "ownerScope");
            if (resourceId == null || !RESOURCE_ID.matcher(resourceId).matches()) {
                throw new IllegalArgumentException("resourceId must be rwres_ + 64 lowercase hex chars");
            }
            Objects.requireNonNull(assetId, "assetId");
            Objects.requireNonNull(expectedKind, "expectedKind");
            rendererAudience = requireBounded(rendererAudience, 256, "rendererAudience");
            if (renderDeadlineEpochMilli <= 0) {
                throw new IllegalArgumentException("renderDeadlineEpochMilli must be positive");
            }
        }
    }

    enum Rejection {
        SCOPE_MISMATCH,
        NOT_FOUND,
        DELETED,
        KIND_MISMATCH
    }

    sealed interface PrecheckOutcome permits PrecheckPassed, PrecheckRejected,
            PrecheckUnavailable {
    }

    record PrecheckPassed() implements PrecheckOutcome {
    }

    record PrecheckRejected(Rejection reason) implements PrecheckOutcome {
        public PrecheckRejected {
            Objects.requireNonNull(reason, "reason");
        }
    }

    record PrecheckUnavailable() implements PrecheckOutcome {
    }

    record FetchLease(String fetchUrl, long expiresAtEpochSecond) {
        public FetchLease {
            fetchUrl = requireBounded(fetchUrl, 2_048, "fetchUrl");
            if (fetchUrl.getBytes(StandardCharsets.UTF_8).length > 2_048) {
                throw new IllegalArgumentException("fetchUrl must be at most 2048 UTF-8 bytes");
            }
            URI uri;
            try {
                uri = URI.create(fetchUrl);
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("fetchUrl must be a canonical HTTPS URL", invalid);
            }
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("fetchUrl must be a canonical HTTPS URL");
            }
            if (expiresAtEpochSecond <= 0) {
                throw new IllegalArgumentException("expiresAtEpochSecond must be positive");
            }
        }
    }

    record ResolvedAsset(
            String resourceId,
            AssetId assetId,
            long contentVersion,
            AssetKind kind,
            String sha256,
            String mediaType,
            long byteLength,
            String acceptanceProfileId,
            TechnicalDescriptor technicalDescriptor,
            FetchLease lease
    ) {
        private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

        public ResolvedAsset {
            Objects.requireNonNull(resourceId, "resourceId");
            Objects.requireNonNull(assetId, "assetId");
            if (contentVersion < 0) {
                throw new IllegalArgumentException("contentVersion must not be negative");
            }
            Objects.requireNonNull(kind, "kind");
            if (sha256 == null || !SHA256.matcher(sha256).matches()) {
                throw new IllegalArgumentException("sha256 must be 64 lowercase hex chars");
            }
            mediaType = requireBounded(mediaType, 128, "mediaType");
            if (byteLength <= 0) {
                throw new IllegalArgumentException("byteLength must be positive");
            }
            acceptanceProfileId = requireBounded(
                    acceptanceProfileId, 128, "acceptanceProfileId");
            Objects.requireNonNull(technicalDescriptor, "technicalDescriptor");
            Objects.requireNonNull(lease, "lease");
        }
    }

    sealed interface ResolveOutcome permits Resolved, ResolveRejected, ResolveConflict,
            ResolveTimedOut, ResolveUnavailable {
    }

    record Resolved(ResolvedAsset asset) implements ResolveOutcome {
        public Resolved {
            Objects.requireNonNull(asset, "asset");
        }
    }

    record ResolveRejected(Rejection reason) implements ResolveOutcome {
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

    private static String requireBounded(String value, int max, String name) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(name + " must be non-blank and at most " + max + " chars");
        }
        return value;
    }
}
