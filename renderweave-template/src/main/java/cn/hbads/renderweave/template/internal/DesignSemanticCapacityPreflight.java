package cn.hbads.renderweave.template.internal;

import cn.hbads.renderweave.template.api.DesignDslAuthority;
import cn.hbads.renderweave.template.api.DesignInputExpressionCapacityAuthority;

import java.util.Objects;
import java.util.Optional;

/**
 * Request-local semantic capacity walk over the already strict-parsed DesignDSL value.
 * It owns no thresholds: every derived observation is decided by the shared Template
 * capacity authority before semantic normalization or dependency I/O begins.
 */
final class DesignSemanticCapacityPreflight {

    private final DesignInputExpressionCapacityAuthority capacity;
    private long authoredNodes;
    private long bindingsTotal;
    private long runsTotal;
    private long vectorEntriesTotal;
    private long literalListItemsTotal;
    private long authoredRunTextScalars;

    DesignSemanticCapacityPreflight(DesignInputExpressionCapacityAuthority capacity) {
        this.capacity = Objects.requireNonNull(capacity, "capacity");
    }

    void verify(JsonValue parsed) throws DesignDslFailureException {
        if (!(parsed instanceof JsonValue.ObjectValue root)) {
            return;
        }
        if (root.members().get("definitions") instanceof JsonValue.ArrayValue definitions) {
            reserve(
                    DesignDslAuthority.Limit.SEMANTIC_DEFINITIONS,
                    definitions.items().size(),
                    "/definitions"
            );
        }
        scanLiteralLists(root, "");
        if (root.members().get("designRoot") instanceof JsonValue.ObjectValue designRoot) {
            scanNode(designRoot, 1, "/designRoot");
        }
    }

    private void scanNode(
            JsonValue.ObjectValue node,
            long depth,
            String pointer
    ) throws DesignDslFailureException {
        authoredNodes = addOne(authoredNodes, DesignDslAuthority.Limit.SEMANTIC_AUTHORED_NODES,
                pointer);
        reserve(DesignDslAuthority.Limit.SEMANTIC_AUTHORED_NODES, authoredNodes, pointer);
        reserve(DesignDslAuthority.Limit.SEMANTIC_AUTHORED_TREE_DEPTH, depth, pointer);

        if (node.members().get("bindings") instanceof JsonValue.ArrayValue bindings) {
            reserve(DesignDslAuthority.Limit.SEMANTIC_BINDINGS_PER_NODE,
                    bindings.items().size(), pointer + "/bindings");
            bindingsTotal = add(bindingsTotal, bindings.items().size(),
                    DesignDslAuthority.Limit.SEMANTIC_BINDINGS_TOTAL, pointer + "/bindings");
            reserve(DesignDslAuthority.Limit.SEMANTIC_BINDINGS_TOTAL,
                    bindingsTotal, pointer + "/bindings");
        }

        var kind = text(node.members().get("kind"));
        if ("text".equals(kind)) {
            scanText(node, pointer);
        } else if ("grid".equals(kind)) {
            scanGrid(node, pointer);
        } else if ("polygon".equals(kind) || "polyline".equals(kind)) {
            scanVectorEntries(node.members().get("points"), pointer + "/points");
        } else if ("path".equals(kind)) {
            scanVectorEntries(node.members().get("commands"), pointer + "/commands");
        } else if ("templateUse".equals(kind)) {
            if (node.members().get("fills") instanceof JsonValue.ArrayValue fills) {
                reserve(DesignDslAuthority.Limit.SEMANTIC_FILLS_PER_TEMPLATE_USE,
                        fills.items().size(), pointer + "/fills");
            }
        }

        if (node.members().get("children") instanceof JsonValue.ArrayValue children) {
            reserve(DesignDslAuthority.Limit.SEMANTIC_CHILDREN_PER_CONTAINER,
                    children.items().size(), pointer + "/children");
            long childDepth = addOne(depth,
                    DesignDslAuthority.Limit.SEMANTIC_AUTHORED_TREE_DEPTH,
                    pointer + "/children");
            for (int index = 0; index < children.items().size(); index++) {
                if (children.items().get(index) instanceof JsonValue.ObjectValue child) {
                    scanNode(child, childDepth, pointer + "/children/" + index);
                }
            }
        }
    }

    private void scanText(
            JsonValue.ObjectValue node,
            String pointer
    ) throws DesignDslFailureException {
        if (!(node.members().get("runs") instanceof JsonValue.ArrayValue runs)) {
            return;
        }
        reserve(DesignDslAuthority.Limit.SEMANTIC_RUNS_PER_TEXT_NODE,
                runs.items().size(), pointer + "/runs");
        runsTotal = add(runsTotal, runs.items().size(),
                DesignDslAuthority.Limit.SEMANTIC_RUNS_TOTAL, pointer + "/runs");
        reserve(DesignDslAuthority.Limit.SEMANTIC_RUNS_TOTAL, runsTotal, pointer + "/runs");
        for (int index = 0; index < runs.items().size(); index++) {
            if (runs.items().get(index) instanceof JsonValue.ObjectValue run
                    && run.members().get("text") instanceof JsonValue.StringValue value) {
                long scalars = value.value().codePointCount(0, value.value().length());
                authoredRunTextScalars = add(
                        authoredRunTextScalars,
                        scalars,
                        DesignDslAuthority.Limit.SEMANTIC_AUTHORED_RUN_TEXT_SCALARS,
                        pointer + "/runs/" + index + "/text"
                );
                reserve(DesignDslAuthority.Limit.SEMANTIC_AUTHORED_RUN_TEXT_SCALARS,
                        authoredRunTextScalars, pointer + "/runs/" + index + "/text");
            }
        }
    }

