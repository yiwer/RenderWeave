# T153 — RenderDocument vector entries 预算

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T152 (resolved)

## 目标

物化 Ticket 19 / `RW-T19-S7-095` 与 cap-022：`renderDocument.vectorEntries` 使用 MAX_INCLUSIVE
`100000`，observed `99999/100000/100001`，contract stage `DOCUMENT_SEAL_COUNTING`、public stage
`DOCUMENT_SEAL`、code `RENDER_DOCUMENT_LIMIT_EXCEEDED`、zero boundary `ZERO_DOCUMENT_OUTPUT`。最终
RenderDocument 中 Polygon/Polyline Point 与 Path Command 必须共享 request-total counter，并在对应 canonical
array-item/object growth 及 atomic bytes/digest commit 前，经现有 production guard 原子预留。

## seam 与计数语义

- 每个最终 `polygon.points[]`、`polyline.points[]` 与 `path.commands[]` item 各计 `1`，三种 item 共享一个
  request-total counter。Line `start/end`、placement、track、Run、children 与普通 JSON object/array 不计。
- 相同 authored vector Node 经 Repeat/TemplateUse 形成多个实际 occurrence 时逐 occurrence 重计。pruned Node 不计；
  `visible:false` 与 `opacity:0` 仍形成完整 occurrence 并照常计数。
- 按 final tree preorder、Node property 与 array index 顺序，在尚未创建对应 canonical Point/Command object 或扩展
  output list 前逐项 reserve；第 100001 项 first-fail，丢弃 builder，不形成 bytes/digest/Engine Command。
- 本轴与 authored per-Node/whole-DSL vector entries、RenderDocument Nodes/edges/Runs/textScalars、Engine paint items
  均为独立 limitId/counter；不以后验 JSON 搜索、坐标 member 数或 authored 数量替代 final occurrence 计数。

## TDD、验证与边界

- guard test 先引用缺失的 `RENDER_DOCUMENT_VECTOR_ENTRIES` 捕获 compile RED，再加入唯一 catalog 项重放
  `99999/100000/100001`。
- Sealer product test 以已预留 100000 的空 Canvas 证明 zero-charge；一个 3-point Polygon、2-point Polyline 与
  2-command Path 共 7 units，以 prefix `99993/99994` 证明 request-total exact at/above、最后一个 Path Command
  first-fail，同时覆盖 `visible:false/opacity:0`。
- focused guard/Sealer/RenderDocument/Evaluator、完整受影响 reactor、`render` 与 `fast`；无 app wiring/API/Web/
  migration/Profile 变化，不重复 server/full。
- 本票不实现 diagnostics、Engine paint/clip、正式 Ticket 19 records/product executor。provider/API Key/真实数据/
  费用/Profile registration/push/tag/PR 均不推进；最高 `automated_verified`、J0。

## Resolution

- guard/Sealer tests 先精确产生 6 个缺失 `RENDER_DOCUMENT_VECTOR_ENTRIES` 的 compile RED；唯一 catalog 加入
  `renderDocument.vectorEntries=100000` 后 guard 15/15 GREEN，stage/code/limitId 与 cap-022 完全一致。
- catalog 接线后 Sealer 13 tests 精确保留 1 个 behavioral RED：prefix 99994 + final 7 entries 错误返回
  `Sealed`；在真正 `sealNode` lowering 中识别 Polygon/Polyline `points` 与 Path `commands`，按 array index 逐项
  reserve 后 13/13 GREEN。
- empty Canvas 在 prefix 100000 后仍 seal；同一 final tree 中 Line start/end 零收费、3-point hidden Polygon +
  2-point opacity-zero Polyline + 2-command Path 精确消费 7 units。prefix 99993 exact 到达 100000，prefix 99994
  在最后一个 Path Command exact reject，证明三种 entry 共享 request-total counter 且 Line object 不误计。
- focused Evaluator 68 + canonical writer 3 + RenderDocument contract 4 + guard 15 + Sealer 13 = 103/103；受影响
  reactor Schema 20、Validation 13、Template 84、Asset 92、Rendering 227 全绿，零 failure/error；
  `git diff --check` 通过。
- A1 `render` `.sdlc/evidence/20260829-062902-render/`（2/2）与 `fast`
  `.sdlc/evidence/20260829-062950-fast/`（3/3）metadata 均 `passed`；既有 RenderDocument independent replay
  83/83 仍 byte-identical。
- cap-022 不执行 Evaluator/Sealer，故无 T153-specific A2/A3；J0 pending、J1 未批准。无 app wiring/API/Web/
  migration/Profile 变化，未重复 server/full；provider attempts/API Key reads/真实数据/Profile registration/
  push/tag/PR 均为 0。
- 状态回填后的 resolution `fast` `.sdlc/evidence/20260829-063054-fast/` metadata 为 `passed`，3/3 steps 全绿。
