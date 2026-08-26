# 物化 Domain Services 类级事务执行目标与独立 replay

Type: task
Status: resolved / automated_verified
Claimed by: —
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

- 实现 revision `b791fccb3c6d3bdfcff31d45ed5ae170fd9d982f` 新增 app test-scope 的真实产品事务 replay，
  直接执行 `PostgresAssetPersistence`：成功 create 原子形成 aggregate/content/audit/idempotency/capacity，幂等
  replay/conflict 零写，末步 duplicate key 返回 `ASSET_ID_COLLISION` 并完整回滚尝试写入。3/3 场景通过，未读取
  scalar fixture payload，也未使用 H2/SQLite/mock transaction。
- 目标 revision `b7d336ae8a0defaca83874f42ef7db31ec6176a1` 物化 exact class target 与两个 executor manifests：
  target SHA-256 `d2b785bcf454c62f0508dc74d195e3875f550df74a949844408fe005c1e2bcfb`，
  `java-domain-authority` manifest SHA-256 `1fcaae74c1bc2f4eaecc6e9aaf436fddaa15d32cc449c5b05b79a4b33fb0dafb`，
  `transactional-integration-replayer` manifest SHA-256
  `1553d1b48ac677f2562d4ad8f1dd03ab519966bb4a0f75c04198e845e751604c`；materializer byte-identical replay
  通过，assigned 12 Case + 12 Oracle corpus digest 为
  `5a236de3cf36155df7244b049b045cefda55960cf16efe8212c3981e5463844f`。
- 正式 `domain-services` 证据 `.sdlc/evidence/20260826-115041-domain-services/` 为两个角色 2/2、capacity
  12/12、transaction 3/3；Testcontainers 实际 PostgreSQL 16.13，runtime image digest 精确为
  `sha256:4e6e670bb069649261c9c18031f0aded7bb249a5b6664ddec29c013a89310d50`。Python 独立闭包 verifier
  重建 exact Git blobs、assigned subset、两个 manifest 与 Java reports 后报告 `preissuanceReady=true`；formal
  registry 仍为 46 Case / 46 Oracle、Domain Services 0/0，`executionClassExecutable=false`。
- 受影响 `asset` `.sdlc/evidence/20260826-115306-asset/`（Asset 97/97、kernel 41/41、capacity 12/12）、
  `fast` `.sdlc/evidence/20260826-115344-fast/` 与顺序 `server`
  `.sdlc/evidence/20260826-115413-server/` 全绿；发布级 `full`
  `.sdlc/evidence/20260826-122113-full/` 在 exact `b7d336ae...` 上 17/17 steps、1641.446 秒通过，覆盖完整 Maven
  reactor、Node 24 Web 32 files / 251 tests、typecheck/lint/build、runtime canary、R0/R1/P0、正式 Template 产品
  journey 与 Draft/Inference 浏览器旅程（25 passed + 1 controlled skip；另有真实 inference replay 1/1）。
- 首次 `full` `.sdlc/evidence/20260826-121219-full/` 在任何产品测试前因 Git-clean 工作树的物理 CRLF 违反静态
  LF 约束而失败；仅将内容等价的 clean tracked bytes 机械归一为 LF并刷新 index stat，确认 staged/semantic diff
  均为 0 后，恢复 gate `.sdlc/evidence/20260826-122045-t128-template-static-recovery/` 与最终 `full` 均通过。
  用户既有 360 项 dirty work、其指纹与备份 stash 保持不变。
- 本票没有 append formal Case/Oracle、注册或认证 Profile、运行独立 native deployment/rehearsal、调用 provider、
  读取 API Key、处理真实数据/生产，亦未取得 J1/A3/READY。Repository `full` 中既有 Rust checks 不改变
  `BUILD_NOT_AUTHORIZED`、`NOT_REGISTERED`、`NOT_CERTIFIED`；formal issuance 留给 DAG 复算后的独立票。
