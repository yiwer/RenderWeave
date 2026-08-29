# 物化 AssetReferenceAuthority reservation seam

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 12b, 20, 123（均已 resolved）

## Question

冻结语义要求 Template current 变更按 assetId 取得 shared reservation，Asset confirmed delete 取得 exclusive
reservation 后重算 proof，并明确禁止共享聚合、表或数据库事务。当前实现却由 Template persistence 在自己的事务中
直接 `FOR SHARE asset_aggregate`。如何保留同一线性化语义，同时消除 Template 对 Asset-owned table 的知识？

## Answer（本票冻结的实施决定）

1. 在 app assembly 建立窄 `PostgresAssetReferenceReservations` seam；以 domain-separated canonical assetId
   映射 PostgreSQL transaction-scoped advisory lock。Template current 事务按 canonical assetId 排序、去重后取得
   shared locks；Asset delete 事务先取得单项 exclusive lock，再锁自己的 aggregate/token 并重算完整 proof。
2. reservation 只能在调用方已开启的真实 PostgreSQL transaction 内取得；不创建嵌套/跨上下文事务，不读写对方
   table，不持久化 lock key。hash collision 最多保守地增加串行化，不会放松安全性。
3. 保持 create/append 的 serializable transaction、delete token/asset row/proof/write 原子性及既有 closed outcomes；
   restore、resolve、audit consumer、migration/OpenAPI/Web/Renderer/Profile/product route 不变。
4. architecture test 禁止 Template app adapter 出现 Asset-owned `asset_aggregate`/`asset_audit_event` 表知识；
   Testcontainers PostgreSQL 验证 shared/shared 兼容与 exclusive 等待 shared transaction 释放。

## TDD 与验证

- 先让 architecture rule 因现有 `asset_aggregate` 读取 RED，并让 reservation concurrency test 因 seam 尚不存在 RED；
  再最小实现至 GREEN。
- focused app tests、`git diff --check`，随后 `fast → server → full → resolution fast`。最高
  `automated_verified`；J0，A3/J1/READY 不推进，provider/API Key/真实数据/费用/push/tag/PR 为 0。

## Results

- RED：architecture 5 项首次精确失败于 `PostgresTemplatePersistence` 的 `asset_aggregate` 读取；进一步检查发现
  snapshot recheck 也在 reservation 前直接读 Asset 表，故同票将其迁至 Asset-owned `PostgresAssetDependencyFacts`
  的独立 `REQUIRES_NEW` 只读事务。首次 `server` `.sdlc/evidence/20260828-125812-server/` 又由 isolated
  configuration test 精确拦截缺少 `AssetDependencyFacts` fixture bean，补齐后转绿。
- GREEN：focused 25/25（architecture 5、reservation concurrency 2、delete/restore 5、Template persistence 8、
  configuration 5）。shared/shared 可并行，exclusive 在 shared transaction 释放前不可取得；事务外调用 fail closed。
  Template production adapters 对 Asset-owned table inventory 零命中。
- A1：`fast` `.sdlc/evidence/20260828-125749-fast/`、最终 `server`
  `.sdlc/evidence/20260828-130911-server/`（365 tests，0 failures/errors，15 skipped）与 17-step `full`
  `.sdlc/evidence/20260828-131919-full/`（17/17，972.259 秒）均 passed；状态回填后的 resolution `fast`
  `.sdlc/evidence/20260828-133630-fast/` 亦以 3/3 passed。
- 状态为 `resolved / automated_verified`、J0；A3/J1/READY 未推进。无 migration/OpenAPI/Web 行为变化，provider
  attempts、API Key reads、真实数据、费用、push/tag/PR 均为 0。
