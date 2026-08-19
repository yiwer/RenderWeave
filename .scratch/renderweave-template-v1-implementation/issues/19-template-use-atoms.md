# 实现 DesignDSL TemplateUse 原子

Type: task
Status: open
Blocked by: 03, 14, 15, 16

## Question

如何在 DesignDSL admission/canonicalization 中实现 TemplateUse 结构原子（旧 map ticket 12 的冻结规格）：
TemplateUse 是禁止 children 的结构 leaf，按 logical TemplateRef 调用同 ownerScope child Template current
（不钉死 revision、无 latest/cross-scope/dynamic 选择）；`useId` 命名空间唯一；显式 ContextSelector 选择
exact StaticSchema typed context 或 `system-empty@v1` 显式 empty context，并声明 `ERROR | SKIP` absent
policy；按 child 当前 PUBLIC definitionId 的显式 typed fills（重复 target/不存在/PRIVATE/类型不兼容是
hard error，fill source ABSENT 用 child default，fill 求值 ERROR 是依赖 ERROR）；固定 consumer order
（render → contextSelector/absentPolicy → placement → visible → opacity → transform → fills（按
targetDefinitionId 稳定）→ child invocation）中可静态判定的部分；TemplateRef closure 边（same-scope、
DAG、readiness 检查属 closure/recheck authority——本票只冻结 authored use 边的 admission 原子与 canonical
向量，不实现反向索引/STALE 消费（投影票）与 compositionViewport lowering（Rendering 侧））。
权威输入：旧 map ticket 12（nested-template-composition）与 08 冻结规格。
