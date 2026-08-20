package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.schema.definition.StaticSchemaRef;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * RenderInput 经 envelope 检查、精确 StaticSchema 权威验证和 Custom override 消解后形成的
 * 请求级不可变语义值（CONTEXT "AdmittedRenderInput"）。不是原始 JSON、持久化输入记录、
 * Workspace fixture、通用对象 map 或允许部分 Evaluation 的容器；Evaluator 不得越过它重读
 * RootDocument。
 */
record AdmittedRenderInput(
        StaticSchemaRef staticSchemaRef,
        TypedObject rootDocument,
        Map<String, DesignValue> customs
) {
    AdmittedRenderInput {
        Objects.requireNonNull(staticSchemaRef, "staticSchemaRef");
        Objects.requireNonNull(rootDocument, "rootDocument");
        customs = Map.copyOf(customs);
    }
}

/**
 * 一份按 exact StaticSchema 声明字段封闭的 typed object view。
 * {@code fields} 的 {@link Optional#empty()} 值表示声明可选字段 ABSENT；每个键都有确定语义。
 */
record TypedObject(
        StaticSchemaRef reference,
        Map<String, Optional<TypedValue>> fields
) {
    TypedObject {
        Objects.requireNonNull(reference, "reference");
        fields = Map.copyOf(fields);
    }
}
