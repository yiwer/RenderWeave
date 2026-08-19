# 实现 Asset create/current/catalog PostgreSQL+S3 纵切

Type: task
Status: resolved
Claimed by: Codex `/root`
Blocked by: 05, 10, 10b

## Question

如何沿 T06 的 Template seam 模式物化首个 Asset 持久化纵切：幂等 create/upload（24h idempotency key、多选 =
多个独立 create 允许部分成功）、getCurrent/detail、catalog 稳定游标查询（默认 ACTIVE、可显式 DELETED，
kind/tagsAll/tagsAny/displayName/sourceFileName 过滤）、metadata update（expectedAssetRevision）、内容版本
列表与精确版本下载、内部预览（只读、需 asset.read）；ownerScope 只来自 Host capability（
`asset.read/create/update/delete/restore`）；Blob 经同一 S3 Adapter（生产 OSS、compose/E2E 与 Testcontainers
MinIO）按 scope 内 SHA-256 内容寻址存储；部署级容量水位由 app 侧 PostgreSQL 事务字节计数器 fail-closed？
本票以当时真实的下一 Flyway 版本（V019，forward-only）、OpenAPI/Web SDK、Testcontainers PostgreSQL+MinIO
与 server/full gate 证明端到端行为；不实现 replace/delete/restore、AssetResolver/lease、Asset UI 或
placeholder，不登记 acceptance Profile available。

## Answer

T11 沿 ADR-0043 冻结 seam 物化首个 Asset create/current/catalog PostgreSQL+S3 纵切；replace/delete/restore、
Resolver/lease 与 Asset UI 保持 open（T12a/T12b/T13 按 Blocked by 解锁），本票未创建 placeholder。

`renderweave-asset` 新增 authoring public Interface `AssetApplication`，只暴露 `create/getCurrent/
updateMetadata/catalog/listContentVersions/downloadExact` 六个 closed 方法：Create 返回
`CreatedReadable|CreatedOpaque|CreateContentRejected|CreateForbidden|CreateIdempotencyConflict|
CreateStorageCapacityExceeded|CreateAuthorityUnavailable|CreatePersistenceUnavailable`，其余方法各自以
`NotFound|Deleted|Forbidden|AuthorityUnavailable|PersistenceUnavailable` fail closed，metadata update 另含
携带 `currentAssetRevision` 的 `UpdateRevisionConflict`，download 另有 `DownloadVersionNotFound` 与
`DownloadBlobUnavailable`。`CreateCommand` 对 rawContent 防御性拷贝并约束 idempotencyKey（1..128）、
displayName（NFC 后 1..200）、tags（≤20 个、NFC 后 1..64）、sourceFileName（≤255）；`CatalogCommand`
closed 过滤（kind/tagsAll/tagsAny/displayNameContains/sourceFileNameContains/includeDeleted/cursor/limit
1..100）。internal `CanonicalAssetApplication` 经 `AssetAcceptanceAuthority` 做完整 admission（41 vectors
的 kernel 语义），ownerScope/capability 只取 Host authority，idempotency 以 scope+key+完整请求指纹判定
replay/conflict，容量水位在新增 Blob 时 fail-closed，download 复验 sha256/length。

`AssetModule` 是 app 可 import 的唯一 Asset `.internal` assembly seam（与 `TemplateModule` 同为 ADR-0041
窄例外，`TemplateV1ArchitectureTest.APP_ASSEMBLY_EXCEPTIONS` 已含两者）。`renderweave-app` 以
`PostgresAssetPersistence`（JdbcClient 事务、keyset cursor）、`S3AssetBlobPersistence`（AWS SDK v2、
`{scope}/blobs/{sha256}`、store 幂等不覆盖）、`ConfiguredSingleOwnerAssetScopeAuthority`（dev/test）与
`FailClosedAssetOwnerScopeAuthority`（生产默认）实现 consumer seam；请求不得自报 ownerScope/capability。
无 S3 endpoint 时 `AssetApplication` 与 `AssetController` 都不装配（fail-closed，路由不存在），条件用
`@ConditionalOnExpression("'${renderweave.asset.s3.endpoint:}' != ''")` 而非 `@ConditionalOnBean`——Boot
4.1 对同批配置类 `@Bean` 的条件求值时机不可靠（实测 canary 上下文会把 `assetApplication` 误装配成
UnsatisfiedDependency，S3 上下文又会把 controller 误跳过）；属性条件与 `S3Client` bean 同一开关、
确定性成立。

