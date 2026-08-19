package cn.hbads.renderweave.app.asset;

import cn.hbads.renderweave.asset.api.AssetApplication;
import cn.hbads.renderweave.asset.spi.AssetOwnerScopeAuthority;

import java.util.Set;

/** Dev/test single-owner adapter: one config-fixed ownerScope with a capability set. */
public class ConfiguredSingleOwnerAssetScopeAuthority implements AssetOwnerScopeAuthority {
    private final AssetApplication.OwnerScope ownerScope;
    private final Set<String> capabilities;

    ConfiguredSingleOwnerAssetScopeAuthority(
            AssetApplication.OwnerScope ownerScope,
            Set<String> capabilities
    ) {
        this.ownerScope = ownerScope;
        this.capabilities = Set.copyOf(capabilities);
    }

    @Override
    public CreateDecision authorizeCreate(AssetApplication.InvocationRef invocation) {
        if (ownerScope.value().isBlank() || !capabilities.contains("asset.create")) {
            return new CreateDenied();
        }
        return new CreateGranted(
                ownerScope,
                new RecheckIdentity(ownerScope.value()),
                capabilities.contains("asset.read") ? Disclosure.READABLE : Disclosure.OPAQUE
        );
    }

    @Override
    public ExistingDecision authorizeExisting(
            AssetApplication.InvocationRef invocation,
            AssetApplication.OwnerScope storedOwnerScope,
            AssetOperation operation
    ) {
        if (!ownerScope.equals(storedOwnerScope)) {
            return new ExistingHidden();
        }
        String capability = operation == AssetOperation.READ ? "asset.read" : "asset.update";
        if (!capabilities.contains(capability)) {
            return new ExistingForbidden();
        }
        return new ExistingGranted(
                capabilities.contains("asset.read") ? Disclosure.READABLE : Disclosure.OPAQUE,
                new RecheckIdentity(ownerScope.value())
        );
    }

    @Override
    public CatalogDecision authorizeCatalog(AssetApplication.InvocationRef invocation) {
        if (ownerScope.value().isBlank() || !capabilities.contains("asset.read")) {
            return new CatalogForbidden();
        }
        return new CatalogGranted(ownerScope);
    }

    @Override
    public RecheckDecision recheck(RecheckIdentity identity) {
        if (!ownerScope.value().equals(identity.value())
                || (!capabilities.contains("asset.create") && !capabilities.contains("asset.update"))) {
            return new RecheckDenied();
        }
        return new RecheckGranted();
    }
}
