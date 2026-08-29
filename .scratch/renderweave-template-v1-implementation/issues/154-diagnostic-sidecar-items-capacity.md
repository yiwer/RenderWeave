# T154 — diagnostic sidecar items 预算

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T153 (resolved)

## 目标

物化 Ticket 19 / `RW-T19-S7-103` 与 cap-023：`diagnostics.sidecarItems` 使用 MAX_INCLUSIVE
`25000`，observed `24999/25000/25001`，contract stage `REQUEST_SIDECAR`、public stage
`MATERIALIZATION`、code `RENDER_DIAGNOSTIC_LIMIT_EXCEEDED`、zero boundary `ZERO_DOCUMENT_OUTPUT`。
现有 `Materializer` 已生成 request-local internal diagnostic sidecar，但达到上限后会静默丢弃后续 entry；本票将其接入
唯一 production capacity guard，并在加入下一项前原子预留，任何超限使整次 Evaluation fail closed。

## seam 与计数语义

- 每个实际生成、带授权投影来源的 `SidecarEntry` 计 `1`；普通 Node、Conditional/Repeat lowering container 与
  TemplateUse compositionViewport 继续沿现有 production insertion point 计数。无独立来源的内部 generated
  container 不新增 entry。
- `render:false`、false Conditional、empty/全部 pruned Repeat 与 skipped TemplateUse 不生成对应 sidecar entry；
  `visible:false` 与 `opacity:0` 不剪枝，仍正常计数。
- 计数为 request-total，按 materialization consumer/order 的真实 sidecar insertion order 累计；在构造并加入下一
  `SidecarEntry` 前 reserve。第 25001 项 first-fail，丢弃整棵 materialized builder，不进入 Sealer/Engine。
- 本轴与 materialized static Nodes、Render occurrences、RenderDocument Nodes/bytes 及后续 diagnostic canonical bytes、
  LayoutTrace items/bytes 均为独立 limitId/counter；不得以静默截断、后验 list size 或 output omission 替代失败。

## TDD、验证与边界

- guard test 先引用缺失的 `DIAGNOSTICS_SIDECAR_ITEMS` 捕获 compile RED，再加入唯一 catalog 项重放
  `24999/25000/25001`。
- Materializer product test 以 request tracker prefix `24999` + empty Canvas 证明 exact-at 成功，以 prefix `25000`
  + 同一 Canvas 证明下一 entry 在加入前 exact reject，并断言 stage/code/full limitId；既有 prune 测试补充 sidecar
  零收费证据。
- focused guard/Materializer/Evaluator、完整受影响 reactor、`render` 与 `fast`；无 app wiring/API/Web/migration/
  Profile 变化，不重复 server/full。
- 本票不实现 `diagnostics.sidecarBytes`、LayoutTrace、公开诊断投影、正式 Ticket 19 records/product executor。
  provider/API Key/真实数据/费用/Profile registration/push/tag/PR 均不推进；最高 `automated_verified`、J0。

## Resolution

- guard/Materializer tests 先精确产生 5 个缺失 `DIAGNOSTICS_SIDECAR_ITEMS` 的 compile RED；唯一 catalog 加入
  `diagnostics.sidecarItems=25000` 后 guard 16/16 GREEN，Materializer 精确保留 1 个 behavioral RED：prefix
  25000 + empty Canvas 仍返回 `Materialized`。
- `recordSidecar` 的四个现有 production insertion point 现统一使用同一 request tracker：reserve 成功后才提取
  `sourceNodeId` 并构造/加入 entry；失败直接传播冻结的 MATERIALIZATION /
  RENDER_DIAGNOSTIC_LIMIT_EXCEEDED / full limitId，不再按 list size 静默截断。
- prefix 24999 + empty Canvas exact 到达 25000 并成功；prefix 25000 + 同一 Canvas 在下一 entry 前 exact reject。
  既有 `render:false` 回归现同时断言 root + surviving sibling 恰有 2 个 sidecar entries，pruned occurrence 为零收费。
- focused guard 16 + Materializer 22 + Evaluator 68 + architecture 6 = 112/112；受影响 reactor Schema 20、
  Validation 13、Template 84、Asset 92、Rendering 229 全绿，零 failure/error；`git diff --check` 通过。
- A1 `render` `.sdlc/evidence/20260829-063808-render/`（2/2）与 `fast`
  `.sdlc/evidence/20260829-063856-fast/`（3/3）metadata 均 `passed`；既有 RenderDocument independent replay
  83/83 仍 byte-identical。
- cap-023 不执行 Evaluator/Materializer，故无 T154-specific A2/A3；J0 pending、J1 未批准。无 app wiring/API/Web/
  migration/Profile 变化，未重复 server/full；provider attempts/API Key reads/真实数据/Profile registration/
  push/tag/PR 均为 0。
- 状态回填后的 resolution `fast` `.sdlc/evidence/20260829-064003-fast/` metadata 为 `passed`，3/3 steps 全绿。
