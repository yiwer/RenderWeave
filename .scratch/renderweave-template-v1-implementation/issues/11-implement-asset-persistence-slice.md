# 实现 Asset create/current/catalog PostgreSQL+S3 纵切

Type: task
Status: in_progress
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

## Handoff notes（2026-08-19，交给下一 session 继续）

已完成并验证（本 worktree，本票未 resolve）：
- 领域合同层：`AssetApplication`（create/getCurrent/updateMetadata/catalog/listContentVersions/downloadExact
  closed outcomes）+ `AssetOwnerScopeAuthority`/`AssetPersistence`/`AssetBlobPersistence` SPI +
  `CanonicalAssetApplication`（幂等指纹/replay/conflict、容量水位、authority 折叠、metadata NFC 规范化、
  download sha256 复验）+ `AssetModule` assembly factory；module 68+ tests 绿。
- app 适配器：V019（asset_aggregate/asset_content_revision/asset_idempotency/asset_capacity，tags/descriptor
  JSONB）、`PostgresAssetPersistence`（JdbcClient+事务、keyset cursor catalog）、`S3AssetBlobPersistence`
  （AWS SDK v2 2.31.24，scope/sha256 key）、fail-closed 与 configured single-owner authority、
  `AssetApplicationConfiguration`（无 S3 endpoint 时 fail-closed 不装配 AssetApplication）；
  `AssetSliceIntegrationTest`（Testcontainers PostgreSQL+MinIO 端到端）与 `EnvironmentCanaryTest`（19
  migrations）绿；fast gate 绿。
- 环境修复：Docker Desktop 手工代理 `127.0.0.1:10808` 指向未运行的本地代理，导致 MinIO 镜像拉取失败；已清空
  `%APPDATA%\Docker\settings-store.json` 的 OverrideProxyHTTP/HTTPS 并重启 Docker Desktop，minio/minio
  `RELEASE.2024-12-18T13-15-44Z` 已本地缓存。

剩余（下一 session 继续）：
- `AssetController` HTTP 面（multipart create / GET current / GET catalog / PUT metadata / GET versions /
  GET download / 内部 preview）+ problem handler；OpenAPI 0.11.0 与 Web SDK 再生成（contractVersion
  0.10.0→0.11.0：SystemStatusController/ResourceFrame/EnvironmentCanaryTest）；compose.yaml 增 minio；
- 完整 server-verify 与 `full` gate；本票 resolve+提交；push 待用户授权（当前分支 ahead 3）。
