# T140 — 强制 Repeat collection per-occurrence 容量

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T139 (resolved)

## 目标

物化 Ticket 19 / `RW-T19-S7-076` 已冻结的
`closureAndExpansion.repeatCollectionItemsPerOccurrence` 动态容量轴。每个实际 Repeat occurrence 的 `items`
ValueSource 在其父 lexical frame 中求值一次；得到 concrete collection 或 EMPTY 映射的零项后，以该 occurrence 的
完整 collection length 进行 MAX_INCLUSIVE `1000` admission。observed `999/1000` 成功，`1001` 必须以公开
stage `MATERIALIZATION`、code `EVALUATION_BUDGET_EXCEEDED`、完整 limitId fail closed，零
RenderDocument/Engine/output。

collection length 是 per-occurrence 值，不跨 sibling/nested Repeat 累计；每个 item 即使 descendants 最终全部剪枝
也已消耗 collection 预算。该 admission 必须在创建 Repeat 外层 container、首个 Loop frame/item occurrence、
materialized node、generated entry 或 descendant demand 前完成；禁止截断到前 1000 项、分页、跳项或 partial output。

## Interface / seam

- 延用 T139 的 package-internal `renderweave-rendering-pipeline-capacity-guard/1.0` 唯一 catalog；加入 closed
  `REPEAT_COLLECTION_ITEMS_PER_OCCURRENCE` limit，不新增产品 API/SPI/config/test override。
- `Materializer.expandRepeat` 在 items type/ABSENT policy 消解后，把 `itemList.size()` 交给同一 guard；admitted zero
  collection 继续立即剪枝，above-limit 在 `itemLayout/instanceLayout` 与任何 frame/node work 前 first-fail。
- 公开 `Evaluator.evaluate(EvaluationCommand)` 以合法 literal list 重放 `999/1000/1001`；internal guard tracer 同步
  固定机器 fixture 的三点边界。既有 10,000-item 单 Repeat node-limit 测试将改为 9×1,000 + 995 项 sibling
  Repeat，保持其 node-limit 目标而不再依赖违反本轴的输入。

## TDD 与验证

- 先仅加入公开 1001-item terminal tracer；现有生产实现应错误 seal，形成 behavioral RED。
- 最小扩展唯一 guard 与 `expandRepeat` 接线，随后补 999/1000 success、isolated guard contract 与 zero/sibling
  regression；保持固定 consumer order和 first-demanded-error。
- 运行 focused Evaluator/guard/Materializer、完整受影响 reactor、`render` 与 `fast`。无 app wiring/API/Web/
  migration/Profile 变化，不重复发布级 full；server 留给周期性容量批次。
- Repeat nesting/loopFrames/renderOccurrences/materialized nodes/generated entries/logical operations 等其余轴、正式
  Ticket 19 records/product executor、provider/API Key/真实数据/push/tag/PR 均不在本票。最高
  `automated_verified`；claim 时 A0、J0。

## Resolution

- `RenderingPipelineCapacityGuard` 新增唯一生产 limit
  `REPEAT_COLLECTION_ITEMS_PER_OCCURRENCE=1000`；`Materializer.expandRepeat` 在 items resolve/type/ABSENT policy
  之后、`itemLayout`/`instanceLayout` 与首个 Loop frame/materialized node 之前，用当前 occurrence 的完整
  `itemList.size()` 调用该 guard。零项继续原有立即剪枝；没有全局或 sibling 累计状态。
- behavioral RED：生产未改时，公开 `Evaluator.evaluate` 的 1001-item tracer 预期 `Rejected`，实际得到
  `SealedDocument`。最小接线后 999/1000 seal，1001 以 exact MATERIALIZATION /
  EVALUATION_BUDGET_EXCEEDED / `closureAndExpansion.repeatCollectionItemsPerOccurrence` 拒绝，且 loop-domain
  capability supply 为 0。guard 同时覆盖 0/999/1000/1001。
- 原 10,000-item 单 Repeat node-limit 回归改为 9×1000 + 995 的 sibling Repeat：每个 occurrence 都合法，仍在
  materialized node 20,001 边界失败，因此同时证明本轴不跨 sibling 累计，且原测试不依赖违反新 contract 的
  payload。
- focused Evaluator/Materializer/guard 73/73；受影响 reactor Schema 20、Validation 13、Template 84、Asset 92、
  Rendering 184 全绿；`git diff --check` 通过。A1 `render`
  `.sdlc/evidence/20260829-033642-render/`（2/2）与 `fast`
  `.sdlc/evidence/20260829-033733-fast/`（3/3）metadata 均 `passed`。
- 无正式 Ticket 19 product target/executor，因此无 T140-specific A2；A3 无，J0 pending、J1 未批准。未重复
  server/full；provider attempts/API Key reads/真实数据/Profile registration/push/tag/PR 均为 0。
- 状态回填后的 resolution `fast` `.sdlc/evidence/20260829-033936-fast/` metadata 为 `passed`，3/3 steps 全绿。
