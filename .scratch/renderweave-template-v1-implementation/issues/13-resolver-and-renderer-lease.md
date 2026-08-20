# 实现 AssetResolver 与 Renderer-only fetch lease 纵切

Type: task
Status: resolved
Lifecycle: automated_verified
Resolved by: Codex（single-writer）
Blocked by: 05, 07, 08, 11, 21

## Question

如何按 ADR-0043 与冻结规格物化 `AssetResolver`：closed 输入 `renderRequestId/ownerScope/resourceId/assetId/
expectedKind/rendererAudience/renderDeadline`，在单次线性化事务内检查 ownerScope/存在/ACTIVE/immutable kind、
读取 current contentVersion 与不可变 descriptor，提交 `(renderRequestId, resourceId)` 短期幂等记录（同 key 同
fingerprint 重放、异输入冲突、提交后绝不重选 current）并签发 Renderer-only fetch lease；fetch URL 由
Asset-owned consumer `AssetFetchEndpoint` Port 物化（app Adapter 校验 claims、签发短时签名 URL、流式供给并
验证 sha256/length），ResolvedAsset 请求内携带，Rendering 原样投影 RenderResource，URL 不入 digest/日志/
持久化，Engine 只对 app origin 拉取？本票与 T07（Evaluator/RenderDocument seam）和 T08（Rust Renderer
protocol）同批接线，被 T07/T08/T11 阻塞；不实现 Editor preview 接口或任何公开下载 route。

## Answer

按已批准的 ADR-0043/0044/0045 与冻结票据实现一个 Asset-owned 深模块，公开测试 seam 为
`AssetResolver`；Rendering 只通过其自有 `AssetResolutionPort` 的生产 Adapter 消费。边界与语义如下：

1. `AssetResolver.precheck` 只读取 owner scope、存在性、lifecycle 与 immutable kind；
   `resolve` 的 closed input 只含 `renderRequestId/ownerScope/resourceId/assetId/expectedKind/
   rendererAudience/renderDeadline`。Asset metadata、OccurrencePath、Design、actor、RootDocument 均不得穿越。
2. `resolve` 在一个 PostgreSQL 事务内锁定 `(renderRequestId, resourceId)`：先查已提交记录；同完整
   fingerprint 重放原 exact content selection 与同 lease identity，异输入返回 conflict；仅在没有记录时检查
   scope/existence/ACTIVE/kind并读取 current content。提交后永不重选 current，unknown-commit retry 也先查记录。
3. 短期 recovery record 保存 opaque key/fingerprint/expiry 与 AES-GCM 加密的 exact selection；AAD 绑定
   request key 与 fingerprint。lease 到期覆盖绝对 render deadline 后精确 5 秒，record 保留至原 deadline 后
   5 分钟；不续租。replace/delete 不撤销已签发 lease。
4. `AssetFetchEndpoint` 是 Asset-owned outbound Port；app Adapter 用 HMAC 签发 canonical HTTPS app-origin
   bearer URL。签名绑定 lease handle、fingerprint、audience 与 expiry。URL 不进入 digest、日志、审计或任何
   request 之外的持久化结构；禁止 redirect/proxy/cookie/range/compression 与公开 download/preview route。
5. 内部 fetch route 先验证 token、expiry 与 recovery record，再取 exact blob；在写出任何字节前验证
   sha256 与 length，随后以固定块流式输出 `200`、identity encoding、精确 Content-Length 与 no-store。
   token 无效/过期/不存在为安全 `404`，后端不可用为 `503`，完整性失败为 `500`。
6. Rendering Adapter 精确映射 resolver outcome；稳定错误至少包括 NOT_FOUND、DELETED、KIND_MISMATCH、
   UNAVAILABLE、TIMEOUT，异 fingerprint 映射既有 render-request conflict。Evaluator 每次请求从 UTC Clock
   计算固定 60 秒绝对 deadline，不能把部署属性 `60000` 误当 Unix epoch。
7. profile 固定为 `renderweave-asset-acceptance/1.0`；fetch attempt 预算与 Rust protocol 留给后续 Engine
   票，本票只物化 Java app endpoint/bridge，不实现 Rust daemon、Editor preview、公共 API/OpenAPI SDK。

## Execution card

- allowed files：`renderweave-asset/**`、`renderweave-rendering/**`、`app/**`、Flyway migration、受影响测试/
  fixtures、T13 票据与计划/NOTES/证据索引。
- forbidden：Template/Workspace/数据适配/图片渲染；公开 asset 下载 API；付费或外部模型；真实数据；
  API key；push/tag/PR。
- production/test seam：`AssetResolver` public contract；production 由 PostgreSQL selection record + S3 blob +
  signed internal endpoint 组成，contract test 使用受控 in-memory port，不 mock 模块内部调用。
- TDD order：public contract RED → canonical resolver GREEN → PostgreSQL replay/conflict/invariant RED/GREEN →
  signed fetch RED/GREEN → Rendering bridge/deadline RED/GREEN → affected gates。
- assurance：先局部模块测试，再 asset/server/fast，最后按影响扩至 full；本地提交只在自动证据绿色后创建，
  生命周期最高报告 `automated_verified`，不伪报人工 accepted。
- completion：exact selection 在 replace/delete 后仍可安全重放与 fetch；同 key 异输入冲突；所有稳定 outcome
  与 deadline 精确；不存在 public route/secret 日志；迁移、集成、架构和公开 surface 测试绿色。

## Resolution

- Asset-owned `AssetResolver` 与 `AssetFetchEndpoint` 已物化；同 key selection 在 PostgreSQL 单事务内先读
  recovery record、再选择 current，完整请求 fingerprint 冲突关闭，exact selection 以 AES-GCM + AAD
  加密保存，lease handle 与 HMAC bearer URL 均为请求级 opaque identity。
- app 内部 fetch endpoint 只接受未过期签名 token，拒绝 cookie/range/compression；从 S3/MinIO 读取 exact
  blob 后先验证 length/sha256，再以固定 1 MiB 块返回 identity body。replace/delete 不撤销已签发 lease，
  token/记录/完整性/后端故障分别收口为安全 404/500/503。
- Rendering production bridge 穷尽映射 NOT_FOUND/DELETED/KIND_MISMATCH/CONFLICT/TIMEOUT/UNAVAILABLE；
  Evaluator 在请求开始时用 UTC Clock 冻结 60 秒绝对 deadline。嵌套 child 的 Asset failure 不再被折叠成
  generic materialization error。
- V024、public-surface/architecture/contract 测试、PostgreSQL+MinIO 幂等/并发/旧版本 fetch/密文/完整性纵切、
  Evaluator→Rendering bridge→Resolver→PostgreSQL/S3 端到端纵切均已通过。证据索引见
  `plans/logs/TV1-T13.md`；生命周期最高为 `automated_verified`，无人工 J1/A3、无 READY 声明。
