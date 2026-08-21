package cn.hbads.renderweave.template.internal;

import cn.hbads.renderweave.template.api.TemplateApplication;
import cn.hbads.renderweave.template.api.TemplateDependencyProjection;
import cn.hbads.renderweave.template.spi.DependencyResolution;
import cn.hbads.renderweave.template.spi.OwnerScopeAuthority;
import cn.hbads.renderweave.template.spi.TemplateDependencySnapshot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Freezes and classifies the materialized E4a dependency surface. */
final class TemplateDependencyEvaluator {
    private static final int MAX_UNIQUE_TEMPLATE_SNAPSHOTS = 64;
    private static final int MAX_AUTHORED_TEMPLATE_REF_EDGES = 256;
    private static final int MAX_CLOSURE_DEPTH = 16;

    private final DependencyResolution resolution;

    TemplateDependencyEvaluator(DependencyResolution resolution) {
        this.resolution = Objects.requireNonNull(resolution, "resolution");
    }

    static final class Unavailable extends RuntimeException {
        Unavailable() {
            super("dependency resolution unavailable");
        }
    }

    enum Classification {
        READY,
        DEPENDENCY_ERROR,
        HARD_ERROR
    }

    record Evaluation(
            Classification classification,
            TemplateApplication.ValidationReport report,
            TemplateDependencySnapshot snapshot
    ) {
        Evaluation {
            Objects.requireNonNull(classification, "classification");
            Objects.requireNonNull(report, "report");
            Objects.requireNonNull(snapshot, "snapshot");
        }

        TemplateApplication.Readiness readiness() {
            return classification == Classification.READY
                    ? TemplateApplication.Readiness.READY
                    : TemplateApplication.Readiness.INVALID;
        }
    }

    Evaluation evaluate(
            TemplateDependencyProjection projection,
            String selfTemplateId,
            OwnerScopeAuthority.OwnerScope ownerScope
    ) {
        Objects.requireNonNull(projection, "projection");
        Objects.requireNonNull(selfTemplateId, "selfTemplateId");
        Objects.requireNonNull(ownerScope, "ownerScope");
        var context = new EvaluationContext(selfTemplateId, ownerScope);
        context.evaluateAssets(projection.assetAtoms());
        if (!context.problemLimitReached) {
            context.evaluateRootUses(projection.templateUses());
        }
        var report = TemplateProblemBudget.bounded(context.problems);
        var classification = report.truncated() || context.hard
                ? Classification.HARD_ERROR
                : report.problems().isEmpty()
                ? Classification.READY
                : Classification.DEPENDENCY_ERROR;
        return new Evaluation(
                classification,
                report,
                new TemplateDependencySnapshot(
                        context.assetFacts.values().stream().toList(),
                        context.templateFacts.values().stream().toList()
                )
        );
    }

    private final class EvaluationContext {
        private final String selfTemplateId;
        private final OwnerScopeAuthority.OwnerScope ownerScope;
        private final List<TemplateApplication.ValidationProblem> problems = new ArrayList<>();
        private final Map<String, TemplateDependencySnapshot.AssetFact> assetFacts =
                new HashMap<>();
        private final Map<String, TemplateDependencySnapshot.TemplateFact> templateFacts =
                new HashMap<>();
        private final Set<String> expanded = new HashSet<>();
        private boolean hard;
        private boolean problemLimitReached;
        private int problemBytes;
        private int edgeCount;

        private EvaluationContext(
                String selfTemplateId,
                OwnerScopeAuthority.OwnerScope ownerScope
        ) {
            this.selfTemplateId = selfTemplateId;
            this.ownerScope = ownerScope;
        }

        private void evaluateAssets(List<TemplateDependencyProjection.AssetRefAtom> atoms) {
            for (var atom : atoms) {
                if (problemLimitReached) {
                    return;
                }
                var fact = assetFacts.computeIfAbsent(atom.assetId(), this::resolveAsset);
                if (fact.state().isEmpty()) {
                    dependency("TEMPLATE_ASSET_NOT_FOUND", atom.canonicalPointer());
                    continue;
                }
                var state = fact.state().orElseThrow();
                if (!ownerScope.equals(state.ownerScope())) {
                    hard("TEMPLATE_DEPENDENCY_SCOPE_MISMATCH", atom.canonicalPointer());
                    continue;
                }
                if (state.lifecycle() != DependencyResolution.Lifecycle.ACTIVE) {
                    dependency("TEMPLATE_ASSET_NOT_ACTIVE", atom.canonicalPointer());
                    continue;
                }
                var expectedKind = switch (atom.kind()) {
                    case "imageRef" -> "IMAGE";
                    case "fontRef" -> "FONT";
                    default -> null;
                };
                if (expectedKind == null) {
                    hard("TEMPLATE_DEPENDENCY_INTEGRITY_MISMATCH", atom.canonicalPointer());
                } else if (!expectedKind.equals(state.kind())) {
                    dependency("TEMPLATE_ASSET_KIND_MISMATCH", atom.canonicalPointer());
                }
            }
        }

