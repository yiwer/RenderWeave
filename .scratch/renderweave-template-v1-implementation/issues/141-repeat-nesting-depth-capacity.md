# T141 — 强制 Repeat nesting depth 容量

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T140 (resolved)

## 目标

物化 Ticket 19 / `RW-T19-S7-077` 已冻结的 `closureAndExpansion.repeatNestingDepth` 动态容量轴。沿当前实际
materialization path，root 的 active Repeat depth 为 `0`；每次真正进入一个未被 `render:false` 或上游结构剪枝
移除的 Repeat occurrence 时 `+1`。同一 Repeat 的不同 items、同层 sibling 与已经返回的分支不累计；
TemplateUse 会隔离 lexical Loop frames，但不能重置物理 occurrence path 上仍活跃的 Repeat depth，否则可用
Repeat→TemplateUse 交替绕过预算。

机器 authority 固定 MAX_INCLUSIVE `8`，observed `7/8/9`，contract stage `SERIAL_MATERIALIZATION`、公开 stage
`MATERIALIZATION`、code `EVALUATION_BUDGET_EXCEEDED`、zero boundary `ZERO_DOCUMENT_OUTPUT`。准备进入 depth `9`
的 Repeat 前必须以完整 limitId `closureAndExpansion.repeatNestingDepth` fail closed，不创建该层 Loop frame、
materialized node、generated entry、descendant demand、RenderDocument、Engine 或 output；禁止截断到前八层或返回
partial tree。

## Interface / seam

- 扩展 T139 已建立的唯一 package-internal `renderweave-rendering-pipeline-capacity-guard/1.0` catalog，加入 closed
  `REPEAT_NESTING_DEPTH=8`；不新增产品 API/SPI/config/test override 或第二份 guard。
- `Materializer.InvocationScope` 增加不可变 path-local Repeat depth：root 为 `0`，`withLoopFrame` 为当前 Repeat
  item frame 携带 `current+1`，普通 descendant 原样传播；child Template invocation 清空 lexical Loop frames，
  但保留 active Repeat depth。离开 Repeat 后父 scope 不变。
- `expandRepeat` 在该 occurrence 的 items/frame/node work 前检查 `current+1`。公开 `Evaluator.evaluate` 以合法、
  单项、嵌套 Repeat tree 重放 7/8/9；isolated guard contract 同步重放机器 fixture。

## TDD 与验证

- 先仅加入 depth 9 的公开 terminal tracer；现有生产实现应错误 seal，形成 behavioral RED。
- 最小扩展唯一 guard 与 depth propagation，再补 depth 7/8 success、sibling path-local 与 pruned branch 回归；
  保持 T140 collection 边界、固定 consumer order 和 first-demanded-error。
- 运行 focused Evaluator/guard、完整受影响 reactor、`render` 与 `fast`。无 app wiring/API/Web/migration/Profile
  变化，不重复发布级 `full`；server 留给周期性容量批次。
- loopFramesTotal/renderOccurrences/materialized nodes/generated entries/logical operations 等后续轴、正式 Ticket 19
  records/product executor、provider/API Key/真实数据/Profile registration/push/tag/PR 均不在本票。最高
  `automated_verified`；claim 时 A0、J0。

## Resolution

- 唯一 package-internal `RenderingPipelineCapacityGuard` 新增 `REPEAT_NESTING_DEPTH=8`。root
  `InvocationScope.repeatNestingDepth=0`；进入 Repeat 前检查 `current+1`，每个 item 的 `withLoopFrame` 携带该新
  depth，离开后父 scope 不变。TemplateUse child 清空 lexical Loop frames，但显式继承 active Repeat depth，
  Repeat→TemplateUse 交替不能绕过预算。
- behavioral RED：生产未改时，合法 9 层 nested Repeat 预期 `Rejected`、实际得到 `SealedDocument`。最小接线后
  depth 7/8 seal，depth 9 返回 exact MATERIALIZATION / EVALUATION_BUDGET_EXCEEDED /
  `closureAndExpansion.repeatNestingDepth`；第九层 items/frame/node/descendant work 不开始，零 document/output。
- 两个 sibling depth-8 tree 共同成功，证明 depth 是 path-local 而非 request-global；第九层 `render:false` 成功
  剪枝，证明未进入的 Repeat 不计；9 个 Template invocation 各包一层 Repeat 的链在第九层同样拒绝，证明 child
  invocation 不能重置 active depth。isolated guard 重放 7/8/9 exact boundary。
- focused Evaluator/guard 66/66；受影响 reactor Schema 20、Validation 13、Template 84、Asset 92、Rendering 191
  全绿；`git diff --check` 通过。A1 `render` `.sdlc/evidence/20260829-034848-render/`（2/2）与 `fast`
  `.sdlc/evidence/20260829-034940-fast/`（3/3）metadata 均 `passed`。
- frozen candidate fixture 尚无正式 product target/executor replay，故无 T141-specific A2；A3 无，J0 pending、
  J1 未批准。未重复 server/full；provider attempts/API Key reads/真实数据/Profile registration/push/tag/PR 均为 0。
- 状态回填后的 resolution `fast` `.sdlc/evidence/20260829-035052-fast/` metadata 为 `passed`，3/3 steps 全绿。
