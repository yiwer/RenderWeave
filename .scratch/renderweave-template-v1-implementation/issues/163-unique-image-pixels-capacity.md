# T163 — unique IMAGE pixels 预算

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T129, T162 (resolved)

## 目标

物化 Ticket 19 / `RW-T19-S8-010` 与 cap-033：`assetsAndFetch.uniqueImagePixels` 使用 MAX_INCLUSIVE
`125000000`，observed `124999999/125000000/125000001`，contract stage
`ASSET_ADMISSION_AND_RESOLUTION`、public stage `ASSET_ADMISSION`、code `ASSET_BUDGET_EXCEEDED`、zero boundary
`ZERO_DOCUMENT_OUTPUT`。每个 successful IMAGE Resolver fact 首次引入新 exact-content identity 时，在
RenderResource entry 与 external fetch 前，按 orientation 后 logical pixels 接入唯一 production capacity
guard/request tracker。

## seam 与计数语义

- request 固定单一 ownerScope；沿 T159 的 exact identity `(kind, sha256, byteLength, mediaType)` 去重。每个新
  IMAGE identity 按 `logicalWidthPx × logicalHeightPx` 计一次；相同 identity 的后续 occurrence 不再消费本轴，
  但继续消费 T160 occurrence raw bytes、T162 occurrence IMAGE pixels，并形成独立 RenderResource entry。
- FONT 对本轴收费 `0`。Resolver rejected/conflict/timeout/unavailable 不产生 exact-content fact，因而不消费本轴。
- 顺序与 Rust RenderDocument authority 对齐：new identity 先预留 unique exact-content count、unique raw bytes，
  再预留本轴；全部成功后才加入 request-local exact set，随后才预留/append RenderResource。
- 第一个使 unique total 超过 `125,000,000` pixels 的 identity 零文档失败；不截断、不退款，不产生 Engine
  Command 或 RenderOutput。
- 本轴与 per-content IMAGE Acceptance Profile、occurrence IMAGE pixels、raw/FONT/cache/fetch/manifest bytes
  预算独立。

## TDD、验证与边界

- guard test 先引用缺失的 `ASSETS_AND_FETCH_UNIQUE_IMAGE_PIXELS` 捕获 compile RED，再将唯一 catalog 项重放
  `124999999/125000000/125000001`。
- Materializer product test 使用两个 logical `1×1` IMAGE fact：prefix `124999999` 时 duplicate exact content
  两项均成功且本轴只收费一次；第二项改为不同 hash 时须在 resource append 前 exact reject。
- focused guard/Materializer/Evaluator/architecture、完整受影响 reactor、`render` 与 `fast`；无 app wiring/API/
  Web/migration/Profile 变化，不重复 server/full。
- 本票不实现 cap-024、FONT/cache/fetch/manifest bytes 预算、跨 duplicate descriptor consistency hardening 或
  正式 Ticket 19 records/product executor。provider/API Key/真实数据/费用/Profile registration/push/tag/PR
  均不推进；最高 `automated_verified`、J0。

## Resolution

- guard/Materializer tests 首先精确产生 5 个缺失 `ASSETS_AND_FETCH_UNIQUE_IMAGE_PIXELS` 的 compile RED；唯一
  catalog 项加入后 guard 25/25 GREEN，Materializer 精确保留 1 个 behavioral RED：第二个新 IMAGE exact
  identity 仍错误形成 resource 并完成 materialization。
- `Materializer.reserveExactContent` 现于 new identity 的 unique exact-content count、unique raw-byte reservation
  后，复用 `reserveImagePixels` 按 `ImageDescriptor.logicalWidthPx() × logicalHeightPx()` 预留本轴；成功后才加入
  exact set。duplicate identity 直接复用既有 admission，不重复消费本轴，但 occurrence raw/pixels 仍逐项收费。
- prefix `124999999` + 两个相同 exact-content logical `1×1` IMAGE occurrence 成功并形成两项 resource；第二项
  改为不同 hash 时，两次 Resolver 均已执行，但第二个新 identity 以 frozen ASSET_ADMISSION /
  ASSET_BUDGET_EXCEEDED / full limitId exact reject。失败后 unique count/raw tracker 已包含两项、resource-entry
  tracker 仅含首项，证明 frozen upstream/downstream 顺序。
- focused guard 25 + Materializer 29 + Evaluator 68 + architecture 6 = 128/128；受影响 reactor Schema 20、
  Validation 13、Template 84、Asset 92、Rendering 249 全绿，零 failure/error；`git diff --check` 通过。
- A1 `render` `.sdlc/evidence/20260829-075320-render/`（2/2）与 `fast`
  `.sdlc/evidence/20260829-075410-fast/`（3/3）metadata 均 `passed`。
- cap-033 fixture 只调用 production guard，明确不执行 Resolver/Evaluator/Sealer/Renderer，故无 T163-specific
  A2/A3；J0 pending、J1 未批准。cap-024 继续 deferred；无 app wiring/API/Web/migration/Profile 变化，未重复
  server/full；provider attempts/API Key reads/真实数据/Profile registration/push/tag/PR 均为 0。
- 状态回填后的 resolution `fast` `.sdlc/evidence/20260829-075521-fast/` metadata 为 `passed`，3/3 steps 全绿。
