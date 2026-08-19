package cn.hbads.renderweave.template.spi;

import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.template.api.TemplateApplication;
import cn.hbads.renderweave.template.api.TemplateDependencyProjection;

/** Transaction-sized persistence seam; it is not a generic repository. */
public interface TemplatePersistence {

    LocateOutcome locate(TemplateApplication.TemplateId templateId);

    LoadCurrentOutcome loadCurrent(TemplateApplication.TemplateId templateId);

    CreateOutcome create(CreateCommit commit);

    AppendOutcome append(AppendCommit commit);

    /** Current-only TemplateUse targets of a template's current (for DAG recheck). */
    LoadUseTargetsOutcome loadUseTargets(TemplateApplication.TemplateId templateId);

    /** Current-only reverse lookup: ACTIVE templates whose current references an asset. */
    FindAssetReferencesOutcome findAssetReferences(String assetId);

    /** Persist a recomputed readiness for the current revision (recheck path). */
    UpdateReadinessOutcome updateReadiness(
            TemplateApplication.TemplateId templateId,
            long currentRevision,
            TemplateApplication.Readiness readiness
    );

    sealed interface LoadUseTargetsOutcome permits
            UseTargetsLoaded,
            UseTargetsNotFound,
            UseTargetsUnavailable {
    }

    record UseTargetsLoaded(java.util.List<String> targetTemplateIds)
            implements LoadUseTargetsOutcome {
        public UseTargetsLoaded {
            java.util.Objects.requireNonNull(targetTemplateIds, "targetTemplateIds");
        }
    }

    record UseTargetsNotFound() implements LoadUseTargetsOutcome {
    }

    record UseTargetsUnavailable() implements LoadUseTargetsOutcome {
    }

    sealed interface FindAssetReferencesOutcome permits
            AssetReferencesLoaded,
            AssetReferencesUnavailable {
    }

    record AssetReferencesLoaded(java.util.List<TemplateApplication.TemplateId> templateIds)
            implements FindAssetReferencesOutcome {
        public AssetReferencesLoaded {
            java.util.Objects.requireNonNull(templateIds, "templateIds");
        }
    }

    record AssetReferencesUnavailable() implements FindAssetReferencesOutcome {
    }

    sealed interface UpdateReadinessOutcome permits
            ReadinessUpdated,
            ReadinessNotFound,
            ReadinessRevisionConflict,
            ReadinessUnavailable {
    }

    record ReadinessUpdated() implements UpdateReadinessOutcome {
    }

    record ReadinessNotFound() implements UpdateReadinessOutcome {
    }

    record ReadinessRevisionConflict() implements UpdateReadinessOutcome {
    }

    record ReadinessUnavailable() implements UpdateReadinessOutcome {
    }

    sealed interface LocateOutcome permits Located, LocateNotFound, LocateUnavailable {
    }

    record Located(TemplateMetadata metadata) implements LocateOutcome {
        public Located {
            java.util.Objects.requireNonNull(metadata, "metadata");
        }
    }

    record LocateNotFound() implements LocateOutcome {
    }

    record LocateUnavailable() implements LocateOutcome {
    }

    sealed interface LoadCurrentOutcome permits
            CurrentLoaded,
            CurrentNotFound,
            CurrentLoadUnavailable {
    }

    record CurrentLoaded(StoredCurrent current) implements LoadCurrentOutcome {
        public CurrentLoaded {
            java.util.Objects.requireNonNull(current, "current");
        }
    }

    record CurrentNotFound() implements LoadCurrentOutcome {
    }

    record CurrentLoadUnavailable() implements LoadCurrentOutcome {
    }

    record TemplateMetadata(
            TemplateApplication.TemplateId templateId,
            OwnerScopeAuthority.OwnerScope ownerScope,
            StaticSchemaRef staticSchema,
            long currentRevision,
            Lifecycle lifecycle
    ) {
        public TemplateMetadata {
            java.util.Objects.requireNonNull(templateId, "templateId");
            java.util.Objects.requireNonNull(ownerScope, "ownerScope");
            java.util.Objects.requireNonNull(staticSchema, "staticSchema");
            if (currentRevision < 0) {
                throw new IllegalArgumentException("currentRevision must not be negative");
            }
            java.util.Objects.requireNonNull(lifecycle, "lifecycle");
        }
    }

    final class StoredCurrent {
        private final TemplateMetadata metadata;
        private final byte[] storedJsonUtf8;
        private final byte[] canonicalDesignDslUtf8;
        private final String contentHash;
        private final TemplateApplication.Readiness readiness;

        public StoredCurrent(
                TemplateMetadata metadata,
                byte[] storedJsonUtf8,
                byte[] canonicalDesignDslUtf8,
                String contentHash,
                TemplateApplication.Readiness readiness
        ) {
            this.metadata = java.util.Objects.requireNonNull(metadata, "metadata");
            this.storedJsonUtf8 = java.util.Objects.requireNonNull(
                    storedJsonUtf8,
                    "storedJsonUtf8"
            ).clone();
            this.canonicalDesignDslUtf8 = java.util.Objects.requireNonNull(
                    canonicalDesignDslUtf8,
                    "canonicalDesignDslUtf8"
            ).clone();
            this.contentHash = java.util.Objects.requireNonNull(contentHash, "contentHash");
            this.readiness = java.util.Objects.requireNonNull(readiness, "readiness");
        }

        public TemplateMetadata metadata() {
            return metadata;
        }

        public byte[] storedJsonUtf8() {
            return storedJsonUtf8.clone();
        }

        public byte[] canonicalDesignDslUtf8() {
            return canonicalDesignDslUtf8.clone();
        }

        public String contentHash() {
            return contentHash;
        }

        public TemplateApplication.Readiness readiness() {
            return readiness;
        }
    }

    enum Lifecycle {
        ACTIVE,
        DELETED
    }

    interface CreateCommit {
        TemplateApplication.TemplateId templateId();

        OwnerScopeAuthority.OwnerScope ownerScope();

        StaticSchemaRef staticSchema();

        long revision();

        byte[] canonicalDesignDslUtf8();

        String contentHash();

        TemplateApplication.Readiness readiness();

        TemplateDependencyProjection projection();
    }

    sealed interface CreateOutcome permits Created, IdCollision, CreateUnavailable {
    }

    record Created() implements CreateOutcome {
    }

    record IdCollision() implements CreateOutcome {
    }

    record CreateUnavailable() implements CreateOutcome {
    }

    interface AppendCommit {
        TemplateApplication.TemplateId templateId();

        OwnerScopeAuthority.OwnerScope ownerScope();

        StaticSchemaRef staticSchema();

        long expectedRevision();

        long nextRevision();

        byte[] canonicalDesignDslUtf8();

        String contentHash();

        TemplateApplication.Readiness readiness();

        TemplateDependencyProjection projection();
    }

    sealed interface AppendOutcome permits
            Appended,
            AppendNotFound,
            AppendDeleted,
            AppendRevisionConflict,
            AppendUnavailable {
    }

    record Appended() implements AppendOutcome {
    }

    record AppendNotFound() implements AppendOutcome {
    }

    record AppendDeleted() implements AppendOutcome {
    }

    record AppendRevisionConflict(long currentRevision) implements AppendOutcome {
        public AppendRevisionConflict {
            if (currentRevision < 0) {
                throw new IllegalArgumentException("currentRevision must not be negative");
            }
        }
    }

    record AppendUnavailable() implements AppendOutcome {
    }
}
