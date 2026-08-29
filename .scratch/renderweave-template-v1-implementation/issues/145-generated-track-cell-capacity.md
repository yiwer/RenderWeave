# T145 — 强制 generated track/cell entries 容量

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T144 (resolved)

## 目标

物化 Ticket 19 / `RW-T19-S7-084` 与机器 `cap-014` 已冻结的
`closureAndExpansion.generatedTrackAndCellEntries` 容量轴。合同为 MAX_INCLUSIVE `100000`，observed
`99999/100000/100001`，contract stage `SERIAL_MATERIALIZATION`、公开 stage `MATERIALIZATION`、code
`EVALUATION_BUDGET_EXCEEDED`、zero boundary `ZERO_DOCUMENT_OUTPUT`。请求级计数必须在创建下一条 lowering-generated
AUTO track 或 GRID cell 前预留；超限不得返回 partial tree、RenderDocument、Engine Command 或 RenderOutput。

## 计数语义与现状

- 一条 generated entry 精确指 Repeat GRID lowering 新产生的一条 explicit AUTO row/column track，或一个 surviving
  direct child/item 的零基 GRID placement cell。cell 是一个语义条目，不因 wire 中同时携带 `row`/`column` 而计两次。
- STACK packing、authored Grid 的 authored tracks/cells、被剪枝 item、零-survivor Repeat 与 RenderDocument-owned
  track 数均不属于本轴。itemLayout 与 instanceLayout 是独立 generated grids，均按实际 surviving sequence 计数。
- GRID 使用冻结的 `effectiveColumns=min(authoredColumns,survivingCount)`；rows 为 ceiling division，末行不生成
  placeholder cell。packing ordinal 必须紧凑，不能误用原 inputIndex；inputIndex 只保留在 Loop frame/OccurrencePath。
- 当前 `Materializer` 直接分配 AUTO arrays/cell fields，没有 request-global generated-entry counter；还用 raw
  authored columns 生成 tracks/placement，并为零-survivor item/Repeat 建 synthetic containers。这些 lowering drift 会
  制造错误计数，必须在同一 production seam 校正后再接入唯一 capacity guard。

## Boundary 与验证

- 扩展唯一 package-internal `renderweave-rendering-pipeline-capacity-guard/1.0`，增加 closed
  `GENERATED_TRACK_AND_CELL_ENTRIES=100000`；Materializer 只持 request-local usage counter，不拥有第二份 limit。
- 对每个即将 lower 的非空 GRID packing 先原子预留 `rows + effectiveColumns + survivingCount`，再创建 tracks/cells；
  overflow fail closed。STACK/空 packing delta 为 0。
- cap-014 target 明确 exact production guard required、duplicate guard forbidden 且不执行 Evaluator/Sealer；因此
  99,999/100,000/100,001 在同一 production guard 隔离重放。真实 Materializer seam 负责证明 generated delta、
  effectiveColumns、compact cell、pruned zero-charge 与 request-global 累计，不把 candidate 冒充 A2/full pipeline。
- TDD 先加入缺失 enum 的 exact guard case，以及会暴露 raw-columns/zero-survivor/非紧凑 cell drift 的 product tests；
  再最小重构 Repeat lowering。运行 focused Materializer/guard、完整受影响 reactor、`render` 与 `fast`。
- logical operations、RenderDocument track/cell/static-node/canonical-byte 轴、正式 Ticket 19 records/product executor、
  provider/API Key/真实数据/Profile registration/push/tag/PR 均不在本票。无 app wiring/API/Web/migration/Profile
  变化，不重复 `server/full`；最高 `automated_verified`，当前 A0、J0。

## Resolution

- TDD RED 先在 focused compile 精确暴露 production enum 缺失的 3 个引用；补入 enum 后，真实
  `Materializer` seam 的 18 tests 精确出现 2 个 behavioral failures：raw authored columns 生成 99 列而不是
  effective 2 列，且 all-pruned Repeat 留下 1 个 synthetic container 而不是 0。
- 唯一 guard 现含 `GENERATED_TRACK_AND_CELL_ENTRIES=100000`，并提供 overflow-safe request-local
  `RequestTracker`。`Materializer` 由同一 `PackingShape` 同时决定实际 AUTO tracks/cells 与预留 delta；非空 GRID
  在 allocation 前预留 `rows + effectiveColumns + survivingCount`，STACK 与零-survivor 不收费。
- Repeat lowering 已按 surviving sequence 计算 effective columns 与 compact cell ordinal；原 inputIndex 只保留在
  loop frame/occurrence path。all-pruned item 不生成 item container，all-pruned Repeat 不生成 instance container。
- focused `MaterializerTest` 18 + guard 7 = 25/25；受影响 reactor Schema 20、Validation 13、Template 84、Asset 92、
  Rendering 204 全绿，`git diff --check` 通过。A1 `render`
  `.sdlc/evidence/20260829-045143-render/`（2/2）与 `fast`
  `.sdlc/evidence/20260829-045238-fast/`（3/3）metadata 均为 `passed`。
- cap-014 无 Evaluator/Sealer product executor，故没有 T145-specific A2/A3；状态为 J0 pending、J1 未批准。
  本票无 app wiring/API/Web/migration/Profile 变化，未重复 server/full；provider attempts、API Key reads、真实数据、
  Profile registration、push/tag/PR 均为 0。
- 状态回填后的 resolution `fast` `.sdlc/evidence/20260829-045433-fast/` metadata 为 `passed`，3/3 steps 全绿。
