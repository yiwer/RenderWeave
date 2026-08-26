# 物化 Domain Services 类级事务执行目标与独立 replay

Type: task
Status: in_progress
Claimed by: Codex `/root`
Blocked by: 127（已 resolved）

## Question

T127 已让 4 个 Asset 容量轴的 12 个 frozen scalar fixture 经过唯一生产 guard，并形成 Java/Python replay；但冻结
bootstrap 仍要求 `EXEC::DOMAIN_SERVICES::1.0` 同时具备 `java-domain-authority` 与
`transactional-integration-replayer`，target kind 还必须绑定精确 domain-service 实现和 PostgreSQL 集成目标。
T127 的 target 明确 `databaseRequiredForScalarProbe=false`、`recordIssuanceAllowed=false`，因此不能直接发行
12 个 Case/Oracle。如何补齐真实事务执行闭环，同时不分配 64/32 MiB payload、不改 public API/业务语义、不把
Testcontainers 当作生产数据库认证，也不越级 append formal registry？

## Answer（本票冻结的实施决定）

1. **职责分离但目标合一**：保留 T127 scalar guard executor 作为 `java-domain-authority`；新增独立 app test-scope
   `transactional-integration-replayer`，直接执行产品 `PostgresAssetPersistence` 的 transaction-sized seam。新的
   class target 同时绑定 capacity component target、产品 domain/persistence artifacts、V028/V029 migration、12+12
   assigned candidate subset 与两个 executor manifest；不复制 guard 或建 test-only 产品入口。
2. **真实 PostgreSQL replay**：只使用 Testcontainers PostgreSQL；`postgres:16-alpine` 必须在运行前已存在且 runtime
   image ID 精确等于冻结 digest。replay 覆盖成功 create 的 aggregate/content/audit/idempotency/capacity 原子提交、
   幂等 replay/conflict 零写，以及在最后一步 duplicate key 失败时此前 aggregate/content/audit 的整事务回滚。
   H2/SQLite、mock transaction 或仅 SQL 文本检查均不满足本票。
3. **确定性、有界报告**：事务报告只记录固定 scenario identity、表计数、capacity bytes、结果枚举、PostgreSQL
   版本与 image digest；不记录随机 UUID、时间戳、连接串、凭据、原始 Asset bytes、RootDocument 或业务 payload。
   T127 的 12 个 scalar fixture 继续不分配大内容；PostgreSQL replay 不读取这些 fixture 或伪称执行边界 payload。
4. **独立闭包验证**：Python stdlib verifier 不导入 Java helper；它从 exact Git blobs 重建 target artifact binding、
   12 Case + 12 Oracle assigned subset digest、formal registry 46/46 零 Domain 记录、两个 executor manifest、T127
   Java/Python report 与 PostgreSQL report。全部一致后只报告 `preissuanceReady=true`；execution class 仍非 executable，
   formal append 留给下一独立票。
5. **TDD 与 gates**：missing transactional executor RED → focused PostgreSQL GREEN → exact implementation commit →
   materialize target/manifests → `domain-services` class gate → affected `asset`/`fast` → sequential `server` → Goal
   `full` → resolution `fast`。Maven 串行，精确 staging，最高只报 `automated_verified`。
6. **诚实边界**：不改 API/OpenAPI/migration/Web/Template/Rendering 语义；不发行 Case/Oracle，不注册或认证 Profile，
   不运行单独 native build、provider/API Key、真实数据、生产、J1/A3/READY；主工作区既有 360 项 Image-Only/Schema/
   Inference dirty work和备份 stash保持原样。

## Results

- pending
