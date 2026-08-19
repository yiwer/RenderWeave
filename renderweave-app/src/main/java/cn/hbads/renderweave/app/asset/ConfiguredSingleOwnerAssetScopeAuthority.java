package cn.hbads.renderweave.app.asset;

import cn.hbads.renderweave.asset.api.AssetApplication;
import cn.hbads.renderweave.asset.spi.AssetOwnerScopeAuthority;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Dev/test single-owner adapter: one config-fixed ownerScope with a capability set. */
public class ConfiguredSingleOwnerAssetScopeAuthority implements AssetOwnerScopeAuthority {
    private static final int MAX_OUTSTANDING_RECHECKS = 4096;

    private final AssetApplication.OwnerScope ownerScope;
    private final Set<String> capabilities;
    private final Map<String, String> issuedRechecks = Collections.synchronizedMap(
            new LinkedHashMap<>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > MAX_OUTSTANDING_RECHECKS;
                }
            }
    );

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
                issue("asset.create"),
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
        String capability = switch (operation) {
            case READ -> "asset.read";
            case UPDATE -> "asset.update";
            case DELETE -> "asset.delete";
            case RESTORE -> "asset.restore";
        };
        if (!capabilities.contains(capability)) {
            return new ExistingForbidden();
        }
        return new ExistingGranted(
                capabilities.contains("asset.read") ? Disclosure.READABLE : Disclosure.OPAQUE,
                issue(capability),
                ownerScope.value()
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
        var capability = issuedRechecks.remove(identity.value());
        if (capability == null) {
            return new RecheckDenied();
        }
        return capabilities.contains(capability)
                ? new RecheckGranted()
                : new RecheckDenied();
    }

    private RecheckIdentity issue(String capability) {
        var identity = UUID.randomUUID().toString();
        issuedRechecks.put(identity, capability);
        return new RecheckIdentity(identity);
    }
}
