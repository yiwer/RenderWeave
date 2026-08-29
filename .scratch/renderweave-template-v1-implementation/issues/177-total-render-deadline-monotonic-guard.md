# T177 — total Render deadline production guard 与 monotonic admission 合流

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T122, T176 (resolved)

## 目标

物化 Ticket 19 / `RW-T19-S8-052`、`RW-T19-S9-015`、`RW-T19-S9-018`、
`RW-T19-S9-019` 与 cap-047：`deadlineAndRetention.totalDeadlineMillis` 使用 EXACT `60000`，observed
`59999/60000/60001`，contract stage `REQUEST_CONTROL`、public stage `ENGINE`、code
`RENDER_DEADLINE_EXCEEDED`、zero boundary `ALGORITHM_INVARIANT`；总 deadline 在公共 Render admission
固定一次，并只转换一次为 request-local monotonic remaining time。

## seam 与兼容边界

- `RenderingPipelineCapacityGuard` 成为 total deadline id/value/comparator/problem/public-stage 的唯一 pipeline
  authority；`CanonicalRenderingApplication` 删除本地 `TOTAL_DEADLINE_MILLIS`。
- admission 仅读取一次 wall clock，形成传给 Evaluator/Renderer Command 的不可延长 absolute
  `deadlineAtEpochMilli`；同一时刻捕获 monotonic origin，之后授权/Profile/Evaluation/Engine retry 与等待不再读取
  wall clock，也不因 wall-clock 前跳、回拨或重试重置 deadline。
- request-local deadline 使用 `System.nanoTime` 语义并通过 package-private deterministic seam 测试；production
  assembly 不开放 caller/Profile/config override。Java `EvaluationCommand` 仅携带进程内 monotonic control value；该值不进入
  HTTP/OpenAPI、Renderer Command wire、digest、日志或持久化。
- Engine `Unknown`/`BUSY` 继续只重发同一个 Renderer Command；到期返回 guard-owned
  `ENGINE / RENDER_DEADLINE_EXCEEDED / deadlineAndRetention.totalDeadlineMillis`，不重新 Evaluation、重seal、续签
  lease 或产生 partial output。
- 本票不推进后续 admission+closure/Evaluation/queue/resource/layout 阶段 deadline、Renderer registry/cancel、lease
  margin、HTTP/OpenAPI/Web/migration/Profile registration 或 READY certification。

## TDD、验证与边界

- guard test 先引用缺失的 `DEADLINE_AND_RETENTION_TOTAL_DEADLINE_MILLIS` 捕获 compile RED，并重放 exact
  `59999/60000/60001` 与 frozen taxonomy。
- application behavioral RED 证明：(1) admission 后 wall clock 不再读取/漂移不影响执行；(2) wall clock 静止时
  monotonic 经过 60 s 会终止 Unknown retry；(3) deadline 在 Evaluation 前耗尽时零 Evaluator/Engine 调用且使用
  frozen public taxonomy。
- evaluator behavioral RED 证明 wall-clock 前跳不再提前终止 Evaluation，且 monotonic 到期分别封住 evaluator entry、
  CapabilityState establish/save/load/unknown-commit replay；focused guard/application/evaluator/architecture 最终 132/132。
- 受影响 reactor 为 Schema 20、Validation 13、Template 84、Asset 92、Rendering 277，全部绿色。因 Java
  `EvaluationCommand` seam 传播到 app assembly/test fixture，按 app-wiring 风险完整执行 asset/server/web/full；首次 server
  在 app testCompile 暴露遗漏的 `AssetSliceIntegrationTest` 构造器，证据
  `.sdlc/evidence/20260829-101947-server/` 为 failed、不采信；修复后重跑通过。
- A1 evidence：asset `.sdlc/evidence/20260829-101926-asset/`（2/2）、server
  `.sdlc/evidence/20260829-103151-server/`（1/1）、web `.sdlc/evidence/20260829-104823-web/`（1/1）、
  full `.sdlc/evidence/20260829-104909-full/`（当前脚本 17/17）与 render
  `.sdlc/evidence/20260829-111255-render/`（2/2）均 passed；状态回填后的 fast
  `.sdlc/evidence/20260829-111501-fast/`（3/3）亦 passed。full 的 provider attempts/API key reads 为 0。
- cap-047 fixture 的 isolated guard 不冒充正式 Ticket 19 record 或完整流水线 A2/A3；provider/API Key/真实数据/
  费用/Profile registration/push/tag/PR 均不推进；无 HTTP/OpenAPI/Web/migration/Profile 变化。T177-specific A2/A3
  仍无，J0 pending、J1 未批准，故状态为 `automated_verified`。
