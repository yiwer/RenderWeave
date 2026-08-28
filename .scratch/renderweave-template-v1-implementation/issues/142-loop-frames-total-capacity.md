# T142 — 强制 request-total Loop frames 容量

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T141 (resolved)

## 目标

物化 Ticket 19 / `RW-T19-S7-078` 已冻结的 `closureAndExpansion.loopFramesTotal` 动态容量轴。每个实际 Repeat
item 按原 collection input order，在任何 descendant pruning、generated item node 或 child demand 前预留一个
request-global Loop frame 单位；该 item 即使全部 direct children 被剪枝也消费 frame。显式/ABSENT→EMPTY 零项
collection 消费 0；计数跨 sibling Repeat、nested Repeat 与 TemplateUse child invocation 累计，同一 Evaluation
发生 terminal failure 时不返回已展开前缀。

机器 authority 固定 MAX_INCLUSIVE `10000`，observed `9999/10000/10001`，contract stage
`SERIAL_MATERIALIZATION`、公开 stage `MATERIALIZATION`、code `EVALUATION_BUDGET_EXCEEDED`、zero boundary
`ZERO_DOCUMENT_OUTPUT`。第 10001 个 item 必须在构造其 `LoopFrame`、materialized node、generated entry、
descendant capability/Asset demand、RenderDocument、Engine/output 前，以完整 limitId
`closureAndExpansion.loopFramesTotal` fail closed；禁止截断、分页、跳过或 partial tree。

## Interface / seam

- 扩展唯一 package-internal `renderweave-rendering-pipeline-capacity-guard/1.0` catalog，加入 closed
  `LOOP_FRAMES_TOTAL=10000`；不新增产品 API/SPI/config/test override 或第二份 guard。
- `Materializer` 增加 request-local、单调的 `loopFrames` counter；每个 item 先调用 `reserveLoopFrame()`，成功后才
  `withLoopFrame` 并继续 node/children work。Materializer 每次 Evaluation 新建，因此 closure retry/request 之间不
  共享计数。
- 公开 `Evaluator.evaluate` 用多个合法、每 occurrence ≤1000 的 Repeat 重放 9999/10000/10001；above case 的
  第 10001 item 携带 loop-domain capability consumer，必须保持 provider supply 0。另以 1000 outer ×10 inner
  nested expansion 验证总量跨嵌套累计且零 partial document。

## TDD 与验证

- 先仅加入 10001-frame public terminal tracer；现有生产实现应错误 seal，形成 behavioral RED。
- 最小扩展唯一 guard 与 request counter，再补 9999/10000 success、nested overflow 与 isolated guard exact
  9999/10000/10001 回归；保持 T140 per-occurrence collection 和 T141 path depth 语义。
- `RW-T19-S7-079` 同时要求每 item 的 logical operation 预留；本票只完成 frame half，logicalOperations exact
  counter 仍由其后独立容量票实现，不提前伪报该 requirement 完成。
- 运行 focused Evaluator/guard、完整受影响 reactor、`render` 与 `fast`。无 app wiring/API/Web/migration/Profile
  变化，不重复发布级 `full`；server 留给周期性容量批次。
- renderOccurrences/materialized nodes/generated entries/logicalOperations 等后续轴、正式 Ticket 19 records/product
  executor、provider/API Key/真实数据/Profile registration/push/tag/PR 均不在本票。最高 `automated_verified`；
  claim 时 A0、J0。

## Resolution

- 唯一 package-internal `RenderingPipelineCapacityGuard` 已增加 `LOOP_FRAMES_TOTAL=10000`；`Materializer` 以
  request-local 单调 `loopFrames` counter 在每个实际 Repeat item 的 `withLoopFrame`、generated/materialized node
  与 descendant work 前调用 `reserveLoopFrame()`。零项 collection 不进入循环，因此消费 0；同一 Evaluation 的
  sibling、nested 与 TemplateUse child materialization 共享同一计数。
- behavioral RED：生产未改时，合法 10001-frame public Evaluator tracer 预期 `Rejected`、实际得到
  `SealedDocument`。最小接线后 9999/10000 均 seal，第 10001 个返回 exact MATERIALIZATION /
  EVALUATION_BUDGET_EXCEEDED / `closureAndExpansion.loopFramesTotal`，且其 loop-domain Random consumer 的
  provider supply 为 0，零 document/output。
- 显式零项 Repeat 前缀加 10000 actual frames 仍成功，证明零项不收费；1000 outer × 10 inner 的合法 nested
  fixture 在下一 frame exact fail，证明总量不按 Repeat occurrence 或 nesting level 重置且不会返回展开前缀。
  isolated guard 同步重放 9999/10000/10001。
- focused Evaluator/guard 72/72；受影响 reactor Schema 20、Validation 13、Template 84、Asset 92、Rendering 197
  全绿；`git diff --check` 通过。A1 `render` `.sdlc/evidence/20260829-035916-render/`（2/2）与 `fast`
  `.sdlc/evidence/20260829-040003-fast/`（3/3）metadata 均 `passed`。
- `RW-T19-S7-079` 的 logical-operation half 仍明确留给后续 exact axis；frozen candidate fixture 尚无正式 product
  target/executor replay，故无 T142-specific A2；A3 无，J0 pending、J1 未批准。未重复 server/full；provider
  attempts/API Key reads/真实数据/Profile registration/push/tag/PR 均为 0。
- 状态回填后的 resolution `fast` `.sdlc/evidence/20260829-040209-fast/` metadata 为 `passed`，3/3 steps 全绿。
