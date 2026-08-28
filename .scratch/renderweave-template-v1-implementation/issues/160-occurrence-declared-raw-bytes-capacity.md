# T160 — occurrence declared raw bytes 预算

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T129, T159 (resolved)

## 目标

物化 Ticket 19 / `RW-T19-S8-006` 与 cap-030：
`assetsAndFetch.occurrenceDeclaredRawBytes` 使用 MAX_INCLUSIVE `2147483648`，observed
`2147483647/2147483648/2147483649`，contract stage `ASSET_ADMISSION_AND_RESOLUTION`、public stage
`ASSET_ADMISSION`、code `ASSET_BUDGET_EXCEEDED`、zero boundary `ZERO_DOCUMENT_OUTPUT`。每个 successful
Resolver fact 在形成 RenderResource entry 与进入 external fetch 前，按声明 `byteLength` 接入唯一 production
capacity guard/request tracker。

## seam 与计数语义

- 每个实际 resolved consumer occurrence 都按其声明 `byteLength` 收费；相同 assetId、contentVersion 或 exact
  content identity，以及后续 raw/decoded cache hit，均不减少 occurrence 预算。
- Resolver rejected/conflict/timeout/unavailable 不产生 resolved fact，因而不消费本轴；successful fact 的 declared
  bytes reserve 成功后，才可进入 unique exact-content admission、RenderResource reservation/append 与 external fetch。
- 第一个使 request total 超过 `2 GiB` 的 occurrence 原子失败；本轴不截断、不退款，不产生该 occurrence 的
  RenderResource entry、RenderDocument、Engine Command 或 RenderOutput。
- 本轴独立于 actual resolve occurrences、RenderResource entries、unique exact contents，以及后续 unique raw
  bytes、occurrence/unique pixels、FONT bytes、cache/fetch/manifest 预算。

## TDD、验证与边界

- guard test 先引用缺失的 `ASSETS_AND_FETCH_OCCURRENCE_DECLARED_RAW_BYTES` 捕获 compile RED，再将唯一 catalog 项
  重放 `2147483647/2147483648/2147483649`。
- Materializer product test 以 request prefix + 两个同 exact-content occurrence 验证 exact-at 与 above；两次
  Resolver 均返回 `byteLength=1`，证明 duplicate exact content 不减免，第二项超限时尚未消费 unique-content/
  RenderResource 下游预算。
- focused guard/Materializer/Evaluator/architecture、完整受影响 reactor、`render` 与 `fast`；无 app wiring/API/
  Web/migration/Profile 变化，不重复 server/full。
- 本票不实现 cap-024、unique raw bytes/pixels/FONT bytes/cache/fetch/manifest bytes 预算、descriptor-consistency
  hardening 或正式 Ticket 19 records/product executor。provider/API Key/真实数据/费用/Profile registration/
  push/tag/PR 均不推进；最高 `automated_verified`、J0。

## Resolution

- guard/Materializer tests 首先精确产生 5 个缺失
  `ASSETS_AND_FETCH_OCCURRENCE_DECLARED_RAW_BYTES` 的 compile RED；唯一 catalog 项加入后 guard 22/22 GREEN，
  Materializer 精确保留 1 个 behavioral RED：prefix `2147483647` 后第二个 one-byte occurrence 仍错误完成
  materialization。
- `Materializer.resolveAtom` 现于 successful Resolver fact 后、unique exact-content admission 与 RenderResource
  reservation/append 前，按 `fact.byteLength()` 经同一 request tracker 原子 reserve。Resolver 失败无 fact、零收费；
  duplicate exact content 仍逐 occurrence 收费。
- prefix `2147483646` + 两个相同 exact-content one-byte occurrence 精确到达 2 GiB 并形成两项 resource；prefix
  `2147483647` 时第二次 Resolver 已执行，但第二项以 frozen ASSET_ADMISSION / ASSET_BUDGET_EXCEEDED / full
  limitId exact reject。失败后 resource-entry tracker 仍只含首项，证明下游 reservation/append 尚未发生。
- focused guard 22 + Materializer 26 + Evaluator 68 + architecture 6 = 122/122；受影响 reactor Schema 20、
  Validation 13、Template 84、Asset 92、Rendering 243 全绿，零 failure/error；`git diff --check` 通过。
- A1 `render` `.sdlc/evidence/20260829-072923-render/`（2/2）与 `fast`
  `.sdlc/evidence/20260829-073009-fast/`（3/3）metadata 均 `passed`。
- cap-030 fixture 只调用 guard，明确不执行 Resolver/Evaluator/Sealer/Renderer，故无 T160-specific A2/A3；J0
  pending、J1 未批准。cap-024 继续 deferred；无 app wiring/API/Web/migration/Profile 变化，未重复 server/full；
  provider attempts/API Key reads/真实数据/Profile registration/push/tag/PR 均为 0。
- 状态回填后的 resolution `fast` `.sdlc/evidence/20260829-073109-fast/` metadata 为 `passed`，3/3 steps 全绿。
