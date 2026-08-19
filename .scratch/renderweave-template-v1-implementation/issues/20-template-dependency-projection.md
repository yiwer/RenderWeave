# 实现 Template 依赖投影（AssetRef/反向索引/STALE 消费）

Type: task
Status: open
Blocked by: 04, 05, 14, 19

## Question

如何在 Template current-only 依赖投影中物化（旧 map tickets 05/13 与 CONTEXT 的冻结规格）：从 ACTIVE
Template current 的完整 authored DesignDSL（含静态不可达 branch、baseline/default/Mapping 与 asset-ref
list 元素）提取全部 AssetRef atom 与 TemplateUse logical TemplateRef occurrence，形成事务性 current-only
投影（current 改变时整体替换，历史不参与，不聚合丢失 occurrence）；`AssetReferenceAuthority`（Template
owned，供 Asset delete 预检的 current-only proof 与 redactedCount）物化；Asset current 内容/删除/恢复
事件消费 `asset_audit_event` 可靠可重放事实使反向索引中 Template 进入 STALE 并异步重检（metadata 变化与
相同内容 no-op 不触发），TemplateReadiness 投影与确认保存 INVALID 的依赖 ERROR 重检接线？本票以
DesignDSL full-Profile 的 AssetRef/TemplateRef 原子（T14/T19）与 V020 审计事件（T12a）为前置；它也是
TV1-T12b（Asset delete/restore + 确认 token 编排）的 blocker。

权威输入：旧 map tickets 05/13、CONTEXT（Template dependency projection/TemplateReadiness/STALE/
AssetReferenceAuthority）、ADR-0042/0043/0044。
