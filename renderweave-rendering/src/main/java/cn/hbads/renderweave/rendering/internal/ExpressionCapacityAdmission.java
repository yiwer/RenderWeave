package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.rendering.api.EvaluationStage;
import cn.hbads.renderweave.rendering.api.RenderingProblem;
import cn.hbads.renderweave.rendering.api.RenderingProblem.LimitId;
import cn.hbads.renderweave.rendering.api.RenderingProblem.ProblemCode;
import cn.hbads.renderweave.template.api.DesignDslAuthority;
import cn.hbads.renderweave.template.api.DesignDslAuthority.Limit;
import cn.hbads.renderweave.template.api.DesignInputExpressionCapacityAuthority;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.ArrayNode;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.DesignNodeValue;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.ExpressionAst;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.ObjectNode;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.Text;
import cn.hbads.renderweave.template.api.TemplateClosureAuthority.ClosureSnapshot;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Optional;

/**
 * Closure-stage replay of statically measurable Expression capacities. Thresholds and terminal
 * taxonomy remain exclusively Template-owned; this adapter only measures immutable canonical
 * semantic values and derived ASTs supplied by {@link DesignSemanticAuthority}.
 */
final class ExpressionCapacityAdmission {

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
            DesignDslAuthority dslAuthority,
            DesignInputExpressionCapacityAuthority capacity,
            EvaluationStageControl stageControl
    ) {
        Objects.requireNonNull(closure, "closure");
        Objects.requireNonNull(semantics, "semantics");
        Objects.requireNonNull(dslAuthority, "dslAuthority");
        Objects.requireNonNull(capacity, "capacity");
        Objects.requireNonNull(stageControl, "stageControl");
        try {
            for (var snapshot : closure.snapshots()) {
                stageControl.checkpoint();
                var snapshotBytes = snapshot.canonicalDesignDslUtf8();
                var replay = dslAuthority.admit(snapshotBytes);
                if (replay instanceof DesignDslAuthority.Rejected rejected) {
                    var problem = replayProblem(rejected);
                    return problem == null ? new Fault() : new Rejected(problem);
                }
                var readmitted = (DesignDslAuthority.Admitted) replay;
                if (!java.util.Arrays.equals(snapshotBytes, readmitted.canonicalUtf8())
                        || !snapshot.contentHash().equals(readmitted.contentHash())) {
                    return new Fault();
                }
                var interpretation = semantics.interpret(snapshotBytes);
                if (!(interpretation instanceof DesignSemanticAuthority.Interpreted interpreted)
                        || !(interpreted.document().members().get("definitions")
                        instanceof ArrayNode definitions)) {
                    return new Fault();
                }

                var graphProblem = admit(
                        capacity,
                        Limit.EXPRESSION_DEFINITION_GRAPH_EDGES,
                        countDefinitionGraphEdges(definitions));
                if (graphProblem != null) {
                    return new Rejected(graphProblem);
                }

                long sourceBytesTotal = 0;
                long inputsTotal = 0;
                long mappingCasesTotal = 0;
                long astNodesTotal = 0;
                for (var definitionValue : definitions.items()) {
                    stageControl.checkpoint();
                    if (!(definitionValue instanceof ObjectNode definition)
                            || !(definition.members().get("kind") instanceof Text kind)) {
                        return new Fault();
                    }
                    if ("mapping".equals(kind.value())) {
                        if (!(definition.members().get("cases") instanceof ArrayNode cases)) {
                            return new Fault();
                        }
                        var caseProblem = admit(
                                capacity,
                                Limit.EXPRESSION_MAPPING_CASES_PER_DEFINITION,
                                cases.items().size());
                        if (caseProblem != null) {
                            return new Rejected(caseProblem);
                        }
                        mappingCasesTotal = Math.addExact(
                                mappingCasesTotal, cases.items().size());
                        caseProblem = admit(
                                capacity,
                                Limit.EXPRESSION_MAPPING_CASES_TOTAL,
                                mappingCasesTotal);
                        if (caseProblem != null) {
                            return new Rejected(caseProblem);
                        }
                        continue;
                    }
                    if (!"expression".equals(kind.value())) {
                        if ("custom".equals(kind.value())) {
                            continue;
                        }
                        return new Fault();
                    }
                    if (!(definition.members().get("definitionId") instanceof Text definitionId)
                            || !(definition.members().get("inputs") instanceof ArrayNode inputs)
                            || !(definition.members().get("source") instanceof Text source)) {
                        return new Fault();
                    }

                    var inputProblem = admit(
                            capacity,
                            Limit.EXPRESSION_INPUTS_PER_EXPRESSION,
                            inputs.items().size());
                    if (inputProblem != null) {
                        return new Rejected(inputProblem);
                    }
                    inputsTotal = Math.addExact(inputsTotal, inputs.items().size());
                    inputProblem = admit(
                            capacity,
                            Limit.EXPRESSION_INPUTS_TOTAL,
                            inputsTotal);
                    if (inputProblem != null) {
                        return new Rejected(inputProblem);
                    }

                    long sourceBytes = source.value().getBytes(StandardCharsets.UTF_8).length;
                    var sourceProblem = admit(
                            capacity,
                            Limit.EXPRESSION_SOURCE_UTF8_BYTES_PER_EXPRESSION,
                            sourceBytes);
                    if (sourceProblem != null) {
                        return new Rejected(sourceProblem);
                    }
                    sourceBytesTotal = Math.addExact(sourceBytesTotal, sourceBytes);
                    sourceProblem = admit(
                            capacity,
                            Limit.EXPRESSION_SOURCE_UTF8_BYTES_TOTAL,
                            sourceBytesTotal);
                    if (sourceProblem != null) {
                        return new Rejected(sourceProblem);
                    }

                    var ast = interpreted.expressionsByDefinitionId().get(definitionId.value());
                    if (ast == null) {
                        return new Fault();
                    }
                    long astNodes = countAstNodesAndAdmitScales(ast, capacity, stageControl);
                    var astProblem = admit(
                            capacity,
                            Limit.EXPRESSION_AST_NODES_PER_EXPRESSION,
                            astNodes);
                    if (astProblem != null) {
                        return new Rejected(astProblem);
                    }
                    astNodesTotal = Math.addExact(astNodesTotal, astNodes);
                    astProblem = admit(
                            capacity,
                            Limit.EXPRESSION_AST_NODES_TOTAL,
                            astNodesTotal);
                    if (astProblem != null) {
                        return new Rejected(astProblem);
                    }
                }
            }
            stageControl.checkpoint();
            return new Admitted();
        } catch (EvaluationStageControl.DeadlineExceeded ignored) {
            return new DeadlineExceeded();
        } catch (CapacityRejected rejected) {
            return new Rejected(rejected.problem);
        } catch (RuntimeException invariantFault) {
            return new Fault();
        }
    }

    private static RenderingProblem replayProblem(DesignDslAuthority.Rejected rejected) {
        if (rejected.code() != DesignDslAuthority.FailureCode.DESIGN_DSL_LIMIT_EXCEEDED
                || rejected.limit().isEmpty()) {
            return null;
        }
        var limitId = rejected.limit().orElseThrow().id();
        var code = limitId.startsWith("expression.")
                ? ProblemCode.EXPRESSION_LIMIT_EXCEEDED
                : ProblemCode.DESIGN_DSL_LIMIT_EXCEEDED;
        return new RenderingProblem(
                code,
                EvaluationStage.TEMPLATE_CLOSURE,
                Optional.empty(),
                Optional.of(new LimitId(limitId)));
    }

    private static long countDefinitionGraphEdges(ArrayNode definitions) {
        long edges = 0;
        var pending = new ArrayDeque<DesignNodeValue>(definitions.items());
        while (!pending.isEmpty()) {
            var value = pending.removeLast();
            if (value instanceof ObjectNode object) {
                if (object.members().get("kind") instanceof Text kind
                        && "definition".equals(kind.value())) {
                    if (!(object.members().get("definitionId") instanceof Text)) {
                        throw new IllegalStateException("admitted definition source malformed");
                    }
                    edges = Math.addExact(edges, 1);
                    continue;
                }
                pending.addAll(object.members().values());
            } else if (value instanceof ArrayNode array) {
                pending.addAll(array.items());
            }
        }
        return edges;
    }

    private static long countAstNodesAndAdmitScales(
            ExpressionAst root,
            DesignInputExpressionCapacityAuthority capacity,
            EvaluationStageControl stageControl
    ) {
        long nodes = 0;
        var pending = new ArrayDeque<ExpressionAst>();
        pending.add(root);
        while (!pending.isEmpty()) {
            stageControl.checkpoint();
            var node = pending.removeLast();
            nodes = Math.addExact(nodes, 1);
            if (node instanceof ExpressionAst.Unary unary) {
                pending.add(unary.operand());
            } else if (node instanceof ExpressionAst.Binary binary) {
                pending.add(binary.right());
                pending.add(binary.left());
            } else if (node instanceof ExpressionAst.Call call) {
                admitExplicitScales(call, capacity);
                for (int index = call.arguments().size() - 1; index >= 0; index--) {
                    pending.add(call.arguments().get(index));
                }
            }
        }
        return nodes;
    }

    private static void admitExplicitScales(
            ExpressionAst.Call call,
            DesignInputExpressionCapacityAuthority capacity
    ) {
        switch (call.function()) {
            case DIVIDE -> admitScale(call, 2, capacity);
            case ROUND -> admitScale(call, 1, capacity);
            case FORMAT_DECIMAL -> {
                admitScale(call, 1, capacity);
                admitScale(call, 2, capacity);
            }
            default -> {
                // No explicit rounding scale in this function contract.
            }
        }
    }

    private static void admitScale(
            ExpressionAst.Call call,
            int index,
            DesignInputExpressionCapacityAuthority capacity
    ) {
        if (index >= call.arguments().size()
                || !(call.arguments().get(index) instanceof ExpressionAst.DecimalLiteral literal)
                || literal.value().signum() < 0
                || literal.value().stripTrailingZeros().scale() > 0) {
            return;
        }
        var problem = admit(
                capacity,
                Limit.EXPRESSION_EXPLICIT_ROUNDING_SCALE_MAX,
                literal.value().toBigIntegerExact().toString());
        if (problem != null) {
            throw new CapacityRejected(problem);
        }
    }

    private static RenderingProblem admit(
            DesignInputExpressionCapacityAuthority capacity,
            Limit limit,
            long observed
    ) {
        return admit(capacity, limit, Long.toString(observed));
    }

    private static RenderingProblem admit(
            DesignInputExpressionCapacityAuthority capacity,
            Limit limit,
            String observed
    ) {
        var decision = capacity.evaluate(new DesignInputExpressionCapacityAuthority.Observation(
                limit.id(), observed));
        if (decision instanceof DesignInputExpressionCapacityAuthority.Accepted) {
            return null;
        }
        if (decision instanceof DesignInputExpressionCapacityAuthority.Rejected rejected) {
            var terminal = rejected.terminal();
            return new RenderingProblem(
                    ProblemCode.valueOf(terminal.code()),
                    EvaluationStage.valueOf(terminal.publicRenderStage()),
                    Optional.empty(),
                    Optional.of(new LimitId(limit.id())));
        }
        throw new IllegalStateException("capacity authority rejected a canonical observation");
    }

    private static final class CapacityRejected extends RuntimeException {
        private final RenderingProblem problem;

        private CapacityRejected(RenderingProblem problem) {
            this.problem = problem;
        }
    }
}
