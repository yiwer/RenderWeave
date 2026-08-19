package cn.hbads.renderweave.template.internal;

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
    private final TemplatePersistence persistence;
    private final AssetRefAtomExtractor extractor;
    private final TemplateDependencyEvaluator dependencies;

    CanonicalTemplateReadinessAuthority(
            TemplatePersistence persistence,
            DependencyResolution resolution
    ) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.extractor = new AssetRefAtomExtractor();
        this.dependencies = new TemplateDependencyEvaluator(
                Objects.requireNonNull(resolution, "resolution"),
                this::useTargetsOf
        );
    }

    private java.util.List<String> useTargetsOf(String templateId) {
        var outcome = persistence.loadUseTargets(TemplateApplication.TemplateId.of(templateId));
        if (outcome instanceof TemplatePersistence.UseTargetsLoaded loaded) {
            return loaded.targetTemplateIds();
        }
        throw new TemplateDependencyEvaluator.Unavailable();
    }

    @Override
    public RecheckOutcome recheck(TemplateApplication.TemplateId templateId) {
        Objects.requireNonNull(templateId, "templateId");
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
        TemplateApplication.Readiness readiness;
        try {
            readiness = dependencies.evaluate(projection, templateId.value());
        } catch (TemplateDependencyEvaluator.Unavailable unavailable) {
            return new RecheckUnavailable();
        }
        var updated = persistence.updateReadiness(
                templateId, stored.metadata().currentRevision(), readiness);
        if (updated instanceof TemplatePersistence.ReadinessUpdated) {
            return new Rechecked(readiness, stored.metadata().currentRevision());
        }
        if (updated instanceof TemplatePersistence.ReadinessRevisionConflict
                || updated instanceof TemplatePersistence.ReadinessNotFound) {
            // Current moved between load and update: the recheck is stale; retry once
            // by re-entering the read path.
            var retried = recheck(templateId);
            if (retried instanceof RecheckOutcome) {
                return retried;
            }
            return new RecheckUnavailable();
        }
        return new RecheckUnavailable();
    }
}
