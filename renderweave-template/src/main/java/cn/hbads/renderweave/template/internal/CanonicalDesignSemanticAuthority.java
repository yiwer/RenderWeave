package cn.hbads.renderweave.template.internal;

import cn.hbads.renderweave.template.api.DesignSemanticAuthority;

import java.util.LinkedHashMap;
import java.util.ArrayList;

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
            return new Interpreted(document);
        } catch (DesignDslFailureException | IllegalStateException invariantFault) {
            return new InterpretationFault();
        }
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