    private void scanGrid(
            JsonValue.ObjectValue node,
            String pointer
    ) throws DesignDslFailureException {
        for (var axis : new String[]{"rows", "columns"}) {
            if (node.members().get(axis) instanceof JsonValue.ArrayValue tracks) {
                reserve(DesignDslAuthority.Limit.SEMANTIC_GRID_TRACKS_PER_AXIS,
                        tracks.items().size(), pointer + "/" + axis);
            }
        }
    }

    private void scanVectorEntries(JsonValue value, String pointer)
            throws DesignDslFailureException {
        if (!(value instanceof JsonValue.ArrayValue entries)) {
            return;
        }
        reserve(DesignDslAuthority.Limit.SEMANTIC_VECTOR_ENTRIES_PER_NODE,
                entries.items().size(), pointer);
        vectorEntriesTotal = add(
                vectorEntriesTotal,
                entries.items().size(),
                DesignDslAuthority.Limit.SEMANTIC_VECTOR_ENTRIES_TOTAL,
                pointer
        );
        reserve(DesignDslAuthority.Limit.SEMANTIC_VECTOR_ENTRIES_TOTAL,
                vectorEntriesTotal, pointer);
    }

    private void scanLiteralLists(JsonValue value, String pointer)
            throws DesignDslFailureException {
        if (value instanceof JsonValue.ObjectValue object) {
            if (isListType(object.members().get("valueType"))) {
                if (object.members().get("defaultValue") instanceof JsonValue.ArrayValue list) {
                    observeLiteralList(list, pointer + "/defaultValue");
                }
                if (object.members().get("value") instanceof JsonValue.ArrayValue list) {
                    observeLiteralList(list, pointer + "/value");
                }
            }
            for (var entry : object.members().entrySet()) {
                scanLiteralLists(entry.getValue(),
                        pointer + "/" + escapePointer(entry.getKey()));
            }
            return;
        }
        if (value instanceof JsonValue.ArrayValue array) {
            for (int index = 0; index < array.items().size(); index++) {
                scanLiteralLists(array.items().get(index), pointer + "/" + index);
            }
        }
    }

    private void observeLiteralList(JsonValue.ArrayValue list, String pointer)
            throws DesignDslFailureException {
        reserve(DesignDslAuthority.Limit.SEMANTIC_LITERAL_LIST_ITEMS_PER_LIST,
                list.items().size(), pointer);
        literalListItemsTotal = add(
                literalListItemsTotal,
                list.items().size(),
                DesignDslAuthority.Limit.SEMANTIC_LITERAL_LIST_ITEMS_TOTAL,
                pointer
        );
        reserve(DesignDslAuthority.Limit.SEMANTIC_LITERAL_LIST_ITEMS_TOTAL,
                literalListItemsTotal, pointer);
    }

    private static boolean isListType(JsonValue value) {
        return value instanceof JsonValue.ObjectValue type
                && "list".equals(text(type.members().get("type")));
    }

    private static String text(JsonValue value) {
        return value instanceof JsonValue.StringValue string ? string.value() : null;
    }

    private static String escapePointer(String segment) {
        return segment.replace("~", "~0").replace("/", "~1");
    }

    private long addOne(
            long current,
            DesignDslAuthority.Limit limit,
            String pointer
    ) throws DesignDslFailureException {
        return add(current, 1, limit, pointer);
    }

    private long add(
            long current,
            long increment,
            DesignDslAuthority.Limit limit,
            String pointer
    ) throws DesignDslFailureException {
        try {
            return Math.addExact(current, increment);
        } catch (ArithmeticException overflow) {
            reject(limit, pointer);
            throw new IllegalStateException("unreachable semantic capacity rejection");
        }
    }

    private void reserve(
            DesignDslAuthority.Limit limit,
            long observedValue,
            String pointer
    ) throws DesignDslFailureException {
        var decision = capacity.evaluate(new DesignInputExpressionCapacityAuthority.Observation(
                limit.id(), Long.toString(observedValue)));
        if (!(decision instanceof DesignInputExpressionCapacityAuthority.Accepted)) {
            reject(limit, pointer);
        }
    }

    private void reject(DesignDslAuthority.Limit limit, String pointer)
            throws DesignDslFailureException {
        throw new DesignDslFailureException(new DesignDslAuthority.Rejected(
                DesignDslAuthority.FailureCode.DESIGN_DSL_LIMIT_EXCEEDED,
                DesignDslAuthority.FailureStage.DESIGN_SEMANTIC_VALIDATION,
                pointer,
                Optional.of(limit)
        ));
    }
}
