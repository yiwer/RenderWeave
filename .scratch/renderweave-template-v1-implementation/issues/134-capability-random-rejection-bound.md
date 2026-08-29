# T134 — 强制 Random rejection attempt bound 与 result-invalid 终态

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T125, T128, T129, T131, T133 (resolved)

## 目标

物化冻结 Ticket 14 §59 与 Ticket 19 `capabilityRuntime.randomRejectionAttempts`：Random
`UNIFORM_DECIMAL_0_1` 对 counter 从 `0` 起，在每次开始下一 HMAC rejection-sampling attempt 前受
固定合同限制；fingerprint-bound effective budget profile 必须为 exact `128`，`127/129` 均非法。全部 128 次
attempt 都被拒绝时，首次
实际 demand 返回 stage `MATERIALIZATION`、code `CAPABILITY_RESULT_INVALID` 与 exact limitId，且停止后续
Capability/Asset/lowering，零 RenderDocument、零 Engine command。

## Interface / seam

- 唯一产品行为测试 seam 仍是公开 `Evaluator.evaluate(EvaluationCommand)`；现有
  `RenderingCapabilityRuntime` 是受信 system adapter seam。不得把 nonce、digest、counter 或 fault schedule 暴露给
  Evaluator、DesignDSL、RenderInput、日志、problem 或持久历史。
- 深化既有 `CapabilityBudget`：把 `randomRejectionAttempts` 从通用可收紧 limit 分离为 exact profile invariant，
  缺失、非 canonical integer、`127` 或 `129` 都 fail closed。完整 effective vector 已进入 evaluation fingerprint；
  state requirements/wire、expiry 与初始化重试不变。
- `CapabilityDerivation` 继续是 app/runtime 共用的唯一 HMAC 实现，固定只运行 counters `0…127`，不接收 caller/
  Template/deployment 自选 attempt 数，也不使用 double、locale 或替代随机源。
- Runtime 用 closed result-invalid outcome 区分 HMAC exhaustion 与 provider unavailable；Rendering 内部把它映射为
  exact `CAPABILITY_RESULT_INVALID`/limitId。普通 provider unavailable、budget exceeded 与 expression decimal limit
  不得被误映射。

## TDD 与验证

- 先经公开 Evaluator seam 写 rejection-exhausted tracer，要求 exact stage/code/limitId、只调用首个 demand 且无
  sealed document，确认真实 RED；再最小补 closed runtime outcome 与 materialization mapping。
- 再固定 effective profile 仅 exact `128` 可构造 Evaluator，`127/129` 均由 budget authority fail closed；保留既有
  HMAC known-answer vector、固定 128-attempt loop 与 deterministic per-position 语义。
- focused Rendering + app adapter/architecture，随后按影响面运行 `render`、`fast`、顺序 `server`；无
  API/OpenAPI/Web/migration/Profile 变化时不重复发布级 `full`。最高 `automated_verified`，A3/J1/READY 不推进。
- 不发行正式 Ticket 19 Case/Oracle，不实现 fault-schedule executor；不运行 provider、不读取 API Key、不发送真实
  数据，不 push/tag/PR。

## Resolution

- 机器权威 `conformance-capacity-coverage-v1.json` 将本轴固定为 comparator `EXACT`；实现中途发现 `127` 不应是
  合法 deployment tightening 后立即撤回该解释。`CapabilityBudget` 现在只接受 canonical integer `128`，缺失、
  非 canonical、`127` 与 `129` 都在构造 Evaluator 时 fail closed；完整 vector/fingerprint 语义保持不变。
- `CapabilityDerivation` 仍是唯一 HMAC/rejection-sampling authority，并继续固定 counters `0…127`。
  app runtime 以 closed `RandomRejectionExhausted` 区分 128 次拒绝耗尽与 provider unavailable；Evaluator 将前者精确
  映射为 stage `MATERIALIZATION`、code `CAPABILITY_RESULT_INVALID`、limitId
  `capabilityRuntime.randomRejectionAttempts`。普通 provider、demand budget 与 decimal fault 映射均未改变。
- 公开 `Evaluator.evaluate` tracer 先取得 closed-outcome symbol 缺失的 compile RED，再取得 `127` 被错误接受的
  behavioral RED；最小 GREEN 后 exhaustion 在第一个实际 demand 终止，第二个 alias 不调用，且无
  RenderDocument/Engine command。既有字面 known-answer vector 与生产 HMAC 路径继续通过。
- 最终 focused `EvaluatorContractTest` 45/45、Rendering module 170/170、生产 app
  assembly/architecture 12/12；`git diff --check` 通过。
- A1 gates：`render` `.sdlc/evidence/20260829-005811-render/`、`fast`
  `.sdlc/evidence/20260829-005906-fast/`、顺序 clean `server`
  `.sdlc/evidence/20260829-005936-server/` metadata 均为 `passed`。server 8-module reactor BUILD SUCCESS，App
  372 tests / 0 failures / 0 errors / 15 skipped。T134 未改 API/OpenAPI/Web/migration/Profile，故不重复发布级
  `full`。
- A2 仅来自 `render` 中未变 Renderer 轴的独立 replay，无 T134-specific issued record；A3 无，J0 pending、J1
  未批准。正式 Ticket 19 records/fault-schedule executor 仍受发行前置阻塞；provider attempts/API Key reads/
  reservations/cost/真实数据/Profile registration/push/tag/PR 均为 0。
- 状态回填后的 resolution `fast` `.sdlc/evidence/20260829-011629-fast/` metadata 仍为 `passed`，3/3 steps
  全绿。
