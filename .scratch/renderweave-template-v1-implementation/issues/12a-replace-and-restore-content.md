# 实现 Asset content replace 与旧内容恢复

Type: task
Status: resolved
Claimed by: Codex `/root`
Blocked by: 05, 11

## Question

如何在既有 Asset persistence 纵切上物化 content replace（携带 `expectedAssetRevision`，先完成验证与安全暂存
再原子追加 contentVersion、切换 current 并增加 assetRevision；验证后内容与 current 完全相同时成功但 no-op，
不增加 revision/事件）与旧内容恢复（复用旧 Blob、追加新 contentVersion 并推进 current，绝不回拨或修改旧
版本）？每次有效操作追加有界审计事件（assetId、前后 assetRevision、actorId、时间、操作类型、内容版本身份，
不记原始字节），并记录 STALE 事实；Template 侧 STALE 消费/反向索引属于 Template 依赖投影票，不在本票实现。
失败必须零部分写入，不暴露未提交的新版本；容量水位 fail-closed 继续生效。本票不实现 delete/restore、
确认 token、Resolver/lease 或 UI。

## Answer

T12a 沿 ADR-0043 §7 的切片边界在 T11 纵切上物化 `AssetApplication.replaceContent/restoreContent` 两个 closed
方法；delete/restore、确认 token、Resolver/lease 与 Asset UI 保持 open（T12b/T13），本票未创建 placeholder。

`ReplaceContentCommand`（assetId、expectedAssetRevision、rawContent 防御性拷贝）与
`RestoreContentCommand`（assetId、expectedAssetRevision、sourceContentVersion）新增到 `AssetApplication`；
closed outcomes：Replace 为 `ReplaceApplied|ReplaceNoOp|ReplaceContentRejected|ReplaceNotFound|ReplaceDeleted|
ReplaceForbidden|ReplaceRevisionConflict|ReplaceStorageCapacityExceeded|ReplaceAuthorityUnavailable|
ReplacePersistenceUnavailable`，Restore 为 `RestoreApplied|RestoreNoOp|RestoreNotFound|RestoreDeleted|
RestoreForbidden|RestoreRevisionConflict|RestoreVersionNotFound|RestoreAuthorityUnavailable|
RestorePersistenceUnavailable`。internal `CanonicalAssetApplication` 复用既有 seam：authorizeExisting(UPDATE)
（update 语义含内容替换与旧内容恢复，不新增 capability 字符串）、admission 按 Asset 永久 kind 复验（新内容
不是该 kind 时 `ReplaceContentRejected`）、recheck、Blob store 后容量水位检查（只对新建 Blob 计数，
复用 Blob 不受影响——恢复永不触发容量拒绝）、事务内 append 后重读 detail 返回。

关键语义（按冻结规格 line 32/44 与 T12a 票据）：(1) 与 current 字节相同时 replace 成功但 no-op——不增加
revision、不追加事件、不产生 STALE 事实，且 no-op 判定先于 expectedAssetRevision 校验（陈旧令牌 + 相同
内容仍为 200 no-op；陈旧令牌 + 不同内容才 409），因为 no-op 不产生任何写入，规格的 no-op 规则无
revision 条件；(2) restore 复用目标历史版本的 Blob/descriptor/sourceFileName，追加新 contentVersion 并推进
current，绝不回拨或改写旧版本；restore 当前版本同样 no-op（与 replace 的 no-op 规则同构）；
(3) replace 的新 contentVersion 沿用 current 的 sourceFileName（本票 HTTP 面不携带文件名，保持最小 surface）；
(4) 失败零部分写入——appendContent 在单事务内 select-for-update 校验 lifecycle/expectedAssetRevision 后
插入 content_revision、切换 current、递增 revision 并写审计事件。

审计事件（STALE 事实记录）：V020（forward-only）建 `asset_audit_event`（event_id、asset_id FK、
before/after_asset_revision、actor_id、operation_type ∈ CREATE/METADATA_UPDATE/CONTENT_REPLACE/
CONTENT_RESTORE/DELETE/RESTORE、content_version、occurred_at + (asset_id, event_id) 索引）；create、
metadata update、content replace/restore 各在自身事务内追加有界事件（actor = invocation，不记原始字节、
完整标签或请求），同内容 no-op 不产生事件；`DELETE/RESTORE` 值预留在 check 约束中由 T12b 使用。
`asset_audit_event` 就是规格要求的可靠可重放 STALE 事实流：Template 依赖投影票将消费其中的
CONTENT_REPLACE/CONTENT_RESTORE（及后续 DELETE/RESTORE）驱动 STALE 重检。SPI 侧
`AssetPersistence` 新增 `appendContent(AppendContentCommit)`（含 operation/actorId/blobCreated）、
`loadContentVersion(assetId, contentVersion)`（返回含 descriptor 的完整 StoredContent，供 restore 复用）与
`AuditOperation` 枚举；`CreateCommit`/`UpdateMetadataCommit` 增加 actorId。

HTTP 面（OpenAPI 0.12.0，Web SDK 已再生成，SystemStatus contractVersion 同步 0.12.0）：
`PUT /api/v1/assets/{assetId}/content?expectedAssetRevision=N`（raw octet-stream body，镜像
TemplateController raw-body 模式）→ 200/409/413/422/507/503 等；`POST /api/v1/assets/{assetId}/restore?
expectedAssetRevision=N&sourceContentVersion=M` → 200/404（`ASSET_CONTENT_VERSION_NOT_FOUND`）/409 等；
错误统一 problem+json（`ASSET_REVISION_CONFLICT` 带 currentAssetRevision 等）。`EnvironmentCanaryTest`
applied migrations 19→20。

验证：Asset module `AssetApplicationContractTest` 22 tests（replace applied/no-op/conflict/rejected/
forbidden/deleted/capacity、restore applied/no-op/version-not-found、create+metadata+replace+restore 的
bounded audit 事件与 actor/revision/version 断言，InMemory fake 扩展出版本历史与审计记录）；
`AssetSliceIntegrationTest` 新增 replace→no-op→restore 全链路（Testcontainers PostgreSQL+MinIO，版本
[0,1,2]、恢复复用 Blob 不增加容量、审计行逐字段断言）；`AssetApiTest` 新增 PUT content/POST restore HTTP
面（含 no-op、陈旧 revision 409、restore 404、无效请求零写入、审计行计数）。完整 `full` 16/16 通过
（evidence `.sdlc/evidence/20260819-170357-full/`；首轮 prototype-e2e 有一个与 T12a 无关的
inference-review 瞬断超时，原样重跑 19/19 通过，失败 evidence `20260819-163856-full` 保留），kernel 33/33
与 asset kernel 41/41（A2，Profile 均 `NOT_REGISTERED`）、IMAGE_ONLY P0 providerAttempts=0，draft/inference
browser E2E 均通过。

本票只形成自动证据（A1；kernel/registry exact replay 在原边界仍为 A2），无 J1/A3；
`renderweave-asset-acceptance/1.0` 仍未登记 available；无 delete/restore/Resolver/UI/placeholder。
Ticket 19 open，Asset/Editor/Renderer 未 READY。下一个 unblocked frontier 是 T12b（以 Template 依赖投影票
为 blocker，未建）；T13 随 T07/T08；push 待用户另行授权。
