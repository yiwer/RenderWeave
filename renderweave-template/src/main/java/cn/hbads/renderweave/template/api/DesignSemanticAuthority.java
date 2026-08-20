package cn.hbads.renderweave.template.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Template-owned DesignDSL 语义解读 seam（ADR-0044：Evaluator 消费服务端权威解析后的
 * Canonical DesignDSL semantic value，而非上传 bytes、数据库 serializer 文本、raw repair
 * buffer 或客户端 AST）。只解读已准入的 canonical bytes；结构不变量破坏是内部不变量违约。
 *
 * <p>per-kind closed member 与 property validation 仍归 Template admission——Render 侧
 * Binding overlay 后以重构文档重新 admission 完成 exact leaf/aggregate 重验，不复制 Node switch。
 */
public interface DesignSemanticAuthority {

    InterpretationOutcome interpret(byte[] canonicalDesignDslUtf8);

    sealed interface InterpretationOutcome permits Interpreted, InterpretationFault {
    }

    /** 顶层文档语义值：object，成员含 dslVersion/expressionProfile/definitions/designRoot。 */
    record Interpreted(ObjectNode document) implements InterpretationOutcome {
        public Interpreted {
            Objects.requireNonNull(document, "document");
        }
    }

    /** canonical bytes 是已准入文档；解读失败属内部不变量违约，对外折叠 RENDER_INTERNAL_ERROR。 */
    record InterpretationFault() implements InterpretationOutcome {
    }

    /** DesignDSL 不可变语义值家族；decimal 保留原始 token 精度。 */
    sealed interface DesignNodeValue permits
            Text,
            NumberToken,
            Bool,
            ObjectNode,
            ArrayNode {
    }

    record Text(String value) implements DesignNodeValue {
        public Text {
            Objects.requireNonNull(value, "value");
        }
    }

    record NumberToken(String rawToken) implements DesignNodeValue {
        public NumberToken {
            Objects.requireNonNull(rawToken, "rawToken");
        }
    }

    record Bool(boolean value) implements DesignNodeValue {
    }

    record ObjectNode(Map<String, DesignNodeValue> members) implements DesignNodeValue {
        public ObjectNode {
            members = Map.copyOf(members);
        }
    }

    record ArrayNode(List<DesignNodeValue> items) implements DesignNodeValue {
        public ArrayNode {
            items = List.copyOf(items);
        }
    }
}
