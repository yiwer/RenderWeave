package cn.hbads.renderweave.asset.internal;

import cn.hbads.renderweave.asset.api.AssetApplication;
import cn.hbads.renderweave.asset.spi.AssetBlobPersistence;
import cn.hbads.renderweave.asset.spi.AssetOwnerScopeAuthority;
import cn.hbads.renderweave.asset.spi.AssetPersistence;

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
            AssetBlobPersistence blobs
    ) {
        Objects.requireNonNull(ownerScopeAuthority, "ownerScopeAuthority");
        Objects.requireNonNull(persistence, "persistence");
        Objects.requireNonNull(blobs, "blobs");
        return new CanonicalAssetApplication(ownerScopeAuthority, persistence, blobs);
    }
}
