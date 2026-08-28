package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.schema.definition.StaticSchemaRef;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * RenderInput 经 envelope 检查、精确 StaticSchema 权威验证和 Custom override 消解后形成的
 * 请求级不可变语义值（CONTEXT "AdmittedRenderInput"）。不是原始 JSON、持久化输入记录、
 * Workspace fixture、通用对象 map 或允许部分 Evaluation 的容器；Evaluator 不得越过它重读
 * RootDocument。{@code externalCustomOverrides} 只保留调用方实际提供且命中 PUBLIC Definition
 * 的 winner，默认值、PRIVATE/unknown assignment 与 duplicate loser 均排除；它只驱动外部 AssetRef
 * 预准入，不形成第二份 effective Custom 语义或 admission proof digest。
 */
record AdmittedRenderInput(
        StaticSchemaRef staticSchemaRef,
        TypedObject rootDocument,
        Map<String, DesignValue> customs,
        Map<String, DesignValue> externalCustomOverrides
) {
    AdmittedRenderInput {
        Objects.requireNonNull(staticSchemaRef, "staticSchemaRef");
        Objects.requireNonNull(rootDocument, "rootDocument");
        customs = Map.copyOf(customs);
        externalCustomOverrides = Map.copyOf(externalCustomOverrides);
    }
}

/**
 * 一份按 exact StaticSchema 声明字段封闭的 typed object view。
 * {@code fields} 的 {@link Optional#empty()} 值表示声明可选字段 ABSENT；每个键都有确定语义。
 */
record TypedObject(
        StaticSchemaRef reference,
        Map<String, Optional<TypedValue>> fields
) implements TypedValue {
    TypedObject {
        Objects.requireNonNull(reference, "reference");
        fields = Map.copyOf(fields);
    }
}
