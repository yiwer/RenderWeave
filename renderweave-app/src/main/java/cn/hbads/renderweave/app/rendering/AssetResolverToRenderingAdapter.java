package cn.hbads.renderweave.app.rendering;

import cn.hbads.renderweave.asset.api.AssetResolver;
import cn.hbads.renderweave.rendering.spi.AssetResolutionPort;

import java.util.Objects;

/** App-owned anti-corruption bridge from Asset facts into Rendering's consumer seam. */
final class AssetResolverToRenderingAdapter implements AssetResolutionPort {

    private final AssetResolver resolver;

    AssetResolverToRenderingAdapter(AssetResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    @Override
    public PrecheckOutcome precheckAdmission(
            cn.hbads.renderweave.asset.api.AssetApplication.OwnerScope ownerScope,
            cn.hbads.renderweave.asset.api.AssetApplication.AssetId assetId,
            cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.AssetKind expectedKind
    ) {
        var outcome = Objects.requireNonNull(resolver.precheck(
                new AssetResolver.PrecheckRequest(ownerScope, assetId, expectedKind)),
                "AssetResolver returned a null precheck outcome");
        if (outcome instanceof AssetResolver.PrecheckPassed) {
            return new PrecheckOutcome.PrecheckPassed();
        }
        if (outcome instanceof AssetResolver.PrecheckRejected rejected) {
            return new PrecheckOutcome.PrecheckRejected(map(rejected.reason()));
        }
        if (outcome instanceof AssetResolver.PrecheckUnavailable) {
            return new PrecheckOutcome.PrecheckUnavailable();
        }
        throw new IllegalStateException("unrecognized AssetResolver precheck outcome");
    }

    @Override
    public ResolveOutcome resolve(ResolveRequest request) {
        Objects.requireNonNull(request, "request");
        var outcome = Objects.requireNonNull(resolver.resolve(new AssetResolver.ResolveRequest(
                request.renderRequestId().value(),
                request.ownerScope(),
                request.resourceId().value(),
                request.assetId(),
                request.expectedKind(),
                request.rendererAudience().value(),
                request.deadlineEpochMilli())),
                "AssetResolver returned a null resolve outcome");
        if (outcome instanceof AssetResolver.Resolved resolved) {
            var asset = resolved.asset();
            if (!asset.resourceId().equals(request.resourceId().value())
                    || !asset.assetId().equals(request.assetId())
                    || asset.kind() != request.expectedKind()) {
                return new ResolveOutcome.ResolveUnavailable();
            }
            return new ResolveOutcome.Resolved(new ResolvedAssetFact(
                    Long.toString(asset.contentVersion()),
                    asset.sha256(),
                    asset.mediaType(),
                    asset.byteLength(),
                    asset.acceptanceProfileId(),
                    asset.technicalDescriptor(),
                    asset.lease().fetchUrl(),
                    asset.lease().expiresAtEpochSecond()));
        }
        if (outcome instanceof AssetResolver.ResolveRejected rejected) {
            return new ResolveOutcome.ResolveRejected(map(rejected.reason()));
        }
        if (outcome instanceof AssetResolver.ResolveConflict) {
            return new ResolveOutcome.ResolveConflict();
        }
        if (outcome instanceof AssetResolver.ResolveTimedOut) {
            return new ResolveOutcome.ResolveTimedOut();
        }
        if (outcome instanceof AssetResolver.ResolveUnavailable) {
            return new ResolveOutcome.ResolveUnavailable();
        }
        throw new IllegalStateException("unrecognized AssetResolver resolve outcome");
    }

    private static AdmissionRejection map(AssetResolver.Rejection rejection) {
        return switch (rejection) {
            case SCOPE_MISMATCH -> AdmissionRejection.SCOPE_MISMATCH;
            case NOT_FOUND -> AdmissionRejection.NOT_FOUND;
            case DELETED -> AdmissionRejection.NOT_ACTIVE;
            case KIND_MISMATCH -> AdmissionRejection.KIND_MISMATCH;
        };
    }
}
