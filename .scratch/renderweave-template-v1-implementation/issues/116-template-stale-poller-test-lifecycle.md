# 隔离 Template STALE production poller 与测试上下文生命周期

Type: bug
Status: resolved
Resolution: automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 20, 114, 115（均已 resolved）

## Problem

T115 的 sequential `server` 与 Goal `full` 虽然断言全部通过，但每个缓存的 Spring 测试上下文都会运行
`TemplateAssetStaleConsumer.@Scheduled`。当对应 Testcontainers PostgreSQL 在测试类结束后停止，仍存活的调度任务
持续请求旧连接，产生 `CannotCreateTransactionException`/connection refused，并令 Surefire 在 `System.exit(0)` 后
等待 30 秒再强制终止 fork JVM。该现象污染 canonical 证据，也掩盖真正的服务端失败。

## Decision

1. **分离 work 与 scheduling**：`TemplateAssetStaleConsumer` 继续作为可显式调用、可重放的消费/recheck work bean，
   不再直接声明 `@Scheduled`；新增窄 `TemplateAssetStalePoller` adapter，只负责按固定 delay 依次调用
   `consumePending()` 与 `recheckStale()`。
2. **生产默认开启**：poller bean 受 `renderweave.template.stale-consumer.enabled` 控制，`true` 或缺省时装配；既有
   `renderweave.template.stale-consumer.delay-ms` 语义保持不变。禁止以“修测试”为由关闭生产 STALE 消费。
3. **测试默认关闭**：`src/test/resources/application.properties` 显式设置 enabled=false。依赖 STALE 语义的
   PostgreSQL tests 继续直接调用 consumer，因而不依赖 wall-clock、后台线程或 test-only product bypass。
4. **独立接线证明**：轻量 `ApplicationContextRunner` 覆盖缺省开启、显式关闭、consumer 始终存在；poller 单测冻结
   consume→recheck 调用顺序。真实 Template PostgreSQL slice 证明 test property 下 poller 不存在而 consumer 可用。
5. **诚实边界**：不改变 audit cursor、事务、readiness、生产 delay、Template/Asset API、OpenAPI/Web/Renderer/
   Profile/正式产品 route；不新增 placeholder 或 `/prototype` 交付。

## Verification

- TDD：先加入期望的 poller/config tests，在 production class 尚不存在时 focused compile/test RED；再最小 GREEN。
- focused：`TemplateApplicationConfigurationTest`、`TemplateDependencyProjectionTest`、
  `AssetDeleteRestoreSliceTest`，并扫描日志不得出现 `TemplateAssetStaleConsumer` scheduled exception。
- 分级：`fast` → sequential `server` → Goal `full` → resolution `fast`，Maven 不并发。
- 完成信号：断言全绿；server/full 日志中 stale poller connection-refused 与 Surefire forced-kill 均为 0；生产默认
  poller 接线仍由独立测试证明。最高只报 `automated_verified`，不推进 Profile/J1/A3/READY，不 push/tag/PR。

## Closeout

- TDD RED：在 production poller 尚不存在时，focused compile/test 以 4 个
  `TemplateAssetStalePoller` missing-symbol 编译错误失败；最小实现后 configuration + projection focused tests
  16/16 通过，`AssetDeleteRestoreSliceTest` 5/5 通过。
- production wiring：`TemplateAssetStaleConsumer` 保持独立可重放 bean；缺省/`true` 时只额外装配窄 poller，
  `false` 时只移除 poller；poll 顺序固定为 `consumePending()` → `recheckStale()`。主配置显式映射 enabled/delay，
  test resources 显式关闭后台 poller，真实 PostgreSQL slice 仍直接验证 consumer 语义。
- affected `fast`：`.sdlc/evidence/20260825-182011-fast/`，3/3 steps exit 0。
- sequential `server`：`.sdlc/evidence/20260825-182031-server/`，`passed/A1`，354 tests、0 failures、
  0 errors、15 controlled skips；`TemplateAssetStaleConsumer`、scheduled-task error、Surefire forced-kill、
  `CannotCreateTransactionException` 四项日志计数均为 0。
- Goal `full`：`.sdlc/evidence/20260825-183237-full/`，17/17 steps exit 0、`passed/A1`、1133.441 秒；
  server 354/0/0/15、Node 24 Web 28 files/217 tests + production build、runtime canary、Playwright
  23 passed + 1 controlled skip 以及 inference replay journey 均通过；同一四项 server 日志计数仍均为 0。
- 状态与证据回填后的 resolution `fast`：`.sdlc/evidence/20260825-185343-fast/`，3/3 steps exit 0。
- 生命周期状态为 `resolved / automated_verified`。一般 Testcontainers/Hikari 旧连接 housekeeper 警告属于既有
  独立测试上下文清理噪声，不再由 STALE poller 触发，也未造成 forced-kill；本票不扩张到该独立问题。
- API/OpenAPI/Web/Renderer/Profile/正式产品 route 均未改变；Profile `NOT_REGISTERED`、certification
  `NOT_CERTIFIED`、process raster `ABSENT`、public Rendering API 与正式产品 route `CLOSED`。最终产品
  Template-v1 页面与真实功能仍未交付，`/prototype` 不计交付；provider attempts/API Key reads/reservations/
  cost/真实数据=0，未推进 J1/A3/READY，未 push/tag/PR。
