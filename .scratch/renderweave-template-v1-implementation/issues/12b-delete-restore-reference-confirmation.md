# 实现被引用 Asset 删除确认与恢复编排

Type: task
Status: open
Blocked by: 05, 11, Template 依赖投影票（未建）

## Question

如何物化被 ACTIVE current DesignDSL 引用 Asset 的软删除与恢复：应用层删除编排先经 Asset-owned
`AssetReferencePort`（app Adapter 桥接 Template-owned `AssetReferenceAuthority`）取得 current-only 引用 proof
与影响报告（完整影响数量 + 调用者有权读取的 Template 明细 + `redactedCount`），再签发有效期 5 分钟、单次
使用的确认 token（绑定 actorId/ownerScopeId/assetId/assetRevision 与完整引用 fingerprint），随后执行删除；
Template current 变更取按 assetId 排序的读 reservation、删除取独占 reservation 后重算 proof，任一 Asset、
引用、token 或权限事实漂移均零写并要求重新确认？删除不撤销已签发 fetch lease；成功删除后相关 Template 先
STALE 再重检为 INVALID（消费属于 Template 依赖投影票）。恢复携带 `expectedAssetRevision`，重新激活相同
assetId 与删除前 current，不新建 contentVersion。本票以 Template 依赖投影票（从 DesignDSL 提取 authored
AssetRef atom 的 current-only 投影与 `AssetReferenceAuthority` 物化）为 blocker，不预建 Template/DesignDSL
语义、跨上下文表或共享事务。
