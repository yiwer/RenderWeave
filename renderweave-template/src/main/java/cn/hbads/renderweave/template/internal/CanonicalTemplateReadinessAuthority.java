package cn.hbads.renderweave.template.internal;

import cn.hbads.renderweave.schema.api.StaticSchemaAuthority;
import cn.hbads.renderweave.template.api.DesignDslAuthority;
import cn.hbads.renderweave.template.api.TemplateApplication;
import cn.hbads.renderweave.template.api.TemplateReadinessAuthority;
import cn.hbads.renderweave.template.spi.DependencyResolution;
import cn.hbads.renderweave.template.spi.TemplatePersistence;

import java.util.Objects;

/**
 * System-level readiness recheck: re-extracts the current-only projection from the
 * persisted current, recomputes READY/INVALID against the current dependency facts and
 * persists the result. Used by the app's STALE consumer and Render-bound rechecks.
 */
final class CanonicalTemplateReadinessAuthority implements TemplateReadinessAuthority {
    private static final int MAX_RECHECK_ATTEMPTS = 3;

    private final TemplatePersistence persistence;
    private final AssetRefAtomExtractor extractor;
    private final TemplateDependencyEvaluator dependencies;

    CanonicalTemplateReadinessAuthority(
            TemplatePersistence persistence,
            DependencyResolution resolution,
            StaticSchemaAuthority schemas,
            DesignDslAuthority designs
    ) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.extractor = new AssetRefAtomExtractor();
        this.dependencies = new TemplateDependencyEvaluator(
                Objects.requireNonNull(resolution, "resolution"),
                Objects.requireNonNull(schemas, "schemas"),
                Objects.requireNonNull(designs, "designs")
        );
    }

    @Override
    public RecheckOutcome recheck(TemplateApplication.TemplateId templateId) {
        Objects.requireNonNull(templateId, "templateId");
        for (int attempt = 0; attempt < MAX_RECHECK_ATTEMPTS; attempt++) {
            var locate = persistence.locate(templateId);
            if (locate instanceof TemplatePersistence.LocateNotFound) {
                return new RecheckNotFound();
            }
            if (locate instanceof TemplatePersistence.LocateUnavailable) {
                return new RecheckUnavailable();
            }
            if (((TemplatePersistence.Located) locate).metadata().lifecycle()
                    == TemplatePersistence.Lifecycle.DELETED) {
                return new RecheckDeleted();
            }
            var loaded = persistence.loadCurrent(templateId);
            if (loaded instanceof TemplatePersistence.CurrentNotFound) {
                return new RecheckNotFound();
            }
            if (loaded instanceof TemplatePersistence.CurrentLoadUnavailable) {
                return new RecheckUnavailable();
            }
            var stored = ((TemplatePersistence.CurrentLoaded) loaded).current();
            var projection = extractor.extract(stored.canonicalDesignDslUtf8());
            TemplateDependencyEvaluator.Evaluation evaluation;
            try {
                evaluation = dependencies.evaluate(
                        projection,
                        stored.canonicalDesignDslUtf8(),
                        stored.metadata().staticSchema(),
                        templateId.value(),
                        stored.metadata().ownerScope()
                );
            } catch (TemplateDependencyEvaluator.Unavailable unavailable) {
                return new RecheckUnavailable();
            }
            var readiness = evaluation.readiness();
            var updated = persistence.updateReadiness(
                    templateId,
                    stored.metadata().currentRevision(),
                    readiness,
                    evaluation.snapshot()
            );
            if (updated instanceof TemplatePersistence.ReadinessUpdated) {
                return new Rechecked(readiness, stored.metadata().currentRevision());
            }
            if (updated instanceof TemplatePersistence.ReadinessUnavailable) {
                return new RecheckUnavailable();
            }
            // Current moved or disappeared between load and guarded update. Re-enter the
            // complete read path, but never recurse or spin without a hard attempt bound.
        }
        return new RecheckUnavailable();
    }
}