        private TemplateDependencySnapshot.AssetFact resolveAsset(String assetId) {
            return switch (resolution.resolveAsset(assetId)) {
                case DependencyResolution.AssetResolved resolved ->
                        TemplateDependencySnapshot.AssetFact.resolved(assetId, resolved.state());
                case DependencyResolution.AssetMissing ignored ->
                        TemplateDependencySnapshot.AssetFact.missing(assetId);
                case DependencyResolution.AssetUnavailable ignored -> throw new Unavailable();
            };
        }

        private void evaluateRootUses(
                List<TemplateDependencyProjection.TemplateUseOccurrence> uses
        ) {
            var path = new HashSet<String>();
            path.add(selfTemplateId);
            for (var use : uses) {
                visit(use.targetTemplateId(), use.canonicalPointer(), 1, path);
            }
        }

        private void visit(
                String targetTemplateId,
                String canonicalPointer,
                int depth,
                Set<String> path
        ) {
            if (problemLimitReached) {
                return;
            }
            edgeCount++;
            if (edgeCount > MAX_AUTHORED_TEMPLATE_REF_EDGES || depth > MAX_CLOSURE_DEPTH) {
                hard("TEMPLATE_DEPENDENCY_CLOSURE_LIMIT_REACHED", canonicalPointer);
                return;
            }
            if (path.contains(targetTemplateId)) {
                hard("TEMPLATE_REF_CYCLE", canonicalPointer);
                return;
            }
            if (!templateFacts.containsKey(targetTemplateId)
                    && templateFacts.size() >= MAX_UNIQUE_TEMPLATE_SNAPSHOTS) {
                hard("TEMPLATE_DEPENDENCY_CLOSURE_LIMIT_REACHED", canonicalPointer);
                return;
            }
            var fact = templateFacts.computeIfAbsent(targetTemplateId, this::resolveTemplate);
            if (fact.state().isEmpty()) {
                dependency("TEMPLATE_CHILD_NOT_FOUND", canonicalPointer);
                return;
            }
            var state = fact.state().orElseThrow();
            if (!targetTemplateId.equals(state.templateId())) {
                hard("TEMPLATE_DEPENDENCY_INTEGRITY_MISMATCH", canonicalPointer);
                return;
            }
            if (!ownerScope.equals(state.ownerScope())) {
                hard("TEMPLATE_DEPENDENCY_SCOPE_MISMATCH", canonicalPointer);
                return;
            }
            if (state.lifecycle() != DependencyResolution.Lifecycle.ACTIVE) {
                dependency("TEMPLATE_CHILD_NOT_ACTIVE", canonicalPointer);
                return;
            }
            if (state.readiness() != TemplateApplication.Readiness.READY) {
                dependency("TEMPLATE_CHILD_NOT_READY", canonicalPointer);
            }
            if (!expanded.add(targetTemplateId)) {
                return;
            }
            path.add(targetTemplateId);
            for (var use : state.uses()) {
                visit(use.targetTemplateId(), use.canonicalPointer(), depth + 1, path);
            }
            path.remove(targetTemplateId);
        }

        private TemplateDependencySnapshot.TemplateFact resolveTemplate(String templateId) {
            return switch (resolution.resolveTemplate(templateId)) {
                case DependencyResolution.TemplateResolved resolved ->
                        TemplateDependencySnapshot.TemplateFact.resolved(
                                templateId, resolved.state());
                case DependencyResolution.TemplateMissing ignored ->
                        TemplateDependencySnapshot.TemplateFact.missing(templateId);
                case DependencyResolution.TemplateUnavailable ignored -> throw new Unavailable();
            };
        }

        private void dependency(String code, String pointer) {
            problem(code, TemplateApplication.ProblemCategory.DEPENDENCY, pointer);
        }

        private void hard(String code, String pointer) {
            hard = true;
            problem(code, TemplateApplication.ProblemCategory.HARD, pointer);
        }

        private void problem(
                String code,
                TemplateApplication.ProblemCategory category,
                String pointer
        ) {
            if (problems.size() <= TemplateProblemBudget.MAX_ITEMS) {
                var problem = new TemplateApplication.ValidationProblem(
                        code,
                        category,
                        TemplateApplication.ProblemSeverity.ERROR,
                        pointer,
                        List.of()
                );
                problems.add(problem);
                var canonicalSize = TemplateProblemBudget.canonicalSize(problem);
                problemLimitReached = problems.size() > TemplateProblemBudget.MAX_ITEMS
                        || canonicalSize > TemplateProblemBudget.MAX_ITEM_BYTES
                        || problemBytes + canonicalSize
                        > TemplateProblemBudget.MAX_ORDINARY_BYTES;
                if (!problemLimitReached) {
                    problemBytes += canonicalSize;
                }
            }
        }
    }
}
