package cn.hbads.renderweave.template.internal;

import cn.hbads.renderweave.template.api.TemplateApplication;
import cn.hbads.renderweave.template.api.TemplateDependencyProjection;
import cn.hbads.renderweave.template.spi.DependencyResolution;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Recomputes Template current readiness from a dependency projection against the exact
 * dependency facts (ticket 09 §213, ticket 12 §110-§113): every AssetRef atom must have a
 * matching ACTIVE Asset current, every TemplateUse target must exist and be ACTIVE, and
 * the authored TemplateUse graph must be acyclic. Any dependency failure is INVALID;
 * probe unavailability aborts via {@link Unavailable}.
 */
final class TemplateDependencyEvaluator {

    private final DependencyResolution resolution;
    private final Function<String, java.util.List<String>> useTargetsOf;

    TemplateDependencyEvaluator(
            DependencyResolution resolution,
            Function<String, java.util.List<String>> useTargetsOf
    ) {
        this.resolution = Objects.requireNonNull(resolution, "resolution");
        this.useTargetsOf = Objects.requireNonNull(useTargetsOf, "useTargetsOf");
    }

    static final class Unavailable extends RuntimeException {
        Unavailable() {
            super("dependency resolution unavailable");
        }
    }

    TemplateApplication.Readiness evaluate(
            TemplateDependencyProjection projection,
            String selfTemplateId
    ) {
        for (var atom : projection.assetAtoms()) {
            var check = resolution.checkAsset(atom.assetId(), atom.kind());
            switch (check) {
                case MATCH -> {
                }
                case KIND_MISMATCH, NOT_FOUND -> {
                    return TemplateApplication.Readiness.INVALID;
                }
                case UNAVAILABLE -> throw new Unavailable();
            }
        }
        var path = new HashSet<String>();
        var done = new HashSet<String>();
        if (!path.add(selfTemplateId)) {
            return TemplateApplication.Readiness.INVALID;
        }
        for (var use : projection.templateUses()) {
            if (resolution.checkTemplateUse(use.targetTemplateId())
                    != DependencyResolution.TemplateCheck.ACTIVE) {
                return TemplateApplication.Readiness.INVALID;
            }
            if (!dagAcyclic(use.targetTemplateId(), path, done)) {
                return TemplateApplication.Readiness.INVALID;
            }
        }
        return TemplateApplication.Readiness.READY;
    }

    private boolean dagAcyclic(String templateId, Set<String> path, Set<String> done) {
        if (done.contains(templateId)) {
            return true;
        }
        if (!path.add(templateId)) {
            return false;
        }
        java.util.List<String> targets;
        try {
            targets = useTargetsOf.apply(templateId);
        } catch (RuntimeException unavailable) {
            throw new Unavailable();
        }
        for (var target : targets) {
            if (resolution.checkTemplateUse(target)
                    != DependencyResolution.TemplateCheck.ACTIVE) {
                return false;
            }
            if (!dagAcyclic(target, path, done)) {
                return false;
            }
        }
        path.remove(templateId);
        done.add(templateId);
        return true;
    }
}
