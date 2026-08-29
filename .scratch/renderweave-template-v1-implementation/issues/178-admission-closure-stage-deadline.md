# T178 — admission + closure 5 秒 stage deadline 合流

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T177 (resolved)

## 目标

物化 Ticket 19 `RW-T19-S8-053`、`RW-T19-S8-058/059`、`RW-T19-S9-015/018/019` 与 cap-048：
`deadlineAndRetention.admissionAndClosureMillis` 使用 EXACT `5000`，observed `4999/5000/5001`，contract
stage `ADMISSION_AND_CLOSURE_DEADLINE`、public stage `TEMPLATE_CLOSURE`、code
`RENDER_DEADLINE_EXCEEDED`、zero boundary `ALGORITHM_INVARIANT`。该 5 秒 cap 与 T177 的 60 秒总 deadline
从同一次公共 Render admission 的 monotonic origin 固定，不能借用后续阶段时间，也不能被 wall drift 或 retry 重置。

## seam 与语义边界

- `RenderingPipelineCapacityGuard` 独占 stage deadline id/value/exact-profile/problem/public-stage；不得在
  application、Evaluator 或 Template 复制 `5000`、比较或手写 limitId。
- `CanonicalRenderingApplication` 在 T177 已有 request-local deadline 中从同一 admission origin 派生 5 秒 stage
  deadline；authorization/Profile 之后不得重新起算，进入 Evaluator 前已耗尽则零 closure/Evaluator/Engine 后续工作。
- Java `EvaluationCommand` 携带 process-local admission+closure deadline；该值不进入 HTTP/OpenAPI、Renderer
  Command wire、digest、日志或持久化。Evaluator 在 closure 成功前使用 stage deadline，成功后仅继续受同一 60 秒总 deadline。
- Template-owned `TemplateClosureAuthority` 只接收不透明 cooperative `ClosureControl`，不接收 clock、duration、
  limitId 或 Rendering problem；authority 在 retry、持久化 IO、integrity replay、DFS 与 final consistency 边界检查，
  到期返回 closed `ClosureDeadlineExceeded`。Rendering 将其映射为唯一 guard problem；不使用线程池超时或遗留后台 closure。
- 保留两参数 closure 调用作为无期限兼容 convenience；生产 Rendering 必须调用带 control 的同一 `freezeClosure`
  operation。现有 closure taxonomy、first-fail、current drift 三次上限与 total deadline 均不改变。
- cap-049+、evaluation/document-seal、Engine queue/resource/layout deadline、registry/cancel/retention 继续 deferred；
  不新增 route/OpenAPI/Web/migration/Profile registration 或 READY certification。

## TDD 与验证

- RED 顺序为 guard 缺失 enum、Template 缺失 controlled overload/outcome、integrity replay 后到期仍返回 frozen、Evaluator
  使用无期限 closure control 后仍 seal、authorization 耗尽 5 秒后 application 仍继续；每个 RED 均先独立观察再实现 GREEN。
- `RenderingPipelineCapacityGuard` 现独占 cap-048 exact value、边界比较与 taxonomy；application 从同一次 wall/monotonic
  admission origin 派生 total/stage 两个 deadline，并在 authorization、Profile 与 Evaluator 边界 fail closed。Evaluator
  把同一 stage control 传入 Template closure；closure 在 retry、IO、integrity、DFS 与 consistency 边界 cooperative 检查，
  成功 freeze 后 stage cap 停止生效而 60 秒 total cap 继续。
- focused：`TemplateClosureAuthorityTest` 22/22、`RenderingPipelineCapacityGuardTest` 40/40、
  `EvaluatorContractTest` 73/73、`RenderingApplicationContractTest` 14/14、app `EvaluatorAssemblyTest` 2/2；受影响 reactor
  Schema 20、Validation 13、Template 86、Asset 92、Rendering 283，全部绿色，`git diff --check` 通过。
- A1 evidence：template `.sdlc/evidence/20260829-113547-template/`、render
  `.sdlc/evidence/20260829-113617-render/`、fast `.sdlc/evidence/20260829-113706-fast/` 与 server
  `.sdlc/evidence/20260829-113739-server/` metadata 均为 `passed`；server 全 Reactor BUILD SUCCESS，app 372 tests、
  0 failure、0 error、15 个显式 live/manual skip。Testcontainers 销毁后的 Hikari stderr noise 不改变 metadata truth。
- cap-048 isolated guard 不冒充正式 Ticket 19 record/product executor 或 T178-specific A2/A3；provider attempts/API Key
  reads/真实数据/费用/Profile registration/push/tag/PR 均为 0 或未推进。无 HTTP/OpenAPI/Web/migration/Profile 变化，
  OpenAPI 保持 0.13.0；J0 pending、J1 未批准，故状态为 `automated_verified`。
