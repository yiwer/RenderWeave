# 实现被引用 Asset 删除确认与恢复编排

Type: task
Status: resolved / automated_verified
Blocked by: 05, 11, 20

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

## Resolution (T12b)

- Asset-owned outbound port：`AssetReferencePort`（asset spi）`references(invocation, assetId)` 返回
  current-only 影响 proof——完整引用总数、调用者有权读取的 TemplateId 明细、`redactedCount`，以及覆盖完整
  （未 redact）排序引用集合的 SHA-256 fingerprint；app Adapter `TemplateAssetReferencePortAdapter`（app
  template 包）桥接 Template-owned `AssetReferenceAuthority`（T20），逐 TemplateId locate 其 ownerScope 并
  用 Template `OwnerScopeAuthority` READ 判定可读性（ExistingUnavailable 一律上抛为依赖不可用）。
- 确认 token（V022 `asset_delete_confirmation`，只追加）：precheck（`deletePrecheck`）在 DELETE 授权 +
  recheck 后签发 64-hex 随机 token，绑定 ownerScope/assetId/actorId（authority 解析的稳定 actorId，而非
  每次请求随机 invocation）/assetRevision/完整引用 fingerprint，TTL 5 分钟；HTTP
  `POST /api/v1/assets/{id}/delete-precheck` 返回影响报告 + token + expiresAt。
- 软删除（`delete`）：`PostgresAssetPersistence.delete` 单事务内取 Asset 行 FOR UPDATE 独占 reservation，
  校验 token 行（存在/未用/未过期/actor+ownerScope+assetId+assetRevision 绑定），在 reservation 下经 port
  重算 proof 并比对 fingerprint；任一事实漂移均零写返回 ConfirmationRequired/Expired/Stale 或
  DependencyUnavailable；通过后标记 token 已用、lifecycle→DELETED、assetRevision+1、追加 DELETE 审计事件
  （T20 consumer 消费 → 引用 Template STALE → recheck INVALID）。HTTP `DELETE /api/v1/assets/{id}` 携带
  `X-Confirmation-Token`。
- 读 reservation：`PostgresTemplatePersistence.create/append` 在同一事务内对投影引用的 asset_aggregate 行按
  assetId 升序 FOR SHARE（Template current 变更的读 reservation），与删除的独占 reservation 按 assetId
  线性化；无共享聚合/表/事务，compile edge 仍为零。
- 恢复（`restore` / `restore-lifecycle`）：RESTORE 授权 + recheck 后，事务内 FOR UPDATE 校验 DELETED 与
  expectedAssetRevision，lifecycle→ACTIVE、assetRevision+1、current content version 不变（不新建
  contentVersion）、追加 RESTORE 审计事件（T20 consumer → STALE → recheck READY）。HTTP
  `POST /api/v1/assets/{id}/restore-lifecycle?expectedAssetRevision=`。
- 验证：asset 模块 86/86（含 precheck/delete/restore 11 个新 contract 场景：影响报告、缺 token、token
  单次使用、Expired/Stale 零写、恢复同 current、恢复 ACTIVE 冲突、revision 冲突）；app 纵切
  `AssetDeleteRestoreSliceTest` 5/5（Testcontainers PG+MinIO：precheck→delete→STALE→INVALID→restore→
  READY、过期拒绝、assetRevision 漂移 Stale、引用漂移 Stale、恢复 ACTIVE 冲突）；HTTP `AssetApiTest` 新增
  4 场景（precheck/delete/restore 全流程 + 缺失 token 400 + 未知 token 零写 + 删除后目录 410）；app 全量
  302 tests 绿（canary 迁移数 22、contractVersion 0.13.0）；`asset`/`template`/`fast` gate 绿；OpenAPI
  0.13.0 + Web SDK 再生成（deleteAsset/precheckDeleteAsset/restoreAssetLifecycle）。
- 边界：删除不撤销已签发 fetch lease（T13 尚未签发 lease，本票保持零 lease 交互）；`redactedCount` 只统计
  调用者不可读的引用 Template，引用 proof 的 fingerprint 始终覆盖完整集合；恢复只针对 DELETED，ACTIVE
  恢复为 ASSET_RESTORE_CONFLICT。Profile 保持 NOT_REGISTERED。
