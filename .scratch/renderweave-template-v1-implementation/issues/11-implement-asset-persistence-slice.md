# 实现 Asset create/current/catalog PostgreSQL+S3 纵切

Type: task
Status: open
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
