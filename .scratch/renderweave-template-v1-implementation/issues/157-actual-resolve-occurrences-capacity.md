# T157 — actual Asset resolve occurrences 预算

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T129, T156 (resolved)

## 目标

物化 Ticket 19 / `RW-T19-S8-003` 与 cap-027：`assetsAndFetch.actualResolveOccurrences` 使用
MAX_INCLUSIVE `2048`，observed `2047/2048/2049`，contract stage `SERIAL_ASSET_RESOLUTION`、public stage
`ASSET_RESOLUTION`、code `RESOURCE_BUDGET_EXCEEDED`、zero boundary `ZERO_DOCUMENT_OUTPUT`。将 `Materializer`
现有私有常量与手写 `resolves` counter 迁入唯一 production capacity guard/request tracker。

## seam 与计数语义

- 每个 materialized concrete AssetRef property 在调用 `AssetResolutionPort.resolve` 前预留 `1`；相同 assetId、
  resource bytes、contentVersion 或 cache/memo reuse 仍按 consumer occurrence 重计。
- 顺序保持 materialized authored DFS、NodeContract/property traversal 与 Text Run index 的现有 serial order；第 2049
  个在构造/发起下一 Resolver operation 前 first-fail，后续 Asset/Capability/Document/Engine 工作均停止。
- 已发起并返回 rejected/conflict/timeout/unavailable 的 Resolver operation 仍消费单位，不退款；超限本身不调用 port。
- pruned occurrence 为零收费；`visible:false` 与 `opacity:0` 不剪枝，仍完整 resolve 并收费。与 authored occurrence、
  unique logical Assets、RenderResource entries、unique exact contents 及 bytes/pixels/cache/fetch 预算独立。

## TDD、验证与边界

- guard test 先引用缺失的 `ASSETS_AND_FETCH_ACTUAL_RESOLVE_OCCURRENCES` 捕获 compile RED，再将唯一 catalog 项
  重放 `2047/2048/2049`。
- Materializer product test 使用两个同 assetId、不同 occurrence path 的 IMAGE Node：prefix `2046` 后两次 resolve
  exact-at 并生成两项 resource；prefix `2047` 后第一项 resolve，第二项在 port 前 exact reject，并断言 frozen
  stage/code/full limitId。
- focused guard/Materializer/Evaluator/architecture、完整受影响 reactor、`render` 与 `fast`；无 app wiring/API/
  Web/migration/Profile 变化，不重复 server/full。
- 本票不实现 cap-024、RenderResource entry/content/bytes/pixels/cache/fetch 预算或正式 Ticket 19 records/product
  executor。provider/API Key/真实数据/费用/Profile registration/push/tag/PR 均不推进；最高
  `automated_verified`、J0。

## Resolution

- guard/Materializer tests 首先精确产生 5 个缺失 `ASSETS_AND_FETCH_ACTUAL_RESOLVE_OCCURRENCES` 的 compile RED；
  唯一 catalog 项加入 `assetsAndFetch.actualResolveOccurrences=2048` 后 guard 19/19 GREEN，Materializer 精确保留
  1 个 behavioral RED：prefix 2047 时第二个 occurrence 仍错误调用 Resolver 并成功。
- `Materializer.resolveAtom` 已删除私有 `MAX_ACTUAL_RESOLVE_OCCURRENCES` 与实例 `resolves` counter；每个 concrete
  AssetRef consumer 现在先经同一 request tracker reserve，再调用 `AssetResolutionPort.resolve`，因此失败 operation
  已收费而第 2049 个超限 occurrence 不会到达 port。
- 两个同 assetId、不同 occurrence path 的 IMAGE Node 在 prefix 2046 后均 resolve 并形成两项 resource；prefix 2047
  后仅第一项到达 port，第二项以 frozen ASSET_RESOLUTION / RESOURCE_BUDGET_EXCEEDED / full limitId exact reject。
- focused guard 19 + Materializer 23 + Evaluator 68 + architecture 6 = 116/116；受影响 reactor Schema 20、
  Validation 13、Template 84、Asset 92、Rendering 237 全绿，零 failure/error；`git diff --check` 通过。
- A1 `render` `.sdlc/evidence/20260829-070357-render/`（2/2）与 `fast`
  `.sdlc/evidence/20260829-070548-fast/`（3/3）metadata 均 `passed`。
- cap-027 fixture 只调用 guard，明确不执行 Resolver/Evaluator/Sealer/Renderer，故无 T157-specific A2/A3；J0
  pending、J1 未批准。cap-024 继续 deferred；无 app wiring/API/Web/migration/Profile 变化，未重复 server/full；
  provider attempts/API Key reads/真实数据/Profile registration/push/tag/PR 均为 0。
- 状态回填后的 resolution `fast` `.sdlc/evidence/20260829-070726-fast/` metadata 为 `passed`，3/3 steps 全绿。
