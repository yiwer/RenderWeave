# T159 — unique exact contents 预算

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T129, T158 (resolved)

## 目标

物化 Ticket 19 / `RW-T19-S8-005` 与 cap-029：`assetsAndFetch.uniqueExactContents` 使用
MAX_INCLUSIVE `128`，observed `127/128/129`，contract stage `ASSET_ADMISSION_AND_RESOLUTION`、public stage
`ASSET_ADMISSION`、code `ASSET_BUDGET_EXCEEDED`、zero boundary `ZERO_DOCUMENT_OUTPUT`。在 successful Resolver
fact 首次引入新 exact content identity 时，接入唯一 production capacity guard/request tracker。

## seam 与计数语义

- request 固定单一 ownerScope；exact content identity 为 `(kind, sha256, byteLength, mediaType)`，与 Rust
  RenderDocument authority 及 trusted-partition raw cache identity 对齐。assetId、contentVersion、resourceId、
  fetch URL/lease、acceptance profile 与 technical descriptor 不参与去重 identity。
- 每个 successful Resolver fact 在形成 RenderResource entry 前检查 identity；新 identity 预留 `1` 后加入
  request-local set，重复 identity 不再收费，但每个 occurrence 仍已独立 resolve 且形成独立 RenderResource。
- Resolver rejected/conflict/timeout/unavailable 不产生 exact content fact，因而不消费本轴。第 129 个新 identity
  在 external fetch 与 RenderDocument 前 exact reject；actual-resolve 已收费，RenderResource entry 尚未 append。
- 本轴与 authored occurrence、unique logical Assets、actual resolve occurrences、RenderResource entries，以及后续
  occurrence/unique raw bytes、pixels、FONT bytes、cache/fetch 预算独立；相同 bytes 的不同 contentVersion 仍去重。

## TDD、验证与边界

- guard test 先引用缺失的 `ASSETS_AND_FETCH_UNIQUE_EXACT_CONTENTS` 捕获 compile RED，再将唯一 catalog 项重放
  `127/128/129`。
- Materializer product test 以 exact-content prefix `127` + 两个 occurrence 验证：相同 kind/hash/length/media、
  不同 contentVersion exact-at 成功并形成两项 resource；第二个 hash 不同时，第 129 个新 identity 在 entry append
  前 exact reject，断言 frozen stage/code/full limitId 与两次 Resolver 调用。
- focused guard/Materializer/Evaluator/architecture、完整受影响 reactor、`render` 与 `fast`；无 app wiring/API/
  Web/migration/Profile 变化，不重复 server/full。
- 本票不实现 cap-024、声明 bytes/pixels/FONT bytes/cache/fetch/manifest bytes 预算、descriptor-consistency hardening
  或正式 Ticket 19 records/product executor。provider/API Key/真实数据/费用/Profile registration/push/tag/PR 均不推进；
  最高 `automated_verified`、J0。

## Resolution

- guard/Materializer tests 首先精确产生 5 个缺失 `ASSETS_AND_FETCH_UNIQUE_EXACT_CONTENTS` 的 compile RED；
  唯一 catalog 项加入 `assetsAndFetch.uniqueExactContents=128` 后 guard 21/21 GREEN，Materializer 精确保留
  1 个 behavioral RED：prefix 127 后第二个新 hash 仍错误 append 并完成 materialization。
- `Materializer` 现维护 request-local `ExactContentIdentity(kind, sha256, byteLength, mediaType)` set；successful
  Resolver fact 首次出现时先经同一 request tracker reserve，成功才入 set。identity 与 Rust RenderDocument
  `ExactContentKey` 对齐，单 request 的 ownerScope 为隐含固定维度。
- prefix 127 + 两个相同 exact content、不同 contentVersion occurrence 成功形成两项 resource；第二个 hash 改变时，
  两次 Resolver 均已执行，但第 129 个 identity 以 frozen ASSET_ADMISSION / ASSET_BUDGET_EXCEEDED / full limitId
  exact reject。失败后 resource-entry tracker 仍只含首项，证明 unique admission 位于第二项 append/reservation 前。
- focused guard 21 + Materializer 25 + Evaluator 68 + architecture 6 = 120/120；受影响 reactor Schema 20、
  Validation 13、Template 84、Asset 92、Rendering 241 全绿，零 failure/error；`git diff --check` 通过。
- A1 `render` `.sdlc/evidence/20260829-071958-render/`（2/2）与 `fast`
  `.sdlc/evidence/20260829-072046-fast/`（3/3）metadata 均 `passed`。
- cap-029 fixture 只调用 guard，明确不执行 Resolver/Evaluator/Sealer/Renderer，故无 T159-specific A2/A3；J0
  pending、J1 未批准。cap-024 继续 deferred；无 app wiring/API/Web/migration/Profile 变化，未重复 server/full；
  provider attempts/API Key reads/真实数据/Profile registration/push/tag/PR 均为 0。
- 状态回填后的 resolution `fast` `.sdlc/evidence/20260829-072153-fast/` metadata 为 `passed`，3/3 steps 全绿。
