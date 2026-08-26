package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.rendering.internal.ExpressionEvaluator.EvalAbsent;
import cn.hbads.renderweave.rendering.internal.ExpressionEvaluator.EvalError;
import cn.hbads.renderweave.rendering.internal.ExpressionEvaluator.EvalOutcome;
import cn.hbads.renderweave.rendering.internal.ExpressionEvaluator.EvalValue;
import cn.hbads.renderweave.rendering.internal.ExpressionEvaluator.RuntimeFailure;
import cn.hbads.renderweave.rendering.internal.ExpressionEvaluator.RuntimeFailureKind;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.ArrayNode;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.Bool;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.DesignNodeValue;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.NumberToken;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.ObjectNode;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.Text;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.ExpressionAst;
import cn.hbads.renderweave.template.api.DesignInputExpressionCapacityAuthority;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Definition 图求值（冻结票据 07 §2–§5）：custom 固定 invocation domain 且永远有具体值；
 * Mapping first-match + otherwise 必填；Expression 惰性 memoize（invocation-domain 每
 * invocation 最多一次、loop-domain 每实际 loop frame 最多一次）。definitions 数组顺序无求值
 * 含义；ABSENT 与 ERROR 按冻结规则传播。
 */
final class DefinitionEngine {

    /** capability 输入供给（Clock/Random 由 CapabilityState 物化）；position 参与派生与 digest。 */
    interface CapabilityProvider {
        EvalOutcome supply(String capability, String operation, byte[] callPosition);
    }

    /** 词法 frame 上下文：invocation typed context/customs + 活跃 loop frames。 */
    interface ResolutionScope {
        TypedObject context();

        Map<String, DesignValue> customs();

        LoopFrames loopFrames();

        DefinitionEngine definitions();

        CapabilityProvider capabilities();
    }

    /** loopId → 实际 loop frame（typed item + 零基 index）。 */
    record LoopFrames(Map<String, LoopFrame> frames) {
        static final LoopFrames EMPTY = new LoopFrames(Map.of());

        LoopFrames {
            frames = Map.copyOf(frames);
        }
    }

    record LoopFrame(TypedValue item, int index) {
    }

    private final Map<String, DesignNodeValue> definitionWires;
    private final Map<String, EvalOutcome> invocationMemo = new HashMap<>();
    private final Map<String, EvalOutcome> frameMemo = new HashMap<>();
    private final Map<String, ExpressionAst> parsedExpressions;
    private final DesignInputExpressionCapacityAuthority capacityAuthority;

    DefinitionEngine(
            List<DesignNodeValue> definitionsWire,
            Map<String, ExpressionAst> parsedExpressions,
            DesignInputExpressionCapacityAuthority capacityAuthority
    ) {
        this.definitionWires = new HashMap<>();
        this.parsedExpressions = Map.copyOf(parsedExpressions);
        this.capacityAuthority = Objects.requireNonNull(capacityAuthority, "capacityAuthority");
        for (var wire : definitionsWire) {
            if (wire instanceof ObjectNode definition
                    && definition.members().get("definitionId") instanceof Text id
                    && definition.members().get("kind") instanceof Text) {
                definitionWires.put(id.value(), definition);
            }
        }
    }

    EvalOutcome resolveDefinition(String definitionId, ResolutionScope scope, String frameKey) {
        var wire = definitionWires.get(definitionId);
        if (wire == null) {
            return dependencyError();
        }
        var kind = ((Text) ((ObjectNode) wire).members().get("kind")).value();
        if ("custom".equals(kind)) {
            var value = scope.customs().get(definitionId);
            return value == null ? dependencyError() : new EvalValue(value);
        }
        var memoKey = definitionId + "#" + frameKey;
        var memo = "expression".equals(kind) || "mapping".equals(kind)
                ? (frameKey.equals("invocation") ? invocationMemo : frameMemo)
                : invocationMemo;
        var cached = memo.get(memoKey);
        if (cached != null) {
            return cached;
        }
        var result = switch (kind) {
            case "mapping" -> evaluateMapping((ObjectNode) wire, scope, frameKey);
            case "expression" -> evaluateExpression((ObjectNode) wire, scope, frameKey);
            default -> dependencyError();
        };
        memo.put(memoKey, result);
        return result;
    }

