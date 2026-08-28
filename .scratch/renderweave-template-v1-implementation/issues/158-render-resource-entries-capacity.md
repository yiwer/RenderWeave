# T158 — RenderResource entries 预算

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T129, T157 (resolved)

## 目标

物化 Ticket 19 / `RW-T19-S8-004` 与 cap-028：`assetsAndFetch.renderResourceEntries` 使用
MAX_INCLUSIVE `2048`，observed `2047/2048/2049`，contract stage `SERIAL_ASSET_RESOLUTION`、public stage
`ASSET_RESOLUTION`、code `RESOURCE_BUDGET_EXCEEDED`、zero boundary `ZERO_DOCUMENT_OUTPUT`。将 `Materializer`
现有私有常量与 `resources.size()` 判断迁入唯一 production capacity guard/request tracker。

## seam 与计数语义

- 每个成功的 actual Resolver operation 产生一项 one-to-one RenderResource；在 append 该项前预留 `1`。相同
  assetId、contentVersion、hash、resource bytes 或 cache/memo reuse 仍按成功 consumer occurrence 重计。
- Resolver rejected/conflict/timeout/unavailable 不形成 RenderResource、因此不消费本轴；它仍已消费 actual resolve
  occurrence。第 2049 项在 Resolver 已成功、但尚未 append manifest entry 时 first-fail，且不产生 RenderDocument。
- request tracker 跨 root、Repeat 与 TemplateUse child Materializer 共享；pruned consumer 为零收费，
  `visible:false`/`opacity:0` 的成功 resolve 仍形成 entry 并收费。
- 本轴与 authored occurrence、unique logical Assets、actual resolve occurrences、unique exact contents 及
  bytes/pixels/cache/fetch 预算独立；最终 Sealer 仍须验证 tree resourceId 引用与 manifest entry 一一对应。

## TDD、验证与边界

- guard test 先引用缺失的 `ASSETS_AND_FETCH_RENDER_RESOURCE_ENTRIES` 捕获 compile RED，再将唯一 catalog 项重放
  `2047/2048/2049`。
- Materializer product test 使用两个同 assetId、不同 occurrence path 的 IMAGE Node：resource-entry prefix `2046`
  后两次 resolve/append exact-at；prefix `2047` 后两次 Resolver 均成功，但第二项在 append 前 exact reject，断言
  frozen stage/code/full limitId 与 Resolver 调用数，证明不被 actual-resolve 轴支配。
- focused guard/Materializer/Evaluator/architecture、完整受影响 reactor、`render` 与 `fast`；无 app wiring/API/
  Web/migration/Profile 变化，不重复 server/full。
- 本票不实现 cap-024、unique content/bytes/pixels/cache/fetch/manifest bytes 预算或正式 Ticket 19 records/product
  executor。provider/API Key/真实数据/费用/Profile registration/push/tag/PR 均不推进；最高
  `automated_verified`、J0。

## Resolution

- guard/Materializer tests 首先精确产生 5 个缺失 `ASSETS_AND_FETCH_RENDER_RESOURCE_ENTRIES` 的 compile RED；
  唯一 catalog 项加入 `assetsAndFetch.renderResourceEntries=2048` 后 guard 20/20 GREEN，Materializer 精确保留
  1 个 behavioral RED：resource prefix 2047 后第二个成功 resolve 仍错误 append 并完成 materialization。
- `Materializer.resolveAtom` 已删除私有 `MAX_RESOURCE_ENTRIES` 与 `resources.size()` 判断；Resolver 成功后、读取
  fact 并 append `ResourceEntry` 前，经同一 request tracker reserve。失败 Resolver 不消费本轴，超限成功 Resolver
  已消费 actual-resolve 单位但不产生 entry 或 RenderDocument。
- 两个同 assetId、不同 occurrence path 的 IMAGE Node 在 resource prefix 2046 后均 resolve/append；prefix 2047
  后两次 Resolver 均成功，但第二项以 frozen ASSET_RESOLUTION / RESOURCE_BUDGET_EXCEEDED / full limitId exact
  reject，证明本轴与 actual-resolve occurrence 独立且 reservation point 位于 resolve 后、append 前。
- focused guard 20 + Materializer 24 + Evaluator 68 + architecture 6 = 118/118；受影响 reactor Schema 20、
  Validation 13、Template 84、Asset 92、Rendering 239 全绿，零 failure/error；`git diff --check` 通过。
- A1 `render` `.sdlc/evidence/20260829-071200-render/`（2/2）与 `fast`
  `.sdlc/evidence/20260829-071248-fast/`（3/3）metadata 均 `passed`。
- cap-028 fixture 只调用 guard，明确不执行 Resolver/Evaluator/Sealer/Renderer，故无 T158-specific A2/A3；J0
  pending、J1 未批准。cap-024 继续 deferred；无 app wiring/API/Web/migration/Profile 变化，未重复 server/full；
  provider attempts/API Key reads/真实数据/Profile registration/push/tag/PR 均为 0。
- 状态回填后的 resolution `fast` `.sdlc/evidence/20260829-071354-fast/` metadata 为 `passed`，3/3 steps 全绿。
