package cn.hbads.renderweave.template.internal;

import cn.hbads.renderweave.template.api.DesignDslAuthority;
import cn.hbads.renderweave.template.api.DesignInputExpressionCapacityAuthority;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

final class CanonicalDesignDslAuthority implements DesignDslAuthority {

    private static final byte[] HASH_DOMAIN =
            "renderweave-design-content/1\0".getBytes(StandardCharsets.UTF_8);
    private static final Set<String> ROOT_MEMBERS = Set.of(
            "dslVersion", "expressionProfile", "displayName", "description",
            "definitions", "designRoot"
    );
    private static final Set<String> CANVAS_MEMBERS = Set.of(
            "nodeId", "kind", "displayName", "widthMm", "heightMm", "backgroundColor",
            "bleed", "bindings", "children"
    );
    private static final Set<String> BLEED_MEMBERS = Set.of(
            "topMm", "rightMm", "bottomMm", "leftMm"
    );
    private static final List<String> BLEED_MEMBER_ORDER = List.of(
            "topMm", "rightMm", "bottomMm", "leftMm"
    );
    private static final Pattern UUID_V4 = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
    );
    private static final Pattern RGBA = Pattern.compile("^#[0-9A-F]{8}$");
    private static final long MAX_CANONICAL_UTF8_BYTES = 16L * 1024L * 1024L;

    private final StrictJsonParser parser;
    private final CanonicalJsonWriter writer;
    private final DesignInputExpressionCapacityAuthority capacity;

    CanonicalDesignDslAuthority() {
        this(CanonicalDesignInputExpressionCapacityAuthority.INSTANCE);
    }

    CanonicalDesignDslAuthority(DesignInputExpressionCapacityAuthority capacity) {
        var requiredCapacity = Objects.requireNonNull(capacity, "capacity");
        this.capacity = requiredCapacity;
        this.parser = new StrictJsonParser(requiredCapacity);
        this.writer = new CanonicalJsonWriter(requiredCapacity);
    }

    @Override
    public Admission admit(byte[] rawUtf8) {
        try {
            var parsed = parser.parse(rawUtf8);
            var normalized = validateAndNormalize(parsed);
            var canonical = writer.write(normalized);
            return new Admitted(canonical, contentHash(canonical));
        } catch (CanonicalJsonWriter.CanonicalLimitException limit) {
            return new Rejected(
                    FailureCode.DESIGN_DSL_LIMIT_EXCEEDED,
                    FailureStage.DESIGN_CANONICAL_COUNT,
                    "",
                    Optional.of(Limit.CANONICAL_BYTES)
            );
        } catch (DesignDslFailureException failure) {
            return failure.rejection();
        }
    }

    private JsonValue validateAndNormalize(JsonValue parsed) throws DesignDslFailureException {
        rejectNull(parsed, "");
        var root = object(parsed, "");
        rejectUnknown(root, ROOT_MEMBERS, "");
        exactVersion(root, "dslVersion", "renderweave-design/1.0", "/dslVersion");
        exactVersion(
                root,
                "expressionProfile",
                "renderweave-expression/1.0",
                "/expressionProfile"
        );
        new DesignSemanticCapacityPreflight(capacity).verify(root);
        var expressionCapacity = new ExpressionDefinitionCapacityBudget(capacity);
        // Best-effort pre-pass: collect authored Repeat loopIds so Definition loop
        // domains / loopIndex sources can resolve before tree validation runs.
        var loopIds = new java.util.HashSet<String>();
        if (root.members().get("designRoot") instanceof JsonValue.ObjectValue preCanvas) {
            var preChildren = preCanvas.members().get("children");
            if (preChildren != null) {
                collectLoopIds(preChildren, loopIds);
            }
        }
        // bindingId namespace is Template-wide across canvas and all nodes.
        var seenBindingIds = new java.util.HashSet<String>();
        // useId namespace is Template-wide across all templateUse nodes.
        var seenUseIds = new java.util.HashSet<String>();
        var displayName = metadata(root, "displayName", 128, false, "/displayName");
        var definitions = array(required(root, "definitions", "/definitions"), "/definitions");
        var definitionsResult = validateDefinitions(definitions, loopIds, expressionCapacity);

        var canvas = object(required(root, "designRoot", "/designRoot"), "/designRoot");
        rejectUnknown(canvas, CANVAS_MEMBERS, "/designRoot");
        var kind = string(required(canvas, "kind", "/designRoot/kind"), "/designRoot/kind");
        if (!"canvas".equals(kind)) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, "/designRoot/kind");
        }
        var nodeId = string(required(canvas, "nodeId", "/designRoot/nodeId"),
                "/designRoot/nodeId");
        if (!UUID_V4.matcher(nodeId).matches()) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, "/designRoot/nodeId");
        }
        canvasTrimDecimal(canvas, "widthMm", "/designRoot/widthMm");
        canvasTrimDecimal(canvas, "heightMm", "/designRoot/heightMm");
        if (canvas.members().containsKey("backgroundColor")) {
            var color = string(
                    canvas.members().get("backgroundColor"),
                    "/designRoot/backgroundColor"
            );
            if (!RGBA.matcher(color).matches()) {
                throw failure(
                        FailureCode.DESIGN_VALUE_INVALID,
                        "/designRoot/backgroundColor"
                );
            }
        }
        if (canvas.members().containsKey("bleed")) {
            var bleed = object(canvas.members().get("bleed"), "/designRoot/bleed");
            rejectUnknown(bleed, BLEED_MEMBERS, "/designRoot/bleed");
            for (var member : BLEED_MEMBER_ORDER) {
                capacityDecimal(
                        bleed,
                        member,
                        "/designRoot/bleed/" + member,
                        Limit.GEOMETRY_BLEED_MM_PER_SIDE_MIN,
                        Limit.GEOMETRY_BLEED_MM_PER_SIDE_MAX
                );
            }
        }
        var bindings = array(required(canvas, "bindings", "/designRoot/bindings"),
                "/designRoot/bindings");
        var normalizedCanvasBindings = validateBindings(
                canvas, "/designRoot", NodeContractCatalog.NodeKind.CANVAS, seenBindingIds,
                definitionsResult.outputTypes(), loopIds);
        var children = array(required(canvas, "children", "/designRoot/children"),
                "/designRoot/children");
        var normalizedChildren = validateChildren(
                children,
                "/designRoot/children",
                NodeContractCatalog.NodeKind.CANVAS,
                null,
                new java.util.HashSet<>(),
                new java.util.HashSet<>(),
                seenBindingIds,
                seenUseIds,
                definitionsResult.outputTypes(),
                loopIds
        );

        var normalizedCanvas = new LinkedHashMap<>(canvas.members());
        normalizedCanvas.put("bindings", normalizedCanvasBindings);
        normalizedCanvas.put("children", normalizedChildren);
        if (canvas.members().containsKey("displayName")) {
            normalizedCanvas.put(
                    "displayName",
                    new JsonValue.StringValue(metadata(
                            canvas, "displayName", 128, false, "/designRoot/displayName"
                    ))
            );
        }
        var normalizedRoot = new LinkedHashMap<>(root.members());
        normalizedRoot.put("definitions", definitionsResult.normalized());
        normalizedRoot.put("displayName", new JsonValue.StringValue(displayName));
        if (root.members().containsKey("description")) {
            var description = metadata(root, "description", 2048, true, "/description");
            if (description.isEmpty()) {
                normalizedRoot.remove("description");
            } else {
                normalizedRoot.put("description", new JsonValue.StringValue(description));
            }
        }
        normalizedRoot.put("designRoot", new JsonValue.ObjectValue(normalizedCanvas));
        return new JsonValue.ObjectValue(normalizedRoot);
    }

    /** One authored reference from a definition ValueSource to another definition. */
    private record DefinitionEdge(String targetId, String pointer) {
    }

    /** Definitions validation result: canonical sorted array plus per-definition output types. */
    private record DefinitionsResult(
            JsonValue.ArrayValue normalized,
            Map<String, String> outputTypes
    ) {
    }

    /** Structurally valid Definition DAG facts, measured in authored reference edges. */
    private record DefinitionGraphFacts(long edgeCount, long chainDepth) {
    }

    /**
     * Validate the top-level definitions[] closed union and return the canonical array
     * sorted by definitionId (set sorting; ticket 08 §108) plus the declared output type
     * of every definition (Repeat items static type proof). Repeat loopId namespaces come
     * from the tree pre-pass; unresolvable loop domains/loopIndex sources fail closed as
     * dangling references.
     */
    private DefinitionsResult validateDefinitions(
            JsonValue.ArrayValue definitions,
            Set<String> loopIds,
            ExpressionDefinitionCapacityBudget expressionCapacity
    ) throws DesignDslFailureException {
        var seenIds = new HashSet<String>();
        var ids = new ArrayList<String>();
        var edgesByDefinition = new ArrayList<List<DefinitionEdge>>();
        var normalized = new ArrayList<JsonValue>();
        var outputTypes = new LinkedHashMap<String, String>();
        for (int index = 0; index < definitions.items().size(); index++) {
            var pointer = "/definitions/" + index;
            var entry = object(definitions.items().get(index), pointer);
            normalized.add(validateDefinition(entry, pointer, seenIds, ids, edgesByDefinition,
                    loopIds, outputTypes, expressionCapacity));
        }
        var graph = validateDefinitionGraph(ids, edgesByDefinition);
        expressionCapacity.reserveDefinitionGraphEdges(graph.edgeCount(), "/definitions");
        expressionCapacity.reserveDefinitionChainDepth(graph.chainDepth(), "/definitions");
        normalized.sort(Comparator.comparing(CanonicalDesignDslAuthority::definitionIdOf));
        return new DefinitionsResult(new JsonValue.ArrayValue(normalized), outputTypes);
    }

    private JsonValue.ObjectValue validateDefinition(
            JsonValue.ObjectValue entry,
            String pointer,
            Set<String> seenIds,
            List<String> ids,
            List<List<DefinitionEdge>> edgesByDefinition,
            Set<String> loopIds,
            Map<String, String> outputTypes,
            ExpressionDefinitionCapacityBudget expressionCapacity
    ) throws DesignDslFailureException {
        var kindToken = string(required(entry, "kind", pointer + "/kind"), pointer + "/kind");
        if (!DefinitionContractCatalog.DEFINITION_KINDS.contains(kindToken)) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/kind");
        }
        var allowed = new HashSet<>(DefinitionContractCatalog.COMMON_DEFINITION_MEMBERS);
        switch (kindToken) {
            case "custom" -> allowed.addAll(DefinitionContractCatalog.CUSTOM_MEMBERS);
            case "mapping" -> allowed.addAll(DefinitionContractCatalog.MAPPING_MEMBERS);
            case "expression" -> allowed.addAll(DefinitionContractCatalog.EXPRESSION_MEMBERS);
            default -> {
                // unreachable: kind already validated against the closed union
            }
        }
        rejectUnknown(entry, allowed, pointer);
        var definitionId = string(required(entry, "definitionId", pointer + "/definitionId"),
                pointer + "/definitionId");
        if (!UUID_V4.matcher(definitionId).matches() || !seenIds.add(definitionId)) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/definitionId");
        }
        var normalized = new LinkedHashMap<>(entry.members());
        normalized.put("displayName", new JsonValue.StringValue(metadata(
                entry, "displayName", 128, false, pointer + "/displayName"
        )));
        var edges = new ArrayList<DefinitionEdge>();
        switch (kindToken) {
            case "custom" -> {
                validateCustomDefinition(entry, pointer);
                outputTypes.put(definitionId, validateValueType(
                        required(entry, "valueType", pointer + "/valueType"),
                        pointer + "/valueType"
                ));
            }
            case "mapping" -> {
                validateMappingDefinition(entry, pointer, edges, loopIds, expressionCapacity);
                outputTypes.put(definitionId, validateValueType(
                        required(entry, "output", pointer + "/output"), pointer + "/output"
                ));
            }
            case "expression" -> {
                normalized.put(
                        "inputs",
                        validateExpressionDefinition(
                                entry, pointer, edges, loopIds, expressionCapacity)
                );
                outputTypes.put(definitionId, validateValueType(
                        required(entry, "output", pointer + "/output"), pointer + "/output"
                ));
            }
            default -> {
                // unreachable
            }
        }
        ids.add(definitionId);
        edgesByDefinition.add(edges);
        return new JsonValue.ObjectValue(normalized);
    }

    private void validateCustomDefinition(
            JsonValue.ObjectValue entry,
            String pointer
    ) throws DesignDslFailureException {
        enumMember(entry, "exposure", DefinitionContractCatalog.EXPOSURE_TOKENS, pointer + "/exposure");
        var valueType = validateValueType(
                required(entry, "valueType", pointer + "/valueType"),
                pointer + "/valueType"
        );
        validateLiteral(
                required(entry, "defaultValue", pointer + "/defaultValue"),
                valueType,
                pointer + "/defaultValue"
        );
    }

    private void validateMappingDefinition(
            JsonValue.ObjectValue entry,
            String pointer,
            List<DefinitionEdge> edges,
            Set<String> loopIds,
            ExpressionDefinitionCapacityBudget expressionCapacity
    ) throws DesignDslFailureException {
        validateDomain(required(entry, "domain", pointer + "/domain"), pointer + "/domain", loopIds);
        var output = validateValueType(required(entry, "output", pointer + "/output"),
                pointer + "/output");
        validateValueSource(required(entry, "input", pointer + "/input"), pointer + "/input",
                false, edges, loopIds);
        var cases = array(required(entry, "cases", pointer + "/cases"), pointer + "/cases");
        if (cases.items().isEmpty()) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/cases");
        }
        expressionCapacity.reserveMappingCases(cases.items().size(), pointer + "/cases");
        for (int index = 0; index < cases.items().size(); index++) {
            var casePointer = pointer + "/cases/" + index;
            var caseEntry = object(cases.items().get(index), casePointer);
            rejectUnknown(caseEntry, DefinitionContractCatalog.CASE_MEMBERS, casePointer);
            var operator = string(required(caseEntry, "operator", casePointer + "/operator"),
                    casePointer + "/operator");
            if (!DefinitionContractCatalog.MAPPING_OPERATORS.contains(operator)) {
                throw failure(FailureCode.DESIGN_VALUE_INVALID, casePointer + "/operator");
            }
            if (DefinitionContractCatalog.NO_OPERAND_OPERATORS.contains(operator)) {
                if (caseEntry.members().containsKey("operand")) {
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, casePointer + "/operand");
                }
            } else {
                var operandPointer = casePointer + "/operand";
                var operand = object(required(caseEntry, "operand", operandPointer), operandPointer);
                rejectUnknown(operand, DefinitionContractCatalog.OPERAND_MEMBERS, operandPointer);
                var operandType = validateValueType(
                        required(operand, "valueType", operandPointer + "/valueType"),
                        operandPointer + "/valueType"
                );
                validateLiteral(
                        required(operand, "value", operandPointer + "/value"),
                        operandType,
                        operandPointer + "/value"
                );
            }
            var thenType = validateValueSource(
                    required(caseEntry, "then", casePointer + "/then"),
                    casePointer + "/then",
                    false,
                    edges,
                    loopIds
            );
            if (thenType != null && !thenType.equals(output)) {
                throw failure(FailureCode.DESIGN_VALUE_INVALID, casePointer + "/then/valueType");
            }
        }
        var otherwiseType = validateValueSource(
                required(entry, "otherwise", pointer + "/otherwise"),
                pointer + "/otherwise",
                false,
                edges,
                loopIds
        );
        if (otherwiseType != null && !otherwiseType.equals(output)) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/otherwise/valueType");
        }
    }

    private JsonValue.ArrayValue validateExpressionDefinition(
            JsonValue.ObjectValue entry,
            String pointer,
            List<DefinitionEdge> edges,
            Set<String> loopIds,
            ExpressionDefinitionCapacityBudget expressionCapacity
    ) throws DesignDslFailureException {
        validateDomain(required(entry, "domain", pointer + "/domain"), pointer + "/domain", loopIds);
        validateValueType(required(entry, "output", pointer + "/output"), pointer + "/output");
        var inputs = array(required(entry, "inputs", pointer + "/inputs"), pointer + "/inputs");
        expressionCapacity.reserveInputs(inputs.items().size(), pointer + "/inputs");
        var aliases = new LinkedHashMap<String, String>();
        var normalizedInputs = new ArrayList<JsonValue>();
        for (int index = 0; index < inputs.items().size(); index++) {
            var inputPointer = pointer + "/inputs/" + index;
            var input = object(inputs.items().get(index), inputPointer);
            rejectUnknown(input, DefinitionContractCatalog.EXPRESSION_INPUT_MEMBERS, inputPointer);
            var alias = string(required(input, "alias", inputPointer + "/alias"), inputPointer + "/alias");
            if (!DefinitionContractCatalog.ALIAS.matcher(alias).matches() || aliases.containsKey(alias)) {
                throw failure(FailureCode.DESIGN_VALUE_INVALID, inputPointer + "/alias");
            }
            aliases.put(alias, inputPointer + "/alias");
            validateValueSource(required(input, "source", inputPointer + "/source"),
                    inputPointer + "/source", true, edges, loopIds);
            normalizedInputs.add(input);
        }
        var source = string(required(entry, "source", pointer + "/source"), pointer + "/source");
        var sourceUtf8 = source.getBytes(StandardCharsets.UTF_8);
        expressionCapacity.reserveSourceUtf8Bytes(
                sourceUtf8.length,
                pointer + "/source"
        );
        var parsed = ExpressionParser.parse(
                sourceUtf8,
                candidate -> expressionCapacity.reserveAstNode(candidate, pointer + "/source"),
                value -> expressionCapacity.reserveAdmittedDecimal(value, pointer + "/source"),
                value -> expressionCapacity.reserveExplicitRoundingScale(
                        value, pointer + "/source")
        );
        if (parsed instanceof ExpressionParser.ParseRejected) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/source");
        }
        var used = new HashSet<String>();
        scanExpressionInputUsage(source, used);
        for (var alias : aliases.keySet()) {
            if (!used.contains(alias)) {
                throw failure(FailureCode.DESIGN_VALUE_INVALID, aliases.get(alias));
            }
        }
        normalizedInputs.sort(Comparator.comparing(a -> ((JsonValue.ObjectValue) a).members()
                .get("alias") instanceof JsonValue.StringValue alias ? alias.value() : ""));
        return new JsonValue.ArrayValue(normalizedInputs);
    }

    /**
     * Lexical scan for {@code input.<alias>} usage in an Expression 1.0 source. Only a
     * bounded identifier-path scan: string literals are skipped, identifier tokens outside
     * strings are collected after the {@code input.} prefix. Full grammar/usage proof is
     * Evaluator-side; this scan only rejects clearly unused declared inputs.
     */
    private void scanExpressionInputUsage(String source, Set<String> used) {
        int index = 0;
        int length = source.length();
        while (index < length) {
            char current = source.charAt(index);
            if (current == '\'') {
                index = skipExpressionString(source, index + 1);
                continue;
            }
            if (isAsciiIdentifierStart(current)) {
                int tokenStart = index;
                while (index < length && isAsciiIdentifierPart(source.charAt(index))) {
                    index++;
                }
                if ("input".equals(source.substring(tokenStart, index))) {
                    index = skipExpressionWhitespace(source, index);
                    if (index < length && source.charAt(index) == '.') {
                        index = skipExpressionWhitespace(source, index + 1);
                        int aliasStart = index;
                        while (index < length && isAsciiIdentifierPart(source.charAt(index))) {
                            index++;
                        }
                        if (index > aliasStart) {
                            used.add(source.substring(aliasStart, index));
                        }
                    }
                }
                continue;
            }
            index++;
        }
    }

    private int skipExpressionString(String source, int index) {
        int length = source.length();
        while (index < length) {
            char current = source.charAt(index);
            if (current == '\\') {
                index += 2;
                continue;
            }
            if (current == '\'') {
                return index + 1;
            }
            index++;
        }
        return index;
    }

    private int skipExpressionWhitespace(String source, int index) {
        int length = source.length();
        while (index < length) {
            char current = source.charAt(index);
            if (current == ' ' || current == '\t' || current == '\r' || current == '\n') {
                index++;
            } else {
                return index;
            }
        }
        return index;
    }

    private boolean isAsciiIdentifierStart(char value) {
        return (value >= 'a' && value <= 'z')
                || (value >= 'A' && value <= 'Z')
                || value == '_';
    }

    private boolean isAsciiIdentifierPart(char value) {
        return isAsciiIdentifierStart(value) || (value >= '0' && value <= '9');
    }

    private void validateDomain(
            JsonValue value,
            String pointer,
            Set<String> loopIds
    ) throws DesignDslFailureException {
        if (value instanceof JsonValue.StringValue string) {
            if (!"invocation".equals(string.value())) {
                throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
            }
            return;
        }
        var domain = object(value, pointer);
        rejectUnknown(domain, DefinitionContractCatalog.DOMAIN_LOOP_MEMBERS, pointer);
        var kind = string(required(domain, "kind", pointer + "/kind"), pointer + "/kind");
        if (!"loop".equals(kind)) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/kind");
        }
        var loopId = string(required(domain, "loopId", pointer + "/loopId"), pointer + "/loopId");
        if (!UUID_V4.matcher(loopId).matches() || !loopIds.contains(loopId)) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/loopId");
        }
    }

    /**
     * ValueType wire: a base token string, {@code {"type":"list","items":<base>}} or
     * {@code {"type":"enum","catalogId":<id>}}. Returns the canonical type key
     * ({@code list<items>} for lists). v1 registers no global enum catalogs, so every
     * enum reference fails closed at the catalogId.
     */
    private String validateValueType(JsonValue value, String pointer)
            throws DesignDslFailureException {
        if (value instanceof JsonValue.StringValue string) {
            if (!DefinitionContractCatalog.BASE_VALUE_TYPES.contains(string.value())) {
                throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
            }
            return string.value();
        }
        var object = object(value, pointer);
        rejectUnknown(object, DefinitionContractCatalog.VALUE_TYPE_MEMBERS, pointer);
        var type = string(required(object, "type", pointer + "/type"), pointer + "/type");
        switch (type) {
            case "list" -> {
                var items = string(required(object, "items", pointer + "/items"), pointer + "/items");
                if (!DefinitionContractCatalog.LIST_ITEM_TYPES.contains(items)) {
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/items");
                }
                return "list<" + items + ">";
            }
            case "enum" -> {
                string(required(object, "catalogId", pointer + "/catalogId"),
                        pointer + "/catalogId");
                throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/catalogId");
            }
            default -> throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/type");
        }
    }

    /** Typed literal decoder shared by Custom defaults, Mapping operands and literal sources. */
    private void validateLiteral(JsonValue value, String typeKey, String pointer)
            throws DesignDslFailureException {
        if (typeKey.startsWith("list<")) {
            var itemType = typeKey.substring("list<".length(), typeKey.length() - 1);
            if (!(value instanceof JsonValue.ArrayValue array)) {
                throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
            }
            for (int index = 0; index < array.items().size(); index++) {
                validateLiteral(array.items().get(index), itemType, pointer + "/" + index);
            }
            return;
        }
        switch (typeKey) {
            case "text" -> {
                if (!(value instanceof JsonValue.StringValue)) {
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
                }
            }
            case "decimal" -> {
                if (!(value instanceof JsonValue.NumberValue)) {
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
                }
            }
            case "boolean" -> {
                if (!(value instanceof JsonValue.BooleanValue)) {
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
                }
            }
            case "date" -> {
                if (!(value instanceof JsonValue.StringValue string)
                        || !DefinitionContractCatalog.DATE.matcher(string.value()).matches()) {
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
                }
            }
            case "time" -> {
                if (!(value instanceof JsonValue.StringValue string)
                        || !DefinitionContractCatalog.TIME.matcher(string.value()).matches()) {
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
                }
            }
            case "color" -> {
                if (!(value instanceof JsonValue.StringValue string)
                        || !DefinitionContractCatalog.COLOR.matcher(string.value()).matches()) {
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
                }
            }
            case "imageRef", "fontRef" -> {
                if (!(value instanceof JsonValue.ObjectValue ref)) {
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
                }
                rejectUnknown(ref, DefinitionContractCatalog.ASSET_REF_MEMBERS, pointer);
                var assetId = string(required(ref, "assetId", pointer + "/assetId"),
                        pointer + "/assetId");
                if (!UUID_V4.matcher(assetId).matches()) {
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/assetId");
                }
            }
            default -> throw new IllegalStateException("unreachable value type: " + typeKey);
        }
    }

    /**
     * Validate one ValueSource occurrence. Capability sources are only legal as Expression
     * inputs. Returns the declared type for literal sources (null otherwise) so callers can
     * enforce exact output compatibility.
     */
    private String validateValueSource(
            JsonValue value,
            String pointer,
            boolean capabilityAllowed,
            List<DefinitionEdge> edges,
            Set<String> loopIds
    ) throws DesignDslFailureException {
        var source = object(value, pointer);
        var kind = string(required(source, "kind", pointer + "/kind"), pointer + "/kind");
        switch (kind) {
            case "literal" -> {
                rejectUnknown(source, DefinitionContractCatalog.LITERAL_SOURCE_MEMBERS, pointer);
                var valueType = validateValueType(
                        required(source, "valueType", pointer + "/valueType"),
                        pointer + "/valueType"
                );
                validateLiteral(required(source, "value", pointer + "/value"), valueType,
                        pointer + "/value");
                return valueType;
            }
            case "context" -> {
                rejectUnknown(source, DefinitionContractCatalog.CONTEXT_SOURCE_MEMBERS, pointer);
                validateDomain(required(source, "domain", pointer + "/domain"),
                        pointer + "/domain", loopIds);
                validateContextPointer(
                        string(required(source, "pointer", pointer + "/pointer"),
                                pointer + "/pointer"),
                        pointer + "/pointer"
                );
                return null;
            }
            case "loopIndex" -> {
                rejectUnknown(source, DefinitionContractCatalog.LOOP_INDEX_SOURCE_MEMBERS, pointer);
                var loopId = string(required(source, "loopId", pointer + "/loopId"),
                        pointer + "/loopId");
                if (!UUID_V4.matcher(loopId).matches() || !loopIds.contains(loopId)) {
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/loopId");
                }
                return null;
            }
            case "definition" -> {
                rejectUnknown(source, DefinitionContractCatalog.DEFINITION_SOURCE_MEMBERS, pointer);
                var target = string(required(source, "definitionId", pointer + "/definitionId"),
                        pointer + "/definitionId");
                if (!UUID_V4.matcher(target).matches()) {
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/definitionId");
                }
                edges.add(new DefinitionEdge(target, pointer + "/definitionId"));
                return null;
            }
            case "capability" -> {
                if (!capabilityAllowed) {
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/kind");
                }
                rejectUnknown(source, DefinitionContractCatalog.CAPABILITY_SOURCE_MEMBERS, pointer);
                var capability = string(required(source, "capability", pointer + "/capability"),
                        pointer + "/capability");
                var operations = DefinitionContractCatalog.CAPABILITY_OPERATIONS.get(capability);
                if (operations == null) {
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/capability");
                }
                var operation = string(required(source, "operation", pointer + "/operation"),
                        pointer + "/operation");
                if (!operations.contains(operation)) {
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/operation");
                }
                return null;
            }
            default -> throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/kind");
        }
    }

    /** Non-empty RFC 6901 pointer, ≤32 segments, decoded UTF-8 ≤1024 bytes; "/" root is rejected. */
    private void validateContextPointer(String contextPointer, String pointer)
            throws DesignDslFailureException {
        if (contextPointer.isEmpty()
                || !contextPointer.startsWith("/")
                || "/".equals(contextPointer)) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
        }
        int segments = 0;
        for (int index = 0; index < contextPointer.length(); index++) {
            char current = contextPointer.charAt(index);
            if (current == '/') {
                segments++;
                continue;
            }
            if (current == '~'
                    && (index + 1 >= contextPointer.length()
                    || (contextPointer.charAt(index + 1) != '0'
                    && contextPointer.charAt(index + 1) != '1'))) {
                throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
            }
        }
        if (segments > DefinitionContractCatalog.MAX_CONTEXT_POINTER_SEGMENTS) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
        }
        var decoded = contextPointer.replace("~1", "/").replace("~0", "~");
        if (decoded.getBytes(StandardCharsets.UTF_8).length
                > DefinitionContractCatalog.MAX_CONTEXT_POINTER_UTF8_BYTES) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
        }
    }

    /**
     * Definition graph must be acyclic; references may be forward but must resolve.
     * Deterministic iterative DFS in authored order; the error points at the source
     * reference that closes a cycle or dangles.
     */
    private DefinitionGraphFacts validateDefinitionGraph(
            List<String> ids,
            List<List<DefinitionEdge>> edgesByDefinition
    ) throws DesignDslFailureException {
        var indexById = new HashMap<String, Integer>();
        for (int index = 0; index < ids.size(); index++) {
            indexById.put(ids.get(index), index);
        }
        var state = new int[ids.size()];
        var chainDepthByDefinition = new long[ids.size()];
        long longestChainDepth = 0;
        for (int start = 0; start < ids.size(); start++) {
            if (state[start] != 0) {
                continue;
            }
            var path = new ArrayDeque<Integer>();
            var cursors = new ArrayDeque<Integer>();
            state[start] = 1;
            path.push(start);
            cursors.push(0);
            while (!path.isEmpty()) {
                int node = path.peek();
                var edges = edgesByDefinition.get(node);
                int cursor = cursors.pop();
                if (cursor >= edges.size()) {
                    long chainDepth = 0;
                    for (var edge : edges) {
                        int target = indexById.get(edge.targetId());
                        chainDepth = Math.max(
                                chainDepth,
                                Math.addExact(1, chainDepthByDefinition[target])
                        );
                    }
                    chainDepthByDefinition[node] = chainDepth;
                    longestChainDepth = Math.max(longestChainDepth, chainDepth);
                    state[node] = 2;
                    path.pop();
                    continue;
                }
                cursors.push(cursor + 1);
                var edge = edges.get(cursor);
                var target = indexById.get(edge.targetId());
                if (target == null) {
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, edge.pointer());
                }
                if (state[target] == 1) {
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, edge.pointer());
                }
                if (state[target] == 0) {
                    state[target] = 1;
                    path.push(target);
                    cursors.push(0);
                }
            }
        }
        long edgeCount = 0;
        for (var edges : edgesByDefinition) {
            edgeCount = Math.addExact(edgeCount, edges.size());
        }
        return new DefinitionGraphFacts(edgeCount, longestChainDepth);
    }

    private static String definitionIdOf(JsonValue value) {
        var member = ((JsonValue.ObjectValue) value).members().get("definitionId");
        return ((JsonValue.StringValue) member).value();
    }

    /** Recursively validate container children; the tree keeps authored order (paint z-order). */
    private JsonValue.ArrayValue validateChildren(
            JsonValue.ArrayValue children,
            String pointer,
            NodeContractCatalog.NodeKind parentKind,
            String parentDirection,
            Set<String> seenNodeIds,
            Set<String> seenLoopIds,
            Set<String> seenBindingIds,
            Set<String> seenUseIds,
            Map<String, String> definitionsOutputTypes,
            Set<String> loopIds
    ) throws DesignDslFailureException {
        var normalized = new ArrayList<JsonValue>();
        for (int index = 0; index < children.items().size(); index++) {
            var childPointer = pointer + "/" + index;
            var child = object(children.items().get(index), childPointer);
            normalized.add(validateNonCanvasNode(
                    child, childPointer, parentKind, parentDirection, seenNodeIds, seenLoopIds,
                    seenBindingIds, seenUseIds, definitionsOutputTypes, loopIds));
        }
        return new JsonValue.ArrayValue(normalized);
    }

    private JsonValue.ObjectValue validateNonCanvasNode(
            JsonValue.ObjectValue node,
            String pointer,
            NodeContractCatalog.NodeKind parentKind,
            String parentDirection,
            Set<String> seenNodeIds,
            Set<String> seenLoopIds,
            Set<String> seenBindingIds,
            Set<String> seenUseIds,
            Map<String, String> definitionsOutputTypes,
            Set<String> loopIds
    ) throws DesignDslFailureException {
        var kindToken = string(required(node, "kind", pointer + "/kind"), pointer + "/kind");
        var kind = NodeContractCatalog.KIND_BY_NAME.get(kindToken);
        if (kind == null) {
            if (NodeContractCatalog.FUTURE_KINDS.contains(kindToken)) {
                throw failure(FailureCode.DESIGN_KERNEL_SCOPE_UNSUPPORTED, pointer + "/kind");
            }
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/kind");
        }
        if (kind == NodeContractCatalog.NodeKind.CANVAS) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/kind");
        }
        rejectUnknown(node, allowedMembers(kind), pointer);
        var nodeId = string(required(node, "nodeId", pointer + "/nodeId"), pointer + "/nodeId");
        if (!UUID_V4.matcher(nodeId).matches() || !seenNodeIds.add(nodeId)) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/nodeId");
        }
        var normalized = new LinkedHashMap<>(node.members());
        normalized.put(
                "bindings",
                validateBindings(node, pointer, kind, seenBindingIds,
                        definitionsOutputTypes, loopIds)
        );
        if (node.members().containsKey("displayName")) {
            normalized.put(
                    "displayName",
                    new JsonValue.StringValue(metadata(
                            node, "displayName", 128, false, pointer + "/displayName"
                    ))
            );
        }
        if (node.members().containsKey("render")) {
            booleanValue(node, "render", pointer + "/render");
        }
        if (node.members().containsKey("visible")) {
            booleanValue(node, "visible", pointer + "/visible");
        }
        if (node.members().containsKey("opacity")) {
            rangedDecimal(node, "opacity", pointer + "/opacity", 0, 1);
        }
        if (node.members().containsKey("transform")) {
            validateTransform(node.members().get("transform"), pointer + "/transform");
        }
        var placement = object(required(node, "placement", pointer + "/placement"),
                pointer + "/placement");
        validatePlacement(placement, pointer + "/placement", kind, parentKind, parentDirection);
        String ownDirection = null;
        switch (kind) {
            case FRAME, STACK, GRID -> validateAppearanceMembers(node, pointer);
            case CANVAS, GROUP, REPEAT, TEXT, IMAGE, RECT, ELLIPSE, LINE, POLYGON, POLYLINE,
                    PATH, QRCODE, BARCODE, TEMPLATE_USE, CONDITIONAL -> {
            }
        }
        switch (kind) {
            case STACK -> ownDirection = validateStackMembers(node, pointer);
            case GRID -> validateGridMembers(node, pointer);
            case REPEAT -> validateRepeatMembers(node, pointer, seenLoopIds,
                    definitionsOutputTypes, loopIds);
            case TEMPLATE_USE -> normalized.put(
                    "fills",
                    validateTemplateUseMembers(node, pointer, seenUseIds,
                            definitionsOutputTypes, loopIds)
            );
            case CONDITIONAL -> validateConditionalMembers(node, pointer,
                    definitionsOutputTypes, loopIds);
            case TEXT -> validateTextMembers(node, pointer);
            case IMAGE -> validateImageMembers(node, pointer);
            case RECT -> validateRectMembers(node, pointer);
            case ELLIPSE -> validateEllipseMembers(node, pointer);
            case LINE -> validateLineMembers(node, pointer);
            case POLYGON -> validatePolygonMembers(node, pointer);
            case POLYLINE -> validatePolylineMembers(node, pointer);
            case PATH -> validatePathMembers(node, pointer);
            case QRCODE -> validateQrCodeMembers(node, pointer);
            case BARCODE -> validateBarcodeMembers(node, pointer);
            case CANVAS, GROUP, FRAME -> {
            }
        }
        if (NodeContractCatalog.allowsChildren(kind)) {
            var children = array(required(node, "children", pointer + "/children"),
                    pointer + "/children");
            if (kind == NodeContractCatalog.NodeKind.REPEAT && children.items().isEmpty()
                    || kind == NodeContractCatalog.NodeKind.CONDITIONAL
                    && children.items().isEmpty()) {
                throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/children");
            }
            normalized.put(
                    "children",
                    validateChildren(children, pointer + "/children", kind, ownDirection,
                            seenNodeIds, seenLoopIds, seenBindingIds, seenUseIds,
                            definitionsOutputTypes, loopIds)
            );
        }
        return new JsonValue.ObjectValue(normalized);
    }

    private void validateAppearanceMembers(
            JsonValue.ObjectValue node,
            String pointer
    ) throws DesignDslFailureException {
        if (node.members().containsKey("fill")) {
            validateFill(node.members().get("fill"), pointer + "/fill");
        }
        if (node.members().containsKey("stroke")) {
            validateStrokeMm(node.members().get("stroke"), pointer + "/stroke");
        }
        if (node.members().containsKey("cornerRadii")) {
            validateCornerRadii(node.members().get("cornerRadii"), pointer + "/cornerRadii");
        }
        if (node.members().containsKey("padding")) {
            validatePadding(node.members().get("padding"), pointer + "/padding");
        }
        if (node.members().containsKey("clipContent")) {
            booleanValue(node, "clipContent", pointer + "/clipContent");
        }
    }

    private String validateStackMembers(
            JsonValue.ObjectValue node,
            String pointer
    ) throws DesignDslFailureException {
        String direction = "COLUMN";
        if (node.members().containsKey("direction")) {
            enumMember(node, "direction", NodeContractCatalog.STACK_DIRECTION_TOKENS,
                    pointer + "/direction");
            direction = string(node.members().get("direction"), pointer + "/direction");
        }
        if (node.members().containsKey("gapMm")) {
            nonNegativeAuthoredMmDecimal(node, "gapMm", pointer + "/gapMm");
        }
        if (node.members().containsKey("justifyContent")) {
            enumMember(node, "justifyContent", NodeContractCatalog.JUSTIFY_CONTENT_TOKENS,
                    pointer + "/justifyContent");
        }
        if (node.members().containsKey("alignItems")) {
            enumMember(node, "alignItems", NodeContractCatalog.ALIGN_ITEMS_TOKENS,
                    pointer + "/alignItems");
        }
        return direction;
    }

    private void validateGridMembers(
            JsonValue.ObjectValue node,
            String pointer
    ) throws DesignDslFailureException {
        if (node.members().containsKey("rowGapMm")) {
            nonNegativeAuthoredMmDecimal(node, "rowGapMm", pointer + "/rowGapMm");
        }
        if (node.members().containsKey("columnGapMm")) {
            nonNegativeAuthoredMmDecimal(node, "columnGapMm", pointer + "/columnGapMm");
        }
        validateTracks(node, "rows", pointer + "/rows");
        validateTracks(node, "columns", pointer + "/columns");
    }

    private void validateTracks(
            JsonValue.ObjectValue node,
            String name,
            String pointer
    ) throws DesignDslFailureException {
        var tracks = array(required(node, name, pointer), pointer);
        if (tracks.items().isEmpty()) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
        }
        for (int index = 0; index < tracks.items().size(); index++) {
            var trackPointer = pointer + "/" + index;
            var track = object(tracks.items().get(index), trackPointer);
            var type = string(required(track, "type", trackPointer + "/type"),
                    trackPointer + "/type");
            switch (type) {
                case "FIXED" -> {
                    rejectUnknown(track, Set.of("type", "valueMm"), trackPointer);
                    positiveAuthoredMmDecimal(track, "valueMm", trackPointer + "/valueMm");
                }
                case "FRACTION" -> {
                    rejectUnknown(track, Set.of("type", "weight"), trackPointer);
                    positiveDecimal(track, "weight", trackPointer + "/weight");
                }
                case "AUTO" -> rejectUnknown(track, Set.of("type"), trackPointer);
                default -> throw failure(FailureCode.DESIGN_VALUE_INVALID, trackPointer + "/type");
            }
        }
    }

    private void validateFill(JsonValue value, String pointer) throws DesignDslFailureException {
        var fill = object(value, pointer);
        rejectUnknown(fill, NodeContractCatalog.FILL_MEMBERS, pointer);
        colorMember(fill, "color", pointer + "/color");
    }

    private void validateStrokeMm(JsonValue value, String pointer) throws DesignDslFailureException {
        var stroke = object(value, pointer);
        rejectUnknown(stroke, NodeContractCatalog.STROKE_MM_MEMBERS, pointer);
        colorMember(stroke, "color", pointer + "/color");
        positiveAuthoredMmDecimal(stroke, "widthMm", pointer + "/widthMm");
        enumMember(stroke, "cap", NodeContractCatalog.STROKE_CAP_TOKENS, pointer + "/cap");
        enumMember(stroke, "join", NodeContractCatalog.STROKE_JOIN_TOKENS, pointer + "/join");
    }

    private void validatePadding(JsonValue value, String pointer) throws DesignDslFailureException {
        var padding = object(value, pointer);
        rejectUnknown(padding, NodeContractCatalog.PADDING_MEMBERS, pointer);
        for (var member : NodeContractCatalog.PADDING_MEMBER_ORDER) {
            nonNegativeAuthoredMmDecimal(padding, member, pointer + "/" + member);
        }
    }

    private void validateCornerRadii(JsonValue value, String pointer) throws DesignDslFailureException {
        var radii = object(value, pointer);
        rejectUnknown(radii, NodeContractCatalog.CORNER_RADII_MEMBERS, pointer);
        for (var member : NodeContractCatalog.CORNER_RADII_MEMBER_ORDER) {
            nonNegativeAuthoredMmDecimal(radii, member, pointer + "/" + member);
        }
    }

    private void colorMember(
            JsonValue.ObjectValue object,
            String name,
            String pointer
    ) throws DesignDslFailureException {
        var color = string(required(object, name, pointer), pointer);
        if (!RGBA.matcher(color).matches()) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
        }
    }

    private Set<String> allowedMembers(NodeContractCatalog.NodeKind kind) {
        var members = new java.util.HashSet<>(NodeContractCatalog.COMMON_NODE_MEMBERS);
        if (NodeContractCatalog.allowsChildren(kind)) {
            members.addAll(NodeContractCatalog.CONTAINER_MEMBERS);
        }
        switch (kind) {
            case FRAME, STACK, GRID -> members.addAll(NodeContractCatalog.APPEARANCE_MEMBERS);
            case CANVAS, GROUP, REPEAT, TEXT, IMAGE, RECT, ELLIPSE, LINE, POLYGON, POLYLINE,
                    PATH, QRCODE, BARCODE, TEMPLATE_USE, CONDITIONAL -> {
            }
        }
        switch (kind) {
            case STACK -> members.addAll(NodeContractCatalog.STACK_MEMBERS);
            case GRID -> members.addAll(NodeContractCatalog.GRID_MEMBERS);
            case REPEAT -> members.addAll(NodeContractCatalog.REPEAT_MEMBERS);
            case TEMPLATE_USE -> members.addAll(NodeContractCatalog.TEMPLATE_USE_MEMBERS);
            case CONDITIONAL -> members.addAll(NodeContractCatalog.CONDITIONAL_MEMBERS);
            case TEXT -> members.addAll(NodeContractCatalog.TEXT_MEMBERS);
            case IMAGE -> members.addAll(NodeContractCatalog.IMAGE_MEMBERS);
            case RECT -> members.addAll(NodeContractCatalog.RECT_MEMBERS);
            case ELLIPSE -> members.addAll(NodeContractCatalog.ELLIPSE_MEMBERS);
            case LINE -> members.addAll(NodeContractCatalog.LINE_MEMBERS);
            case POLYGON -> members.addAll(NodeContractCatalog.POLYGON_MEMBERS);
            case POLYLINE -> members.addAll(NodeContractCatalog.POLYLINE_MEMBERS);
            case PATH -> members.addAll(NodeContractCatalog.PATH_MEMBERS);
            case QRCODE -> members.addAll(NodeContractCatalog.QRCODE_MEMBERS);
            case BARCODE -> members.addAll(NodeContractCatalog.BARCODE_MEMBERS);
            case CANVAS, GROUP, FRAME -> {
            }
        }
        return Set.copyOf(members);
    }

    /** Best-effort pre-pass that collects authored Repeat loopIds (never rejects). */
    private void collectLoopIds(JsonValue value, Set<String> loopIds) {
        if (value instanceof JsonValue.ObjectValue object) {
            if (object.members().get("kind") instanceof JsonValue.StringValue kind
                    && "repeat".equals(kind.value())
                    && object.members().get("loopId") instanceof JsonValue.StringValue loopId) {
                loopIds.add(loopId.value());
            }
            for (var member : object.members().values()) {
                collectLoopIds(member, loopIds);
            }
        } else if (value instanceof JsonValue.ArrayValue array) {
            for (var item : array.items()) {
                collectLoopIds(item, loopIds);
            }
        }
    }

    /**
     * Node-local bindings[] (ticket 07 §7): bindingId Template-unique, closed
     * targetPropertyRef resolved against the authored static tree with a unique
     * BindingPolicyCatalog entry, source restricted to context/loopIndex/definition.
     * Returns the canonical array sorted by bindingId.
     */
    private JsonValue.ArrayValue validateBindings(
            JsonValue.ObjectValue node,
            String nodePointer,
            NodeContractCatalog.NodeKind kind,
            Set<String> seenBindingIds,
            Map<String, String> definitionsOutputTypes,
            Set<String> loopIds
    ) throws DesignDslFailureException {
        var pointer = nodePointer + "/bindings";
        var bindings = array(required(node, "bindings", pointer), pointer);
        var normalized = new ArrayList<JsonValue>();
        var seenTargets = new HashSet<String>();
        for (int index = 0; index < bindings.items().size(); index++) {
            var bindingPointer = pointer + "/" + index;
            var binding = object(bindings.items().get(index), bindingPointer);
            rejectUnknown(binding, NodeContractCatalog.BINDING_MEMBERS, bindingPointer);
            var bindingId = string(required(binding, "bindingId", bindingPointer + "/bindingId"),
                    bindingPointer + "/bindingId");
            if (!UUID_V4.matcher(bindingId).matches() || !seenBindingIds.add(bindingId)) {
                throw failure(FailureCode.DESIGN_VALUE_INVALID, bindingPointer + "/bindingId");
            }
            var target = object(required(binding, "targetPropertyRef",
                    bindingPointer + "/targetPropertyRef"), bindingPointer + "/targetPropertyRef");
            var resolved = validateTargetPropertyRef(
                    target, bindingPointer + "/targetPropertyRef", node, kind);
            if (!seenTargets.add(resolved)) {
                throw failure(FailureCode.DESIGN_VALUE_INVALID,
                        bindingPointer + "/targetPropertyRef");
            }
            validateBindingSource(
                    required(binding, "source", bindingPointer + "/source"),
                    bindingPointer + "/source",
                    definitionsOutputTypes,
                    loopIds
            );
            normalized.add(binding);
        }
        normalized.sort(Comparator.comparing(a -> ((JsonValue.ObjectValue) a).members()
                .get("bindingId") instanceof JsonValue.StringValue bindingId
                ? bindingId.value() : ""));
        return new JsonValue.ArrayValue(normalized);
    }

    /**
     * Resolve a targetPropertyRef against the authored node: root must be authored,
     * at most one member and one fixed non-negative index selector, containers and the
     * final leaf must exist, and the derived {@code root[*].member} pattern must have a
     * unique BindingPolicyCatalog entry. Returns the resolved target identity.
     */
    private String validateTargetPropertyRef(
            JsonValue.ObjectValue target,
            String pointer,
            JsonValue.ObjectValue node,
            NodeContractCatalog.NodeKind kind
    ) throws DesignDslFailureException {
        rejectUnknown(target, NodeContractCatalog.TARGET_PROPERTY_REF_MEMBERS, pointer);
        var rootPropertyId = string(required(target, "rootPropertyId", pointer + "/rootPropertyId"),
                pointer + "/rootPropertyId");
        var selectors = array(required(target, "selectors", pointer + "/selectors"),
                pointer + "/selectors");
        if (selectors.items().size() > 2) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/selectors");
        }
        String member = null;
        String memberPointer = null;
        String indexToken = null;
        String indexPointer = null;
        for (int index = 0; index < selectors.items().size(); index++) {
            var selectorPointer = pointer + "/selectors/" + index;
            var selector = object(selectors.items().get(index), selectorPointer);
            var selectorKind = string(required(selector, "kind", selectorPointer + "/kind"),
                    selectorPointer + "/kind");
            switch (selectorKind) {
                case "member" -> {
                    rejectUnknown(selector, NodeContractCatalog.MEMBER_SELECTOR_MEMBERS,
                            selectorPointer);
                    if (member != null) {
                        throw failure(FailureCode.DESIGN_VALUE_INVALID, selectorPointer + "/kind");
                    }
                    member = string(required(selector, "name", selectorPointer + "/name"),
                            selectorPointer + "/name");
                    memberPointer = selectorPointer;
                }
                case "index" -> {
                    rejectUnknown(selector, NodeContractCatalog.INDEX_SELECTOR_MEMBERS,
                            selectorPointer);
                    if (indexToken != null) {
                        throw failure(FailureCode.DESIGN_VALUE_INVALID, selectorPointer + "/kind");
                    }
                    nonNegativeIntegerMember(selector, "index", selectorPointer + "/index");
                    indexToken = tokenOf(required(selector, "index", selectorPointer + "/index"));
                    indexPointer = selectorPointer;
                }
                default -> throw failure(FailureCode.DESIGN_VALUE_INVALID, selectorPointer + "/kind");
            }
        }
        var container = node.members().get(rootPropertyId);
        if (container == null) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/rootPropertyId");
        }
        if (indexToken != null) {
            if (!(container instanceof JsonValue.ArrayValue array)
                    || new BigDecimal(indexToken).compareTo(
                    new BigDecimal(array.items().size())) >= 0) {
                throw failure(FailureCode.DESIGN_VALUE_INVALID, indexPointer);
            }
            container = array.items().get(Integer.parseInt(indexToken));
        }
        if (member != null) {
            if (!(container instanceof JsonValue.ObjectValue objectValue)
                    || !objectValue.members().containsKey(member)) {
                throw failure(FailureCode.DESIGN_VALUE_INVALID, memberPointer);
            }
        }
        var pattern = rootPropertyId + (indexToken != null ? "[*]" : "")
                + (member != null ? "." + member : "");
        if (!BindingPolicyCatalog.allows(NodeContractCatalog.wireName(kind), pattern)) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
        }
        return rootPropertyId + (indexToken != null ? "[" + indexToken + "]" : "")
                + (member != null ? "." + member : "");
    }

    private String tokenOf(JsonValue value) {
        return ((JsonValue.NumberValue) value).token();
    }

    /**
     * TemplateUse structural members (ticket 12 §1, §3, §4): useId Template-unique,
     * templateRef {@code {templateId}} current-only, closed contextSelector union, and
     * fills sorted canonically by targetDefinitionId. Child-side existence/type checks
     * are dependency ERRORs resolved against the child Template current.
     */
    private JsonValue.ArrayValue validateTemplateUseMembers(
            JsonValue.ObjectValue node,
            String pointer,
            Set<String> seenUseIds,
            Map<String, String> definitionsOutputTypes,
            Set<String> loopIds
    ) throws DesignDslFailureException {
        var useId = string(required(node, "useId", pointer + "/useId"), pointer + "/useId");
        if (!UUID_V4.matcher(useId).matches() || !seenUseIds.add(useId)) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/useId");
        }
        var templateRef = object(required(node, "templateRef", pointer + "/templateRef"),
                pointer + "/templateRef");
        rejectUnknown(templateRef, NodeContractCatalog.TEMPLATE_REF_MEMBERS,
                pointer + "/templateRef");
        var templateId = string(required(templateRef, "templateId",
                pointer + "/templateRef/templateId"), pointer + "/templateRef/templateId");
        if (!UUID_V4.matcher(templateId).matches()) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/templateRef/templateId");
        }
        validateTemplateUseContextSelector(
                required(node, "contextSelector", pointer + "/contextSelector"),
                pointer + "/contextSelector",
                loopIds
        );
        var fills = array(required(node, "fills", pointer + "/fills"), pointer + "/fills");
        var normalizedFills = new ArrayList<JsonValue>();
        var seenTargets = new HashSet<String>();
        for (int index = 0; index < fills.items().size(); index++) {
            var fillPointer = pointer + "/fills/" + index;
            var fill = object(fills.items().get(index), fillPointer);
            rejectUnknown(fill, NodeContractCatalog.USE_FILL_MEMBERS, fillPointer);
            var targetDefinitionId = string(required(fill, "targetDefinitionId",
                    fillPointer + "/targetDefinitionId"), fillPointer + "/targetDefinitionId");
            if (!UUID_V4.matcher(targetDefinitionId).matches()
                    || !seenTargets.add(targetDefinitionId)) {
                throw failure(FailureCode.DESIGN_VALUE_INVALID,
                        fillPointer + "/targetDefinitionId");
            }
            validateBindingSource(
                    required(fill, "source", fillPointer + "/source"),
                    fillPointer + "/source",
                    definitionsOutputTypes,
                    loopIds
            );
            normalizedFills.add(fill);
        }
        normalizedFills.sort(Comparator.comparing(a -> ((JsonValue.ObjectValue) a).members()
                .get("targetDefinitionId") instanceof JsonValue.StringValue target
                ? target.value() : ""));
        return new JsonValue.ArrayValue(normalizedFills);
    }

    /**
     * Closed contextSelector union (ticket 12 §3): {@code {kind:"context",domain,pointer?,
     * contextAbsentPolicy}} with the selector-specific {@code {kind:"invocation"}} domain
     * object, or {@code {kind:"empty"}} (system-empty-only; no absent policy).
     */
    private void validateTemplateUseContextSelector(
            JsonValue value,
            String pointer,
            Set<String> loopIds
    ) throws DesignDslFailureException {
        var selector = object(value, pointer);
        var kind = string(required(selector, "kind", pointer + "/kind"), pointer + "/kind");
        switch (kind) {
            case "context" -> {
                rejectUnknown(selector, NodeContractCatalog.CONTEXT_SELECTOR_MEMBERS, pointer);
                validateTemplateUseSelectorDomain(
                        required(selector, "domain", pointer + "/domain"),
                        pointer + "/domain",
                        loopIds
                );
                if (selector.members().containsKey("pointer")) {
                    var selectorPointer = string(selector.members().get("pointer"),
                            pointer + "/pointer");
                    if (!selectorPointer.isEmpty()) {
                        validateContextPointer(selectorPointer, pointer + "/pointer");
                    }
                }
                enumMember(selector, "contextAbsentPolicy",
                        NodeContractCatalog.CONTEXT_ABSENT_POLICY_TOKENS,
                        pointer + "/contextAbsentPolicy");
            }
            case "empty" -> rejectUnknown(selector, NodeContractCatalog.EMPTY_SELECTOR_MEMBERS,
                    pointer);
            default -> throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/kind");
        }
    }

    private void validateTemplateUseSelectorDomain(
            JsonValue value,
            String pointer,
            Set<String> loopIds
    ) throws DesignDslFailureException {
        var domain = object(value, pointer);
        rejectUnknown(domain, NodeContractCatalog.SELECTOR_DOMAIN_MEMBERS, pointer);
        var kind = string(required(domain, "kind", pointer + "/kind"), pointer + "/kind");
        switch (kind) {
            case "invocation" -> {
            }
            case "loop" -> {
                var loopId = string(required(domain, "loopId", pointer + "/loopId"),
                        pointer + "/loopId");
                if (!UUID_V4.matcher(loopId).matches() || !loopIds.contains(loopId)) {
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/loopId");
                }
            }
            default -> throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/kind");
        }
    }

    /**
     * Conditional structural members (ticket 11 §1, §5): condition must be statically
     * boolean, absentPolicy exactly FALSE|ERROR, children non-empty with ABSOLUTE
     * placement (expectedVariant(CONDITIONAL)).
     */
    private void validateConditionalMembers(
            JsonValue.ObjectValue node,
            String pointer,
            Map<String, String> definitionsOutputTypes,
            Set<String> loopIds
    ) throws DesignDslFailureException {
        validateConditionalCondition(
                required(node, "condition", pointer + "/condition"),
                pointer + "/condition",
                definitionsOutputTypes,
                loopIds
        );
        enumMember(node, "absentPolicy", NodeContractCatalog.CONDITIONAL_ABSENT_POLICY_TOKENS,
                pointer + "/absentPolicy");
    }

    /**
     * Condition structural ValueSource (ticket 11 §2, §5): literal/definition sources
     * must be statically boolean; context type proof is deferred to dependency
     * resolution; loopIndex (decimal) and capability (date/time/decimal) are statically
     * non-boolean.
     */
    private void validateConditionalCondition(
            JsonValue value,
            String pointer,
            Map<String, String> definitionsOutputTypes,
            Set<String> loopIds
    ) throws DesignDslFailureException {
        var source = object(value, pointer);
        var kind = string(required(source, "kind", pointer + "/kind"), pointer + "/kind");
        switch (kind) {
            case "literal" -> {
                rejectUnknown(source, DefinitionContractCatalog.LITERAL_SOURCE_MEMBERS, pointer);
                var valueType = validateValueType(
                        required(source, "valueType", pointer + "/valueType"),
                        pointer + "/valueType"
                );
                if (!"boolean".equals(valueType)) {
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/valueType");
                }
                validateLiteral(required(source, "value", pointer + "/value"), valueType,
                        pointer + "/value");
            }
            case "definition" -> {
                rejectUnknown(source, DefinitionContractCatalog.DEFINITION_SOURCE_MEMBERS, pointer);
                var target = string(required(source, "definitionId", pointer + "/definitionId"),
                        pointer + "/definitionId");
                if (!UUID_V4.matcher(target).matches()) {
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/definitionId");
                }
                var output = definitionsOutputTypes.get(target);
                if (output == null || !"boolean".equals(output)) {
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/definitionId");
                }
            }
            case "context" -> {
                rejectUnknown(source, DefinitionContractCatalog.CONTEXT_SOURCE_MEMBERS, pointer);
                validateDomain(required(source, "domain", pointer + "/domain"),
                        pointer + "/domain", loopIds);
                validateContextPointer(
                        string(required(source, "pointer", pointer + "/pointer"),
                                pointer + "/pointer"),
                        pointer + "/pointer"
                );
            }
            default -> throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/kind");
        }
    }
    private void validateBindingSource(
            JsonValue value,
            String pointer,
            Map<String, String> definitionsOutputTypes,
            Set<String> loopIds
    ) throws DesignDslFailureException {
        var source = object(value, pointer);
        var kind = string(required(source, "kind", pointer + "/kind"), pointer + "/kind");
        switch (kind) {
            case "context" -> {
                rejectUnknown(source, DefinitionContractCatalog.CONTEXT_SOURCE_MEMBERS, pointer);
                validateDomain(required(source, "domain", pointer + "/domain"),
                        pointer + "/domain", loopIds);
                validateContextPointer(
                        string(required(source, "pointer", pointer + "/pointer"),
                                pointer + "/pointer"),
                        pointer + "/pointer"
                );
            }
            case "loopIndex" -> {
                rejectUnknown(source, DefinitionContractCatalog.LOOP_INDEX_SOURCE_MEMBERS, pointer);
                var loopId = string(required(source, "loopId", pointer + "/loopId"),
                        pointer + "/loopId");
                if (!UUID_V4.matcher(loopId).matches() || !loopIds.contains(loopId)) {
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/loopId");
                }
            }
            case "definition" -> {
                rejectUnknown(source, DefinitionContractCatalog.DEFINITION_SOURCE_MEMBERS, pointer);
                var target = string(required(source, "definitionId", pointer + "/definitionId"),
                        pointer + "/definitionId");
                if (!UUID_V4.matcher(target).matches()
                        || !definitionsOutputTypes.containsKey(target)) {
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/definitionId");
                }
            }
            default -> throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/kind");
        }
    }

    /** Repeat structural members: loopId, items, absentPolicy, itemLayout, instanceLayout. */
    private void validateRepeatMembers(
            JsonValue.ObjectValue node,
            String pointer,
            Set<String> seenLoopIds,
            Map<String, String> definitionsOutputTypes,
            Set<String> loopIds
    ) throws DesignDslFailureException {
        var loopId = string(required(node, "loopId", pointer + "/loopId"), pointer + "/loopId");
        if (!UUID_V4.matcher(loopId).matches() || !seenLoopIds.add(loopId)) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/loopId");
        }
        validateRepeatItems(node.members().get("items"), pointer + "/items",
                definitionsOutputTypes, loopIds);
        enumMember(node, "absentPolicy", NodeContractCatalog.ABSENT_POLICY_TOKENS,
                pointer + "/absentPolicy");
        validateRepeatPackingSpec(
                required(node, "itemLayout", pointer + "/itemLayout"), pointer + "/itemLayout");
        validateRepeatPackingSpec(
                required(node, "instanceLayout", pointer + "/instanceLayout"),
                pointer + "/instanceLayout");
    }

    /**
     * Repeat items structural ValueSource (ticket 11 §2): literal/context/definition only.
     * Literal and definition sources must be statically provable as {@code list<T>} with
     * T one of the five StaticSchema scalars; context type proof is deferred to dependency
     * resolution (StaticSchema), not an admission hard error.
     */
    private void validateRepeatItems(
            JsonValue value,
            String pointer,
            Map<String, String> definitionsOutputTypes,
            Set<String> loopIds
    ) throws DesignDslFailureException {
        var source = object(value, pointer);
        var kind = string(required(source, "kind", pointer + "/kind"), pointer + "/kind");
        switch (kind) {
            case "literal" -> {
                rejectUnknown(source, DefinitionContractCatalog.LITERAL_SOURCE_MEMBERS, pointer);
                var valueType = validateValueType(
                        required(source, "valueType", pointer + "/valueType"),
                        pointer + "/valueType"
                );
                if (!isRepeatListType(valueType)) {
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/valueType");
                }
                validateLiteral(required(source, "value", pointer + "/value"), valueType,
                        pointer + "/value");
            }
            case "definition" -> {
                rejectUnknown(source, DefinitionContractCatalog.DEFINITION_SOURCE_MEMBERS, pointer);
                var target = string(required(source, "definitionId", pointer + "/definitionId"),
                        pointer + "/definitionId");
                if (!UUID_V4.matcher(target).matches()) {
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/definitionId");
                }
                var output = definitionsOutputTypes.get(target);
                if (output == null || !isRepeatListType(output)) {
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/definitionId");
                }
            }
            case "context" -> {
                rejectUnknown(source, DefinitionContractCatalog.CONTEXT_SOURCE_MEMBERS, pointer);
                validateDomain(required(source, "domain", pointer + "/domain"),
                        pointer + "/domain", loopIds);
                validateContextPointer(
                        string(required(source, "pointer", pointer + "/pointer"),
                                pointer + "/pointer"),
                        pointer + "/pointer"
                );
            }
            default -> throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/kind");
        }
    }

    private boolean isRepeatListType(String typeKey) {
        if (!typeKey.startsWith("list<") || !typeKey.endsWith(">")) {
            return false;
        }
        var itemType = typeKey.substring("list<".length(), typeKey.length() - 1);
        return NodeContractCatalog.REPEAT_ITEM_TYPES.contains(itemType);
    }

    /** Closed RepeatPackingSpec union: STACK{direction,gapMm?} | GRID{columns,columnGapMm?,rowGapMm?}. */
    private void validateRepeatPackingSpec(JsonValue value, String pointer)
            throws DesignDslFailureException {
        var spec = object(value, pointer);
        var kind = string(required(spec, "kind", pointer + "/kind"), pointer + "/kind");
        switch (kind) {
            case "STACK" -> {
                rejectUnknown(spec, NodeContractCatalog.STACK_PACKING_SPEC_MEMBERS, pointer);
                enumMember(spec, "direction", NodeContractCatalog.STACK_DIRECTION_TOKENS,
                        pointer + "/direction");
                if (spec.members().containsKey("gapMm")) {
                    nonNegativeAuthoredMmDecimal(spec, "gapMm", pointer + "/gapMm");
                }
            }
            case "GRID" -> {
                rejectUnknown(spec, NodeContractCatalog.GRID_PACKING_SPEC_MEMBERS, pointer);
                positiveIntegerMember(spec, "columns", pointer + "/columns");
                if (spec.members().containsKey("columnGapMm")) {
                    nonNegativeAuthoredMmDecimal(
                            spec, "columnGapMm", pointer + "/columnGapMm");
                }
                if (spec.members().containsKey("rowGapMm")) {
                    nonNegativeAuthoredMmDecimal(spec, "rowGapMm", pointer + "/rowGapMm");
                }
            }
            default -> throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/kind");
        }
    }

    // --- Visual leaf members (ticket 09 §6-§7) -----------------------------------

    private void validateTextMembers(
            JsonValue.ObjectValue node,
            String pointer
    ) throws DesignDslFailureException {
        var runs = array(required(node, "runs", pointer + "/runs"), pointer + "/runs");
        if (runs.items().isEmpty()) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/runs");
        }
        for (int index = 0; index < runs.items().size(); index++) {
            var runPointer = pointer + "/runs/" + index;
            var run = object(runs.items().get(index), runPointer);
            rejectUnknown(run, NodeContractCatalog.RUN_MEMBERS, runPointer);
            var text = string(required(run, "text", runPointer + "/text"), runPointer + "/text");
            for (int at = 0; at < text.length(); at++) {
                if (text.charAt(at) < 0x20 && text.charAt(at) != '\n') {
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, runPointer + "/text");
                }
            }
            validateAssetRef(required(run, "fontRef", runPointer + "/fontRef"),
                    runPointer + "/fontRef");
            positiveDecimal(run, "fontSizePt", runPointer + "/fontSizePt");
            colorMember(run, "color", runPointer + "/color");
            enumMember(run, "decoration", NodeContractCatalog.DECORATION_TOKENS,
                    runPointer + "/decoration");
            var hasPt = run.members().containsKey("letterSpacingPt");
            var hasFactor = run.members().containsKey("letterSpacingFactor");
            if (!hasPt && !hasFactor) {
                throw failure(FailureCode.DESIGN_STRUCTURE_INVALID,
                        runPointer + "/letterSpacingPt");
            }
            if (hasPt && hasFactor) {
                throw failure(FailureCode.DESIGN_VALUE_INVALID,
                        runPointer + "/letterSpacingFactor");
            }
            if (hasPt) {
                decimalMember(run, "letterSpacingPt", runPointer + "/letterSpacingPt");
            } else {
                decimalMember(run, "letterSpacingFactor", runPointer + "/letterSpacingFactor");
            }
        }
        if (node.members().containsKey("writingMode")) {
            enumMember(node, "writingMode", NodeContractCatalog.WRITING_MODE_TOKENS,
                    pointer + "/writingMode");
        }
        if (node.members().containsKey("horizontalAlign")) {
            enumMember(node, "horizontalAlign", NodeContractCatalog.HORIZONTAL_ALIGN_TOKENS,
                    pointer + "/horizontalAlign");
        }
        if (node.members().containsKey("verticalAlign")) {
            enumMember(node, "verticalAlign", NodeContractCatalog.VERTICAL_ALIGN_TOKENS,
                    pointer + "/verticalAlign");
        }
        if (node.members().containsKey("lineBreak")) {
            enumMember(node, "lineBreak", NodeContractCatalog.LINE_BREAK_TOKENS,
                    pointer + "/lineBreak");
        }
        var overflow = "CLIP";
        if (node.members().containsKey("overflow")) {
            enumMember(node, "overflow", NodeContractCatalog.TEXT_OVERFLOW_TOKENS,
                    pointer + "/overflow");
            overflow = string(node.members().get("overflow"), pointer + "/overflow");
        }
        if (node.members().containsKey("lineHeight")) {
            validateLineHeight(node.members().get("lineHeight"), pointer + "/lineHeight");
        }
        if (node.members().containsKey("maxLines")) {
            positiveIntegerMember(node, "maxLines", pointer + "/maxLines");
            if ("VISIBLE".equals(overflow)) {
                throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/maxLines");
            }
        }
        if (node.members().containsKey("padding")) {
            validatePadding(node.members().get("padding"), pointer + "/padding");
        }
        if (node.members().containsKey("stroke")) {
            validateStrokePt(node.members().get("stroke"), pointer + "/stroke");
        }
        if (node.members().containsKey("fitMode")) {
            enumMember(node, "fitMode", NodeContractCatalog.FIT_MODE_TOKENS, pointer + "/fitMode");
            var fitMode = string(node.members().get("fitMode"), pointer + "/fitMode");
            if ("SHRINK_TO_FIT".equals(fitMode)) {
                var minScale = required(node, "minScale", pointer + "/minScale");
                rangedDecimalValue(minScale, pointer + "/minScale", 0, 1);
                if (decimalValue(minScale, pointer + "/minScale").signum() == 0) {
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/minScale");
                }
            } else if (node.members().containsKey("minScale")) {
                throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/minScale");
            }
        } else if (node.members().containsKey("minScale")) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/minScale");
        }
    }

    private void validateLineHeight(JsonValue value, String pointer)
            throws DesignDslFailureException {
        var lineHeight = object(value, pointer);
        rejectUnknown(lineHeight, NodeContractCatalog.LINE_HEIGHT_MEMBERS, pointer);
        var type = string(required(lineHeight, "type", pointer + "/type"), pointer + "/type");
        switch (type) {
            case "FACTOR" -> {
                if (lineHeight.members().containsKey("valuePt")) {
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/valuePt");
                }
                positiveDecimal(lineHeight, "factor", pointer + "/factor");
            }
            case "FIXED" -> {
                if (lineHeight.members().containsKey("factor")) {
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/factor");
                }
                positiveDecimal(lineHeight, "valuePt", pointer + "/valuePt");
            }
            default -> throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/type");
        }
    }

    private void validateStrokePt(JsonValue value, String pointer)
            throws DesignDslFailureException {
        var stroke = object(value, pointer);
        rejectUnknown(stroke, NodeContractCatalog.STROKE_PT_MEMBERS, pointer);
        colorMember(stroke, "color", pointer + "/color");
        positiveDecimal(stroke, "widthPt", pointer + "/widthPt");
        enumMember(stroke, "cap", NodeContractCatalog.STROKE_CAP_TOKENS, pointer + "/cap");
        enumMember(stroke, "join", NodeContractCatalog.STROKE_JOIN_TOKENS, pointer + "/join");
    }

    private void validateImageMembers(
            JsonValue.ObjectValue node,
            String pointer
    ) throws DesignDslFailureException {
        validateAssetRef(required(node, "imageRef", pointer + "/imageRef"), pointer + "/imageRef");
        if (node.members().containsKey("fit")) {
            enumMember(node, "fit", NodeContractCatalog.IMAGE_FIT_TOKENS, pointer + "/fit");
        }
        if (node.members().containsKey("sampling")) {
            enumMember(node, "sampling", NodeContractCatalog.IMAGE_SAMPLING_TOKENS,
                    pointer + "/sampling");
        }
    }

    private void validateRectMembers(
            JsonValue.ObjectValue node,
            String pointer
    ) throws DesignDslFailureException {
        if (node.members().containsKey("fill")) {
            validateFill(node.members().get("fill"), pointer + "/fill");
        }
        if (node.members().containsKey("stroke")) {
            validateStrokeMm(node.members().get("stroke"), pointer + "/stroke");
        }
        if (node.members().containsKey("cornerRadii")) {
            validateCornerRadii(node.members().get("cornerRadii"), pointer + "/cornerRadii");
        }
        if (!node.members().containsKey("fill") && !node.members().containsKey("stroke")) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/fill");
        }
    }

    private void validateEllipseMembers(
            JsonValue.ObjectValue node,
            String pointer
    ) throws DesignDslFailureException {
        if (node.members().containsKey("fill")) {
            validateFill(node.members().get("fill"), pointer + "/fill");
        }
        if (node.members().containsKey("stroke")) {
            validateStrokeMm(node.members().get("stroke"), pointer + "/stroke");
        }
        if (!node.members().containsKey("fill") && !node.members().containsKey("stroke")) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/fill");
        }
    }

    private void validateLineMembers(
            JsonValue.ObjectValue node,
            String pointer
    ) throws DesignDslFailureException {
        var start = validatePointMm(required(node, "start", pointer + "/start"),
                pointer + "/start");
        var end = validatePointMm(required(node, "end", pointer + "/end"), pointer + "/end");
        if (start[0].compareTo(end[0]) == 0 && start[1].compareTo(end[1]) == 0) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/end");
        }
        validateStrokeMm(required(node, "stroke", pointer + "/stroke"), pointer + "/stroke");
    }

    private void validatePolygonMembers(
            JsonValue.ObjectValue node,
            String pointer
    ) throws DesignDslFailureException {
        var points = validatePointArray(node, pointer, "points", 3);
        if (points.get(0)[0].compareTo(points.get(points.size() - 1)[0]) == 0
                && points.get(0)[1].compareTo(points.get(points.size() - 1)[1]) == 0) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/points/"
                    + (points.size() - 1));
        }
        boolean collinear = true;
        for (int index = 2; index < points.size(); index++) {
            var cross = points.get(1)[0].subtract(points.get(0)[0])
                    .multiply(points.get(index)[1].subtract(points.get(0)[1]))
                    .subtract(points.get(1)[1].subtract(points.get(0)[1])
                            .multiply(points.get(index)[0].subtract(points.get(0)[0])));
            if (cross.signum() != 0) {
                collinear = false;
                break;
            }
        }
        if (collinear) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/points");
        }
        validateOptionalFillStroke(node, pointer);
    }

    private void validatePolylineMembers(
            JsonValue.ObjectValue node,
            String pointer
    ) throws DesignDslFailureException {
        validatePointArray(node, pointer, "points", 2);
        validateStrokeMm(required(node, "stroke", pointer + "/stroke"), pointer + "/stroke");
    }

    private void validatePathMembers(
            JsonValue.ObjectValue node,
            String pointer
    ) throws DesignDslFailureException {
        var commands = array(required(node, "commands", pointer + "/commands"),
                pointer + "/commands");
        if (commands.items().isEmpty()) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/commands");
        }
        boolean hasDrawing = false;
        for (int index = 0; index < commands.items().size(); index++) {
            var commandPointer = pointer + "/commands/" + index;
            var command = object(commands.items().get(index), commandPointer);
            var type = string(required(command, "type", commandPointer + "/type"),
                    commandPointer + "/type");
            switch (type) {
                case "MOVE_TO" -> {
                    rejectUnknown(command, NodeContractCatalog.MOVE_TO_COMMAND_MEMBERS,
                            commandPointer);
                    authoredMmDecimal(command, "xMm", commandPointer + "/xMm");
                    authoredMmDecimal(command, "yMm", commandPointer + "/yMm");
                }
                case "LINE_TO" -> {
                    rejectUnknown(command, NodeContractCatalog.LINE_TO_COMMAND_MEMBERS,
                            commandPointer);
                    authoredMmDecimal(command, "xMm", commandPointer + "/xMm");
                    authoredMmDecimal(command, "yMm", commandPointer + "/yMm");
                    hasDrawing = true;
                }
                case "QUAD_TO" -> {
                    rejectUnknown(command, NodeContractCatalog.QUAD_TO_COMMAND_MEMBERS,
                            commandPointer);
                    authoredMmDecimal(command, "cxMm", commandPointer + "/cxMm");
                    authoredMmDecimal(command, "cyMm", commandPointer + "/cyMm");
                    authoredMmDecimal(command, "xMm", commandPointer + "/xMm");
                    authoredMmDecimal(command, "yMm", commandPointer + "/yMm");
                    hasDrawing = true;
                }
                case "CUBIC_TO" -> {
                    rejectUnknown(command, NodeContractCatalog.CUBIC_TO_COMMAND_MEMBERS,
                            commandPointer);
                    authoredMmDecimal(command, "c1xMm", commandPointer + "/c1xMm");
                    authoredMmDecimal(command, "c1yMm", commandPointer + "/c1yMm");
                    authoredMmDecimal(command, "c2xMm", commandPointer + "/c2xMm");
                    authoredMmDecimal(command, "c2yMm", commandPointer + "/c2yMm");
                    authoredMmDecimal(command, "xMm", commandPointer + "/xMm");
                    authoredMmDecimal(command, "yMm", commandPointer + "/yMm");
                    hasDrawing = true;
                }
                case "CLOSE" -> {
                    rejectUnknown(command, NodeContractCatalog.CLOSE_COMMAND_MEMBERS,
                            commandPointer);
                    if (index == 0) {
                        throw failure(FailureCode.DESIGN_VALUE_INVALID, commandPointer + "/type");
                    }
                    if (index + 1 < commands.items().size()
                            && !"MOVE_TO".equals(peekCommandType(commands.items().get(index + 1)))) {
                        throw failure(FailureCode.DESIGN_VALUE_INVALID,
                                pointer + "/commands/" + (index + 1) + "/type");
                    }
                }
                default -> throw failure(FailureCode.DESIGN_VALUE_INVALID, commandPointer + "/type");
            }
        }
        var firstType = commandType(commands.items().get(0), pointer + "/commands/0");
        if (!"MOVE_TO".equals(firstType)) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/commands/0/type");
        }
        if (!hasDrawing) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/commands");
        }
        validateOptionalFillStroke(node, pointer);
        if (node.members().containsKey("fillRule")) {
            enumMember(node, "fillRule", NodeContractCatalog.FILL_RULE_TOKENS, pointer + "/fillRule");
        }
    }

    private String commandType(JsonValue command, String pointer)
            throws DesignDslFailureException {
        return string(required(object(command, pointer), "type", pointer + "/type"),
                pointer + "/type");
    }

    /** Defensive peek at the next command's type; never rejects. */
    private String peekCommandType(JsonValue command) {
        if (command instanceof JsonValue.ObjectValue object
                && object.members().get("type") instanceof JsonValue.StringValue type) {
            return type.value();
        }
        return null;
    }

    private void validateQrCodeMembers(
            JsonValue.ObjectValue node,
            String pointer
    ) throws DesignDslFailureException {
        var content = string(required(node, "content", pointer + "/content"),
                pointer + "/content");
        if (content.isEmpty()) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/content");
        }
        if (node.members().containsKey("errorCorrectionLevel")) {
            enumMember(node, "errorCorrectionLevel",
                    NodeContractCatalog.QR_ERROR_CORRECTION_TOKENS,
                    pointer + "/errorCorrectionLevel");
        }
        if (node.members().containsKey("foregroundColor")) {
            colorMember(node, "foregroundColor", pointer + "/foregroundColor");
        }
        if (node.members().containsKey("backgroundColor")) {
            colorMember(node, "backgroundColor", pointer + "/backgroundColor");
        }
    }

    private void validateBarcodeMembers(
            JsonValue.ObjectValue node,
            String pointer
    ) throws DesignDslFailureException {
        var format = string(required(node, "format", pointer + "/format"), pointer + "/format");
        if (!NodeContractCatalog.BARCODE_FORMAT_TOKENS.contains(format)) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/format");
        }
        var value = string(required(node, "value", pointer + "/value"), pointer + "/value");
        if ("CODE_128".equals(format)) {
            if (value.isEmpty() || value.length() > 128) {
                throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/value");
            }
            for (int at = 0; at < value.length(); at++) {
                char current = value.charAt(at);
                if (current < 0x20 || current > 0x7e) {
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/value");
                }
            }
        } else {
            var expectedLength = switch (format) {
                case "EAN_8" -> 8;
                case "EAN_13" -> 13;
                case "UPC_A" -> 12;
                default -> throw new IllegalStateException("unreachable format");
            };
            if (value.length() != expectedLength || !value.chars().allMatch(
                    Character::isDigit)) {
                throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/value");
            }
            int sum = 0;
            for (int at = 0; at < expectedLength - 1; at++) {
                int digit = value.charAt(at) - '0';
                boolean oddPosition = (at % 2 == 0);
                boolean weightThree = "EAN_13".equals(format) ? !oddPosition : oddPosition;
                sum += digit * (weightThree ? 3 : 1);
            }
            int check = (10 - sum % 10) % 10;
            if (value.charAt(expectedLength - 1) - '0' != check) {
                throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/value");
            }
        }
        if (node.members().containsKey("foregroundColor")) {
            colorMember(node, "foregroundColor", pointer + "/foregroundColor");
        }
        if (node.members().containsKey("backgroundColor")) {
            colorMember(node, "backgroundColor", pointer + "/backgroundColor");
        }
    }

    /** points array: min size, each a PointMm, no adjacent duplicates. */
    private List<BigDecimal[]> validatePointArray(
            JsonValue.ObjectValue node,
            String pointer,
            String name,
            int minimum
    ) throws DesignDslFailureException {
        var points = array(required(node, name, pointer + "/" + name), pointer + "/" + name);
        if (points.items().size() < minimum) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/" + name);
        }
        var parsed = new ArrayList<BigDecimal[]>();
        for (int index = 0; index < points.items().size(); index++) {
            var pointPointer = pointer + "/" + name + "/" + index;
            var parsedPoint = validatePointMm(points.items().get(index), pointPointer);
            if (index > 0) {
                var previous = parsed.get(index - 1);
                if (previous[0].compareTo(parsedPoint[0]) == 0
                        && previous[1].compareTo(parsedPoint[1]) == 0) {
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, pointPointer);
                }
            }
            parsed.add(parsedPoint);
        }
        return parsed;
    }

    /** PointMm composite; coordinates may be negative. */
    private BigDecimal[] validatePointMm(JsonValue value, String pointer)
            throws DesignDslFailureException {
        var point = object(value, pointer);
        rejectUnknown(point, NodeContractCatalog.POINT_MM_MEMBERS, pointer);
        var x = authoredMmValue(required(point, "xMm", pointer + "/xMm"), pointer + "/xMm");
        var y = authoredMmValue(required(point, "yMm", pointer + "/yMm"), pointer + "/yMm");
        return new BigDecimal[]{x, y};
    }

    /** AssetRef atomic value: closed {assetId} with server-generated canonical UUID v4. */
    private void validateAssetRef(JsonValue value, String pointer)
            throws DesignDslFailureException {
        var refValue = object(value, pointer);
        rejectUnknown(refValue, DefinitionContractCatalog.ASSET_REF_MEMBERS, pointer);
        var assetId = string(required(refValue, "assetId", pointer + "/assetId"),
                pointer + "/assetId");
        if (!UUID_V4.matcher(assetId).matches()) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/assetId");
        }
    }

    /** Shared rect/ellipse/polygon/path rule: fill and/or stroke must be present. */
    private void validateOptionalFillStroke(
            JsonValue.ObjectValue node,
            String pointer
    ) throws DesignDslFailureException {
        if (node.members().containsKey("fill")) {
            validateFill(node.members().get("fill"), pointer + "/fill");
        }
        if (node.members().containsKey("stroke")) {
            validateStrokeMm(node.members().get("stroke"), pointer + "/stroke");
        }
        if (!node.members().containsKey("fill") && !node.members().containsKey("stroke")) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/fill");
        }
    }

    private void validatePlacement(
            JsonValue.ObjectValue placement,
            String pointer,
            NodeContractCatalog.NodeKind kind,
            NodeContractCatalog.NodeKind parentKind,
            String parentDirection
    ) throws DesignDslFailureException {
        var variantToken = string(required(placement, "type", pointer + "/type"), pointer + "/type");
        var expected = NodeContractCatalog.expectedVariant(parentKind);
        var variant = switch (variantToken) {
            case "ABSOLUTE" -> NodeContractCatalog.PlacementVariant.ABSOLUTE;
            case "STACK" -> NodeContractCatalog.PlacementVariant.STACK;
            case "GRID" -> NodeContractCatalog.PlacementVariant.GRID;
            case "PACK" -> NodeContractCatalog.PlacementVariant.PACK;
            default -> throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/type");
        };
        if (variant != expected) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/type");
        }
        switch (variant) {
            case ABSOLUTE -> {
                rejectUnknown(placement, NodeContractCatalog.ABSOLUTE_PLACEMENT_MEMBERS, pointer);
                authoredMmDecimal(placement, "xMm", pointer + "/xMm");
                authoredMmDecimal(placement, "yMm", pointer + "/yMm");
            }
            case STACK -> rejectUnknown(placement, NodeContractCatalog.STACK_PLACEMENT_MEMBERS, pointer);
            case GRID -> {
                rejectUnknown(placement, NodeContractCatalog.GRID_PLACEMENT_MEMBERS, pointer);
                nonNegativeIntegerMember(placement, "row", pointer + "/row");
                nonNegativeIntegerMember(placement, "column", pointer + "/column");
                if (placement.members().containsKey("rowSpan")) {
                    positiveIntegerMember(placement, "rowSpan", pointer + "/rowSpan");
                }
                if (placement.members().containsKey("columnSpan")) {
                    positiveIntegerMember(placement, "columnSpan", pointer + "/columnSpan");
                }
                if (placement.members().containsKey("horizontalAlignSelf")) {
                    enumMember(
                            placement, "horizontalAlignSelf",
                            NodeContractCatalog.ALIGN_ITEMS_TOKENS,
                            pointer + "/horizontalAlignSelf"
                    );
                }
                if (placement.members().containsKey("verticalAlignSelf")) {
                    enumMember(
                            placement, "verticalAlignSelf",
                            NodeContractCatalog.ALIGN_ITEMS_TOKENS,
                            pointer + "/verticalAlignSelf"
                    );
                }
            }
            case PACK -> rejectUnknown(placement, NodeContractCatalog.PACK_PLACEMENT_MEMBERS, pointer);
        }

        var widthMode = sizeModeMember(placement, "widthMode", pointer + "/widthMode");
        var heightMode = sizeModeMember(placement, "heightMode", pointer + "/heightMode");
        var modes = NodeContractCatalog.sizeModes(kind);
        if (variant == NodeContractCatalog.PlacementVariant.PACK && modes.contains(
                NodeContractCatalog.SizeMode.FILL)) {
            modes = Set.of(
                    NodeContractCatalog.SizeMode.FIXED, NodeContractCatalog.SizeMode.HUG_CONTENT);
        }
        if (!modes.contains(widthMode)) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/widthMode");
        }
        if (!modes.contains(heightMode)) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/heightMode");
        }
        if (kind == NodeContractCatalog.NodeKind.IMAGE
                && widthMode == NodeContractCatalog.SizeMode.HUG_CONTENT
                && heightMode == NodeContractCatalog.SizeMode.HUG_CONTENT) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/heightMode");
        }
        if (widthMode == NodeContractCatalog.SizeMode.FIXED) {
            positiveAuthoredMmDecimal(placement, "widthMm", pointer + "/widthMm");
        } else if (placement.members().containsKey("widthMm")) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/widthMm");
        }
        if (heightMode == NodeContractCatalog.SizeMode.FIXED) {
            positiveAuthoredMmDecimal(placement, "heightMm", pointer + "/heightMm");
        } else if (placement.members().containsKey("heightMm")) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/heightMm");
        }

        if (kind == NodeContractCatalog.NodeKind.GROUP) {
            for (var member : List.of(
                    "minWidthMm", "minHeightMm", "maxWidthMm", "maxHeightMm")) {
                if (placement.members().containsKey(member)) {
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/" + member);
                }
            }
        } else {
            validateMinMax(placement, pointer);
        }

        if (variant == NodeContractCatalog.PlacementVariant.ABSOLUTE) {
            if (placement.members().containsKey("rightInsetMm")) {
                if (widthMode != NodeContractCatalog.SizeMode.FILL) {
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/rightInsetMm");
                }
                authoredMmDecimal(placement, "rightInsetMm", pointer + "/rightInsetMm");
            }
            if (placement.members().containsKey("bottomInsetMm")) {
                if (heightMode != NodeContractCatalog.SizeMode.FILL) {
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/bottomInsetMm");
                }
                authoredMmDecimal(placement, "bottomInsetMm", pointer + "/bottomInsetMm");
            }
        }
        if (variant == NodeContractCatalog.PlacementVariant.STACK) {
            for (var member : List.of(
                    "marginTopMm", "marginRightMm", "marginBottomMm", "marginLeftMm")) {
                if (placement.members().containsKey(member)) {
                    authoredMmDecimal(placement, member, pointer + "/" + member);
                }
            }
            if (placement.members().containsKey("alignSelf")) {
                enumMember(placement, "alignSelf", NodeContractCatalog.ALIGN_ITEMS_TOKENS,
                        pointer + "/alignSelf");
                var crossAxisFill = "ROW".equals(parentDirection)
                        ? heightMode == NodeContractCatalog.SizeMode.FILL
                        : widthMode == NodeContractCatalog.SizeMode.FILL;
                if (crossAxisFill) {
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/alignSelf");
                }
            }
            if (placement.members().containsKey("fillWeight")) {
                var mainAxisFill = "ROW".equals(parentDirection)
                        ? widthMode == NodeContractCatalog.SizeMode.FILL
                        : heightMode == NodeContractCatalog.SizeMode.FILL;
                if (!mainAxisFill) {
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/fillWeight");
                }
                positiveDecimal(placement, "fillWeight", pointer + "/fillWeight");
            }
        }
        if (variant == NodeContractCatalog.PlacementVariant.GRID) {
            for (var member : List.of(
                    "marginTopMm", "marginRightMm", "marginBottomMm", "marginLeftMm")) {
                if (placement.members().containsKey(member)) {
                    authoredMmDecimal(placement, member, pointer + "/" + member);
                }
            }
            if (widthMode == NodeContractCatalog.SizeMode.FILL
                    && placement.members().containsKey("horizontalAlignSelf")) {
                throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/horizontalAlignSelf");
            }
            if (heightMode == NodeContractCatalog.SizeMode.FILL
                    && placement.members().containsKey("verticalAlignSelf")) {
                throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/verticalAlignSelf");
            }
        }
    }

    private void validateMinMax(
            JsonValue.ObjectValue placement,
            String pointer
    ) throws DesignDslFailureException {
        for (var axis : List.of("Width", "Height")) {
            var minName = "min" + axis + "Mm";
            var maxName = "max" + axis + "Mm";
            if (placement.members().containsKey(minName)) {
                nonNegativeAuthoredMmDecimal(placement, minName, pointer + "/" + minName);
            }
            if (placement.members().containsKey(maxName)) {
                positiveAuthoredMmDecimal(placement, maxName, pointer + "/" + maxName);
            }
            if (placement.members().containsKey(minName) && placement.members().containsKey(maxName)) {
                var min = decimalValue(placement.members().get(minName), pointer + "/" + minName);
                var max = decimalValue(placement.members().get(maxName), pointer + "/" + maxName);
                if (min.compareTo(max) > 0) {
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/" + minName);
                }
            }
            if (placement.members().containsKey("widthMm") && "Width".equals(axis)) {
                var fixed = decimalValue(placement.members().get("widthMm"), pointer + "/widthMm");
                var min = placement.members().containsKey(minName)
                        ? decimalValue(placement.members().get(minName), pointer + "/" + minName)
                        : null;
                var max = placement.members().containsKey(maxName)
                        ? decimalValue(placement.members().get(maxName), pointer + "/" + maxName)
                        : null;
                if (min != null && fixed.compareTo(min) < 0) {
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/widthMm");
                }
                if (max != null && fixed.compareTo(max) > 0) {
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/widthMm");
                }
            }
            if (placement.members().containsKey("heightMm") && "Height".equals(axis)) {
                var fixed = decimalValue(placement.members().get("heightMm"), pointer + "/heightMm");
                var min = placement.members().containsKey(minName)
                        ? decimalValue(placement.members().get(minName), pointer + "/" + minName)
                        : null;
                var max = placement.members().containsKey(maxName)
                        ? decimalValue(placement.members().get(maxName), pointer + "/" + maxName)
                        : null;
                if (min != null && fixed.compareTo(min) < 0) {
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/heightMm");
                }
                if (max != null && fixed.compareTo(max) > 0) {
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/heightMm");
                }
            }
        }
    }

    private void validateTransform(JsonValue value, String pointer) throws DesignDslFailureException {
        var transform = object(value, pointer);
        rejectUnknown(transform, NodeContractCatalog.TRANSFORM_MEMBERS, pointer);
        decimalMember(transform, "rotationDeg", pointer + "/rotationDeg");
        nonZeroDecimal(transform, "scaleX", pointer + "/scaleX");
        nonZeroDecimal(transform, "scaleY", pointer + "/scaleY");
        rangedDecimal(transform, "originX", pointer + "/originX", 0, 1);
        rangedDecimal(transform, "originY", pointer + "/originY", 0, 1);
    }

    private NodeContractCatalog.SizeMode sizeModeMember(
            JsonValue.ObjectValue object,
            String name,
            String pointer
    ) throws DesignDslFailureException {
        var token = string(required(object, name, pointer), pointer);
        return switch (token) {
            case "FIXED" -> NodeContractCatalog.SizeMode.FIXED;
            case "HUG_CONTENT" -> NodeContractCatalog.SizeMode.HUG_CONTENT;
            case "FILL" -> NodeContractCatalog.SizeMode.FILL;
            default -> throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
        };
    }

    private void booleanValue(
            JsonValue.ObjectValue object,
            String name,
            String pointer
    ) throws DesignDslFailureException {
        if (!(required(object, name, pointer) instanceof JsonValue.BooleanValue)) {
            throw failure(FailureCode.DESIGN_STRUCTURE_INVALID, pointer);
        }
    }

    private void enumMember(
            JsonValue.ObjectValue object,
            String name,
            Set<String> allowed,
            String pointer
    ) throws DesignDslFailureException {
        var token = string(required(object, name, pointer), pointer);
        if (!allowed.contains(token)) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
        }
    }

    private void decimalMember(
            JsonValue.ObjectValue object,
            String name,
            String pointer
    ) throws DesignDslFailureException {
        var value = required(object, name, pointer);
        if (!(value instanceof JsonValue.NumberValue)) {
            throw failure(FailureCode.DESIGN_STRUCTURE_INVALID, pointer);
        }
        try {
            new BigDecimal(((JsonValue.NumberValue) value).token());
        } catch (NumberFormatException exception) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
        }
    }

    private BigDecimal decimalValue(JsonValue value, String pointer) throws DesignDslFailureException {
        if (!(value instanceof JsonValue.NumberValue number)) {
            throw failure(FailureCode.DESIGN_STRUCTURE_INVALID, pointer);
        }
        try {
            return new BigDecimal(number.token());
        } catch (NumberFormatException exception) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
        }
    }

    private void nonZeroDecimal(
            JsonValue.ObjectValue object,
            String name,
            String pointer
    ) throws DesignDslFailureException {
        var value = decimalValue(required(object, name, pointer), pointer);
        if (value.signum() == 0) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
        }
    }

    private void rangedDecimal(
            JsonValue.ObjectValue object,
            String name,
            String pointer,
            int minimum,
            int maximum
    ) throws DesignDslFailureException {
        var value = decimalValue(required(object, name, pointer), pointer);
        if (value.signum() < minimum || value.compareTo(new BigDecimal(maximum)) > 0) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
        }
    }

    private void nonNegativeIntegerMember(
            JsonValue.ObjectValue object,
            String name,
            String pointer
    ) throws DesignDslFailureException {
        var value = decimalValue(required(object, name, pointer), pointer);
        if (value.signum() < 0 || value.stripTrailingZeros().scale() > 0) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
        }
    }

    private void positiveIntegerMember(
            JsonValue.ObjectValue object,
            String name,
            String pointer
    ) throws DesignDslFailureException {
        var value = decimalValue(required(object, name, pointer), pointer);
        if (value.signum() <= 0 || value.stripTrailingZeros().scale() > 0) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
        }
    }

    private void rejectNull(JsonValue value, String pointer) throws DesignDslFailureException {
        switch (value) {
            case JsonValue.NullValue ignored ->
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
            case JsonValue.ObjectValue object -> {
                for (var entry : object.members().entrySet()) {
                    rejectNull(entry.getValue(), pointer + "/" + escape(entry.getKey()));
                }
            }
            case JsonValue.ArrayValue array -> {
                for (int index = 0; index < array.items().size(); index++) {
                    rejectNull(array.items().get(index), pointer + "/" + index);
                }
            }
            default -> {
            }
        }
    }

    private void rejectUnknown(
            JsonValue.ObjectValue object,
            Set<String> allowed,
            String pointer
    ) throws DesignDslFailureException {
        for (var name : object.members().keySet()) {
            if (!allowed.contains(name)) {
                throw failure(FailureCode.DESIGN_MEMBER_UNKNOWN, pointer + "/" + escape(name));
            }
        }
    }

    private void exactVersion(
            JsonValue.ObjectValue object,
            String name,
            String expected,
            String pointer
    ) throws DesignDslFailureException {
        var actual = string(required(object, name, pointer), pointer);
        if (!expected.equals(actual)) {
            throw failure(FailureCode.DESIGN_VERSION_UNSUPPORTED, pointer);
        }
    }

    private String metadata(
            JsonValue.ObjectValue object,
            String name,
            int maximumCodePoints,
            boolean blankMayDisappear,
            String pointer
    ) throws DesignDslFailureException {
        var value = string(required(object, name, pointer), pointer).trim();
        var length = value.codePointCount(0, value.length());
        if ((!blankMayDisappear && length == 0) || length > maximumCodePoints) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
        }
        return value;
    }

    private void positiveDecimal(
            JsonValue.ObjectValue object,
            String name,
            String pointer
    ) throws DesignDslFailureException {
        var value = required(object, name, pointer);
        if (!(value instanceof JsonValue.NumberValue number)) {
            throw failure(FailureCode.DESIGN_STRUCTURE_INVALID, pointer);
        }
        try {
            if (new BigDecimal(number.token()).signum() <= 0) {
                throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
            }
        } catch (NumberFormatException exception) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
        }
    }

    private void authoredMmDecimal(
            JsonValue.ObjectValue object,
            String name,
            String pointer
    ) throws DesignDslFailureException {
        reserveAuthoredMm(decimalValue(required(object, name, pointer), pointer), pointer);
    }

    private void positiveAuthoredMmDecimal(
            JsonValue.ObjectValue object,
            String name,
            String pointer
    ) throws DesignDslFailureException {
        var value = decimalValue(required(object, name, pointer), pointer);
        if (value.signum() <= 0) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
        }
        reserveAuthoredMm(value, pointer);
    }

    private void nonNegativeAuthoredMmDecimal(
            JsonValue.ObjectValue object,
            String name,
            String pointer
    ) throws DesignDslFailureException {
        var value = decimalValue(required(object, name, pointer), pointer);
        if (value.signum() < 0) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
        }
        reserveAuthoredMm(value, pointer);
    }

    private BigDecimal authoredMmValue(JsonValue value, String pointer)
            throws DesignDslFailureException {
        var decimal = decimalValue(value, pointer);
        reserveAuthoredMm(decimal, pointer);
        return decimal;
    }

    private void reserveAuthoredMm(BigDecimal value, String pointer)
            throws DesignDslFailureException {
        reserveCapacityDecimal(
                value.abs(),
                pointer,
                Limit.GEOMETRY_AUTHORED_COORDINATE_OR_LENGTH_MM_ABSOLUTE_MAX
        );
    }

    private void canvasTrimDecimal(
            JsonValue.ObjectValue object,
            String name,
            String pointer
    ) throws DesignDslFailureException {
        capacityDecimal(
                object,
                name,
                pointer,
                Limit.GEOMETRY_CANVAS_TRIM_MM_PER_AXIS_EXCLUSIVE_MIN,
                Limit.GEOMETRY_CANVAS_TRIM_MM_PER_AXIS_MAX
        );
    }

    private void capacityDecimal(
            JsonValue.ObjectValue object,
            String name,
            String pointer,
            Limit... limits
    ) throws DesignDslFailureException {
        reserveCapacityDecimal(
                decimalValue(required(object, name, pointer), pointer),
                pointer,
                limits
        );
    }

    private void reserveCapacityDecimal(
            BigDecimal decimal,
            String pointer,
            Limit... limits
    ) throws DesignDslFailureException {
        String observed;
        if (decimal.signum() == 0) {
            observed = "0";
        } else {
            var normalized = decimal.stripTrailingZeros();
            if (plainDecimalLength(normalized) > MAX_CANONICAL_UTF8_BYTES) {
                throw canonicalLimitFailure();
            }
            observed = normalized.toPlainString();
        }
        for (var limit : limits) {
            DesignInputExpressionCapacityAuthority.Decision decision;
            try {
                decision = capacity.evaluate(
                        new DesignInputExpressionCapacityAuthority.Observation(
                                limit.id(),
                                observed
                        )
                );
            } catch (RuntimeException unavailable) {
                throw geometryFailure(limit, pointer);
            }
            if (!(decision instanceof DesignInputExpressionCapacityAuthority.Accepted)) {
                throw geometryFailure(limit, pointer);
            }
        }
    }

    private long plainDecimalLength(BigDecimal decimal) {
        long digits = decimal.precision();
        long scale = decimal.scale();
        long magnitudeLength;
        if (scale <= 0) {
            magnitudeLength = Math.addExact(digits, -scale);
        } else if (digits <= scale) {
            magnitudeLength = Math.addExact(scale, 2L);
        } else {
            magnitudeLength = Math.addExact(digits, 1L);
        }
        return decimal.signum() < 0
                ? Math.addExact(magnitudeLength, 1L)
                : magnitudeLength;
    }

    private DesignDslFailureException canonicalLimitFailure() {
        return new DesignDslFailureException(new Rejected(
                FailureCode.DESIGN_DSL_LIMIT_EXCEEDED,
                FailureStage.DESIGN_CANONICAL_COUNT,
                "",
                Optional.of(Limit.CANONICAL_BYTES)
        ));
    }

    private DesignDslFailureException geometryFailure(Limit limit, String pointer) {
        return new DesignDslFailureException(new Rejected(
                FailureCode.DESIGN_PROPERTY_CONSTRAINT_INVALID,
                FailureStage.DESIGN_PROPERTY_AND_AGGREGATE_VALIDATION,
                pointer,
                Optional.of(limit)
        ));
    }

    private void rangedDecimalValue(
            JsonValue value,
            String pointer,
            int minimum,
            int maximum
    ) throws DesignDslFailureException {
        var number = decimalValue(value, pointer);
        if (number.signum() < minimum || number.compareTo(new BigDecimal(maximum)) > 0) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
        }
    }

    private void nonNegativeDecimal(
            JsonValue.ObjectValue object,
            String name,
            String pointer
    ) throws DesignDslFailureException {
        var value = required(object, name, pointer);
        if (!(value instanceof JsonValue.NumberValue number)) {
            throw failure(FailureCode.DESIGN_STRUCTURE_INVALID, pointer);
        }
        try {
            if (new BigDecimal(number.token()).signum() < 0) {
                throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
            }
        } catch (NumberFormatException exception) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
        }
    }

    private JsonValue required(
            JsonValue.ObjectValue object,
            String name,
            String pointer
    ) throws DesignDslFailureException {
        var value = object.members().get(name);
        if (value == null) {
            throw failure(FailureCode.DESIGN_STRUCTURE_INVALID, pointer);
        }
        return value;
    }

    private JsonValue.ObjectValue object(JsonValue value, String pointer)
            throws DesignDslFailureException {
        if (value instanceof JsonValue.ObjectValue object) {
            return object;
        }
        throw failure(FailureCode.DESIGN_STRUCTURE_INVALID, pointer);
    }

    private JsonValue.ArrayValue array(JsonValue value, String pointer)
            throws DesignDslFailureException {
        if (value instanceof JsonValue.ArrayValue array) {
            return array;
        }
        throw failure(FailureCode.DESIGN_STRUCTURE_INVALID, pointer);
    }

    private String string(JsonValue value, String pointer) throws DesignDslFailureException {
        if (value instanceof JsonValue.StringValue string) {
            return string.value();
        }
        throw failure(FailureCode.DESIGN_STRUCTURE_INVALID, pointer);
    }

    private DesignDslFailureException failure(FailureCode code, String pointer) {
        return new DesignDslFailureException(new Rejected(
                code,
                FailureStage.DESIGN_SEMANTIC_VALIDATION,
                pointer,
                Optional.empty()
        ));
    }

    private String contentHash(byte[] canonical) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(HASH_DOMAIN);
            digest.update(canonical);
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private String escape(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }
}
