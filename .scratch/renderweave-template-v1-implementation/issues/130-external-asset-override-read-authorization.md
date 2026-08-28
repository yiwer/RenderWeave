# T130 — 强制 root PUBLIC AssetRef override 的 caller `asset.read`

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T125, T129 (resolved)

## 目标

补齐冻结 Ticket 13 §3 的请求级授权语义：根 Template 的 PUBLIC Custom override 中，每个最终胜出的
`imageRef`/`fontRef` atom（含 list 元素）只有在调用者拥有同 ownerScope 的 `asset.read` 时才能形成
AdmittedAssetValue。跨 scope、缺失、不可读统一收口为 `ASSET_NOT_FOUND`；Asset 授权依赖不可用保持
fail-closed。authored baseline/default、PRIVATE/unknown override、duplicate loser 与 child fill 不得被误要求
`asset.read`。

## Interface / seam

- `RenderingAuthority.Authorized` 产生 server-created、请求准入时冻结的 closed external-asset-read 事实；
  request DTO/RenderInput 不携带、不能伪造该事实。
- app adapter 通过 Asset-owned `AssetOwnerScopeAuthority` 获取 `asset.read` scope 决策，并要求返回 scope
  与已授权 root Template scope 精确相同；不读取 Asset 表、不调用逐对象产品 API、不建立跨上下文事务。
- `EvaluationCommand` 只携带闭合授权结果；`AssetAdmission` 在 authored atoms 全部预准入后、external
  PUBLIC winners 预准入前消费该结果。DENIED 统一为 `ASSET_NOT_FOUND`，UNAVAILABLE fail-closed；两者均不
  探测 external asset existence/kind。
- 授权只在请求 admission 检查一次；结果释放时仍只 recheck root Template 权限，后续 `asset.read` 漂移不
  取消当前请求。既有 `AssetResolutionPort` 保持 caller-agnostic，实际消费仍按 occurrence 独立 resolve。

## TDD 与边界

- 先在 `RenderingApplication.render` seam 证明 server-created 授权事实完整传入 Evaluation；再在
  `Evaluator.evaluate` seam 证明 denied/unavailable external override 在 stage 5 停止且 capability state
  work 为零，granted IMAGE/FONT/list 正常预准入。
- 覆盖 authored-only 在 DENIED 下仍可使用、unknown/PRIVATE/duplicate loser 不触发 caller 权限、scope
  mismatch 收口为 DENIED、授权事实进入 authorization-context digest。
- 不新增 route/OpenAPI/migration/Profile，不调用 provider，不读取 API Key，不发送真实数据，不
  push/tag/PR。capability demand/position/digest 容量、初始化重试与 Ticket 19 records 继续另票。

## 验证

focused Rendering/app RED→GREEN；`render`、`asset`、`fast`、`web`、顺序 `server` 与 app-wiring `full`。
最高 `automated_verified`；A3/J1/READY 不推进。

## Resolution

- 新增 server-created `ExternalAssetReadAuthorization` closed value，并从 `RenderingAuthority.Authorized` 经
  `RenderingApplication` 传播至 `EvaluationCommand`；request DTO/RenderInput 无法携带或伪造该事实，且
  authorization-context digest 显式绑定 GRANTED/DENIED/UNAVAILABLE。
- app authority 只通过 Asset-owned `AssetOwnerScopeAuthority.authorizeCatalog` 获取 `asset.read` 决策；只有
  exact root ownerScope 的 `CatalogGranted` 才映射 GRANTED，forbidden/scope mismatch 映射 DENIED，其余映射
  UNAVAILABLE。授权仅在初始 admission 检查一次，release 仍只重查 root Template grant。
- `AssetAdmission` 保持 authored atoms 先行；没有 external winner 时完全忽略 external 授权。DENIED 在不探测
  external Asset 的前提下收口 `ASSET_NOT_FOUND`，UNAVAILABLE 收口 stage 5 fail-closed，GRANTED 才执行既有
  exact scope/existence/ACTIVE/kind 预准入。authored/default、unknown/PRIVATE、duplicate loser 与 child fill 不受影响。
- 公共 seam focused 40/40、app authority/config focused 14/14、Rendering 148/148、Template 81/81、Asset 92/92
  全绿。A1 gates：`render` `20260828-212630-render`、`asset` `20260828-212719-asset`、`fast`
  `20260828-212741-fast`、`web` `20260828-212812-web`、`server` `20260828-212857-server`，最终 `full`
  `20260828-220847-full` 为 17/17 steps passed。
- 首次 full `20260828-214438-full` 仅在既有 Vite chunk recovery Playwright 用例发生一次 5 秒启动竞态；同一
  文件立即复跑 3/3，通过后未改代码原样重跑 full，最终 recovery 用例及 Playwright 23 passed + 1 controlled
  skip、draft browser journey、inference replay E2E 均通过。失败 metadata 保留为事实。
- A2 仅来自 full 中未变 Template/Asset/Renderer/R0/R1/P0 轴的独立重放；本票授权行为没有 ticket-specific
  issued replay。A3 未外部强制，J0 pending、J1 未批准；provider attempts/API Key reads/reservations/cost、真实
  数据、migration/OpenAPI/Profile/push/tag/PR 均为 0。
- 状态回填后的 resolution `fast` `20260828-223446-fast` 为 3/3 steps passed。
- capability demand/position/digest 容量、初始化重试预算与 Ticket 19 正式 records 继续另票，不在本票冒充完成。
