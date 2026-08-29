# T176 — Capability random rejection attempts production guard 合流

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T134, T175 (resolved)

## 目标

物化 Ticket 19 / `RW-T19-S8-033` 与 cap-046：
`capabilityRuntime.randomRejectionAttempts` 使用 EXACT `128`，observed `127/128/129`，contract stage
`CAPABILITY_FIRST_DEMAND`、public stage `MATERIALIZATION`、code `CAPABILITY_RESULT_INVALID`、zero boundary
`ZERO_DOCUMENT_OUTPUT`，reservation point 为开始下一次 HMAC rejection-sampling counter attempt 之前。
T134 已实现真实 exact profile 与 counter `0…127` 的 fail-closed exhaustion；本票将该轴合流到唯一
`RenderingPipelineCapacityGuard`，满足 frozen probe 的 exact production guard 与 no-duplicate 要求。

## seam 与兼容边界

- production guard catalog 成为 random-rejection frozen id/value/comparator/problem/public-stage 唯一 pipeline
  authority；`CapabilityBudget` 删除 `MAX_RANDOM_REJECTION_ATTEMPTS`、本地 exact 比较与旧
  `exactLimit` helper，effective vector `127/129` 通过同一 exact guard fail closed。
- `CapabilityDerivation` 保持唯一 HMAC/rejection-sampling 算法与 public app/runtime 共用 seam；其既有
  `MAX_REJECTION_ATTEMPTS` 是 counter `0…127` 的算法合同，guard catalog 直接复用该值，不复制 `128`
  数字权威。loop condition 继续在每次 HMAC 前检查，不暴露 nonce/counter/fault schedule。
- internal state path 与 external `RandomRejectionExhausted` path 均继续在第一个实际 demand 终止；错误 limitId
  改由 production guard 投影，第二个 alias 不调用、零 RenderDocument/Engine command。
- capability demand/result-digest 记账、CapabilityState、fingerprint、memo/lazy、provider unavailable 与
  decimal fault 映射均不改变。

## TDD、验证与边界

- guard test 先引用缺失的 `CAPABILITY_RUNTIME_RANDOM_REJECTION_ATTEMPTS` 捕获 compile RED，再重放 exact
  `127/128/129`、frozen taxonomy 与 runtime `127/128/129` ceiling。
- 公开 Evaluator 既有回归继续证明 profile `127/129` 拒绝、128 次 exhaustion 在首 demand 返回 exact
  stage/code/limitId、后续 alias 不调用；CapabilityValues known-answer vectors 保持不变。
- focused guard/Evaluator/CapabilityValues/CapabilityDeclarations/architecture、完整受影响 reactor、`render`
  与 `fast`；无 app wiring/API shape/OpenAPI/Web/migration/Profile，不重复 server/full。
- 不发行正式 Ticket 19 Case/Oracle，不实现 fault-schedule executor。provider/API Key/真实数据/费用/
  Profile registration/push/tag/PR 均不推进；最高 `automated_verified`、J0。

## Resolution

- TDD 首轮得到唯一预期 compile RED：guard test 引用了尚不存在的
  `CAPABILITY_RUNTIME_RANDOM_REJECTION_ATTEMPTS`。补入 exact catalog、profile admission 与 exhaustion
  projection 后，guard 38/38、expanded guard/Evaluator/CapabilityValues/CapabilityDeclarations/architecture
  focused 132/132 转绿。未伪造新的行为 RED，因为 T134 已有正确 exact-profile 与 exhaustion 行为，本票只做
  冻结 guard 合流。
- `RenderingPipelineCapacityGuard` 现在独占 random-rejection id、EXACT comparator、
  `CAPABILITY_RESULT_INVALID` 与 public stage `MATERIALIZATION`；profile `127/129` 均 fail closed，`128`
  接受，runtime ceiling 接受第 127/128 次并在 would-be 第 129 次前返回同一 taxonomy。
- `CapabilityBudget` 已删除 `MAX_RANDOM_REJECTION_ATTEMPTS`、旧 `exactLimit` 与本地比较；effective vector
  与 exhaustion limitId 均通过唯一 guard。`CapabilityDerivation` 仍是唯一 HMAC/counter `0…127` 算法，
  guard catalog 直接引用其 `MAX_REJECTION_ATTEMPTS`，主代码中 `128` 数值只定义一次。
- 既有公开 Evaluator 回归继续证明 profile `127/129` 拒绝、128 次 exhaustion 在第一个实际 demand 返回 exact
  code/limitId 且 `supplyCalls=1`、后续 alias 不调用、零 RenderDocument；CapabilityValues known-answer 与
  deterministic per-position vectors 保持绿色。
- 最终受影响 reactor 为 Schema 20/20、Validation 13/13、Template 84/84、Asset 92/92、Rendering 272/272。
  `render` A1 `.sdlc/evidence/20260829-095911-render/`（2/2）与 `fast` A1
  `.sdlc/evidence/20260829-100004-fast/`（3/3）metadata 均 passed；`git diff --check` 通过，重复
  authority 搜索只剩 guard id/reference 与唯一算法 constant/loop。
- cap-046 fixture 只要求 exact production guard，本票没有行为路径专属 A2/A3；render gate 中既有独立 replay
  保持绿色但不冒充 T176 证据。J0 pending、J1 未批准；按非 app-wiring/API-shape 边界未重复 server/full。
  正式 Ticket 19 records/fault-schedule executor 仍未发行，provider attempts、API Key reads、真实数据、费用、
  Profile registration、push/tag/PR 均为 0。
- 状态回填后的 `fast` A1 `.sdlc/evidence/20260829-100110-fast/` metadata 为 passed，3/3 steps 全绿；
  npm 配置 warning 仅写入 stderr，没有改变 step exit code 或 metadata truth。
