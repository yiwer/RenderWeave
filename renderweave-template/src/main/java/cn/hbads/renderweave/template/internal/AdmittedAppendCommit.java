package cn.hbads.renderweave.template.internal;

import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.template.api.TemplateApplication;
import cn.hbads.renderweave.template.api.TemplateDependencyProjection;
import cn.hbads.renderweave.template.spi.OwnerScopeAuthority;
import cn.hbads.renderweave.template.spi.TemplatePersistence;
import cn.hbads.renderweave.template.spi.TemplateDependencySnapshot;

import java.util.Objects;

final class AdmittedAppendCommit implements TemplatePersistence.AppendCommit {
    private final TemplateApplication.TemplateId templateId;
    private final OwnerScopeAuthority.OwnerScope ownerScope;
    private final StaticSchemaRef staticSchema;
    private final long expectedRevision;
    private final long nextRevision;
    private final byte[] canonicalDesignDslUtf8;
    private final String contentHash;
    private final TemplateApplication.Readiness readiness;
    private final TemplateDependencyProjection projection;
    private final TemplateDependencySnapshot dependencySnapshot;

    AdmittedAppendCommit(
            TemplateApplication.TemplateId templateId,
            OwnerScopeAuthority.OwnerScope ownerScope,
            StaticSchemaRef staticSchema,
            long expectedRevision,
            byte[] canonicalDesignDslUtf8,
            String contentHash,
            TemplateApplication.Readiness readiness,
            TemplateDependencyProjection projection,
            TemplateDependencySnapshot dependencySnapshot
    ) {
        this.templateId = Objects.requireNonNull(templateId, "templateId");
        this.ownerScope = Objects.requireNonNull(ownerScope, "ownerScope");
        this.staticSchema = Objects.requireNonNull(staticSchema, "staticSchema");
        if (expectedRevision < 0 || expectedRevision == Long.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "expectedRevision must be non-negative and have a successor"
            );
        }
        this.expectedRevision = expectedRevision;
        this.nextRevision = expectedRevision + 1;
        this.canonicalDesignDslUtf8 = Objects.requireNonNull(
                canonicalDesignDslUtf8,
                "canonicalDesignDslUtf8"
        ).clone();
        this.contentHash = Objects.requireNonNull(contentHash, "contentHash");
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.projection = Objects.requireNonNull(projection, "projection");
        this.dependencySnapshot = Objects.requireNonNull(
                dependencySnapshot, "dependencySnapshot");
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
    public long expectedRevision() {
        return expectedRevision;
    }

    @Override
    public long nextRevision() {
        return nextRevision;
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
        return readiness;
    }

    @Override
    public TemplateDependencyProjection projection() {
        return projection;
    }

    @Override
    public TemplateDependencySnapshot dependencySnapshot() {
        return dependencySnapshot;
    }
}
