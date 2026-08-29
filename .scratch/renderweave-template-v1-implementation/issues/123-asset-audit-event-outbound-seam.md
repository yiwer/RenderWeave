# 物化 Asset audit event outbound seam

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 12a, 20（均已 resolved）

## Question

Template 的 STALE consumer 当前直接读取 Asset-owned `asset_audit_event`，形成跨上下文共享表知识；同时
`AssetApplication` 还残留一个未使用的 API→SPI import。如何保持既有可重放 cursor、200 项上限、事件顺序和
Template-owned STALE 事务不变，同时恢复 CONTEXT 规定的 Asset-owned outbound Interface？

## Answer（本票冻结的实施决定）

1. 在 `asset.spi` 新增窄 `AssetAuditEventSource`，只暴露 `readAfter(exclusiveEventId, limit)` 与 closed
   `MutationEvent(eventId, assetId, operation)`；构造器强制正 eventId、canonical AssetId、正且有界 limit，返回值
   必须严格按 eventId 递增且不超过 limit。
2. `PostgresAssetAuditEventSource` 作为 app 的 Asset adapter 独占 `asset_audit_event` SQL；读取只做一条有界、稳定
   排序查询，不推进任何 Template cursor，不开启跨上下文事务。
3. `TemplateAssetStaleConsumer` 只消费该 Interface；Template-owned transaction 仍原子推进自身 cursor、投影查询与
   STALE 写入。CREATE/METADATA_UPDATE 仍只推进 cursor，CONTENT_REPLACE/CONTENT_RESTORE/DELETE/RESTORE 才触发 STALE。
4. 删除 `AssetApplication` 的未使用 SPI import，并以 architecture test 固定 public API 不可依赖 SPI。
5. 本票不修改 reservation/delete 线性化、migration/OpenAPI/Web/Renderer/Profile/product route，也不 push/tag/PR。

## TDD 与验证

- 先以 Asset adapter contract、Template consumer seam test 与 architecture rule 共同 RED；再最小 GREEN。
- focused asset/app tests、`git diff --check`，随后 `fast → server → full → resolution fast`。最高只报
  `automated_verified`；A3/J1/READY 不推进，provider/API Key/真实数据/费用为 0。

## Results

- RED：新增 SPI contract test 首次因 `AssetAuditEventSource` 尚不存在而编译失败；首次 `server` 也由 public surface
  inventory 精确拦截新增顶层类型，修正 inventory 后转绿。
- GREEN：Asset focused 8/8、Asset module 92/92；app focused 26/26（architecture 5、configuration 5、delete/restore
  5、dependency projection 11）。Template adapter 已无 `asset_audit_event` 表知识，Asset adapter 独占有界 SQL。
- A1：`fast` `.sdlc/evidence/20260828-114535-fast/`、`server`
  `.sdlc/evidence/20260828-115650-server/`（363 tests，0 failures/errors，15 skipped）与 17-step `full`
  `.sdlc/evidence/20260828-123150-full/`（17/17，970.732 秒）均 passed。第一次 full 的单一 deployment chunk
  reload 波动由独立 `e2e` `.sdlc/evidence/20260828-123048-e2e/`（23 passed、1 controlled skip）排除，并由第二次
  full 完整重放确认。
- 状态为 `resolved / automated_verified`；J0 保持，A3/J1/READY 未推进。reservation/delete 线性化仍留给独立票；
  provider attempts、API Key reads、真实数据、费用、push/tag/PR 均为 0。
