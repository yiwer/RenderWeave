# 实现 Template 依赖投影（AssetRef/反向索引/STALE 消费）

Type: task
Status: resolved / automated_verified
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

## Resolution (T20)

- 提取：`AssetRefAtomExtractor` 在 admitted canonical DesignDSL 上按冻结契约提取——member 集恰为
  `{assetId}` 且值为 canonical UUID v4 的对象即 AssetRef atom；kind 来自宿主 member（imageRef/fontRef）
  或 typing valueType（literal value/defaultValue、mapping 操作数、asset-ref list 项）；
  TemplateUse occurrence 来自 `kind:"templateUse"` + `templateRef.templateId`；walk 按 canonical 树序，
  指针为 RFC 6901 转义。`TemplateDependencyProjection`（api）冻结 records。
- 投影：`TemplatePersistence` 的 create/append commit 携带 projection+readiness；PostgreSQL 端在同一
  事务内整体替换 `template_asset_reference`/`template_use_reference`（V021，FK CASCADE、asset_id/target
  索引），current 改变才替换，历史 revision 不参与。
- 反向 proof：`AssetReferenceAuthority.references(assetId)` 返回引用该 asset 的 ACTIVE TemplateId 列表
  （raw current-only proof；redactedCount 与 caller-scope 属 T12b 编排面，不在本票越权）。
- STALE：`TemplateAssetStaleConsumer` 以 `template_asset_stale_cursor` 可重放消费 `asset_audit_event`
  （仅 CONTENT_REPLACE/CONTENT_RESTORE/DELETE/RESTORE；METADATA_UPDATE 永不触发），把引用模板置
  STALE，再由 `TemplateReadinessAuthority.recheck`（系统级、无 invocation）重算 READY/INVALID 并持久化
  （乐观 revision 冲突自动重试）；`@Scheduled` 轮询生产接线。
- 评估：`TemplateDependencyEvaluator` 对 projection 的 AssetRef atom 与 TemplateUse 目标逐项核对
  （ACTIVE 存在/kind 匹配），并以 use-closure 传递 walk 做 DAG 环检测；探针不可用即
  Unavailable→Create/Save/Recheck Unavailable，依赖失败→INVALID，全过→READY。
- 验证：模板模块 31/31；app 纵切 `TemplateDependencyProjectionTest` 6/6（Testcontainers PG）；
  提取 fixtures 3 份 Java primary/Python independent 全等（A2）；`template` gate 全绿
  （kernel 211/211 + 提取 A2 + static authorityDiff=0，evidence
  `.sdlc/evidence/20260819-213252-template/`）；`fast` gate 绿；app 全量 294 tests 绿
  （含 canary 迁移数 21）。V021 只追加；Profile 仍 NOT_REGISTERED。
- 边界：Asset 删除的物理行删除不在本票（asset_aggregate→content_revision FK 为 ON DELETE RESTRICT，
  删除语义走 lifecycle=DELETED 软删，属 T12b）；child current 存在性/PUBLIC fill/StaticSchemaRef 匹配为
  依赖 ERROR 重检面（recheck 路径），不在 admission。
