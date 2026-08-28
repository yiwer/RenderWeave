# T162 — occurrence IMAGE pixels 预算

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T129, T161 (resolved)

## 目标

物化 Ticket 19 / `RW-T19-S8-007` 与 cap-032：`assetsAndFetch.occurrenceImagePixels` 使用 MAX_INCLUSIVE
`1000000000`，observed `999999999/1000000000/1000000001`，contract stage
`ASSET_ADMISSION_AND_RESOLUTION`、public stage `ASSET_ADMISSION`、code `ASSET_BUDGET_EXCEEDED`、zero boundary
`ZERO_DOCUMENT_OUTPUT`。每个 successful IMAGE Resolver fact 在 exact-content admission、RenderResource entry 与
external fetch 前，按 orientation 后逻辑尺寸累计 pixels 并接入唯一 production capacity guard/request tracker。

## seam 与计数语义

- IMAGE unit 精确为 `logicalWidthPx × logicalHeightPx`，即 Acceptance Profile 已应用 orientation 后的 logical pixels；
  使用 long 乘法。FONT 对本轴收费 `0`。
- 每个实际 IMAGE consumer occurrence 都收费；相同 assetId、contentVersion、exact content，以及后续 cache hit，
  均不减少 occurrence pixels。Resolver 失败没有 fact，因而不收费。
- 顺序与 Rust RenderDocument authority 对齐：successful fact 先消费 T160 occurrence declared raw bytes，再消费本轴，
  随后才进入 unique exact-content count/bytes 和 RenderResource reservation/append。
- 第一个使 request total 超过 `1,000,000,000` 的 IMAGE occurrence 原子失败；该 occurrence 不消费 unique/entry
  下游预算，不产生 RenderDocument、Engine Command 或 RenderOutput。
- 本轴与 per-content IMAGE Acceptance Profile、unique IMAGE pixels、FONT bytes、cache/fetch/manifest bytes 独立。

## TDD、验证与边界

- guard test 先引用缺失的 `ASSETS_AND_FETCH_OCCURRENCE_IMAGE_PIXELS` 捕获 compile RED，再将唯一 catalog 项重放
  `999999999/1000000000/1000000001`。
- Materializer product test 使用两个 logical `1×1` IMAGE fact：prefix `999999998` exact-at 成功且 duplicate exact
  content 仍收费；prefix `999999999` 时第二项失败，并证明其 unique-content/raw/resource-entry 下游预算未消费。
- focused guard/Materializer/Evaluator/architecture、完整受影响 reactor、`render` 与 `fast`；无 app wiring/API/
  Web/migration/Profile 变化，不重复 server/full。
- 本票不实现 cap-024、unique IMAGE pixels、FONT bytes/cache/fetch/manifest bytes 预算、跨 duplicate descriptor
  consistency hardening 或正式 Ticket 19 records/product executor。provider/API Key/真实数据/费用/Profile
  registration/push/tag/PR 均不推进；最高 `automated_verified`、J0。

## Resolution

- guard/Materializer tests 首先精确产生 5 个缺失 `ASSETS_AND_FETCH_OCCURRENCE_IMAGE_PIXELS` 的 compile RED；
  唯一 catalog 项加入后 guard 24/24 GREEN，Materializer 精确保留 1 个 behavioral RED：第二个超限 IMAGE
  occurrence 仍错误形成 resource 并完成 materialization。
- `Materializer.resolveAtom` 现于 successful IMAGE fact 的 T160 occurrence raw-byte reservation 后、unique exact-content
  admission 前，按 `ImageDescriptor.logicalWidthPx() × logicalHeightPx()` 预留本轴；FONT 不进入本轴，IMAGE fact
  缺失精确 descriptor 时 fail closed 为 internal invariant。
- prefix `999999998` + 两个相同 exact-content logical `1×1` IMAGE occurrence 精确到达上限并形成两项 resource，
  证明 duplicate 仍逐 occurrence 收费；prefix `999999999` 时第二次 Resolver 已执行，但第二项以 frozen
  ASSET_ADMISSION / ASSET_BUDGET_EXCEEDED / full limitId exact reject。失败后的 unique-content、unique-raw 与
  resource-entry tracker 均只含首项，证明下游 reservation/append 尚未发生。
- focused guard 24 + Materializer 28 + Evaluator 68 + architecture 6 = 126/126；受影响 reactor Schema 20、
  Validation 13、Template 84、Asset 92、Rendering 247 全绿，零 failure/error；`git diff --check` 通过。
- A1 `render` `.sdlc/evidence/20260829-074507-render/`（2/2）与 `fast`
  `.sdlc/evidence/20260829-074555-fast/`（3/3）metadata 均 `passed`。
- cap-032 fixture 只调用 production guard，明确不执行 Resolver/Evaluator/Sealer/Renderer，故无 T162-specific
  A2/A3；J0 pending、J1 未批准。cap-024 继续 deferred；无 app wiring/API/Web/migration/Profile 变化，未重复
  server/full；provider attempts/API Key reads/真实数据/Profile registration/push/tag/PR 均为 0。
- 状态回填后的 resolution `fast` `.sdlc/evidence/20260829-074728-fast/` metadata 为 `passed`，3/3 steps 全绿。
