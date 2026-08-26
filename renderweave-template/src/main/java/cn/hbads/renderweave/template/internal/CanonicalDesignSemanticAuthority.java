package cn.hbads.renderweave.template.internal;

import cn.hbads.renderweave.template.api.DesignSemanticAuthority;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.ExpressionAst;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Map;

/**
 * Canonical DesignDSL → 语义值树解读：只消费已准入 canonical bytes，复用模块内部 strict
 * parser；任何解析失败都是内部不变量违约（admitted canonical 必然可解析）。
 */
final class CanonicalDesignSemanticAuthority implements DesignSemanticAuthority {

    @Override
    public InterpretationOutcome interpret(byte[] canonicalDesignDslUtf8) {
        try {
            var parsed = new StrictJsonParser().parse(canonicalDesignDslUtf8);
            if (!(translate(parsed) instanceof ObjectNode document)) {
                return new InterpretationFault();
            }
            return new Interpreted(document, expressionsByDefinitionId(parsed));
        } catch (DesignDslFailureException | IllegalStateException invariantFault) {
            return new InterpretationFault();
        }
    }

    private Map<String, ExpressionAst> expressionsByDefinitionId(JsonValue parsed)
            throws DesignDslFailureException {
        if (!(parsed instanceof JsonValue.ObjectValue root)
                || !(root.members().get("definitions") instanceof JsonValue.ArrayValue definitions)) {
            throw new IllegalStateException("admitted DesignDSL definitions missing");
        }
        var expressions = new LinkedHashMap<String, ExpressionAst>();
        for (var value : definitions.items()) {
            if (!(value instanceof JsonValue.ObjectValue definition)
                    || !(definition.members().get("kind") instanceof JsonValue.StringValue kind)) {
                throw new IllegalStateException("admitted DesignDSL definition malformed");
            }
            if (!"expression".equals(kind.value())) {
                continue;
            }
            if (!(definition.members().get("definitionId")
                    instanceof JsonValue.StringValue definitionId)
                    || !(definition.members().get("source") instanceof JsonValue.StringValue source)) {
                throw new IllegalStateException("admitted Expression definition malformed");
            }
            var outcome = ExpressionParser.parse(
                    source.value().getBytes(StandardCharsets.UTF_8), candidate -> {
                        // Capacity was atomically enforced by DesignDSL admission.
                    });
            if (!(outcome instanceof ExpressionParser.ParsedAst parsedAst)
                    || expressions.put(definitionId.value(), parsedAst.ast()) != null) {
                throw new IllegalStateException("admitted Expression syntax invariant failed");
            }
        }
        return expressions;
    }

    private DesignNodeValue translate(JsonValue value) {
        if (value instanceof JsonValue.ObjectValue object) {
            var members = new LinkedHashMap<String, DesignNodeValue>();
            for (var entry : object.members().entrySet()) {
                members.put(entry.getKey(), translate(entry.getValue()));
            }
            return new ObjectNode(members);
        }
        if (value instanceof JsonValue.ArrayValue array) {
            var items = new ArrayList<DesignNodeValue>(array.items().size());
            for (var item : array.items()) {
                items.add(translate(item));
            }
            return new ArrayNode(items);
        }
        if (value instanceof JsonValue.StringValue string) {
            return new Text(string.value());
        }
        if (value instanceof JsonValue.NumberValue number) {
            return new NumberToken(number.token());
        }
        if (value instanceof JsonValue.BooleanValue bool) {
            return new Bool(bool.value());
        }
        throw new IllegalStateException("unknown JsonValue variant");
    }
}
