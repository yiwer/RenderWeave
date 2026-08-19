package cn.hbads.renderweave.template.internal;

import cn.hbads.renderweave.template.api.DesignDslAuthority;

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

    private final StrictJsonParser parser = new StrictJsonParser();
    private final CanonicalJsonWriter writer = new CanonicalJsonWriter();

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
        var displayName = metadata(root, "displayName", 128, false, "/displayName");
        var definitions = array(required(root, "definitions", "/definitions"), "/definitions");
        var normalizedDefinitions = validateDefinitions(definitions, new java.util.HashSet<>());

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
        positiveDecimal(canvas, "widthMm", "/designRoot/widthMm");
        positiveDecimal(canvas, "heightMm", "/designRoot/heightMm");
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
                nonNegativeDecimal(
                        bleed,
                        member,
                        "/designRoot/bleed/" + member
                );
            }
        }
        var bindings = array(required(canvas, "bindings", "/designRoot/bindings"),
                "/designRoot/bindings");
        if (!bindings.items().isEmpty()) {
            throw failure(FailureCode.DESIGN_KERNEL_SCOPE_UNSUPPORTED, "/designRoot/bindings");
        }
        var children = array(required(canvas, "children", "/designRoot/children"),
                "/designRoot/children");
        var normalizedChildren = validateChildren(
                children,
                "/designRoot/children",
                NodeContractCatalog.NodeKind.CANVAS,
                null,
                new java.util.HashSet<>()
        );

        var normalizedCanvas = new LinkedHashMap<>(canvas.members());
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
        normalizedRoot.put("definitions", normalizedDefinitions);
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

    /**
     * Validate the top-level definitions[] closed union and return the canonical array
     * sorted by definitionId (set sorting; ticket 08 §108). Repeat loopId namespaces
     * cannot resolve until the Repeat atoms ticket lands, so loop domains/loopIndex
     * sources fail closed as dangling references.
     */
    private JsonValue.ArrayValue validateDefinitions(
            JsonValue.ArrayValue definitions,
            Set<String> loopIds
    ) throws DesignDslFailureException {
        var seenIds = new HashSet<String>();
        var ids = new ArrayList<String>();
        var edgesByDefinition = new ArrayList<List<DefinitionEdge>>();
        var normalized = new ArrayList<JsonValue>();
        for (int index = 0; index < definitions.items().size(); index++) {
            var pointer = "/definitions/" + index;
            var entry = object(definitions.items().get(index), pointer);
            normalized.add(validateDefinition(entry, pointer, seenIds, ids, edgesByDefinition, loopIds));
        }
        validateDefinitionGraph(ids, edgesByDefinition);
        normalized.sort(Comparator.comparing(CanonicalDesignDslAuthority::definitionIdOf));
        return new JsonValue.ArrayValue(normalized);
    }

    private JsonValue.ObjectValue validateDefinition(
            JsonValue.ObjectValue entry,
            String pointer,
            Set<String> seenIds,
            List<String> ids,
            List<List<DefinitionEdge>> edgesByDefinition,
            Set<String> loopIds
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
            case "custom" -> validateCustomDefinition(entry, pointer);
            case "mapping" -> validateMappingDefinition(entry, pointer, edges, loopIds);
            case "expression" -> normalized.put(
                    "inputs",
                    validateExpressionDefinition(entry, pointer, edges, loopIds)
            );
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
            Set<String> loopIds
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
            Set<String> loopIds
    ) throws DesignDslFailureException {
        validateDomain(required(entry, "domain", pointer + "/domain"), pointer + "/domain", loopIds);
        validateValueType(required(entry, "output", pointer + "/output"), pointer + "/output");
        var inputs = array(required(entry, "inputs", pointer + "/inputs"), pointer + "/inputs");
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
    private void validateDefinitionGraph(
            List<String> ids,
            List<List<DefinitionEdge>> edgesByDefinition
    ) throws DesignDslFailureException {
        var indexById = new HashMap<String, Integer>();
        for (int index = 0; index < ids.size(); index++) {
            indexById.put(ids.get(index), index);
        }
        var state = new int[ids.size()];
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
            Set<String> seenNodeIds
    ) throws DesignDslFailureException {
        var normalized = new ArrayList<JsonValue>();
        for (int index = 0; index < children.items().size(); index++) {
            var childPointer = pointer + "/" + index;
            var child = object(children.items().get(index), childPointer);
            normalized.add(validateNonCanvasNode(
                    child, childPointer, parentKind, parentDirection, seenNodeIds));
        }
        return new JsonValue.ArrayValue(normalized);
    }

    private JsonValue.ObjectValue validateNonCanvasNode(
            JsonValue.ObjectValue node,
            String pointer,
            NodeContractCatalog.NodeKind parentKind,
            String parentDirection,
            Set<String> seenNodeIds
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
        var bindings = array(required(node, "bindings", pointer + "/bindings"), pointer + "/bindings");
        if (!bindings.items().isEmpty()) {
            throw failure(FailureCode.DESIGN_KERNEL_SCOPE_UNSUPPORTED, pointer + "/bindings");
        }
        var normalized = new LinkedHashMap<>(node.members());
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
            case CANVAS, GROUP -> {
            }
        }
        switch (kind) {
            case STACK -> ownDirection = validateStackMembers(node, pointer);
            case GRID -> validateGridMembers(node, pointer);
            case CANVAS, FRAME, GROUP -> {
            }
        }
        var children = array(required(node, "children", pointer + "/children"), pointer + "/children");
        normalized.put(
                "children",
                validateChildren(children, pointer + "/children", kind, ownDirection, seenNodeIds)
        );
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
            nonNegativeDecimal(node, "gapMm", pointer + "/gapMm");
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
            nonNegativeDecimal(node, "rowGapMm", pointer + "/rowGapMm");
        }
        if (node.members().containsKey("columnGapMm")) {
            nonNegativeDecimal(node, "columnGapMm", pointer + "/columnGapMm");
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
                    positiveDecimal(track, "valueMm", trackPointer + "/valueMm");
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
        positiveDecimal(stroke, "widthMm", pointer + "/widthMm");
        enumMember(stroke, "cap", NodeContractCatalog.STROKE_CAP_TOKENS, pointer + "/cap");
        enumMember(stroke, "join", NodeContractCatalog.STROKE_JOIN_TOKENS, pointer + "/join");
    }

    private void validatePadding(JsonValue value, String pointer) throws DesignDslFailureException {
        var padding = object(value, pointer);
        rejectUnknown(padding, NodeContractCatalog.PADDING_MEMBERS, pointer);
        for (var member : NodeContractCatalog.PADDING_MEMBER_ORDER) {
            nonNegativeDecimal(padding, member, pointer + "/" + member);
        }
    }

    private void validateCornerRadii(JsonValue value, String pointer) throws DesignDslFailureException {
        var radii = object(value, pointer);
        rejectUnknown(radii, NodeContractCatalog.CORNER_RADII_MEMBERS, pointer);
        for (var member : NodeContractCatalog.CORNER_RADII_MEMBER_ORDER) {
            nonNegativeDecimal(radii, member, pointer + "/" + member);
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
        members.addAll(NodeContractCatalog.CONTAINER_MEMBERS);
        switch (kind) {
            case FRAME, STACK, GRID -> members.addAll(NodeContractCatalog.APPEARANCE_MEMBERS);
            case CANVAS, GROUP -> {
            }
        }
        switch (kind) {
            case STACK -> members.addAll(NodeContractCatalog.STACK_MEMBERS);
            case GRID -> members.addAll(NodeContractCatalog.GRID_MEMBERS);
            case CANVAS, FRAME, GROUP -> {
            }
        }
        return Set.copyOf(members);
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
        if ("PACK".equals(variantToken)) {
            throw failure(FailureCode.DESIGN_KERNEL_SCOPE_UNSUPPORTED, pointer + "/type");
        }
        var variant = switch (variantToken) {
            case "ABSOLUTE" -> NodeContractCatalog.PlacementVariant.ABSOLUTE;
            case "STACK" -> NodeContractCatalog.PlacementVariant.STACK;
            case "GRID" -> NodeContractCatalog.PlacementVariant.GRID;
            default -> throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/type");
        };
        if (variant != expected) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/type");
        }
        switch (variant) {
            case ABSOLUTE -> {
                rejectUnknown(placement, NodeContractCatalog.ABSOLUTE_PLACEMENT_MEMBERS, pointer);
                decimalMember(placement, "xMm", pointer + "/xMm");
                decimalMember(placement, "yMm", pointer + "/yMm");
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
            case PACK -> {
                // unreachable: PACK rejected above
            }
        }

        var widthMode = sizeModeMember(placement, "widthMode", pointer + "/widthMode");
        var heightMode = sizeModeMember(placement, "heightMode", pointer + "/heightMode");
        var modes = NodeContractCatalog.sizeModes(kind);
        if (!modes.contains(widthMode)) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/widthMode");
        }
        if (!modes.contains(heightMode)) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/heightMode");
        }
        if (widthMode == NodeContractCatalog.SizeMode.FIXED) {
            positiveDecimal(placement, "widthMm", pointer + "/widthMm");
        } else if (placement.members().containsKey("widthMm")) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/widthMm");
        }
        if (heightMode == NodeContractCatalog.SizeMode.FIXED) {
            positiveDecimal(placement, "heightMm", pointer + "/heightMm");
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
                decimalMember(placement, "rightInsetMm", pointer + "/rightInsetMm");
            }
            if (placement.members().containsKey("bottomInsetMm")) {
                if (heightMode != NodeContractCatalog.SizeMode.FILL) {
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/bottomInsetMm");
                }
                decimalMember(placement, "bottomInsetMm", pointer + "/bottomInsetMm");
            }
        }
        if (variant == NodeContractCatalog.PlacementVariant.STACK) {
            for (var member : List.of(
                    "marginTopMm", "marginRightMm", "marginBottomMm", "marginLeftMm")) {
                if (placement.members().containsKey(member)) {
                    decimalMember(placement, member, pointer + "/" + member);
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
                    decimalMember(placement, member, pointer + "/" + member);
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
                nonNegativeDecimal(placement, minName, pointer + "/" + minName);
            }
            if (placement.members().containsKey(maxName)) {
                positiveDecimal(placement, maxName, pointer + "/" + maxName);
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
