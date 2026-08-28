# T161 — unique raw bytes 预算

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T129, T160 (resolved)

## 目标

物化 Ticket 19 / `RW-T19-S8-007` 与 cap-031：`assetsAndFetch.uniqueRawBytes` 使用 MAX_INCLUSIVE
`268435456`，observed `268435455/268435456/268435457`，contract stage
`ASSET_ADMISSION_AND_RESOLUTION`、public stage `ASSET_ADMISSION`、code `ASSET_BUDGET_EXCEEDED`、zero boundary
`ZERO_DOCUMENT_OUTPUT`。successful Resolver fact 首次引入新 exact-content identity 时，在 RenderResource entry 与
external fetch 前按声明 `byteLength` 接入唯一 production capacity guard/request tracker。

## seam 与计数语义

- request 固定单一 ownerScope；沿 T159 的 exact identity `(kind, sha256, byteLength, mediaType)` 去重。每个新
  identity 的声明 `byteLength` 计一次；相同 identity 的后续 occurrence 不再消费本轴，但继续消费 T160
  occurrence declared bytes 并独立形成 RenderResource entry。
- Resolver rejected/conflict/timeout/unavailable 不产生 exact-content fact，因而不消费本轴。新 identity 先通过
  `uniqueExactContents` unit，再原子预留 unique raw bytes；两者成功后才加入 request-local exact set。
- 第一个使 unique total 超过 `256 MiB` 的 identity 在 RenderResource reservation/append 与 external fetch 前失败；
  不截断、不退款，不产生 RenderDocument、Engine Command 或 RenderOutput。
- 本轴与 occurrence raw bytes、unique exact-content count，以及后续 occurrence/unique pixels、FONT bytes、cache/
  fetch/manifest bytes 预算独立。

## TDD、验证与边界

- guard test 先引用缺失的 `ASSETS_AND_FETCH_UNIQUE_RAW_BYTES` 捕获 compile RED，再将唯一 catalog 项重放
  `268435455/268435456/268435457`。
- Materializer product test 使用 prefix `268435455`：两个相同 one-byte exact identity 应成功，证明 duplicate
  不重复收费；第二项改为不同 hash 时，第二个新 identity 应在 entry append 前 exact reject。
- focused guard/Materializer/Evaluator/architecture、完整受影响 reactor、`render` 与 `fast`；无 app wiring/API/
  Web/migration/Profile 变化，不重复 server/full。
- 本票不实现 cap-024、pixels/FONT bytes/cache/fetch/manifest bytes 预算、descriptor-consistency hardening 或正式
  Ticket 19 records/product executor。provider/API Key/真实数据/费用/Profile registration/push/tag/PR 均不推进；
  最高 `automated_verified`、J0。

## Resolution

- guard/Materializer tests 首先精确产生 5 个缺失 `ASSETS_AND_FETCH_UNIQUE_RAW_BYTES` 的 compile RED；唯一
  catalog 项加入后 guard 23/23 GREEN，Materializer 精确保留 1 个 behavioral RED：第二个新 identity 仍错误形成
  resource 并完成 materialization。
- `Materializer.reserveExactContent` 现对新 `(kind, sha256, byteLength, mediaType)` identity 先预留 unique-content
  unit，再按 `fact.byteLength()` 预留 unique raw bytes；两者成功后才加入 request-local set。duplicate identity
  直接复用既有 admission，不重复消费本轴，但 T160 occurrence bytes 仍逐项收费。
- prefix `268435455` + 两个相同 one-byte exact identity 精确到达 256 MiB 并形成两项 resource；第二项改为不同
  hash 时，两次 Resolver 均已执行，但第二个新 identity 以 frozen ASSET_ADMISSION / ASSET_BUDGET_EXCEEDED /
  full limitId exact reject。失败后 resource-entry tracker 仍只含首项，证明下游 reservation/append 尚未发生。
- focused guard 23 + Materializer 27 + Evaluator 68 + architecture 6 = 124/124；受影响 reactor Schema 20、
  Validation 13、Template 84、Asset 92、Rendering 245 全绿，零 failure/error；`git diff --check` 通过。
- A1 `render` `.sdlc/evidence/20260829-073600-render/`（2/2）与 `fast`
  `.sdlc/evidence/20260829-073649-fast/`（3/3）metadata 均 `passed`。
- cap-031 fixture 只调用 guard，明确不执行 Resolver/Evaluator/Sealer/Renderer，故无 T161-specific A2/A3；J0
  pending、J1 未批准。cap-024 继续 deferred；无 app wiring/API/Web/migration/Profile 变化，未重复 server/full；
  provider attempts/API Key reads/真实数据/Profile registration/push/tag/PR 均为 0。
- 状态回填后的 resolution `fast` `.sdlc/evidence/20260829-073758-fast/` metadata 为 `passed`，3/3 steps 全绿。
