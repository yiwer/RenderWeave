# 冻结 Template aggregate、revision 与 persistence seam

Type: grilling
Status: resolved
Claimed by: Codex /root
Blocked by: 02, 03

## Question

在 canonical DesignDSL authority 已有真实接口后，Template aggregate、永久 exact StaticSchema binding、不可变 revision/current pointer、`OwnerScopeAuthority`、create/save optimistic concurrency、INVALID confirmation、read/export 与 forward-only persistence 应由哪些 deep interfaces 拥有，并以哪些 closed success/failure 与事务不变量阻止 ownerScope 自报、内容 UPDATE/DELETE、lost update、partial save 和 repository-specific 语义泄漏？本票冻结 ADR、contract 与可验证不变量，不提前创建 migration、表、route 或占位实现。

## Answer

采用 ADR-0042 的“一个作者 deep Interface + 两个专用 provider Interface + transaction-sized outbound seams”
方案：

- `TemplateApplication` 独占作者侧 create/read/save/history/export aggregate use cases；
  `TemplateSnapshotAuthority` 只向 Rendering 提供权威 snapshot/closure，`AssetReferenceAuthority` 只向 Asset
  删除流程提供 current-only proof/reservation。三者不共享 repository、聚合或泛化 query API。
- Ticket 06 只在真实纵切同票物化 `TemplateApplication.create/getCurrent/save`；copy/restore/delete、history/
  export、INVALID confirmation、snapshot 和 reference proof 没有真实 consumer/behavior 前不创建 placeholder
  method、SPI 或 outcome。`DesignDslAuthority` 保持唯一 canonical admission/hash 权威。
- app 建立的 server-only `TemplateInvocationRef` 与用户 command 分离。Template-owned
  `OwnerScopeAuthority` 按 create/existing/recheck 三类 closed operation 返回可信 scope、access、recheck 与
  response disclosure；HTTP/DesignDSL 不接受 ownerScope、capability、role、Workspace 或授权 boolean。跨 scope/
  无 read+operation 折叠 `TEMPLATE_NOT_FOUND`，有 read 无 operation 返回 `TEMPLATE_FORBIDDEN`；只有 mutation
  capability 的成功只给 opaque receipt，不泄漏 revision/DesignDSL/child/Asset detail。
- `.spi.TemplatePersistence` 只提供 metadata locate、trusted current/revision load 与 admitted create/append
  transaction，不暴露 row CRUD、SQL/JDBC/transaction callback、generic Result/Problem 或 repository exception。
  Postgres Adapter 与 ordered scripted Adapter 构成真实 seam；commit 只能接收 server-resolved immutable scope、
  permanent Schema、canonical DesignDSL/hash、expected/next revision、readiness/report 与真实 current projection。
- Template durable aggregate 只存 opaque identity、immutable ownerScope、permanent exact StaticSchemaRef、
  lifecycle/current/readiness/report；revision 从 0 连续追加完整 JSONB snapshot 和 contentHash。trusted read 必须
  重新 canonicalize 持久 JSON 并核对 bytes/hash，漂移返回 `TEMPLATE_INTEGRITY_MISMATCH`，不自动修复历史。
- Create 依次执行 Host create authority、hard/canonical admission、exact Schema/dependency validation、authority
  recheck 和单一事务；只允许全合法 READY revision 0，不允许 INVALID create。Save command 只有
  `{templateId, expectedRevision, complete raw DesignDSL}`；Schema/scope 从既有聚合取得。成功事务对 immutable
  new revision、current、current-only projections、readiness、report 全成或全不成；same-hash save 仍追加，两个
  并发相同 expectedRevision 恰一成功。
- dependency-only 且问题完整未截断时，未来 save/copy/restore 可返回 `ConfirmationRequired`；token 绑定
  operation、subject/scope、target permanent Schema、source/target、expectedRevision、hash、完整 problem/
  dependency snapshot 与 expiry。确认请求重交完整 DesignDSL，重跑全部检查并 recheck authority；任何漂移零写，
  hard error/create/`PROBLEM_LIMIT_REACHED` 永不签 token，也没有 `force=true`。具体 TTL 留到首个真实
  confirmation slice 与 clock/key Adapter 一起冻结，T06 不创建不可达路径。
- exact revision read/export 对 ACTIVE/DELETED history 都可用，先做 integrity check；current read 返回 canonical
  editor baseline，history/export 不附 current readiness/report。export 一次性 seal 冻结媒体类型/envelope，
  current 后续漂移不改 artifact。
- persistence 服从 ADR-0003：下一真实纵切才选当时下一 Flyway version；旧 migration 不改、无 down migration、
  普通 PK/unique/FK/check 且无 immutability trigger。窄 SPI/explicit SQL 禁止 revision content UPDATE/DELETE、
  aggregate purge 与 scope/Schema rebind；Testcontainers/public-surface/architecture tests 验证 append-only、双连接
  conflict、fault rollback、scope injection absence、permanent binding、corruption fail-closed 和零 partial write。

未选择 public CRUD repository、command-per-service、generic command bus、current JSON 原地覆盖、event sourcing、
每 revision 重复 scope/Schema、server-side pending invalid draft 或 DB immutability trigger；它们分别泄漏 Adapter
语义、分散不变量、破坏 closed outcome/append-only，或与既有 ADR-0003 冲突。

本票只有 ADR/领域语言/实施合同与验证计划，没有新增 Java 产品 Interface、migration、表、OpenAPI、route 或
页面，也没有执行 DB/HTTP/browser/Renderer。Ticket 19 保持 open；Template、Editor、Renderer 仍未 READY。