    // ------------------------------------------------------------------
    // ValueSource resolution
    // ------------------------------------------------------------------

    EvalOutcome resolveSource(DesignNodeValue sourceWire, ResolutionScope scope, String frameKey) {
        if (!(sourceWire instanceof ObjectNode source)) {
            return dependencyError();
        }
        var kindNode = source.members().get("kind");
        if (!(kindNode instanceof Text kind)) {
            return dependencyError();
        }
        return switch (kind.value()) {
            case "literal" -> decodeLiteral(source);
            case "context" -> resolveContext(source, scope);
            case "loopIndex" -> resolveLoopIndex(source, scope);
            case "definition" -> {
                if (!(source.members().get("definitionId") instanceof Text target)) {
                    yield dependencyError();
                }
                yield resolveDefinition(target.value(), scope, frameKeyOf(source, scope, frameKey));
            }
            case "capability" -> {
                if (!(source.members().get("capability") instanceof Text capability)
                        || !(source.members().get("operation") instanceof Text operation)) {
                    yield dependencyError();
                }
                yield scope.capabilities().supply(
                        capability.value(), operation.value(),
                        callPositionOf(source, frameKey));
            }
            default -> dependencyError();
        };
    }

    /** definition source 的消费 frame：loop-domain definition 在其声明 loop frame 求值。 */
    private String frameKeyOf(ObjectNode source, ResolutionScope scope, String consumerFrameKey) {
        return consumerFrameKey;
    }

    /** capability demand 位置 canonical bytes：source wire + 消费 frame。 */
    static byte[] callPositionOf(ObjectNode source, String frameKey) {
        var wire = DesignJsonWriter.write(source);
        var framed = new byte[wire.length + 1 + frameKey.length()];
        System.arraycopy(wire, 0, framed, 0, wire.length);
        framed[wire.length] = 0;
        var keyBytes = frameKey.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        System.arraycopy(keyBytes, 0, framed, wire.length + 1, keyBytes.length);
        return framed;
    }

    private EvalOutcome decodeLiteral(ObjectNode literal) {
        var valueTypeWire = literal.members().get("valueType");
        var valueWire = literal.members().get("value");
        if (valueTypeWire == null || valueWire == null) {
            return dependencyError();
        }
        DesignValueDecoder.DesignValueType type;
        try {
            type = DesignValueDecoder.parseValueType(toRenderJson(valueTypeWire));
        } catch (IllegalArgumentException invalid) {
            return dependencyError();
        }
        var decoded = DesignValueDecoder.decode(toRenderJson(valueWire), type, "/value");
        if (decoded instanceof DesignValueDecoder.Decoded success) {
            return new EvalValue(success.value());
        }
        return dependencyError();
    }

    /** template-owned 语义值 → rendering-owned strict JSON 模型（decoder 复用）。 */
    static RenderJson toRenderJson(DesignNodeValue value) {
        if (value instanceof Text text) {
            return new RenderJson.StringValue(text.value(), 0, 0);
        }
        if (value instanceof NumberToken number) {
            return new RenderJson.NumberValue(number.rawToken(), 0, 0);
        }
        if (value instanceof Bool bool) {
            return new RenderJson.BooleanValue(bool.value(), 0, 0);
        }
        if (value instanceof ObjectNode object) {
            var members = new java.util.LinkedHashMap<String, RenderJson>();
            for (var entry : object.members().entrySet()) {
                members.put(entry.getKey(), toRenderJson(entry.getValue()));
            }
            return new RenderJson.ObjectValue(members, 0, 0);
        }
        if (value instanceof ArrayNode array) {
            var items = new java.util.ArrayList<RenderJson>(array.items().size());
            for (var item : array.items()) {
                items.add(toRenderJson(item));
            }
            return new RenderJson.ArrayValue(items, 0, 0);
        }
        throw new IllegalStateException("unknown DesignNodeValue variant");
    }

