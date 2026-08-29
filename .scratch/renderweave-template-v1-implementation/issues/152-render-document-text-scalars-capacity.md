# T152 — RenderDocument text scalars 预算

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T151 (resolved)

## 目标

物化 Ticket 19 / `RW-T19-S7-094` 与 cap-021：`renderDocument.textScalars` 使用 MAX_INCLUSIVE
`1000000`，observed `999999/1000000/1000001`，contract stage `DOCUMENT_SEAL_COUNTING`、public stage
`DOCUMENT_SEAL`、code `RENDER_DOCUMENT_LIMIT_EXCEEDED`、zero boundary `ZERO_DOCUMENT_OUTPUT`。最终
RenderDocument 中每个 surviving Text Run 的 `text` 必须在对应 canonical string/object/list growth 及 atomic
bytes/digest commit 前，经现有 request-local production guard 原子预留。

## seam 与计数语义

- 计数域只包含最终 `text.runs[*].text`；QR `content` 与 Barcode `value` 由各自编码/内容容量拥有，不混入本轴。
- 以 Unicode scalar 计数：合法 surrogate pair 计 `1`，组合序列逐 scalar 计数，LF 计 `1`，空 Run 计 `0`；不按
  UTF-16 code unit、UTF-8 byte、grapheme/glyph 或 normalization 后字符计数。
- 相同 authored Run 经 Repeat/TemplateUse 形成多个实际 occurrence 时逐 occurrence 重计。pruned Text 不计；
  `visible:false` 与 `opacity:0` 仍形成完整 Text occurrence并照常计数。
- 在 T151 已成功预留 Run unit 后、尚未创建对应 canonical Run object/string 前，按 Text occurrence 与 Run index
  顺序预留该 Run 的完整 scalar count；失败丢弃 builder，不形成 bytes/digest/Engine Command。
- 本轴与 authored Run text、RenderDocument Runs、static Nodes、child edges、Engine grapheme/glyph 均为独立
  limitId/counter，不以后验 JSON 搜索或 authored 数量替代 final occurrence 计数。

## TDD、验证与边界

- guard test 先引用缺失的 `RENDER_DOCUMENT_TEXT_SCALARS` 捕获 compile RED，再加入唯一 catalog 项重放
  `999999/1000000/1000001`。
- Sealer product test 以已预留 1000000 的空 Canvas 证明 zero-charge；两个 one-Run Text 分别携带非 BMP scalar 与
  未 normalization 的组合序列 + LF，并用 prefix `999996/999997` 证明 request-total exact at/above、第二个 Run
  first-fail、`visible:false/opacity:0` 仍计。
- focused guard/Sealer/RenderDocument/Evaluator、完整受影响 reactor、`render` 与 `fast`；无 app wiring/API/Web/
  migration/Profile 变化，不重复 server/full。
- 本票不实现 `renderDocument.vectorEntries`、diagnostics、Engine grapheme/glyph、正式 Ticket 19 records/product
  executor。provider/API Key/真实数据/费用/Profile registration/push/tag/PR 均不推进；最高
  `automated_verified`、J0。

## Resolution

- guard/Sealer tests 先精确产生 6 个缺失 `RENDER_DOCUMENT_TEXT_SCALARS` 的 compile RED；唯一 catalog 加入
  `renderDocument.textScalars=1000000` 后 guard 14/14 GREEN，stage/code/limitId 与 cap-021 完全一致。
- catalog 接线后 Sealer 12 tests 精确保留 1 个 behavioral RED：prefix 999997 + 最终四个 scalar 错误返回
  `Sealed`；在真正 `sealNode` Text Run lowering 中先 reserve Run、再按完整 `text` scalar count 原子 reserve 后
  12/12 GREEN。
- empty Canvas 在 scalar prefix 1000000 后仍 seal；两个 surviving one-Run Text 依次携带一个非 BMP scalar 与
  `e + combining acute + LF` 三个 scalars。prefix 999996 exact 到达 1000000，prefix 999997 在第二个 Run exact
  reject，同时证明不按 UTF-16、不 normalization、不忽略 LF，且 `visible:false/opacity:0` 仍计。
- focused Evaluator 68 + canonical writer 3 + RenderDocument contract 4 + guard 14 + Sealer 12 = 101/101；受影响
  reactor Schema 20、Validation 13、Template 84、Asset 92、Rendering 225 全绿，零 failure/error；
  `git diff --check` 通过。
- A1 `render` `.sdlc/evidence/20260829-062042-render/`（2/2）与 `fast`
  `.sdlc/evidence/20260829-062129-fast/`（3/3）metadata 均 `passed`；既有 RenderDocument independent replay
  83/83 仍 byte-identical。
- cap-021 不执行 Evaluator/Sealer，故无 T152-specific A2/A3；J0 pending、J1 未批准。无 app wiring/API/Web/
  migration/Profile 变化，未重复 server/full；provider attempts/API Key reads/真实数据/Profile registration/
  push/tag/PR 均为 0。
- 状态回填后的 resolution `fast` `.sdlc/evidence/20260829-062243-fast/` metadata 为 `passed`，3/3 steps 全绿。
