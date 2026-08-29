# T132 — 强制 CapabilityState record bytes 容量

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T125, T129, T131 (resolved)

## 目标

物化冻结 Ticket 19 `capabilityRuntime.capabilityStateRecordBytes`：Capability runtime 新建的 opaque、store-ready
`Established.sealedState` record payload 最大 `1,048,576` bytes，部署可经已绑定 fingerprint 的 effective budget
vector 收紧但不能放宽。超限必须在 CapabilityState commit 前 fail closed，返回 stage `CAPABILITY_STATE`、
`CAPABILITY_BUDGET_EXCEEDED` 与 exact limitId，且零 state-store write、零 RenderDocument、零 Engine command。

## Interface / seam

- 唯一产品与测试 seam 继续是公开 `Evaluator.evaluate(EvaluationCommand)`；使用既有 system-boundary
  `RenderingCapabilityRuntime` 与 `CapabilityStateStore` fake，不测试 `CapabilityBudget` 私有实现。
- 深化现有 Rendering-internal `CapabilityBudget`：同一次 effective vector 解析新增 record-byte limit，并提供单一
  pre-commit admission。Evaluator 只提交 opaque bytes 长度，不接收散落上限或自行构造 limitId。
- admission 位于 `capabilities.establish(requirements)` 成功之后、`CapabilityStateStore.save` 与
  `SaveRequest` 构造之前。exact limit inclusive；above-limit 不调用 store。已按同 fingerprint/同 budget vector
  线性化提交的 replay 不产生新 record，继续走既有 restore，不重复 charge。
- 不改变 SPI、PostgreSQL schema/encryption、state wire、fingerprint、expiry 或 retry semantics；初始化 retry 与
  Random rejection 仍为后续独立 frontier。

## TDD 与验证

- 先经 `Evaluator.evaluate` 写一个 above-limit tracer：3-byte established record + deployment limit 2，期望
  `CAPABILITY_BUDGET_EXCEEDED`/`CAPABILITY_STATE`/exact limitId 且 saveCalls=0；确认真实 RED 后实现最小纵切。
- 再补 exact-at-limit 3-byte record 成功提交与既有 no-capability zero-state-work 回归。focused Rendering →
  app assembly → `render`/`fast`/顺序 `server`，按最终影响面决定是否追加 `full`。
- 不新增 route/OpenAPI/Web/migration/Profile，不运行 provider，不读取 API Key，不发送真实数据，不
  push/tag/PR；最高 `automated_verified`，A3/J1/READY 不推进。

## Resolution

- `CapabilityBudget` 现在从已进入 evaluation fingerprint 的 effective vector fail-closed 解析
  `capabilityStateRecordBytes`，冻结最大值 `1,048,576`；部署值可收紧，缺项、非 canonical integer、负值或放宽均
  继续由同一 budget authority 拒绝。Evaluator 只把 `Established.sealedState().length` 交给该 authority。
- 新建 state 在 runtime establish 成功后、`SaveRequest` 构造与 `CapabilityStateStore.save` 前执行 admission。
  exact-at-limit 成功；above-limit 返回 stage `CAPABILITY_STATE`、code `CAPABILITY_BUDGET_EXCEEDED` 与 exact
  `capabilityRuntime.capabilityStateRecordBytes`，且 `saveCalls=0`。既有 load/restore replay 与无 capability 零 state
  work 路径保持不变。
- 公开 `Evaluator.evaluate` tracer 先取得真实 RED：期望 `Rejected`、实际仍为 `SealedDocument`；最小实现后同一
  tracer GREEN，并补 3-byte exact-at-limit 成功。最终 focused Evaluator 34/34、Rendering module 159/159、生产
  Spring assembly 7/7。
- A1 gates：`render` `.sdlc/evidence/20260828-234144-render/`、`fast`
  `.sdlc/evidence/20260828-234233-fast/`、顺序 clean `server`
  `.sdlc/evidence/20260828-234303-server/` 均为 metadata `passed`；server 8-module reactor BUILD SUCCESS，App
  372 tests / 0 failures / 0 errors / 15 skipped。T132 未改 API/Web/migration/Profile，`full` 只会重复 clean server
  与未变 Web 轴，故不追加。
- A2 仅来自未变 Renderer 轴的独立 replay，无 T132-specific issued replay；A3 无，J0 pending、J1 未批准。
  provider attempts/API Key reads/reservations/cost/真实数据/Profile registration/push/tag/PR 均为 0。
- 状态回填后的 resolution `fast` `.sdlc/evidence/20260828-235941-fast/` metadata 仍为 `passed`。
