# T148 — RenderDocument strict JSON depth 预算

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T147 (resolved)

## 目标

物化 Ticket 19 / `RW-T19-S7-090` 与 cap-017 的真实纵切：RenderDocument strict JSON container depth 使用
MAX_INCLUSIVE `128`，observed `127/128/129`，contract stage `DOCUMENT_SEAL_COUNTING`、public stage
`DOCUMENT_SEAL`、code `RENDER_DOCUMENT_LIMIT_EXCEEDED`、zero boundary `ZERO_DOCUMENT_OUTPUT`。深度必须由 T147
已建立的 production canonical writer 在任何 document bytes/digest 原子 commit 与 Engine Command 前强制。

## seam 与计数语义

- strict JSON depth 与现有 `RenderJsonParser` 一致：root object/array depth 为 `1`；每进入一个 object/array `+1`；
  scalar、member name 与 string 内容中的 `{`/`[` 不计深度。
- 扩展 `CanonicalJson.Utf8Sink` 的结构事件，普通 String encoder 使用默认 no-op；唯一
  `RenderDocumentCanonicalWriter` 在 container opening bytes 前观察 request-local maximum depth，并通过同一
  `RenderingPipelineCapacityGuard` 返回 frozen stage/code/limitId。
- request tracker 需要区分 cumulative reservation 与 maximum observation；新增 maximum API 仍由同一 catalog
  判定，不建立第二套 limit、parser 或 post-hoc byte scan。
- cap-017 明确不执行 Evaluator/Sealer；隔离 guard 三点不冒充 full pipeline。production writer 用真实 nested
  canonical object/array 重放 exact `127/128/129`，并证明 above 时没有 committed byte array。

## TDD、验证与边界

- guard test 先引用缺失的 `RENDER_DOCUMENT_JSON_DEPTH`，形成 compile RED；最小 catalog 扩展后 GREEN。
- writer test 再以 127/128/129 层 canonical containers 捕获 production writer 未计深度的 behavioral RED；随后
  仅增加 structural depth observation，保持现有 canonical bytes/digests byte-identical。
- focused guard/writer/Sealer/RenderDocument/Evaluator、完整受影响 reactor、`render` 与 `fast`；无 app wiring/API/
  Web/migration/Profile 变化，不重复 server/full。
- 本票不实现 `renderDocument.staticNodes/childEdges/runs/textScalars/vectorEntries`、diagnostics、正式 Ticket 19
  records/product executor 或 Engine。provider/API Key/真实数据/费用/Profile registration/push/tag/PR 均不推进；
  最高 `automated_verified`、J0。

## Resolution

- 正确引用参数的 guard tracer 精确产生 3 个 compile RED，全部为 production enum 缺少
  `RENDER_DOCUMENT_JSON_DEPTH`；此前一次 PowerShell 参数引用错误未进入编译，不计 RED。唯一 catalog 最小扩展后
  cap-017 `127/128` admit、`129` 返回 exact DOCUMENT_SEAL / RENDER_DOCUMENT_LIMIT_EXCEEDED /
  `renderDocument.jsonDepth`，guard 10/10 GREEN。
- writer tracer 在 2 tests 中精确 1 个 behavioral RED：129 层期望 `CapacityExceeded`、实际正常返回；127/128 已成功。
  `CanonicalValue` 现为 object/array 发出结构事件，writer 在 opening bytes 前经 request tracker `observeMaximum`，
  root container 为 1。string 内 `{[` 不收费，129 个 sibling containers 也不跨 path 累计。
- mixed object/array `127/128` byte commit 成功；`129` 返回 exact problem 且调用无返回 byte array。focused Evaluator 68 +
  writer 3 + RenderDocument 4 + guard 10 + Sealer 6 = 91/91；受影响 reactor Schema 20、Validation 13、Template 84、
  Asset 92、Rendering 215 全绿，`git diff --check` 通过。
- A1 `render` `.sdlc/evidence/20260829-053313-render/`（2/2，含既有 RenderDocument independent replay 83/83）与
  `fast` `.sdlc/evidence/20260829-053400-fast/`（3/3）metadata 均 `passed`。cap-017 明确不执行 Evaluator/Sealer，
  故无 T148-specific A2/A3；J0 pending、J1 未批准。未重复 server/full，provider attempts/API Key reads/真实数据/
  Profile registration/push/tag/PR 均为 0。
- 状态回填后的 resolution `fast` `.sdlc/evidence/20260829-053518-fast/` metadata 为 `passed`，3/3 steps 全绿。
