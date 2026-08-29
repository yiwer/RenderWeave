# T165 — unique FONT bytes 预算

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T129, T164 (resolved)

## 目标

物化 Ticket 19 / `RW-T19-S8-011` 与 cap-035：`assetsAndFetch.uniqueFontBytes` 使用 MAX_INCLUSIVE
`67108864`，observed `67108863/67108864/67108865`，contract stage
`ASSET_ADMISSION_AND_RESOLUTION`、public stage `ASSET_ADMISSION`、code `ASSET_BUDGET_EXCEEDED`、zero boundary
`ZERO_DOCUMENT_OUTPUT`。每个首次出现的 successful FONT exact-content identity 在 exact-set、RenderResource entry
与 external fetch 前，按声明 `byteLength` 接入唯一 production capacity guard/request tracker。

## seam 与计数语义

- exact identity 为 `(kind, sha256, byteLength, mediaType)`；同 identity 后续 occurrence 不重复收取 unique FONT
  bytes，但继续逐次收取 occurrence raw/FONT bytes 并形成一对一 resource。
- 新 FONT identity 按 `byteLength` 收费一次；IMAGE 对本轴收费 `0`。
- 顺序与 Rust RenderDocument authority 对齐：unique exact count → unique raw bytes → unique IMAGE pixels → unique
  FONT bytes → exact-set insert → RenderResource append。
- 第一个使 unique FONT total 超过 `64 MiB` 的新 identity 失败；其此前已成功的 unique count/raw reservation 保留，
  但 identity 不进入 exact set、resource 不 append，且不产生 RenderDocument、Engine Command 或 RenderOutput。
- 本轴与 occurrence FONT bytes、per-content FONT Acceptance Profile、raw cache/fetch/manifest bytes 独立。

## TDD、验证与边界

- guard test 先引用缺失的 `ASSETS_AND_FETCH_UNIQUE_FONT_BYTES` 捕获 compile RED，再将唯一 catalog 项重放
  `67108863/67108864/67108865`。
- Materializer product test 使用两个 Text Run FONT consumer：prefix `67108863` 下 duplicate exact content 仅首次
  收费并成功；第二项改为新 identity 时于本轴 exact reject，并证明 resource-entry 下游预算未消费。
- focused guard/Materializer/Evaluator/architecture、完整受影响 reactor、`render` 与 `fast`；无 app wiring/API/
  Web/migration/Profile 变化，不重复 server/full。
- 本票不实现 cap-036 manifest bytes、request cache/fetch bytes、跨 duplicate descriptor consistency hardening 或正式
  Ticket 19 records/product executor。provider/API Key/真实数据/费用/Profile registration/push/tag/PR 均不推进；
  最高 `automated_verified`、J0。

## Resolution

- guard/Materializer tests 首先精确产生 5 个缺失 `ASSETS_AND_FETCH_UNIQUE_FONT_BYTES` 的 compile RED；唯一 catalog
  项加入后 guard 27/27 GREEN，Materializer 精确保留 1 个 behavioral RED：第二个使 unique total 超限的新 FONT
  identity 仍错误形成 resource 并完成 materialization。
- `Materializer.reserveExactContent` 现于 new identity 的 unique count/raw 与 IMAGE-pixel reservation 后、exact-set
  insert 前，复用 `reserveFontBytes` 对 FONT 按 `fact.byteLength()` 预留本轴；IMAGE 零收费，duplicate identity 因
  exact-set early return 不重复收费。
- prefix `67108863` + 两个相同 exact-content one-byte Text Run FONT occurrence 成功并形成两项 FONT resource，证明
  duplicate 仅首次收取 unique bytes；第二项改 hash 时第二次 Resolver 已执行，但以 frozen ASSET_ADMISSION /
  ASSET_BUDGET_EXCEEDED / full limitId exact reject。失败时 unique count/raw 已按 authority 包含两项，resource-entry
  tracker 仅含首项，证明 exact-set/resource append 尚未发生。
- focused guard 27 + Materializer 31 + Evaluator 68 + architecture 6 = 132/132；受影响 reactor Schema 20、
  Validation 13、Template 84、Asset 92、Rendering 253 全绿，零 failure/error；`git diff --check` 通过。
- A1 `render` `.sdlc/evidence/20260829-081137-render/`（2/2）与 `fast`
  `.sdlc/evidence/20260829-081226-fast/`（3/3）metadata 均 `passed`。
- cap-035 fixture 只调用 production guard，明确不执行 Resolver/Evaluator/Sealer/Renderer，故无 T165-specific
  A2/A3；J0 pending、J1 未批准。cap-036 继续 deferred；无 app wiring/API/Web/migration/Profile 变化，未重复
  server/full；provider attempts/API Key reads/真实数据/Profile registration/push/tag/PR 均为 0。
- 状态回填后的 resolution `fast` `.sdlc/evidence/20260829-081327-fast/` metadata 为 `passed`，3/3 steps 全绿。
