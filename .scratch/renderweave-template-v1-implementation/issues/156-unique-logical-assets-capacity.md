# T156 — unique logical Assets 预算

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T130, T155 (resolved)

## 目标

物化 Ticket 19 / `RW-T19-S8-002` 与 cap-026：`assetsAndFetch.uniqueLogicalAssets` 使用
MAX_INCLUSIVE `512`，observed `511/512/513`，contract stage `ASSET_ADMISSION_AND_RESOLUTION`、public
stage `ASSET_ADMISSION`、code `ASSET_BUDGET_EXCEEDED`、zero boundary `ZERO_DOCUMENT_OUTPUT`。在 T155 已建立的
stage-5 request tracker 上加入独立唯一逻辑 Asset 轴，并在下一次 Asset-owned precheck 前 fail closed。

## seam 与计数语义

- request-local logical Asset identity 为同一 `ownerScope + assetId`；Asset kind 是 Asset 的 immutable 属性和每次
  precheck 的 exact expectation，不把同一 assetId 因重复 occurrence、不同来源或错误 expectedKind 变成多个逻辑 Asset。
- authored closure atoms 与通过 caller `asset.read` 门控后的 external PUBLIC Custom override atoms 共享同一集合；
  新 assetId 首次出现计 `1`，后续重复不再收费，但每个 occurrence 的 kind/ACTIVE/existence precheck 仍逐次执行。
- authored 仍按 closure/canonical atom 顺序先行，external 仍按 canonical definitionId 顺序后行；DENIED/UNAVAILABLE
  不探测 external identity。第 513 个新 assetId 在其 precheck 前 exact reject，停止全部后续 Asset/Capability/Document 工作。
- 本轴与 T155 authored occurrences、actual resolve occurrences、RenderResource entries、unique exact contents 及
  bytes/pixels/cache/fetch 预算独立；同一内容 hash、contentVersion 或 cache hit 不改变 logical Asset identity。

## TDD、验证与边界

- guard test 先引用缺失的 `ASSETS_AND_FETCH_UNIQUE_LOGICAL_ASSETS` 捕获 compile RED，再将唯一 catalog 项重放
  `511/512/513`。
- AssetAdmission product test 以 prefix `511` + 重复 authored assetId 证明 exact-at 且每个 occurrence 仍 precheck；
  再加入一个新 assetId，证明第 513 个在 port 前 exact reject。另覆盖 authored/external duplicate 共享集合以及 external
  新 assetId 的同一失败边界。
- focused guard/AssetAdmission/Evaluator/architecture、完整受影响 reactor、`render` 与 `fast`；无 app wiring/API/
  Web/migration/Profile 变化，不重复 server/full。
- 本票不实现 cap-024、actual resolve/resource/content/bytes/pixels/cache/fetch 预算或正式 Ticket 19 records/product
  executor。provider/API Key/真实数据/费用/Profile registration/push/tag/PR 均不推进；最高
  `automated_verified`、J0。

## Resolution

- guard/AssetAdmission tests 先精确产生 7 个缺失 `ASSETS_AND_FETCH_UNIQUE_LOGICAL_ASSETS` 的 compile RED；唯一
  catalog 加入 `assetsAndFetch.uniqueLogicalAssets=512` 后 guard 18/18 GREEN，AssetAdmission 精确保留 2 个
  behavioral RED：第 513 个 authored 与 external 新 Asset 均仍错误成功。
- `AssetAdmission` 现以 request-local assetId set 覆盖完整 authored→authorized external 顺序；新 id 在 port 前经
  同一 request tracker reserve，成功后才进入集合，重复 occurrence 不收费但仍逐次执行 immutable-kind precheck。
- prefix 511 + 两个同 id authored defaults exact-at 成功且 port 收到两次调用；再追加新 id 时只前两个到达 port，
  第三个以 frozen stage/code/full limitId exact reject。authored + 同 id external exact-at 成功，external 新 id 同样
  在 port 前 reject，证明跨来源共享集合。
- focused guard 18 + AssetAdmission 6 + Evaluator 68 + architecture 6 = 98/98；受影响 reactor Schema 20、
  Validation 13、Template 84、Asset 92、Rendering 235 全绿，零 failure/error；`git diff --check` 通过。
- A1 `render` `.sdlc/evidence/20260829-065635-render/`（2/2）与 `fast`
  `.sdlc/evidence/20260829-065722-fast/`（3/3）metadata 均 `passed`。
- cap-026 fixture 只调用 guard，明确不执行 closure/Asset resolution/Evaluator/Sealer/Renderer，故无 T156-specific
  A2/A3；J0 pending、J1 未批准。cap-024 继续 deferred；无 app wiring/API/Web/migration/Profile 变化，未重复
  server/full；provider attempts/API Key reads/真实数据/Profile registration/push/tag/PR 均为 0。
- 状态回填后的 resolution `fast` `.sdlc/evidence/20260829-065828-fast/` metadata 为 `passed`，3/3 steps 全绿。
