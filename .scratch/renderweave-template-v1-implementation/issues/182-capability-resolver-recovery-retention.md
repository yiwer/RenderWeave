# T182 — CapabilityState / AssetResolver recovery 5 分钟 retention

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T13, T21, T122, T181 (resolved)

## 目标

物化 Ticket 19 `RW-T19-S8-074`、`RW-T19-S9-015/018/019` 与 Rendering-pipeline cap-052：
`deadlineAndRetention.capabilityAndResolverRecoveryRetentionAfterDeadlineMillis` 使用 EXACT `300000`，observed
`299999/300000/300001`，contract stage `REQUEST_CONTROL`、public stage `ENGINE`、zero boundary
`ALGORITHM_INVARIANT`。该 retention-invariant 没有 public problem code；deployment/profile 不匹配必须在 accepted
execution 前失败封闭，禁止伪造 Render problem code。

CapabilityState 与 Asset selection recovery record 均以原始 Render deadline 为锚点固定保留 `300000 ms`。相同
requestId/fingerprint 的 retry、load、cancel、下游失败或 wall-clock 回读不得续期；exact expiry 后记录不可恢复。
期限计算必须保留毫秒精度并使用 checked arithmetic，不能以秒取整、saturation 或当前访问时刻改变冻结 expiry。

## Interface / seam

- 既有 package-internal `renderweave-rendering-pipeline-capacity-guard/1.0` 增加 code-less exact invariant identity；
  只暴露 limitId 与 public stage，不扩展 public API。
- Rendering-owned `CapabilityStateStore` 改为 epoch-millisecond lifecycle；`CanonicalEvaluator` 从原始 deadline 计算唯一
  expiry，PostgreSQL adapter 只保存、重放并按该 fixed expiry 清理。
- Asset-owned `AssetResolver` / persistence 继续拥有 selection record lifecycle；不把 retention 变成 caller-controlled
  ResolveRequest 字段，不新增跨上下文共享 retention module、共享表或事务。
- cap-053+ deferred；不修改 HTTP/OpenAPI/Web、Flyway schema、Renderer Command/process wire、manifest identity、
  Profile registration/certification；不运行 provider、读取 API Key、处理真实数据、push、tag 或 PR。

## TDD 与验证

- Java guard tracer 先因 cap-052 enum identity 缺失形成 compile RED，再覆盖 `299999/300000/300001`、EXACT
  accessor 与禁止 public rejection。
- Capability tracer 先证明非整秒 deadline 会被旧秒级 contract 改写，再改为 exact millisecond fixed expiry；通过
  store seam 验证 confirmed-missing retry 与 committed replay 均不续期。
- Asset tracer 通过 resolver/persistence seam 固化现有 exact `deadline + 300000` 与 retry 不续期行为。
- 运行 focused、受影响 reactor，以及 app-wiring 要求的 asset/server/web/full gates；最高
  `automated_verified`。claim 时 A0、J0 pending，J1 未批准。

## Resolution

- `RenderingPipelineCapacityGuard` 已登记 code-less EXACT cap-052 identity，固定值 `300000`，覆盖
  `299999/300000/300001`，并证明该 invariant 不产生 public rejection。
- `CapabilityStateStore` lifecycle 已改为 epoch-millisecond；`CanonicalEvaluator` 从原始 Render deadline 使用
  `Math.addExact(deadline, 300000)` 唯一计算 fixed expiry。非整秒 deadline 不再被改写，confirmed-missing retry、
  committed replay、load 与 wall-clock 漂移均不续期；加法 overflow 在采样/写记录前 fail-closed 为既有
  `CAPABILITY_STATE_UNAVAILABLE`。
- PostgreSQL adapter 按毫秒保存、比较与清理 recovery record；同 requestId/fingerprint replay 返回首次记录及首次
  expiry。既有 Asset-owned resolver 继续使用原始 deadline + `300000 ms`，应用层 PostgreSQL tracer 证明 content
  replacement 后 replay 仍保留原 selection record 与原 expiry。
- TDD/focused：guard `44/44`、Evaluator `84/84`、AssetResolver contract `4/4`、app PostgreSQL slices `17/17`
  全绿。A1 gates：asset `.sdlc/evidence/20260829-133704-asset/`、server
  `.sdlc/evidence/20260829-133725-server/`、web `.sdlc/evidence/20260829-135305-web/`、full
  `.sdlc/evidence/20260829-135355-full/` 全部 metadata passed；full 为 `17/17` steps、provider attempts `0`。
- T182-specific A2/A3 无，J0 pending、J1 未批准，因此生命周期为 `automated_verified`。cap-053+ deferred；无
  HTTP/OpenAPI/Web/Flyway/Renderer wire/manifest/Profile registration/certification/provider/API Key/真实数据/费用/
  push/tag/PR 变化，OpenAPI 保持当前 `0.16.0`。