    private EvalOutcome resolveContext(ObjectNode source, ResolutionScope scope) {
        var domain = source.members().get("domain");
        var pointerNode = source.members().get("pointer");
        if (!(pointerNode instanceof Text pointer)) {
            return dependencyError();
        }
        TypedValue root;
        if (domain instanceof ObjectNode domainObject
                && domainObject.members().get("kind") instanceof Text domainKind
                && "loop".equals(domainKind.value())
                && domainObject.members().get("loopId") instanceof Text loopId) {
            var frame = scope.loopFrames().frames().get(loopId.value());
            if (frame == null) {
                return dependencyError();
            }
            root = frame.item();
        } else {
            root = scope.context();
        }
        return walkPointer(root, pointer.value());
    }

    /** RFC 6901 field path：只按声明字段下钻，optional 缺失 → typed ABSENT。 */
    private static EvalOutcome walkPointer(TypedValue root, String pointer) {
        if (pointer.isEmpty() || !pointer.startsWith("/")) {
            return dependencyError();
        }
        var segments = pointer.substring(1).split("/", -1);
        TypedValue current = root;
        for (var rawSegment : segments) {
            var segment = rawSegment.replace("~1", "/").replace("~0", "~");
            TypedObject typedObject;
            if (current instanceof TypedObject object) {
                typedObject = object;
            } else if (current instanceof TypedValue.Nested nested) {
                typedObject = nested.object();
            } else {
                return dependencyError();
            }
            var field = typedObject.fields().get(segment);
            if (field == null) {
                return dependencyError();
            }
            if (field.isEmpty()) {
                return new EvalAbsent();
            }
            current = field.get();
        }
        if (current instanceof TypedObject || current instanceof TypedValue.Nested) {
            return dependencyError();
        }
        return new EvalValue(toDesignValue(current));
    }

    private static DesignValue toDesignValue(TypedValue value) {
        if (value instanceof TypedValue.Text text) {
            return new DesignValue.Text(text.value());
        }
        if (value instanceof TypedValue.Decimal decimal) {
            return new DesignValue.Decimal(decimal.value());
        }
        if (value instanceof TypedValue.Bool bool) {
            return new DesignValue.Bool(bool.value());
        }
        if (value instanceof TypedValue.Date date) {
            return new DesignValue.Date(date.value());
        }
        if (value instanceof TypedValue.Time time) {
            return new DesignValue.Time(time.value());
        }
        if (value instanceof TypedValue.Array array) {
            var items = new java.util.ArrayList<DesignValue>(array.items().size());
            for (var item : array.items()) {
                items.add(toDesignValue(item));
            }
            var itemType = items.isEmpty() ? "text" : items.get(0).baseType();
            return new DesignValue.ListValue(itemType, items);
        }
        throw new IllegalStateException("context pointer must not end at object/reference");
    }

    private EvalOutcome resolveLoopIndex(ObjectNode source, ResolutionScope scope) {
        if (!(source.members().get("loopId") instanceof Text loopId)) {
            return dependencyError();
        }
        var frame = scope.loopFrames().frames().get(loopId.value());
        if (frame == null) {
            return dependencyError();
        }
        return new EvalValue(new DesignValue.Decimal(BigDecimal.valueOf(frame.index())));
    }

    // ------------------------------------------------------------------
    // Mapping
    // ------------------------------------------------------------------

