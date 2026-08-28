# T146 — Repeat item logical-operation 预算基础

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T142, T145 (resolved)

## 目标

物化 Ticket 19 / `RW-T19-S7-079` 明文要求的真实纵切：每个 actual Repeat item 必须在 descendant pruning 与
Loop frame 创建前，同时预留一个 Evaluator logical operation。该单位接入 cap-015 使用的唯一
`renderweave-rendering-pipeline-capacity-guard/1.0`，其冻结合同为
`closureAndExpansion.logicalOperations`、MAX_INCLUSIVE `1000000`、observed
`999999/1000000/1000001`、contract stage `SERIAL_MATERIALIZATION`、public stage `MATERIALIZATION`、code
`EVALUATION_BUDGET_EXCEEDED`、zero boundary `ZERO_DOCUMENT_OUTPUT`。

## Scope 与诚实边界

- 冻结文本还没有枚举 Expression AST、Mapping predicate、ValueSource、Binding 等其余 Evaluator semantic step
  分别如何形成 logical-operation unit；这些选择会改变公开 first-fail，不能由本票凭实现调用次数发明。
- 本票只宣称完成明文固定的 Repeat-item mandatory unit 与 cap-015 唯一生产 guard 基础，不宣称
  `RW-T19-S7-085/086/087/088` 的完整 product executor、完整 taxonomy 或整轴关闭。其余 logical units 与 retry/
  memo position 语义继续后续独立票。
- 每个 collection item 都消费一个 logical unit，即使其 descendants 最终全部被 `render:false`、false Conditional
  或其他合法 pruning 移除；空 collection 消费 0。该计数 request-global，跨 sibling/nested Repeat 与 TemplateUse
  child 累计，新 Materializer/request 才重置。
- logical operation 必须先于 `reserveLoopFrame()`、LoopFrame allocation、Definition/capability demand、child expansion
  与 generated/materialized allocation。超限立即返回 exact capacity problem，零 partial tree/document/output。

## Seam、TDD 与验证

- 不创建外部 Interface/config/route。扩展既有 package-internal deep module：closed
  `LOGICAL_OPERATIONS=1000000` 与同一 overflow-safe `RequestTracker`；`Materializer` 接受该 internal tracker，默认
  production 路径创建新 request-local tracker，测试可预留前序 logical units 后从同一 Materializer outcome 观察终态。
- 先向 production guard test 加 `999999/1000000/1000001`，生产 enum 缺失应形成 compile RED；再以已预留
  `1000000` 的同一 tracker materialize 一个最终会被剪枝的 Repeat item，现状会错误成功，形成 behavioral RED。
- 最小 GREEN 只在每个 Repeat item 的 frame/pruning 前 `reserve(LOGICAL_OPERATIONS, 1)`；补空 collection、同一 request
  累计、下一 item demand 截止与 exact stage/code/limitId 回归。运行 focused Materializer/guard、完整受影响 reactor、
  `render` 与 `fast`。
- cap-015 fixture 明确 `evaluatorOrSealerExecutedByThisProbe=false`，因此 exact million boundary 只在同一 production
  guard 隔离重放，不冒充 full pipeline A2。无 app wiring/API/Web/migration/Profile 变化，不重复 server/full。
- provider/API Key/真实数据/费用/Profile registration/push/tag/PR 均不在本票；最高 `automated_verified`。

## Resolution

- production guard test 的首个 RED 为编译失败：`LOGICAL_OPERATIONS` 尚不存在，共 6 个引用失败；补 enum 后 guard
  8/8 GREEN。随后 Materializer test seam 的 RED 先精确失败于 1 个缺失 overload；只加入 tracker injection、尚未
  reservation 时，Materializer 19 tests 精确 1 个 behavioral failure：期望 `MaterializationFailed`，实际为
  `Materialized`，证明最终被剪枝 item 没有收费。
- 唯一 guard 已加入 `LOGICAL_OPERATIONS=1000000`。默认 production materialize 创建新 request-local tracker；
  package-internal seam 只允许同一实现注入预留 tracker。`expandRepeat` 在每个 actual item 的 `reserveLoopFrame()` 与
  pruning/demand 前先原子预留 1 logical operation，未创建第二套上限或 counter。
- 回归证明：tracker 已满时被剪枝 item exact fail；空 collection 在 tracker 已满时成功；预留 `999999` 后两 item
  路径只允许第一个 capability position demand，第二个 item 在 demand 前以 exact MATERIALIZATION /
  EVALUATION_BUDGET_EXCEEDED / `closureAndExpansion.logicalOperations` 失败。
- focused Materializer 21 + guard 8 = 29/29；受影响 reactor Schema 20、Validation 13、Template 84、Asset 92、
  Rendering 208 全绿，`git diff --check` 通过。A1 `render`
  `.sdlc/evidence/20260829-050504-render/`（2/2）与 `fast`
  `.sdlc/evidence/20260829-050552-fast/`（3/3）metadata 均 `passed`。
- cap-015 明确不执行 Evaluator/Sealer 且没有正式 product executor，故无 T146-specific A2/A3；其余 logical-unit
  taxonomy 与 `RW-T19-S7-085..088` 仍开放，未虚报整轴完成。J0 pending、J1 未批准；未重复 server/full，provider
  attempts/API Key reads/真实数据/Profile registration/push/tag/PR 均为 0。
- 状态回填后的 resolution `fast` `.sdlc/evidence/20260829-050800-fast/` metadata 为 `passed`，3/3 steps 全绿。
