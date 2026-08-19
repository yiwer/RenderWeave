package cn.hbads.renderweave.template.internal;

import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.template.api.TemplateApplication;
import cn.hbads.renderweave.template.spi.OwnerScopeAuthority;
import cn.hbads.renderweave.template.spi.TemplatePersistence;

import java.util.Objects;

final class AdmittedCreateCommit implements TemplatePersistence.CreateCommit {
    private final TemplateApplication.TemplateId templateId;
    private final OwnerScopeAuthority.OwnerScope ownerScope;
    private final StaticSchemaRef staticSchema;
    private final byte[] canonicalDesignDslUtf8;
    private final String contentHash;

    AdmittedCreateCommit(
            TemplateApplication.TemplateId templateId,
            OwnerScopeAuthority.OwnerScope ownerScope,
            StaticSchemaRef staticSchema,
            byte[] canonicalDesignDslUtf8,
            String contentHash
    ) {
        this.templateId = Objects.requireNonNull(templateId, "templateId");
        this.ownerScope = Objects.requireNonNull(ownerScope, "ownerScope");
        this.staticSchema = Objects.requireNonNull(staticSchema, "staticSchema");
        this.canonicalDesignDslUtf8 = Objects.requireNonNull(
                canonicalDesignDslUtf8,
                "canonicalDesignDslUtf8"
        ).clone();
        this.contentHash = Objects.requireNonNull(contentHash, "contentHash");
    }

    @Override
    public TemplateApplication.TemplateId templateId() {
        return templateId;
    }

    @Override
    public OwnerScopeAuthority.OwnerScope ownerScope() {
        return ownerScope;
    }

    @Override
    public StaticSchemaRef staticSchema() {
        return staticSchema;
    }

    @Override
    public long revision() {
        return 0;
    }

    @Override
    public byte[] canonicalDesignDslUtf8() {
        return canonicalDesignDslUtf8.clone();
    }

    @Override
    public String contentHash() {
        return contentHash;
    }

    @Override
    public TemplateApplication.Readiness readiness() {
        return TemplateApplication.Readiness.READY;
    }
}
