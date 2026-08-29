# T125 — 物化 Evaluator CapabilityState 持久化重放纵切

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T122 (resolved)

## 目标

在既有 `Evaluator` Interface 内完成 stage 6 `CAPABILITY_STATE`：用冻结输入计算
`evaluationFingerprint`，将单一 UTC 秒快照与 256-bit server-only nonce 作为 opaque state
经 `CapabilityStateStore` 线性化持久化；同 `renderRequestId`/同 fingerprint 必须恢复既有状态，
异 fingerprint 必须以 `CAPABILITY_STATE_CONFLICT` fail closed，store/runtime 不可用以
`CAPABILITY_STATE_UNAVAILABLE` 收口，且不得在 replay 后重新采样。

## Interface / seam

- `RenderingAuthority.Authorized` 产生不可由请求自报的 `authorizationContextDigest`，app 编排只传递。
- `EvaluationCommand` 携带该摘要；`Evaluator` 从 closure、admitted input、冻结 Profile/contracts 与
  effective budget vector 计算完整 fingerprint。
- `RenderingCapabilityRuntime` 建立或从 opaque bytes 恢复 runtime；序列化细节留在 app adapter。
- `CapabilityStateStore` 继续是唯一持久化 seam；不得向 Rendering 泄漏 JDBC/加密实现。

## TDD 与验证

先在 `Evaluator` 公共 seam 写 RED：首次 save、同 fingerprint replay、异 fingerprint conflict、
store unavailable；再实现最小纵切。随后运行 focused Rendering/app tests、`fast`、顺序 `server`、
Goal `full`，回填 A1/A2/A3 与 J0/J1，独立 commit。禁止 push/tag/PR、Profile 注册、公开 route、
付费 provider、真实数据、H2/SQLite 或 placeholder。

## Resolution

- Evaluator stage 6 已将 authority-produced authorization digest、closure/input digests、exact
  profiles/contracts 与完整冻结 budget vector 绑定进 evaluation fingerprint。
- Capability state 加密落盘并按 `renderRequestId` 线性化；first save、exact replay、
  fingerprint conflict、store unavailable、opaque runtime restore 及并发 save/replay 均有测试。
  Replay 不重新采样 clock/nonce。
- A1 证据：asset `20260828-140900-asset`、web `20260828-140922-web`、fast
  `20260828-141009-fast`、server `20260828-135801-server`、full `20260828-141041-full`
  （17/17 steps，983.407 秒）、resolution fast `20260828-144008-fast`。
  A2 ticket-specific independent implementation replay 未签发；
  A3 未外部强制；J0。provider/API Key/真实数据/Profile/push/tag/PR = 0。
