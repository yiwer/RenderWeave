package cn.hbads.renderweave.template.internal;

import cn.hbads.renderweave.schema.api.StaticSchemaAuthority;
import cn.hbads.renderweave.template.api.AssetReferenceAuthority;
import cn.hbads.renderweave.template.api.TemplateApplication;
import cn.hbads.renderweave.template.api.TemplateClosureAuthority;
import cn.hbads.renderweave.template.api.TemplateReadinessAuthority;
import cn.hbads.renderweave.template.spi.DependencyResolution;
import cn.hbads.renderweave.template.spi.OwnerScopeAuthority;
import cn.hbads.renderweave.template.spi.TemplatePersistence;

import java.util.Objects;

/** Exact app-only composition root; it does not add a Template product behavior. */
public final class TemplateModule {
    private TemplateModule() {
    }

    public static TemplateApplication application(
            OwnerScopeAuthority ownerScopes,
            TemplatePersistence persistence,
            StaticSchemaAuthority schemas,
            DependencyResolution dependencyResolution
    ) {
        return new CanonicalTemplateApplication(
                new CanonicalDesignDslAuthority(),
                Objects.requireNonNull(ownerScopes, "ownerScopes"),
                Objects.requireNonNull(persistence, "persistence"),
                Objects.requireNonNull(schemas, "schemas"),
                Objects.requireNonNull(dependencyResolution, "dependencyResolution")
        );
    }

    /** Template-owned current-only proof for Asset delete prechecks. */
    public static AssetReferenceAuthority assetReferenceAuthority(
            TemplatePersistence persistence
    ) {
        return new CanonicalAssetReferenceAuthority(Objects.requireNonNull(persistence, "persistence"));
    }

    /** Template-owned render-only closure freeze consumed by Rendering (ADR-0044 §1). */
    public static TemplateClosureAuthority closureAuthority(
            TemplatePersistence persistence
    ) {
        return new CanonicalTemplateClosureAuthority(Objects.requireNonNull(persistence, "persistence"));
    }

    /** Template-owned canonical DesignDSL semantic interpretation consumed by Rendering. */
    public static cn.hbads.renderweave.template.api.DesignSemanticAuthority designSemanticAuthority() {
        return new CanonicalDesignSemanticAuthority();
    }

    /** Template-owned DesignDSL admission authority (assembly seam for consumers). */
    public static cn.hbads.renderweave.template.api.DesignDslAuthority designDslAuthority() {
        return new CanonicalDesignDslAuthority();
    }

    /** System-level readiness recheck consumed by the app STALE consumer. */
    public static TemplateReadinessAuthority readinessAuthority(
            TemplatePersistence persistence,
            DependencyResolution dependencyResolution
    ) {
        return new CanonicalTemplateReadinessAuthority(
                Objects.requireNonNull(persistence, "persistence"),
                Objects.requireNonNull(dependencyResolution, "dependencyResolution")
        );
    }
}
