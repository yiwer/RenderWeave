# T149 — RenderDocument final static Nodes 预算

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T148 (resolved)

## 目标

物化 Ticket 19 / `RW-T19-S7-091` 与 cap-018：RenderDocument final static Nodes 使用 MAX_INCLUSIVE `20000`，
observed `19999/20000/20001`，contract stage `DOCUMENT_SEAL_COUNTING`、public stage `DOCUMENT_SEAL`、code
`RENDER_DOCUMENT_LIMIT_EXCEEDED`、zero boundary `ZERO_DOCUMENT_OUTPUT`。每个 final static Node 必须在 occurrenceId 与
canonical node object 分配前，经 T147/T148 的同一 request-local production guard 原子预留。

## seam 与计数语义

- final static Node 精确对应 issue 15 §7 的 occurrenceId domain：root Canvas、所有 surviving authored/synthetic
  RenderDSL Nodes、每个 compositionViewport 的 sourceCanvas 各计 `1`；被剪枝内容不在 final tree 中，计 `0`；
  `visible:false`、`opacity:0` 与普通空 Node 仍计 `1`。
- 计数接入 `Sealer.nextOccurrenceId()`，使每次成功 reservation 与一个连续 occurrenceId 一一对应；失败发生在 id、
  canonical node bytes/digest 与 Engine Command 前，partial builder 不可见。
- 本轴不能复用 T144 `closureAndExpansion.materializedStaticNodes`：两者虽同为 `20000`，但拥有不同 limitId、stage/code
  与 reservation point。只在现有 `RenderingPipelineCapacityGuard` 增加独立 DOCUMENT_SEAL limit，不创建第二 catalog。
- cap-018 自身明确不执行 Evaluator/Sealer；isolated guard 三点不冒充 full pipeline。Sealer product seam 通过预留
  prefix 后封存 minimal Canvas 与 compositionViewport/sourceCanvas，证明 node-domain 计数而非 JSON object 计数。

## TDD、验证与边界

- guard test 先引用缺失的 `RENDER_DOCUMENT_STATIC_NODES`，形成 compile RED；最小 catalog 扩展后 GREEN。
- Sealer test 再预留 `20000-1` 与 `20000` 后封存单 root，捕获未计数的 behavioral RED；另覆盖 sourceCanvas 消耗
  独立 node unit。随后仅在 occurrence allocation 前 reserve，并直接投影 guard problem。
- focused guard/Sealer/RenderDocument/Evaluator、完整受影响 reactor、`render` 与 `fast`；无 app wiring/API/Web/
  migration/Profile 变化，不重复 server/full。
- 本票不实现 `renderDocument.childEdges/runs/textScalars/vectorEntries`、diagnostics、正式 Ticket 19 records/product
  executor 或 Engine。provider/API Key/真实数据/费用/Profile registration/push/tag/PR 均不推进；最高
  `automated_verified`、J0。

## Resolution

- guard compile RED 精确为 3 个缺失 `RENDER_DOCUMENT_STATIC_NODES` 的编译错误；加入独立 catalog 项后 guard
  11/11 GREEN。该项固定 DOCUMENT_SEAL / RENDER_DOCUMENT_LIMIT_EXCEEDED，并未复用 T144 的 MATERIALIZATION /
  EVALUATION_BUDGET_EXCEEDED counter。
- Sealer 8 tests 在 production 接线前精确出现 2 个 behavioral RED：超限 single root 与超限 sourceCanvas 均错误
  Sealed；在 `nextOccurrenceId()` 分配前 reserve 后 8/8 GREEN。minimal root 证明 envelope/bleed/resources object 不计
  Node；compositionViewport fixture 证明 root + viewport + sourceCanvas 精确消耗 3 units。
- focused Evaluator 68 + canonical writer 3 + RenderDocument contract 4 + guard 11 + Sealer 8 = 94/94；受影响
  reactor Schema 20、Validation 13、Template 84、Asset 92、Rendering 218 全绿，零 failure/error。
- A1 `render` `.sdlc/evidence/20260829-054138-render/`（2/2）与 `fast`
  `.sdlc/evidence/20260829-054226-fast/`（3/3）metadata 均 `passed`；既有 RenderDocument independent replay
  83/83 仍 byte-identical。
- cap-018 不执行 Evaluator/Sealer，故无 T149-specific A2/A3；J0 pending、J1 未批准。无 app wiring/API/Web/
  migration/Profile 变化，未重复 server/full；provider attempts/API Key reads/真实数据/Profile registration/
  push/tag/PR 均为 0。
- 状态回填后的 resolution `fast` `.sdlc/evidence/20260829-054443-fast/` metadata 为 `passed`，3/3 steps 全绿。
