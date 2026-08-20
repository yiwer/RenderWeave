package cn.hbads.renderweave.asset.internal;

import cn.hbads.renderweave.asset.api.AssetApplication;
import cn.hbads.renderweave.asset.api.AssetResolver;
import cn.hbads.renderweave.asset.spi.AssetBlobPersistence;
import cn.hbads.renderweave.asset.spi.AssetFetchEndpoint;
import cn.hbads.renderweave.asset.spi.AssetOwnerScopeAuthority;
import cn.hbads.renderweave.asset.spi.AssetPersistence;
import cn.hbads.renderweave.asset.spi.AssetReferencePort;

import java.time.Clock;
import java.util.Objects;

/**
 * The one app-visible internal type: a narrow assembly factory that injects the
 * app-owned adapters into the internal implementation (ADR-0043 §1).
 */
public final class AssetModule {

    private AssetModule() {
    }

    public static AssetApplication application(
            AssetOwnerScopeAuthority ownerScopeAuthority,
            AssetPersistence persistence,
            AssetBlobPersistence blobs,
            AssetReferencePort referencePort
    ) {
        Objects.requireNonNull(ownerScopeAuthority, "ownerScopeAuthority");
        Objects.requireNonNull(persistence, "persistence");
        Objects.requireNonNull(blobs, "blobs");
        Objects.requireNonNull(referencePort, "referencePort");
        return new CanonicalAssetApplication(
                ownerScopeAuthority, persistence, blobs, referencePort);
    }

    public static AssetResolver resolver(
            AssetPersistence persistence,
            AssetFetchEndpoint fetchEndpoint,
            Clock clock
    ) {
        Objects.requireNonNull(persistence, "persistence");
        Objects.requireNonNull(fetchEndpoint, "fetchEndpoint");
        Objects.requireNonNull(clock, "clock");
        return new CanonicalAssetResolver(persistence, fetchEndpoint, clock);
    }
}
