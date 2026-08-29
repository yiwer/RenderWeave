# T175 — CapabilityState initialization attempts production guard 合流

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T133, T135, T174 (resolved)

## 目标

物化 Ticket 19 / `RW-T19-S8-032` 与 cap-045：
`capabilityRuntime.initializationAttempts` 使用 EXACT `3`，observed `2/3/4`，contract stage
`CAPABILITY_STATE_INITIALIZATION`、public stage `CAPABILITY_STATE`、code
`CAPABILITY_STATE_UNAVAILABLE`、zero boundary `ZERO_DOCUMENT_OUTPUT`，reservation point 为开始下一次
initialization attempt 之前。T133/T135 已实现真实三次初始化与 exact-profile 校验，但冻结 probe 要求 exact
production guard 且禁止 duplicate guard；本票将 profile 与 runtime admission 合流到唯一
`RenderingPipelineCapacityGuard`。

## seam 与兼容边界

- production guard catalog 成为 initialization-attempt frozen id/value/comparator/problem/public-stage 唯一权威；
  `CapabilityBudget` 删除 `MAX_INITIALIZATION_ATTEMPTS`、本地 exact 比较、运行时 maximum 与手写 limitId。
- 配置 admission 使用 EXACT：effective vector 只能声明 `3`，`2` 与 `4` 均 fail closed；该轴不可按
  MAX_INCLUSIVE deployment tightening 解释。
- runtime admission 使用同一 exact catalog 的 attempt ceiling：第 1、2、3 次在 store/provider 交互前成功
  reserve，第 4 次在开始前以 `CAPABILITY_STATE_UNAVAILABLE` / exact limitId 拒绝；失败不得提交 counter，
  不得产生第 4 次 CapabilityStateStore 写入或 Capability provider 调用。
- retry、unknown-commit recovery、evaluation fingerprint、state record、deadline、capability result digest、
  memo/lazy 与 error projection 均不改变。

## TDD、验证与边界

- guard test 先引用缺失的 `CAPABILITY_RUNTIME_INITIALIZATION_ATTEMPTS` 捕获 compile RED，再重放 exact
  `2/3/4`、frozen taxonomy 与 runtime `1/2/3/4` reservation 边界。
- 公开 Evaluator 既有回归继续证明第三次可成功、非 exact profile `2/4` 拒绝、第四次前停止且 store
  establishCalls 恰为 `3`。
- focused guard/Evaluator/CapabilityValues/CapabilityDeclarations/architecture、完整受影响 reactor、`render`
  与 `fast`；无 app wiring/API/Web/migration/Profile，不重复 server/full。
- 本票不迁移 cap-046 random rejection attempts，不改变 effective vector wire。provider/API Key/真实数据/
  费用/Profile registration/push/tag/PR 均不推进；最高 `automated_verified`、J0。

## Resolution

- TDD 首轮得到唯一预期 compile RED：guard test 引用了尚不存在的
  `CAPABILITY_RUNTIME_INITIALIZATION_ATTEMPTS`。补入 exact catalog 与 runtime ceiling 后，guard 37/37、
  expanded guard/Evaluator/CapabilityValues/CapabilityDeclarations/architecture focused 131/131 转绿。未伪造
  新的行为 RED，因为 T135 已有正确 exact-profile 与 retry 行为，本票只做冻结 guard 合流。
- `RenderingPipelineCapacityGuard` 现在独占 initialization-attempt id、EXACT `3`、
  `CAPABILITY_STATE_UNAVAILABLE` 与 public stage `CAPABILITY_STATE`。普通 `admit` 对 profile 执行 exact
  `2/3/4` 比较；显式 `admitRuntimeMaximum` 复用同一 frozen value/taxonomy，允许第 1–3 次 reserve 并在第
  4 次前拒绝，避免把 exact profile 错当成可收紧 maximum。
- `CapabilityBudget` 已删除 `MAX_INITIALIZATION_ATTEMPTS`、stored maximum、本地 exact 比较与手写
  limitId；effective vector 通过唯一 exact guard fail closed，runtime counter 以 overflow-safe projected
  value 检查并仅在成功后提交。既有公开 Evaluator 回归继续证明 profile `2/4` 拒绝、第三次成功、持续瞬时
  失败时 `establishCalls=3` / `saveCalls=0` / 零 RenderDocument。
- 最终受影响 reactor 为 Schema 20/20、Validation 13/13、Template 84/84、Asset 92/92、Rendering 271/271。
  `render` A1 `.sdlc/evidence/20260829-094806-render/`（2/2）与 `fast` A1
  `.sdlc/evidence/20260829-094853-fast/`（3/3）metadata 均 passed；`git diff --check` 通过，重复
  authority 搜索只剩 catalog id 与无参 counter 构造。
- cap-045 fixture 只要求 exact production guard，本票没有行为路径专属 A2/A3；render gate 中既有独立 replay
  保持绿色但不冒充 T175 证据。J0 pending、J1 未批准；按非 app-wiring 边界未重复 server/full。cap-046 仍
  deferred，provider attempts、API Key reads、真实数据、费用、Profile registration、push/tag/PR 均为 0。
- 状态回填后的 `fast` A1 `.sdlc/evidence/20260829-095005-fast/` metadata 为 passed，3/3 steps 全绿；
  npm 配置 warning 仅写入 stderr，没有改变 step exit code 或 metadata truth。
