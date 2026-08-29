package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.rendering.api.RenderingProblem;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.ArrayNode;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.ObjectNode;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.Text;
import cn.hbads.renderweave.template.api.TemplateClosureAuthority.ClosureSnapshot;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Closure-stage admission for Expression capacities whose operands are statically observable.
 * Every expression is inspected, including definitions that no materialized node ever demands.
 */
final class ExpressionCapacityAdmission {

    private static final DesignInputExpressionCapacityGuard CAPACITY_GUARD =
            new DesignInputExpressionCapacityGuard();

    sealed interface Outcome permits Admitted, Rejected, Fault, DeadlineExceeded {
    }

    record Admitted() implements Outcome {
    }

    record Rejected(RenderingProblem problem) implements Outcome {
        Rejected {
            Objects.requireNonNull(problem, "problem");
        }
    }

    record Fault() implements Outcome {
    }

    record DeadlineExceeded() implements Outcome {
    }

    private ExpressionCapacityAdmission() {
    }

    static Outcome admit(
            ClosureSnapshot closure,
            DesignSemanticAuthority semantics,
            EvaluationStageControl stageControl
    ) {
        Objects.requireNonNull(closure, "closure");
        Objects.requireNonNull(semantics, "semantics");
        Objects.requireNonNull(stageControl, "stageControl");
        try {
            for (var snapshot : closure.snapshots()) {
                stageControl.checkpoint();
                var sourceBudget = CAPACITY_GUARD.newSourceBudget();
                var interpretation = semantics.interpret(snapshot.canonicalDesignDslUtf8());
                if (!(interpretation instanceof DesignSemanticAuthority.Interpreted interpreted)
                        || !(interpreted.document().members().get("definitions")
                        instanceof ArrayNode definitions)) {
                    return new Fault();
                }
                for (var definitionValue : definitions.items()) {
                    stageControl.checkpoint();
                    if (!(definitionValue instanceof ObjectNode definition)
                            || !(definition.members().get("kind") instanceof Text kind)) {
                        return new Fault();
                    }
                    if (!"expression".equals(kind.value())) {
                        continue;
                    }
                    if (!(definition.members().get("source") instanceof Text source)) {
                        return new Fault();
                    }
                    var parsed = ExpressionParser.parse(
                            source.value().getBytes(StandardCharsets.UTF_8),
                            sourceBudget);
                    if (parsed instanceof ExpressionParser.ParseLimitExceeded limited) {
                        return new Rejected(limited.problem());
                    }
                    if (!(parsed instanceof ExpressionParser.ParsedAst parsedAst)) {
                        return new Fault();
                    }
                    var capacityProblem = ExpressionAnalyzer.admitCapacity(parsedAst.ast());
                    if (capacityProblem != null) {
                        return new Rejected(capacityProblem);
                    }
                }
            }
            stageControl.checkpoint();
            return new Admitted();
        } catch (EvaluationStageControl.DeadlineExceeded ignored) {
            return new DeadlineExceeded();
        }
    }
}
