# T166 — RenderResource manifest bytes 预算

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T129, T165 (resolved)

## 目标

物化 Ticket 19 / `RW-T19-S8-020` 与 cap-036：`assetsAndFetch.manifestBytes` 使用 MAX_INCLUSIVE
`4194304`，observed `4194303/4194304/4194305`，contract stage
`ASSET_ADMISSION_AND_RESOLUTION`、public stage `ASSET_ADMISSION`、code `ASSET_BUDGET_EXCEEDED`、zero boundary
`ZERO_DOCUMENT_OUTPUT`。Materializer 对最终 closed RenderResource manifest 的 exact canonical UTF-8 bytes 使用唯一
production capacity guard/request tracker，并在对应 entry admission、append 与 external fetch 前失败关闭。

## seam 与计数语义

- 计数对象是最终 `resources` JSON array 本身：空 manifest 的 `[]` 两字节、entry 间逗号、每个 closed
  RenderResource object 的 canonical member ordering/number/string escaping/UTF-8；不计 envelope 的
  `"resources":` member name，也不计 ResolvedAsset 的 assetId/contentVersion/occurrencePath 等业务字段。
- 空数组 framing 在 root materialization/frame 前预留 `2`；每个 successful Resolver fact 在 exact-content 预算后，
  以共享 RenderResource canonical projector 流式计量 `entry bytes + optional comma`，随后才预留/append
  RenderResource entry。
- projector 必须由 Materializer 容量计量与 Sealer 最终 bytes 共用；禁止另写一份 JSON escape/member projection。
  计量只保留小块，不先构造完整 manifest 字符串或 byte array。
- duplicate exact content 仍一对一形成 manifest entry 并逐 entry 收费；第一个使 total 超过 `4 MiB` 的 entry 失败，
  已执行 Resolver 与更早 selection budget 不退款，但该 entry 不进入 manifest/resource-entry counter，零
  RenderDocument、Engine Command、RenderOutput。
- 本轴与 RenderDocument canonical bytes、entry count、URL per-entry/total、cache/fetch bytes 独立。

## TDD、验证与边界

- guard test 先引用缺失的 `ASSETS_AND_FETCH_MANIFEST_BYTES` 捕获 compile RED，再将唯一 catalog 项重放
  `4194303/4194304/4194305`。
- Materializer product test 覆盖空 manifest `2` bytes exact/above，以及两个 canonical FONT entries 的 frozen
  `1013` bytes exact/above；above 必须在第二项 resource append 前失败且两次 Resolver 均已执行。
- 先提取共享 RenderResource canonical projector 并用既有 RenderDocument byte/digest vectors证明 refactor
  byte-identical；focused guard/Materializer/Evaluator/architecture、完整受影响 reactor、`render` 与 `fast`。
- 本票不实现 fetch URL、request cache/fetch bytes、descriptor-consistency hardening 或正式 Ticket 19 records/product
  executor。无 app wiring/API/Web/migration/Profile；provider/API Key/真实数据/费用/Profile registration/
  push/tag/PR 均不推进；最高 `automated_verified`、J0。

## Resolution

- 共享 `RenderResourceCanonicalizer` 现作为 Materializer 字节计量与 Sealer 最终 manifest projection 的唯一闭合字段
  权威；`CanonicalJson.canonicalUtf8Length` 通过 streaming sink 复用完全相同的 key ordering、number 与 UTF-8/string
  escaping，未构造完整 manifest 字符串或 byte array。提取后既有 RenderDocument/digest focused 24/24 先行通过，
  证明行为 refactor 未改变既有 sealed bytes。
- TDD 先精确得到 4 个缺失 `ASSETS_AND_FETCH_MANIFEST_BYTES` 的 compile RED；唯一 catalog 项接入后 guard 28/28
  GREEN，并保留 1 个 Materializer behavioral RED，证明生产路径尚未计入空 `[]`。实现后 Materializer 在 root
  frame/invocation 前预留 2 bytes；每个 resolved resource 在 exact-content 预算后，按 `optional comma + exact canonical
  entry bytes` 预留 manifest bytes，成功后才预留 resource-entry 并 append。
- 空 manifest prefix `4194302` 后成功且预算正好耗尽，prefix `4194303` 在 root frame 前以 frozen problem 失败；两个
  closed FONT entries 的 exact manifest 为 `1013` bytes，exact case 形成两个一对一 resources，above case 的两次
  Resolver 均已执行但第二项未消耗 resource-entry/未 append，并以 ASSET_ADMISSION / ASSET_BUDGET_EXCEEDED /
  `assetsAndFetch.manifestBytes` 零文档失败。
- focused guard 28 + Materializer 32 + Evaluator 68 + architecture 6 + canonical writer 3 + RenderDocument 4 + digests 4 +
  Sealer 13 = 158/158；受影响 reactor Schema 20、Validation 13、Template 84、Asset 92、Rendering 255 全绿，零
  failure/error；`git diff --check` 通过。
- A1 `render` `.sdlc/evidence/20260829-082511-render/`（2/2）与 `fast`
  `.sdlc/evidence/20260829-082600-fast/`（3/3）metadata 均 `passed`；render 内独立 RenderDocument replay 83/83
  证明共享 projector 的既有 bytes 轴未漂移。cap-036 fixture 只调用 production guard，故无 T166 行为路径专属
  A2/A3；J0 pending、J1 未批准。未重复 server/full；无 app wiring/API/Web/migration/Profile 变化，provider attempts/
  API Key reads/真实数据/Profile registration/push/tag/PR 均为 0。
- 状态回填后的 resolution `fast` `.sdlc/evidence/20260829-082750-fast/` metadata 为 `passed`，3/3 steps 全绿。