V019（forward-only）建 `asset_aggregate`（asset_id UUID v4、owner_scope、kind、lifecycle ACTIVE/DELETED、
display_name、tags/descriptor JSONB、asset_revision）、`asset_content_revision`（content_version、
sha256/media_type/byte_length/source_file_name）、`asset_idempotency`（scope+key 24h TTL 指纹）与
`asset_capacity`（事务字节计数器，只增不减）；任何 revision 无 UPDATE/DELETE 路径。HTTP 面为
`/api/v1/assets`：multipart POST（`Idempotency-Key` header + kind/displayName/tags/sourceFileName 表单字段
+ content 文件 part）→ 201/Location + READABLE|OPAQUE；GET（catalog，`updatedAt DESC, assetId ASC` 稳定
游标 + 全部 closed 过滤）→ 200；GET `/{assetId}` → current detail；PUT `/{assetId}/metadata?expectedAsset
Revision` → 200/409；GET `/{assetId}/versions`；GET `/{assetId}/download?contentVersion`（精确版本字节）；
GET `/{assetId}/preview`（内部只读当前内容，需 asset.read）。错误统一 problem+json：400
`ASSET_REQUEST_INVALID`、403 `ASSET_FORBIDDEN`、404 `ASSET_NOT_FOUND`/`ASSET_CONTENT_VERSION_NOT_FOUND`、
409 `ASSET_IDEMPOTENCY_CONFLICT`/`ASSET_REVISION_CONFLICT`（含 currentAssetRevision）、410
`ASSET_DELETED`、413 `ASSET_PAYLOAD_TOO_LARGE`（transport 超限）与 admission 413/422
（`ASSET_CONTENT_LIMIT_EXCEEDED` 带 stage/pointer/limit 或 `ASSET_CONTENT_INVALID/UNSUPPORTED`）、507
`ASSET_STORAGE_CAPACITY_EXCEEDED`、503 `ASSET_AUTHORITY/PERSISTENCE/BLOB_UNAVAILABLE`。

验证中修复的既有隐藏缺陷（均未在任何通过过的 gate 中覆盖到）：(1) `tagsAny` 路径从未被任何测试触达，
`jsonb_exists_any(tags, :tagsAny::jsonb)` 第二参应为 `text[]` 而非 jsonb，改为 `String[]` JDBC 绑定
（pgjdbc 原生映射 text[]）；(2) Spring 7 的 `RequestMappingHandlerMapping.isHandler` 只认 `@Controller`
（`@RequestMapping` 不再足够），controller 保留 `@RestController`；(3) Spring 7 MockMvc 对
`@RequestPart List<String>` 文本 part 报 415（`Content-Type 'text/plain' is not supported`），表单字段
改按真实 Tomcat multipart 语义用 `@RequestParam` 绑定（openapi-ts 生成的客户端同样以表单字段发送）。

OpenAPI 升 0.11.0（info.version + `SystemStatus.contractVersion` const），新增 Assets tag、6 条路径、10 个
参数（AssetId/AssetKind/AssetTagsAll/AssetTagsAny/AssetDisplayNameContains/AssetSourceFileNameContains/
AssetIncludeDeleted/AssetCursor/AssetLimit/AssetExpectedAssetRevision/AssetContentVersion）、12 个 schema
（AssetReadableResponse/AssetOpaqueCommitResponse/AssetCommitResponse/AssetTechnicalDescriptor/
AssetImageDescriptor/AssetFontDescriptor/AssetCatalogEntry/AssetCatalogResponse/
AssetContentVersionEntry/AssetVersionsResponse/UpdateAssetMetadataRequest）与 5 个 response
（AssetForbidden/NotFound/PayloadTooLarge/CapacityExceeded/ServiceUnavailable）；Web SDK 已重新生成，
`SystemStatusController`、`EnvironmentCanaryTest` 与 `ResourceFrame.tsx` 的 contractVersion 同步 0.11.0。
`compose.yaml` 增 `minio` 服务（`minio/minio:RELEASE.2024-12-18T13-15-44Z`，镜像内自带 curl/mc，
healthcheck 用 `/minio/health/live`）与 `minio-init` 一次性建桶（同一镜像的 mc，无需新拉镜像），api 注入
`RENDERWEAVE_ASSET_S3_*` 并依赖 minio healthy；`spring.servlet.multipart` 上限提到 65MB/66MB（略高于
admission 的 64MiB 图片上限），`InferenceController.createLive` 相应在 app 层按权威预算
（10 MiB item / 32 MiB batch）拒绝超限上传（仍为 413 `INFERENCE_PAYLOAD_TOO_LARGE`），既有 inference
上传语义不因 transport 放宽而回退。

验证：Asset module contract/public-surface/architecture 测试、`AssetSliceIntegrationTest`
（Testcontainers PostgreSQL+MinIO 端到端：create/current/catalog/update/versions/download + 幂等
replay/conflict）、`AssetApiTest`（MockMvc HTTP 面：multipart create + catalog 过滤 + metadata 409 +
versions + download + preview + 幂等 replay/conflict + 无效请求零写入）、`EnvironmentCanaryTest`（19
applied migrations）全部绿；`TemplateV1ArchitectureTest` 4/4。完整 `full` 16/16 通过（evidence
`.sdlc/evidence/20260819-160012-full/`），kernel 33/33 与 asset kernel 41/41（Java primary/Python
independent，A2，两者 Profile=`NOT_REGISTERED`）、IMAGE_ONLY P0 providerAttempts=0，draft/inference
browser E2E 旅程与进程清理均通过。

本票只形成自动证据（A1；kernel/registry exact replay 在原边界仍为 A2），无 J1/A3；`renderweave-asset-
acceptance/1.0` 仍未登记 available（本票不登记，T11 只是其登记边界），无 Editor/Asset UI/placeholder。
Ticket 19 open，Asset/Editor/Renderer 未 READY。下一个 unblocked frontier 是 T12a；T12b 以 Template 依赖
投影票为 blocker，T13 随 T07/T08；push 待用户另行授权。
