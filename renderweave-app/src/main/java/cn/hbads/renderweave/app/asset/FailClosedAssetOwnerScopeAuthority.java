package cn.hbads.renderweave.app.asset;

import cn.hbads.renderweave.asset.api.AssetApplication;
import cn.hbads.renderweave.asset.spi.AssetOwnerScopeAuthority;

/** Production default: no trusted Host adapter, every operation fails closed. */
public class FailClosedAssetOwnerScopeAuthority implements AssetOwnerScopeAuthority {
    @Override
    public CreateDecision authorizeCreate(AssetApplication.InvocationRef invocation) {
        return new CreateUnavailable();
    }

    @Override
    public ExistingDecision authorizeExisting(
            AssetApplication.InvocationRef invocation,
            AssetApplication.OwnerScope storedOwnerScope,
            AssetOperation operation
    ) {
        return new ExistingUnavailable();
    }

    @Override
    public CatalogDecision authorizeCatalog(AssetApplication.InvocationRef invocation) {
        return new CatalogUnavailable();
    }

    @Override
    public RecheckDecision recheck(RecheckIdentity identity) {
        return new RecheckUnavailable();
    }
}
