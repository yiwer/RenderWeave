package cn.hbads.renderweave.template.internal;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Single machine authority for the Definition contract of {@code renderweave-design/1.0}
 * (T15: custom/mapping/expression definitions, ValueSource union, lexical domains).
 * Every wire shape, ValueType and identity fact below is a permanent Definition Property
 * Identity fact; future extensions must create a new dslVersion. Expression evaluation,
 * StaticSchema context resolution and capability execution are Evaluator-side and never
 * implemented here.
 */
final class DefinitionContractCatalog {

    static final Set<String> DEFINITION_KINDS = Set.of("custom", "mapping", "expression");

    static final Set<String> COMMON_DEFINITION_MEMBERS = Set.of("definitionId", "kind", "displayName");
    static final Set<String> CUSTOM_MEMBERS = Set.of("exposure", "valueType", "defaultValue");
    static final Set<String> MAPPING_MEMBERS = Set.of("domain", "output", "input", "cases", "otherwise");
    static final Set<String> EXPRESSION_MEMBERS = Set.of("domain", "output", "inputs", "source");

    static final Set<String> EXPOSURE_TOKENS = Set.of("PUBLIC", "PRIVATE");

    /** BaseValueType exactly: text | decimal | boolean | date | time | color | imageRef | fontRef. */
    static final Set<String> BASE_VALUE_TYPES = Set.of(
            "text", "decimal", "boolean", "date", "time", "color", "imageRef", "fontRef"
    );

    /**
     * list&lt;T&gt; v1 items: the five StaticSchema scalars plus imageRef/fontRef.
     * color and nested lists are not list item types.
     */
    static final Set<String> LIST_ITEM_TYPES = Set.of(
            "text", "decimal", "boolean", "date", "time", "imageRef", "fontRef"
    );

    /** Union-wide prefilter only; each discriminator branch still applies its exact member set. */
    static final Set<String> VALUE_TYPE_UNION_MEMBERS = Set.of("type", "items", "catalogId");
    static final Set<String> LIST_VALUE_TYPE_MEMBERS = Set.of("type", "items");
    static final Set<String> ENUM_VALUE_TYPE_MEMBERS = Set.of("type", "catalogId");

    /** ValueSource closed union (ticket 07 §2). */
    static final Set<String> VALUE_SOURCE_KINDS = Set.of(
            "literal", "context", "loopIndex", "definition", "capability"
    );

    static final Set<String> LITERAL_SOURCE_MEMBERS = Set.of("kind", "valueType", "value");
    static final Set<String> CONTEXT_SOURCE_MEMBERS = Set.of("kind", "domain", "pointer");
    static final Set<String> LOOP_INDEX_SOURCE_MEMBERS = Set.of("kind", "loopId");
    static final Set<String> DEFINITION_SOURCE_MEMBERS = Set.of("kind", "definitionId");
    static final Set<String> CAPABILITY_SOURCE_MEMBERS = Set.of("kind", "capability", "operation");

    static final Set<String> MAPPING_OPERATORS = Set.of(
            "IS_ABSENT", "IS_PRESENT", "EQ", "NOT_EQ",
            "GT", "GTE", "LT", "LTE",
            "CONTAINS", "STARTS_WITH", "ENDS_WITH",
            "PATTERN_MATCH", "IS_BLANK", "IS_NOT_BLANK"
    );

    /** Operators that must not carry an operand literal (ticket 07 §58). */
    static final Set<String> NO_OPERAND_OPERATORS = Set.of("IS_ABSENT", "IS_PRESENT");

    /**
     * Expression-input capability pairs; exactly three exist in v1 and they are only legal
     * as Expression input sources (ticket 07 §41, §45).
     */
    static final Map<String, Set<String>> CAPABILITY_OPERATIONS = Map.of(
            "CLOCK", Set.of("UTC_DATE", "UTC_TIME"),
            "RANDOM", Set.of("UNIFORM_DECIMAL_0_1")
    );

    static final Set<String> CASE_MEMBERS = Set.of("operator", "operand", "then");
    static final Set<String> OPERAND_MEMBERS = Set.of("valueType", "value");
    static final Set<String> EXPRESSION_INPUT_MEMBERS = Set.of("alias", "source");
    static final Set<String> DOMAIN_LOOP_MEMBERS = Set.of("kind", "loopId");
    static final Set<String> ASSET_REF_MEMBERS = Set.of("assetId");

    /** Expression input alias: at most 64 ASCII identifier characters (ticket 07 §69). */
    static final Pattern ALIAS = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,63}$");

    /** date/time lexical formats (ticket 07 §29); calendar validity is Evaluator-side. */
    static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    static final Pattern TIME = Pattern.compile("^\\d{2}:\\d{2}:\\d{2}$");

    /** color canonical form is uppercase #RRGGBBAA (ticket 07 §29). */
    static final Pattern COLOR = Pattern.compile("^#[0-9A-F]{8}$");

    static final int MAX_CONTEXT_POINTER_SEGMENTS = 32;
    static final int MAX_CONTEXT_POINTER_UTF8_BYTES = 1024;

    private DefinitionContractCatalog() {
    }
}
