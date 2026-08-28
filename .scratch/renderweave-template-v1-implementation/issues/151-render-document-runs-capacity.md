# T151 — RenderDocument Runs 预算

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T150 (resolved)

## 目标

物化 Ticket 19 / `RW-T19-S7-093` 与 cap-020：`renderDocument.runs` 使用 MAX_INCLUSIVE `10000`，
observed `9999/10000/10001`，contract stage `DOCUMENT_SEAL_COUNTING`、public stage `DOCUMENT_SEAL`、code
`RENDER_DOCUMENT_LIMIT_EXCEEDED`、zero boundary `ZERO_DOCUMENT_OUTPUT`。最终 RenderDocument 中每个 surviving Text
occurrence 的每个 `runs[]` item 必须在对应 canonical Run array/object 分配及 atomic bytes/digest commit 前，经现有
request-local production guard 原子预留。

## seam 与计数语义

- 每个最终 `text.runs[]` item 计 `1`；相同 authored Text 经 Repeat/TemplateUse 形成多个实际 occurrence 时逐 occurrence
  重计。非 Text Node、root Canvas 与空数组计 `0`。
- `render:false`、false Conditional、SKIP 与零-survivor Repeat 已不进入 final tree，故不计；`visible:false` 与
  `opacity:0` 仍形成完整 Text occurrence，Runs 必须照常计数。
- 在 `sealNode()` 已取得 final expanded Text property、但尚未为对应 Run 创建 canonical object/list growth 前，按
  Run index 逐项原子 reserve；失败即丢弃 builder，不形成 bytes/digest/Engine Command。
- 本轴与 authored Runs、`renderDocument.textScalars`、static Nodes、child edges、Asset FONT occurrence 均为独立
  limitId/counter；不得以后验 JSON 搜索、资源数或 authored DesignDSL 数量替代 final occurrence 计数。

## TDD、验证与边界

- guard test 先引用缺失的 `RENDER_DOCUMENT_RUNS` 捕获 compile RED，再加入唯一 catalog 项重放
  `9999/10000/10001`。
- Sealer product test 以已预留 10000 的空 Canvas 证明 zero-charge，并以 `visible:false` / `opacity:0` 两个 one-Run
  Text occurrence + prefix `9998/9999` 证明 request-total exact at/above 与第二个 Run first-fail。
- focused guard/Sealer/RenderDocument/Evaluator、完整受影响 reactor、`render` 与 `fast`；无 app wiring/API/Web/
  migration/Profile 变化，不重复 server/full。
- 本票不实现 `renderDocument.textScalars/vectorEntries`、diagnostics、正式 Ticket 19 records/product executor 或
  Engine。provider/API Key/真实数据/费用/Profile registration/push/tag/PR 均不推进；最高
  `automated_verified`、J0。

## Resolution

- guard/Sealer tests 先精确产生 6 个缺失 `RENDER_DOCUMENT_RUNS` 的 compile RED；唯一 catalog 加入
  `renderDocument.runs=10000` 后 guard 13/13 GREEN，stage/code/limitId 与 cap-020 完全一致。
- catalog 接线后 Sealer 11 tests 精确保留 1 个 behavioral RED：prefix 9999 + 两条 final Run 错误返回 `Sealed`；
  在真正 `sealNode` Text lowering 中按 Run index 逐项 reserve 后 11/11 GREEN。
- empty Canvas 在 Run prefix 10000 后仍 seal；`visible:false` 与 `opacity:0` 两个 one-Run Text occurrence 在 prefix
  9998 后 exact 到达 10000，prefix 9999 后于第二个 Run exact reject。专用 empty-list lowering 确保每次 canonical
  Run object/list growth 均发生在对应 reservation 成功后。
- focused Evaluator 68 + canonical writer 3 + RenderDocument contract 4 + guard 13 + Sealer 11 = 99/99；受影响
  reactor Schema 20、Validation 13、Template 84、Asset 92、Rendering 223 全绿，零 failure/error。
- A1 `render` `.sdlc/evidence/20260829-060958-render/`（2/2）与 `fast`
  `.sdlc/evidence/20260829-061045-fast/`（3/3）metadata 均 `passed`；既有 RenderDocument independent replay
  83/83 仍 byte-identical。
- cap-020 不执行 Evaluator/Sealer，故无 T151-specific A2/A3；J0 pending、J1 未批准。无 app wiring/API/Web/
  migration/Profile 变化，未重复 server/full；provider attempts/API Key reads/真实数据/Profile registration/
  push/tag/PR 均为 0。
- 状态回填后的 resolution `fast` `.sdlc/evidence/20260829-061156-fast/` metadata 为 `passed`，3/3 steps 全绿。
