# T133 — CapabilityState 初始化有界重试与 unknown-commit 恢复

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T125, T129, T131, T132 (resolved)

## 目标

物化冻结 Ticket 14 §7 与 Ticket 19 `capabilityRuntime.initializationAttempts`：CapabilityState 初始化最多
`3` 次 attempt（首次加两次 retry），部署可经已绑定 evaluation fingerprint 的 effective budget vector 收紧但
不能放宽。提交前瞬时失败只在同一不可延长 deadline 内重试；`save` 结果不明时必须先查询记录，只有明确
`Missing` 才允许再次建立 runtime/重采样。耗尽返回 stage `CAPABILITY_STATE`、code
`CAPABILITY_STATE_UNAVAILABLE` 与 exact limitId，且零 RenderDocument、零 Engine command。

## Interface / seam

- 唯一产品与测试 seam 继续是公开 `Evaluator.evaluate(EvaluationCommand)`；现有 system-boundary
  `RenderingCapabilityRuntime` 与 `CapabilityStateStore` 只作为 seam 后的 adapter，不新增公开 SPI 方法。
- 深化既有 Rendering-internal `CapabilityBudget`：解析 `initializationAttempts` 的冻结最大值并提供请求级 attempt
  authority；Evaluator 不持有散落的数值或自行构造 limitId。
- 首次 store `load` 仍发生在任何采样前；`Loaded` 直接 restore，fingerprint conflict 与 load unavailable 均
  fail closed。每次 attempt 包含 establish、record-byte admission 与 save；establish 的提交前瞬时失败可进入
  下一次 attempt。
- `SaveUnavailable` 视为 unknown commit：立即按同 request/fingerprint 查询；`Loaded` restore 且绝不重采样，
  `LoadFingerprintConflict` 冲突，`LoadUnavailable` fail closed，只有 `Missing` 才可开始下一次 attempt。
- `issuedAt` 与 `expiresAt` 在首次 initialization attempt 前计算一次并在所有重试复用；不因重试续期。record-byte
  超限、fingerprint conflict、restore invalid 均为 terminal，不消耗额外初始化 attempt。

## TDD 与验证

- 先经 `Evaluator.evaluate` 写 transient establish 两次失败、第三次成功的 tracer，确认当前实现真实 RED；最小
  GREEN 后再补 deployment limit 耗尽的 exact stage/code/limitId 与 zero save。
- 再以 unknown-save committed 与 unknown-save/missing 两个 race tracer 固定“先查询、后决定是否重采样”；
  committed 路径只 restore，missing 路径才进入下一 attempt。测试只观察公开 outcome，adapter counters 仅用于
  证明零重采样/零提交副作用。
- focused Rendering → app assembly → `render`/`fast`/顺序 `server`；无 API/Web/migration/Profile 变更时不重复
  发布级 `full`。最高 `automated_verified`，A3/J1/READY 不推进。
- 不实现 Random HMAC rejection/fault schedule 或正式 Ticket 19 records；不运行 provider、不读取 API Key、不
  发送真实数据，不 push/tag/PR。

## Resolution

- `CapabilityBudget` 现在从已绑定 evaluation fingerprint 的 effective vector fail-closed 解析
  `initializationAttempts`，冻结最大值为 `3`；部署值只可收紧。请求级 attempt authority 在每次 establish 前预约，
  耗尽返回 stage `CAPABILITY_STATE`、code `CAPABILITY_STATE_UNAVAILABLE` 与 exact
  `capabilityRuntime.initializationAttempts`。
- Evaluator 首次 load 仍在采样前；precommit transient establish failure 只在同一总 deadline 与 attempt budget 内
  重试。`SaveUnavailable`/`Replayed` 一律先 load：已提交的 `Loaded` 直接 restore 且不重采样，只有明确 `Missing`
  才进入下一 attempt，conflict/unavailable fail closed。`issuedAt`/`expiresAt` 在首轮前固定，重试不续期。
- deadline 在 initial replay restore、establish、save 后的 unknown-commit restore 各边界都重新检查；一旦到期便不
  commit、不 restore、也不建立 root frame。record-byte admission、fingerprint conflict 与 restore invalid 保持
  terminal，不额外消耗 attempt。
- 公开 `Evaluator.evaluate` tracer 对 transient retry、unknown committed/missing 与四个 deadline race 均先取得真实
  RED，再最小 GREEN。最终 focused Evaluator 43/43、Rendering module 168/168、生产 assembly/architecture 12/12。
- A1 gates：`render` `.sdlc/evidence/20260829-001925-render/`、`fast`
  `.sdlc/evidence/20260829-002016-fast/`、顺序 clean `server`
  `.sdlc/evidence/20260829-002049-server/` metadata 均为 `passed`；server 8-module reactor BUILD SUCCESS，App
  372 tests / 0 failures / 0 errors / 15 skipped。T133 未改 API/Web/migration/Profile，故不重复发布级 `full`。
- A2 仅来自未变 Renderer 轴的独立 replay，无 T133-specific issued record；A3 无，J0 pending、J1 未批准。
  provider attempts/API Key reads/reservations/cost/真实数据/Profile registration/push/tag/PR 均为 0。
- 状态回填后的 resolution `fast` `.sdlc/evidence/20260829-003825-fast/` metadata 仍为 `passed`，3/3 steps
  全绿。