    private EvalOutcome evaluateMapping(ObjectNode wire, ResolutionScope scope, String frameKey) {
        var inputWire = wire.members().get("input");
        if (inputWire == null) {
            return dependencyError();
        }
        var input = resolveSource(inputWire, scope, frameKey);
        if (input instanceof EvalError) {
            return input;
        }
        var cases = wire.members().get("cases");
        if (cases instanceof ArrayNode caseList) {
            for (var caseWire : caseList.items()) {
                if (!(caseWire instanceof ObjectNode mappingCase)) {
                    return dependencyError();
                }
                var matches = matchCase(mappingCase, input);
                if (matches instanceof EvalError) {
                    return matches;
                }
                if (matches instanceof EvalValue matched && matched.valueBool()) {
                    var thenWire = mappingCase.members().get("then");
                    return thenWire == null ? dependencyError()
                            : resolveSource(thenWire, scope, frameKey);
                }
            }
        }
        var otherwiseWire = wire.members().get("otherwise");
        return otherwiseWire == null ? dependencyError()
                : resolveSource(otherwiseWire, scope, frameKey);
    }

    private EvalOutcome matchCase(ObjectNode mappingCase, EvalOutcome input) {
        if (!(mappingCase.members().get("operator") instanceof Text operator)) {
            return dependencyError();
        }
        var name = operator.value();
        if (input instanceof EvalAbsent) {
            return bool("IS_ABSENT".equals(name));
        }
        if ("IS_ABSENT".equals(name)) {
            return bool(false);
        }
        if ("IS_PRESENT".equals(name)) {
            return bool(true);
        }
        var value = ((EvalValue) input).value();
        return switch (name) {
            case "EQ", "NOT_EQ" -> {
                var operand = operandValue(mappingCase);
                if (!(operand instanceof EvalValue operandValue)) {
                    yield dependencyError();
                }
                var equal = valuesEqual(value, operandValue.value());
                if (!(equal instanceof EvalValue equalFlag)) {
                    yield equal;
                }
                yield bool("EQ".equals(name) ? equalFlag.valueBool() : !equalFlag.valueBool());
            }
            case "GT", "GTE", "LT", "LTE" -> {
                var operand = operandValue(mappingCase);
                if (!(operand instanceof EvalValue operandValue)) {
                    yield dependencyError();
                }
                var ordered = compareOrdered(value, operandValue.value());
                if (ordered == null) {
                    yield dependencyError();
                }
                yield bool(switch (name) {
                    case "GT" -> ordered > 0;
                    case "GTE" -> ordered >= 0;
                    case "LT" -> ordered < 0;
                    default -> ordered <= 0;
                });
            }
            case "CONTAINS" -> {
                if (value instanceof DesignValue.ListValue list) {
                    var operand = operandValue(mappingCase);
                    if (!(operand instanceof EvalValue operandValue)) {
                        yield dependencyError();
                    }
                    yield bool(list.items().stream().anyMatch(item ->
                            valuesEqual(item, operandValue.value()) instanceof EvalValue flag
                                    && flag.valueBool()));
                }
                var operand = operandValue(mappingCase);
                if (!(operand instanceof EvalValue operandValue)
                        || !(value instanceof DesignValue.Text text)
                        || !(operandValue.value() instanceof DesignValue.Text needle)) {
                    yield dependencyError();
                }
                yield bool(text.value().contains(needle.value()));
            }
            case "STARTS_WITH", "ENDS_WITH" -> {
                var operand = operandValue(mappingCase);
                if (!(operand instanceof EvalValue operandValue)
                        || !(value instanceof DesignValue.Text text)
                        || !(operandValue.value() instanceof DesignValue.Text needle)) {
                    yield dependencyError();
                }
                yield bool("STARTS_WITH".equals(name)
                        ? text.value().startsWith(needle.value())
                        : text.value().endsWith(needle.value()));
            }
            case "PATTERN_MATCH" -> {
                var operand = operandValue(mappingCase);
                if (!(operand instanceof EvalValue operandValue)
                        || !(value instanceof DesignValue.Text text)
                        || !(operandValue.value() instanceof DesignValue.Text patternText)) {
                    yield dependencyError();
                }
                if (text.value().codePointCount(0, text.value().length()) > 1024) {
                    yield dependencyError();
                }
                yield bool(Pattern.compile(patternText.value()).matcher(text.value()).find());
            }
            case "IS_BLANK", "IS_NOT_BLANK" -> {
                if (!(value instanceof DesignValue.Text text)) {
                    yield dependencyError();
                }
                boolean blank = text.value().codePoints()
                        .allMatch(DefinitionEngine::isUnicodeWhiteSpace);
                yield bool("IS_BLANK".equals(name) ? blank : !blank);
            }
            default -> dependencyError();
        };
    }

