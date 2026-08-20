package cn.hbads.renderweave.rendering.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * RenderInput Custom 消解所需的根 Template CustomDefinition 视图（冻结票据 06）：
 * 从 admitted canonical DesignDSL 提取 {@code kind:"custom"} definitions——稳定 definitionId、
 * 声明类型、必填 typed literal defaultValue 与 {@code PUBLIC | PRIVATE} exposure。
 */
final class CustomDefinitionView {

    /** DesignDSL canonical 解析预算，镜像 designDslParser 组。 */
    static final RenderJsonParser.JsonBudget DESIGN_DSL_BUDGET = new RenderJsonParser.JsonBudget(
            "designDslParser",
            16L * 1024 * 1024,
            64,
            1024,
            100_000,
            1_000_000L,
            1_048_576L,
            256
    );

    enum Exposure {
        PUBLIC,
        PRIVATE
    }

    record CustomDefinition(
            String definitionId,
            Exposure exposure,
            DesignValueDecoder.DesignValueType valueType,
            DesignValue defaultValue
    ) {
    }

    sealed interface ExtractionResult permits Extracted, ExtractionFailed {
    }

    record Extracted(Map<String, CustomDefinition> byDefinitionId) implements ExtractionResult {
        Extracted {
            byDefinitionId = Map.copyOf(byDefinitionId);
        }
    }

    /** canonical DesignDSL 是已准入文档；结构缺失属内部不变量违约。 */
    record ExtractionFailed() implements ExtractionResult {
    }

    private CustomDefinitionView() {
    }

    static ExtractionResult extract(byte[] canonicalDesignDslUtf8) {
        Objects.requireNonNull(canonicalDesignDslUtf8, "canonicalDesignDslUtf8");
        var parseResult = RenderJsonParser.parse(canonicalDesignDslUtf8, DESIGN_DSL_BUDGET);
        if (!(parseResult instanceof RenderJsonParser.Parsed parsed)
                || !(parsed.value() instanceof RenderJson.ObjectValue root)) {
            return new ExtractionFailed();
        }
        var definitionsNode = root.members().get("definitions");
        if (!(definitionsNode instanceof RenderJson.ArrayValue definitions)) {
            return new ExtractionFailed();
        }
        var byId = new LinkedHashMap<String, CustomDefinition>();
        for (var entry : definitions.items()) {
            if (!(entry instanceof RenderJson.ObjectValue definition)) {
                return new ExtractionFailed();
            }
            if (!(definition.members().get("kind") instanceof RenderJson.StringValue kind)
                    || !"custom".equals(kind.value())) {
                continue;
            }
            if (!(definition.members().get("definitionId") instanceof RenderJson.StringValue definitionId)
                    || !(definition.members().get("exposure") instanceof RenderJson.StringValue exposure)
                    || definition.members().get("valueType") == null
                    || definition.members().get("defaultValue") == null) {
                return new ExtractionFailed();
            }
            var exposureValue = switch (exposure.value()) {
                case "PUBLIC" -> Exposure.PUBLIC;
                case "PRIVATE" -> Exposure.PRIVATE;
                default -> null;
            };
            if (exposureValue == null) {
                return new ExtractionFailed();
            }
            DesignValueDecoder.DesignValueType valueType;
            try {
                valueType = DesignValueDecoder.parseValueType(definition.members().get("valueType"));
            } catch (IllegalArgumentException unsupported) {
                return new ExtractionFailed();
            }
            var decodedDefault = DesignValueDecoder.decode(
                    definition.members().get("defaultValue"), valueType, "/defaultValue");
            if (!(decodedDefault instanceof DesignValueDecoder.Decoded decoded)) {
                return new ExtractionFailed();
            }
            byId.put(definitionId.value(), new CustomDefinition(
                    definitionId.value(), exposureValue, valueType, decoded.value()));
        }
        return new Extracted(byId);
    }
}
