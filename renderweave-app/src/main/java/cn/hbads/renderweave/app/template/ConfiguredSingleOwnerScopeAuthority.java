package cn.hbads.renderweave.app.template;

import cn.hbads.renderweave.template.api.TemplateApplication;
import cn.hbads.renderweave.template.spi.OwnerScopeAuthority;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class ConfiguredSingleOwnerScopeAuthority implements OwnerScopeAuthority {
    static final String CREATE = "template.create";
    static final String READ = "template.read";
    static final String UPDATE = "template.update";
    private static final int MAX_OUTSTANDING_RECHECKS = 4096;
    private static final Set<String> KNOWN = Set.of(CREATE, READ, UPDATE);

    private final OwnerScope ownerScope;
    private final Set<String> capabilities;
    private final Map<String, String> issuedRechecks = Collections.synchronizedMap(
            new LinkedHashMap<>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > MAX_OUTSTANDING_RECHECKS;
                }
            }
    );

    ConfiguredSingleOwnerScopeAuthority(String ownerScope, Set<String> capabilities) {
        this.ownerScope = new OwnerScope(ownerScope);
        if (!KNOWN.containsAll(capabilities)) {
            throw new IllegalArgumentException("unknown Template single-owner capability");
        }
        this.capabilities = Set.copyOf(capabilities);
    }

    @Override
    public CreateDecision authorizeCreate(TemplateApplication.TemplateInvocationRef invocation) {
        if (!capabilities.contains(CREATE)) {
            return new CreateDenied();
        }
        return new CreateGranted(ownerScope, issue(CREATE), disclosure());
    }

    @Override
    public CatalogDecision authorizeCatalog(
            TemplateApplication.TemplateInvocationRef invocation
    ) {
        if (!capabilities.contains(READ)) {
            return new CatalogDenied();
        }
        return new CatalogGranted(ownerScope);
    }

    @Override
    public ExistingDecision authorizeExisting(
            TemplateApplication.TemplateInvocationRef invocation,
            OwnerScope storedOwnerScope,
            ExistingOperation operation
    ) {
        if (!ownerScope.equals(storedOwnerScope)) {
            return new ExistingHidden();
        }
        var required = switch (operation) {
            case READ -> READ;
            case UPDATE -> UPDATE;
        };
        if (!capabilities.contains(required)) {
            return capabilities.contains(READ)
                    ? new ExistingForbidden()
                    : new ExistingHidden();
        }
        return new ExistingGranted(
                disclosure(),
                operation == ExistingOperation.READ
                        ? new RecheckIdentity(UUID.randomUUID().toString())
                        : issue(required),
                ownerScope.value()
        );
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

    private Disclosure disclosure() {
        return capabilities.contains(READ) ? Disclosure.READABLE : Disclosure.OPAQUE;
    }

    private RecheckIdentity issue(String capability) {
        var identity = UUID.randomUUID().toString();
        issuedRechecks.put(identity, capability);
        return new RecheckIdentity(identity);
    }
}
