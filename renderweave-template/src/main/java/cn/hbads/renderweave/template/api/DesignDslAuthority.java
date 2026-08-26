package cn.hbads.renderweave.template.api;

import java.util.Objects;
import java.util.Optional;

public interface DesignDslAuthority {

    Admission admit(byte[] rawUtf8);

    sealed interface Admission permits Admitted, Rejected {
    }

    final class Admitted implements Admission {
        private final byte[] canonicalUtf8;
        private final String contentHash;

        public Admitted(byte[] canonicalUtf8, String contentHash) {
            this.canonicalUtf8 = Objects.requireNonNull(canonicalUtf8, "canonicalUtf8").clone();
            this.contentHash = Objects.requireNonNull(contentHash, "contentHash");
        }

        public byte[] canonicalUtf8() {
            return canonicalUtf8.clone();
        }

        public String contentHash() {
            return contentHash;
        }
    }

    record Rejected(
            FailureCode code,
            FailureStage stage,
            String pointer,
            Optional<Limit> limit
    ) implements Admission {
        public Rejected {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(stage, "stage");
            Objects.requireNonNull(pointer, "pointer");
            limit = Objects.requireNonNull(limit, "limit");
        }
    }

    enum FailureCode {
        DESIGN_UTF8_INVALID,
        DESIGN_JSON_INVALID,
        DESIGN_DUPLICATE_MEMBER,
        DESIGN_DSL_LIMIT_EXCEEDED,
        DESIGN_VERSION_UNSUPPORTED,
        DESIGN_MEMBER_UNKNOWN,
        DESIGN_STRUCTURE_INVALID,
        DESIGN_VALUE_INVALID,
        DESIGN_KERNEL_SCOPE_UNSUPPORTED
    }

    enum FailureStage {
        DESIGN_PARSE,
        DESIGN_SEMANTIC_VALIDATION,
        DESIGN_CANONICAL_COUNT
    }

    enum Limit {
        RAW_UTF8_BYTES("designDslParser.rawUtf8Bytes"),
        CANONICAL_BYTES("designDslParser.canonicalBytes"),
        JSON_DEPTH("designDslParser.jsonDepth"),
        OBJECT_MEMBERS("designDslParser.objectMembers"),
        ARRAY_ITEMS("designDslParser.arrayItems"),
        TOTAL_VALUES_AND_CONTAINERS("designDslParser.totalValuesAndContainers"),
        STRING_UTF8_BYTES("designDslParser.stringUtf8Bytes"),
        MEMBER_NAME_UTF8_BYTES("designDslParser.memberNameUtf8Bytes"),
        NUMBER_TOKEN_BYTES("designDslParser.numberTokenBytes"),
        SEMANTIC_AUTHORED_NODES("designDslSemantics.authoredNodes"),
        SEMANTIC_AUTHORED_TREE_DEPTH("designDslSemantics.authoredTreeDepth"),
        SEMANTIC_CHILDREN_PER_CONTAINER("designDslSemantics.childrenPerContainer"),
        SEMANTIC_DEFINITIONS("designDslSemantics.definitions"),
        SEMANTIC_BINDINGS_TOTAL("designDslSemantics.bindingsTotal"),
        SEMANTIC_BINDINGS_PER_NODE("designDslSemantics.bindingsPerNode"),
        SEMANTIC_RUNS_PER_TEXT_NODE("designDslSemantics.runsPerTextNode"),
        SEMANTIC_RUNS_TOTAL("designDslSemantics.runsTotal"),
        SEMANTIC_GRID_TRACKS_PER_AXIS("designDslSemantics.gridTracksPerAxis"),
        SEMANTIC_VECTOR_ENTRIES_PER_NODE("designDslSemantics.vectorEntriesPerNode"),
        SEMANTIC_VECTOR_ENTRIES_TOTAL("designDslSemantics.vectorEntriesTotal"),
        SEMANTIC_FILLS_PER_TEMPLATE_USE("designDslSemantics.fillsPerTemplateUse"),
        SEMANTIC_LITERAL_LIST_ITEMS_PER_LIST("designDslSemantics.literalListItemsPerList"),
        SEMANTIC_LITERAL_LIST_ITEMS_TOTAL("designDslSemantics.literalListItemsTotal"),
        SEMANTIC_AUTHORED_RUN_TEXT_SCALARS("designDslSemantics.authoredRunTextScalars"),
        EXPRESSION_SOURCE_UTF8_BYTES_PER_EXPRESSION(
                "expression.sourceUtf8BytesPerExpression"),
        EXPRESSION_SOURCE_UTF8_BYTES_TOTAL("expression.sourceUtf8BytesTotal"),
        EXPRESSION_INPUTS_PER_EXPRESSION("expression.inputsPerExpression"),
        EXPRESSION_INPUTS_TOTAL("expression.inputsTotal"),
        EXPRESSION_MAPPING_CASES_PER_DEFINITION(
                "expression.mappingCasesPerDefinition"),
        EXPRESSION_MAPPING_CASES_TOTAL("expression.mappingCasesTotal"),
        EXPRESSION_AST_NODES_PER_EXPRESSION("expression.astNodesPerExpression"),
        EXPRESSION_AST_NODES_TOTAL("expression.astNodesTotal"),
        EXPRESSION_DEFINITION_GRAPH_EDGES("expression.definitionGraphEdges"),
        EXPRESSION_DEFINITION_CHAIN_DEPTH("expression.definitionChainDepth");

        private final String id;

        Limit(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }
}
