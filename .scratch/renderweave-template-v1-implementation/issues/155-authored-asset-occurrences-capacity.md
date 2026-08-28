# T155 — authored Asset occurrences 预算

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T130, T154 (resolved)

## 目标

物化 Ticket 19 / `RW-T19-S8-001` 与 cap-025：`assetsAndFetch.authoredAssetOccurrences` 使用
MAX_INCLUSIVE `4096`，observed `4095/4096/4097`，contract stage
`ASSET_ADMISSION_AND_RESOLUTION`、public stage `ASSET_ADMISSION`、code `ASSET_BUDGET_EXCEEDED`、zero
boundary `ZERO_DOCUMENT_OUTPUT`。现有 `AssetAdmission` 已执行 authored atom 逐 occurrence 预准入，但仍由私有
常量与手写计数判断；本票将真实 precheck seam 接入唯一 production capacity guard。

## seam 与计数语义

- closure 每个 unique TemplateSnapshot 的全部 authored AssetRef atom 各计 `1`：Node static baseline、Custom
  default、Definition literal、Mapping case/default 与 `list<imageRef/fontRef>` item 均计；静态不可达、被 Binding
  覆盖或最终不 demand 的 atom 仍计。
- 相同 assetId/kind 在不同 authored 位置逐 occurrence 重计，不按 logical Asset、assetId、contentVersion 或 exact
  bytes 去重；unique logical Assets 属 cap-026 独立轴。
- external PUBLIC Custom override AssetRef 不属于 authored counter，不得消耗本轴；其 caller `asset.read` 与现有
  admission 顺序保持不变。
- 按 closure snapshot order 与现有 canonical atom extraction order，在调用下一次 Asset-owned precheck 前原子
  reserve。第 4097 个 first-fail，不执行该 atom 或任何后续 precheck，不建立 CapabilityState/RenderDocument。
- cap-024 `diagnostics.sidecarBytes` 因最终 opaque occurrence/resource locator 的 canonical shape 尚未物化而保持
  deferred；本票不以现有二字段临时 sidecar 发明格式。

## TDD、验证与边界

- guard test 先引用缺失的 `ASSETS_AND_FETCH_AUTHORED_ASSET_OCCURRENCES` 捕获 compile RED，再加入唯一 catalog
  项重放 `4095/4096/4097`。
- AssetAdmission product test 使用两个指向同一 assetId 的 authored atoms：prefix `4094` 后两项 exact-at 全部
  precheck；prefix `4095` 后第一项到达 4096，第二项在 precheck 前 exact reject。另以 prefix `4096` + 仅 external
  override 证明本轴零收费。
- focused guard/AssetAdmission/Evaluator/architecture、完整受影响 reactor、`render` 与 `fast`；无 app wiring/API/
  Web/migration/Profile 变化，不重复 server/full。
- 本票不实现 cap-024、unique logical Assets、actual resolve/resource/bytes/pixels/cache/fetch 预算或正式 Ticket 19
  records/product executor。provider/API Key/真实数据/费用/Profile registration/push/tag/PR 均不推进；最高
  `automated_verified`、J0。

## Resolution

- guard/AssetAdmission tests 先精确产生 9 个 compile RED：6 个缺失
  `ASSETS_AND_FETCH_AUTHORED_ASSET_OCCURRENCES`，3 个缺失 request-tracker overload；唯一 catalog 加入
  `assetsAndFetch.authoredAssetOccurrences=4096` 并接入真实 admission seam 后 21/21 GREEN。
- `AssetAdmission` 不再维护私有 4096 常量或后验 `authoredAtomCount`；默认调用创建 request tracker，全部 authored
  atom 均在下一次 Asset-owned precheck 前 reserve，guard 的 frozen stage/code/full limitId 原样传播。
- 两个指向同一 assetId 的 authored image defaults 在 prefix 4094 后均 precheck；prefix 4095 后第一项到达 4096，
  第二项 exact reject 且 port 仅收到一次调用。prefix 4096 + 仅 external PUBLIC override 仍成功，证明该来源零收费。
- focused guard 17 + AssetAdmission 4 + Evaluator 68 + architecture 6 = 95/95；受影响 reactor Schema 20、
  Validation 13、Template 84、Asset 92、Rendering 232 全绿，零 failure/error；`git diff --check` 通过。
- A1 `render` `.sdlc/evidence/20260829-064628-render/`（2/2）与 `fast`
  `.sdlc/evidence/20260829-064814-fast/`（3/3）metadata 均 `passed`。
- cap-025 replay fixture 只调用 guard、未执行 AssetAdmission/Evaluator，故无 T155-specific A2/A3；J0 pending、
  J1 未批准。cap-024 继续 deferred；无 app wiring/API/Web/migration/Profile 变化，未重复 server/full；provider
  attempts/API Key reads/真实数据/Profile registration/push/tag/PR 均为 0。
- 状态回填后的 resolution `fast` `.sdlc/evidence/20260829-064949-fast/` metadata 为 `passed`，3/3 steps 全绿。