    private EvalOutcome operandValue(ObjectNode mappingCase) {
        var operandWire = mappingCase.members().get("operand");
        if (operandWire == null) {
            return null;
        }
        if (!(operandWire instanceof ObjectNode operandObject)) {
            return null;
        }
        var decoded = decodeLiteral(operandObject);
        return decoded instanceof EvalValue ? decoded : null;
    }

    private static boolean isUnicodeWhiteSpace(int codePoint) {
        return (codePoint >= 0x0009 && codePoint <= 0x000D) || codePoint == 0x0020
                || codePoint == 0x0085 || codePoint == 0x00A0 || codePoint == 0x1680
                || (codePoint >= 0x2000 && codePoint <= 0x200A) || codePoint == 0x2028
                || codePoint == 0x2029 || codePoint == 0x202F || codePoint == 0x205F
                || codePoint == 0x3000;
    }

    private static EvalOutcome bool(boolean flag) {
        return new EvalValue(new DesignValue.Bool(flag));
    }

    private static EvalOutcome valuesEqual(DesignValue left, DesignValue right) {
        if (left instanceof DesignValue.Text a && right instanceof DesignValue.Text b) {
            return bool(a.value().equals(b.value()));
        }
        if (left instanceof DesignValue.Decimal a && right instanceof DesignValue.Decimal b) {
            return bool(a.value().compareTo(b.value()) == 0);
        }
        if (left instanceof DesignValue.Bool a && right instanceof DesignValue.Bool b) {
            return bool(a.value() == b.value());
        }
        if (left instanceof DesignValue.Date a && right instanceof DesignValue.Date b) {
            return bool(a.value().equals(b.value()));
        }
        if (left instanceof DesignValue.Time a && right instanceof DesignValue.Time b) {
            return bool(a.value().equals(b.value()));
        }
        if (left instanceof DesignValue.ImageRef a && right instanceof DesignValue.ImageRef b) {
            return bool(a.assetId().equals(b.assetId()));
        }
        if (left instanceof DesignValue.FontRef a && right instanceof DesignValue.FontRef b) {
            return bool(a.assetId().equals(b.assetId()));
        }
        return dependencyError();
    }

