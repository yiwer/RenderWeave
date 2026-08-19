package cn.hbads.renderweave.template.internal;

import cn.hbads.renderweave.schema.api.StaticSchemaAuthority;
import cn.hbads.renderweave.template.api.TemplateApplication;
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
            StaticSchemaAuthority schemas
    ) {
        return new CanonicalTemplateApplication(
                new CanonicalDesignDslAuthority(),
                Objects.requireNonNull(ownerScopes, "ownerScopes"),
                Objects.requireNonNull(persistence, "persistence"),
                Objects.requireNonNull(schemas, "schemas")
        );
    }
}
