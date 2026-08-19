# 冻结 Asset admission 与 resolution 首个增量

Type: grilling
Status: resolved
Claimed by: Codex `/root`
Blocked by: 01, 02

## Question

Asset、immutable contentVersion、scope-local content addressing、IMAGE/FONT admission、logical current、soft delete/restore 与 `AssetRef → ResolvedAsset → RenderResource` 应如何收成最小 deep interface，使普通产品读取、Evaluator occurrence resolution 和 Renderer-only lease 各自只获得所需能力，并且不复用 inference-only `BlobStore` 语义、不泄露路径/token/hash、不引入外部 URL 或占位 persistence？

## Answer

T05 经两轮 HITL 对答（Q1–Q10 全部确认）把 Asset 实施 seam 收口为 ADR-0043，并登记 T10/T11/T12a/T12b/T13
五张后续票。
Asset 领域语义本身不重开：IMAGE/FONT、assetId/assetRevision/contentVersion、ACTIVE↔DELETED、scope 内
SHA-256 Blob、AssetRef 封闭选择器、ResolvedAsset/RenderResource、Renderer-only lease、幂等键、删除确认
token、容量水位与 fog 均以冻结规格（旧 map tickets 05/13 + CONTEXT.md glossary）为准。

决策要点：

1. renderweave-asset 设三个 provider-owned Interface：`AssetApplication`（作者/产品命令与查询，app
   HTTP/assembly 调用）、`AssetResolver`（仅 Rendering：closed 输入 `renderRequestId/ownerScope/resourceId/
   assetId/expectedKind/rendererAudience/renderDeadline` → ResolvedAsset+lease）、`AssetAcceptanceAuthority`
   （静态 IMAGE/FONT admission → TechnicalDescriptor/sha256/length）；TemplateModule 式 internal assembly
   factory 注入 app Adapter。方法只随同票真实行为出现。
2. 首个真实增量是 `AssetAcceptanceAuthority` admission kernel（T10）：无 DB/网络/UI/route/OpenAPI/S3；
   Java primary + 独立 Python（Pillow/fontTools）exact-vector replay，Python 覆盖不了的位级断言标 A1；
   `renderweave-asset-acceptance/1.0` 语义 T10 即完整，available 登记以 T11 真实 create 纵切为界。
3. fetch lease 的 URL 由 Asset-owned consumer `AssetFetchEndpoint` Port 物化（app Adapter 校验 claims、
   签发短时签名 URL、流式供给并验证 sha256/length）；ResolvedAsset 请求内携带，Rendering 原样投影进
   RenderResource；URL 不入 digest/日志/持久化；Engine 只对 app origin 拉取，绝不直发 S3 presigned URL。
4. Blob 存储走 S3 协议：`AssetBlobPersistence` SPI（store/exists/load+容量探针）后是同一 S3 Adapter ——
   生产 OSS、本地 compose 与 E2E MinIO 容器、测试 Testcontainers MinIO；单 bucket + `{scope}/blobs/
   {sha256-hex}`，store 客户端算/验 sha256、同键不覆盖，load 流式验 hash/length；凭据只经环境变量；
   S3 客户端用 AWS SDK v2 `s3`（endpoint override + path-style 可配），随 T11 引入。不复用 inference
   `BlobStore`（自由 locator/可 delete/无 scope 分区）语义。
5. 部署级容量水位 = app 侧 PostgreSQL 事务字节计数器，与 contentVersion 追加同事务单调累加（Asset v1
   无物理删除），阈值部署配置 fail-closed；达到硬水位只拒绝需新 Blob 的 create/replace。
6. Host authority = Asset-owned `AssetOwnerScopeAuthority` sibling SPI（不共享 Template 的），closed 三操作
   `authorizeCreate`/`authorizeExisting(storedOwnerScope, READ|UPDATE|DELETE|RESTORE)`/`recheck`，只映射
   `asset.read/create/update/delete/restore`；生产 fail-closed，dev/test 配置固定 single-owner Adapter。
7. 切片顺序：T10 kernel → T11 create/upload 幂等 + detail/catalog/metadata/版本列表/精确下载/内部预览
   （V019、OpenAPI/Web SDK、Host facet、S3/MinIO、容量计数器）→ T12a content replace/旧内容恢复（审计与
   STALE 事实记录）→ T12b delete/restore + `AssetReferencePort`/5 分钟确认 token 编排（blocked by Template
   依赖投影票）→ T13 Resolver/lease（blocked by T07/T08/T11）；Asset UI 随 Editor（T09）同批。
8. closed outcome 与稳定 code 以冻结规格为准；Asset module 不返回 Template/Rendering problem，HTTP
   status/RFC envelope/redaction 只属 app Adapter，路径/token/hash/URL 不出 closed 交接值。

本票只冻结合同，未创建 Java Interface、migration、route、gate 组成或任何产品代码；`template`+`fast`
composite 复验通过（docs-only）。Ticket 19 open，Asset/Editor/Renderer 未 READY。T10 成为唯一 unblocked
frontier。
