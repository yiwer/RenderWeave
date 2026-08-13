# 从历史系统提取 DesignDSL 语义证据

Type: research
Status: resolved
Claimed by: research/template-v1-legacy-evidence
Blocked by: none

## Question

`D:\Yiwer\code\hbads-design-v2` 对 Template 上下文、DesignDSL 权威边界、数据/表达式、节点属性、布局、循环、嵌套、Asset、Evaluator/Scene 与预览实际做出了哪些可验证决定；哪些能力仅存在于规格或 UI、未形成闭环；哪些冲突与失败应成为新规格的反例？

## Answer

历史证据确认：Template 精确绑定不可变 PublishedSchema 上下文，DesignDSL 不由 Schema 生成；ValueSource 封闭为 `literal/context/scope/definition`，不存在通用外部数据源适配协议；动态语义由服务端消解为 sealed static scene，Renderer 不解释 DSL。取证commit中的重要反例是 v2 元素属性 authority 分散、缺少完整封闭的 per-kind schema，以及 loop/layout/nested/asset 虽有规格和纯组件、production kernel 当时只接通部分 kind 并明确拒绝 ArrayLoop。历史 Artifact 只是 sealed-scene JSON，SVG 仅为 Web 预览，不能证明存在 PNG/PDF 生产 Renderer。

2026-08-13 对历史项目最新clean HEAD的后续只读复核确认，production kernel 已新增`arrayLoop/templateHost`接线；这修正“最新实现仍拒绝ArrayLoop”的时点事实，但不改变上述取证commit，也不改变反例结论：最新历史系统仍在Java内形成带final box/transform/text line的可持久化MaterializedScene，并内嵌旧Asset/font语义，不能作为RenderWeave请求级RenderDocument或Rust Engine合同复用。

Context pointer：分支 `research/template-v1-legacy-evidence`，commit `dadd0a37986fc3ecb46e1772672e87defa26bc01`，文件 `docs/research/template-v1/historical-design-dsl-evidence.md`。
