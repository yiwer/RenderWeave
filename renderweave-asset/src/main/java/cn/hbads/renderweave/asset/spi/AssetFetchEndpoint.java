package cn.hbads.renderweave.asset.spi;

import cn.hbads.renderweave.asset.api.AssetApplication.AssetId;

import java.util.Objects;
import java.util.regex.Pattern;

/** Asset-owned Port that materializes one renderer-only exact-content fetch URL. */
public interface AssetFetchEndpoint {

    FetchIssueOutcome issue(IssueRequest request);

    record IssueRequest(
            String leaseHandle,
            String requestFingerprint,
            String renderRequestId,
            String resourceId,
            AssetId assetId,
            long contentVersion,
            String sha256,
            long byteLength,
            String rendererAudience,
            long expiresAtEpochSecond
    ) {
        private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

        public IssueRequest {
            leaseHandle = requireBounded(leaseHandle, 128, "leaseHandle");
            if (requestFingerprint == null || !SHA256.matcher(requestFingerprint).matches()) {
                throw new IllegalArgumentException("requestFingerprint must be 64 lowercase hex chars");
            }
            renderRequestId = requireBounded(renderRequestId, 256, "renderRequestId");
            resourceId = requireBounded(resourceId, 70, "resourceId");
            Objects.requireNonNull(assetId, "assetId");
            if (contentVersion < 0 || byteLength <= 0 || expiresAtEpochSecond <= 0) {
                throw new IllegalArgumentException("invalid exact-content lease claims");
            }
            if (sha256 == null || !SHA256.matcher(sha256).matches()) {
                throw new IllegalArgumentException("sha256 must be 64 lowercase hex chars");
            }
            rendererAudience = requireBounded(rendererAudience, 256, "rendererAudience");
        }
    }

    sealed interface FetchIssueOutcome permits FetchIssued, FetchUnavailable {
    }

    record FetchIssued(String fetchUrl) implements FetchIssueOutcome {
        public FetchIssued {
            if (fetchUrl == null || fetchUrl.isBlank()) {
                throw new IllegalArgumentException("fetchUrl must be non-blank");
            }
        }
    }

    record FetchUnavailable() implements FetchIssueOutcome {
    }

    private static String requireBounded(String value, int max, String name) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(name + " must be non-blank and at most " + max + " chars");
        }
        return value;
    }
}