    private static Integer compareOrdered(DesignValue left, DesignValue right) {
        if (left instanceof DesignValue.Decimal a && right instanceof DesignValue.Decimal b) {
            return a.value().compareTo(b.value());
        }
        if (left instanceof DesignValue.Date a && right instanceof DesignValue.Date b) {
            return a.value().compareTo(b.value());
        }
        if (left instanceof DesignValue.Time a && right instanceof DesignValue.Time b) {
            return a.value().compareTo(b.value());
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Expression definition
    // ------------------------------------------------------------------

    private EvalOutcome evaluateExpression(ObjectNode wire, ResolutionScope scope, String frameKey) {
        if (!(wire.members().get("source") instanceof Text source)) {
            return dependencyError();
        }
        var definitionId = ((Text) wire.members().get("definitionId")).value();
        var ast = parsedExpressions.get(definitionId);
        if (ast == null) {
            return dependencyError();
        }

        var inputWires = new HashMap<String, DesignNodeValue>();
        var declarations = new HashMap<String, ExpressionAnalyzer.InputDeclaration>();
        if (wire.members().get("inputs") instanceof ArrayNode inputs) {
            for (var inputWire : inputs.items()) {
                if (!(inputWire instanceof ObjectNode input)
                        || !(input.members().get("alias") instanceof Text alias)
                        || input.members().get("source") == null) {
                    return dependencyError();
                }
                inputWires.put(alias.value(), input.members().get("source"));
                declarations.put(alias.value(), declareInput(input.members().get("source"), scope));
            }
        }
        var analysis = ExpressionAnalyzer.analyze(ast, declarations);
        if (analysis instanceof ExpressionAnalyzer.AnalysisRejected) {
            return dependencyError();
        }
        return ExpressionEvaluator.evaluate(ast, alias -> {
            var inputSource = inputWires.get(alias);
            return inputSource == null ? dependencyError()
                    : resolveSource(inputSource, scope, frameKey);
        }, capacityAuthority);
    }

    private ExpressionAnalyzer.InputDeclaration declareInput(
            DesignNodeValue sourceWire, ResolutionScope scope) {
        if (!(sourceWire instanceof ObjectNode source)
                || !(source.members().get("kind") instanceof Text kind)) {
            return new ExpressionAnalyzer.InputDeclaration(
                    new DesignValueDecoder.BaseType("text"), true);
        }
        return switch (kind.value()) {
            case "literal" -> {
                var valueTypeWire = source.members().get("valueType");
                DesignValueDecoder.DesignValueType type;
                try {
                    type = valueTypeWire == null
                            ? new DesignValueDecoder.BaseType("text")
                            : DesignValueDecoder.parseValueType(toRenderJson(valueTypeWire));
                } catch (IllegalArgumentException invalid) {
                    type = new DesignValueDecoder.BaseType("text");
                }
                yield new ExpressionAnalyzer.InputDeclaration(type, false);
            }
            case "loopIndex" -> new ExpressionAnalyzer.InputDeclaration(
                    new DesignValueDecoder.BaseType("decimal"), false);
            case "capability" -> {
                var operation = source.members().get("operation");
                var type = operation instanceof Text op && op.value().startsWith("UTC_")
                        ? new DesignValueDecoder.BaseType(
                        op.value().equals("UTC_DATE") ? "date" : "time")
                        : new DesignValueDecoder.BaseType("decimal");
                yield new ExpressionAnalyzer.InputDeclaration(type, false);
            }
            case "context" -> {
                var pointerNode = source.members().get("pointer");
                var pointer = pointerNode instanceof Text text ? text.value() : "";
                yield contextDeclaration(pointer, source.members().get("domain"), scope);
            }
            default -> new ExpressionAnalyzer.InputDeclaration(
                    new DesignValueDecoder.BaseType("text"), true);
        };
    }

    private ExpressionAnalyzer.InputDeclaration contextDeclaration(
            String pointer, DesignNodeValue domain, ResolutionScope scope) {
        TypedValue root = scope.context();
        if (domain instanceof ObjectNode domainObject
                && domainObject.members().get("kind") instanceof Text domainKind
                && "loop".equals(domainKind.value())
                && domainObject.members().get("loopId") instanceof Text loopId) {
            var frame = scope.loopFrames().frames().get(loopId.value());
            if (frame == null) {
                return new ExpressionAnalyzer.InputDeclaration(
                        new DesignValueDecoder.BaseType("text"), true);
            }
            root = frame.item();
        }
        var target = root;
        boolean mayBeAbsent = false;
        if (!pointer.isEmpty() && pointer.startsWith("/")) {
            for (var segment : pointer.substring(1).split("/", -1)) {
                var decoded = segment.replace("~1", "/").replace("~0", "~");
                TypedObject object;
                if (target instanceof TypedObject typedObject) {
                    object = typedObject;
                } else if (target instanceof TypedValue.Nested nested) {
                    object = nested.object();
                } else {
                    return new ExpressionAnalyzer.InputDeclaration(
                            new DesignValueDecoder.BaseType("text"), true);
                }
                var field = object.fields().get(decoded);
                if (field == null) {
                    return new ExpressionAnalyzer.InputDeclaration(
                            new DesignValueDecoder.BaseType("text"), true);
                }
                mayBeAbsent = field.isEmpty();
                if (field.isEmpty()) {
                    return new ExpressionAnalyzer.InputDeclaration(
                            new DesignValueDecoder.BaseType("text"), true);
                }
                target = field.get();
            }
        }
        var type = designTypeOf(target);
        return new ExpressionAnalyzer.InputDeclaration(type, mayBeAbsent);
    }

    private static DesignValueDecoder.DesignValueType designTypeOf(TypedValue value) {
        if (value instanceof TypedValue.Text) {
            return new DesignValueDecoder.BaseType("text");
        }
        if (value instanceof TypedValue.Decimal) {
            return new DesignValueDecoder.BaseType("decimal");
        }
        if (value instanceof TypedValue.Bool) {
            return new DesignValueDecoder.BaseType("boolean");
        }
        if (value instanceof TypedValue.Date) {
            return new DesignValueDecoder.BaseType("date");
        }
        if (value instanceof TypedValue.Time) {
            return new DesignValueDecoder.BaseType("time");
        }
        if (value instanceof TypedValue.Array array) {
            var itemType = "text";
            if (!array.items().isEmpty()) {
                var first = array.items().get(0);
                if (first instanceof TypedValue.Nested) {
                    itemType = "text";
                } else {
                    itemType = designTypeOf(first) instanceof DesignValueDecoder.BaseType base
                            ? base.name() : "text";
                }
            }
            return new DesignValueDecoder.ListOf(itemType);
        }
        return new DesignValueDecoder.BaseType("text");
    }

    private static EvalOutcome dependencyError() {
        return new EvalError(new RuntimeFailure(RuntimeFailureKind.TYPE_FAULT, null));
    }

    // ------------------------------------------------------------------
    // seams consumed by the Materializer
    // ------------------------------------------------------------------

    /** context selector 用：pointer 选取 typed subview；ABSENT/非法 → empty。 */
    static TypedObject selectSubview(TypedValue root, String pointer) {
        if (pointer.isEmpty()) {
            if (root instanceof TypedObject object) {
                return object;
            }
            if (root instanceof TypedValue.Nested nested) {
                return nested.object();
            }
            return null;
        }
        var outcome = walkPointer(root, pointer);
        if (outcome instanceof EvalValue value) {
            return null;
        }
        return null;
    }

    /** context selector（reference 字段）：取嵌套 typed object。 */
    static TypedObject selectReferenceSubview(TypedValue root, String pointer) {
        if (pointer.isEmpty() || !pointer.startsWith("/")) {
            return null;
        }
        var segments = pointer.substring(1).split("/", -1);
        TypedValue current = root;
        for (int index = 0; index < segments.length; index++) {
            var segment = segments[index].replace("~1", "/").replace("~0", "~");
            TypedObject typedObject;
            if (current instanceof TypedObject object) {
                typedObject = object;
            } else if (current instanceof TypedValue.Nested nested) {
                typedObject = nested.object();
            } else {
                return null;
            }
            var field = typedObject.fields().get(segment);
            if (field == null || field.isEmpty()) {
                return null;
            }
            current = field.get();
        }
        if (current instanceof TypedValue.Nested nested) {
            return nested.object();
        }
        if (current instanceof TypedObject object) {
            return object;
        }
        return null;
    }

    /** child invocation 的默认 Custom map：全部 custom definition 的 typed literal 默认值。 */
    Map<String, DesignValue> customDefaults() {
        var defaults = new HashMap<String, DesignValue>();
        for (var entry : definitionWires.entrySet()) {
            if (entry.getValue() instanceof ObjectNode definition
                    && definition.members().get("kind") instanceof Text kind
                    && "custom".equals(kind.value())
                    && definition.members().get("valueType") != null
                    && definition.members().get("defaultValue") != null) {
                DesignValueDecoder.DesignValueType type;
                try {
                    type = DesignValueDecoder.parseValueType(toRenderJson(definition.members().get("valueType")));
                } catch (IllegalArgumentException invalid) {
                    continue;
                }
                var decoded = DesignValueDecoder.decode(
                        toRenderJson(definition.members().get("defaultValue")), type, "/defaultValue");
                if (decoded instanceof DesignValueDecoder.Decoded success) {
                    defaults.put(entry.getKey(), success.value());
                }
            }
        }
        return defaults;
    }
}
