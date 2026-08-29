# T173 — CapabilityState record bytes production guard 合流

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T132, T172 (resolved)

## 目标

物化 Ticket 19 / `RW-T19-S8-030` 与 cap-043：`capabilityRuntime.capabilityStateRecordBytes` 使用
MAX_INCLUSIVE `1048576`，observed `1048575/1048576/1048577`，contract stage
`CAPABILITY_STATE_INITIALIZATION`、public stage `CAPABILITY_STATE`、code `CAPABILITY_BUDGET_EXCEEDED`、
reservation point `before committing CapabilityState`、zero boundary `ZERO_DOCUMENT_OUTPUT`。T132 已实现真实
state-record bytes pre-commit admission，但冻结 probe 要求 exact production guard 且禁止 duplicate guard；本票将该轴
合流到唯一 `RenderingPipelineCapacityGuard`。

## seam 与兼容边界

- production guard catalog 成为 state-record frozen id/maximum/problem/public-stage 唯一权威；`CapabilityBudget`
  删除 `MAX_CAPABILITY_STATE_RECORD_BYTES` 与手写 `recordBytes > limit` 判断，frozen/effective vector 均从 catalog
  取得上限，认证部署只能在 `0..1048576` 内收紧。
- `CanonicalEvaluator` 继续在 runtime establish 成功后、构造 `SaveRequest` 与调用 `CapabilityStateStore.save` 前，
  将 opaque `sealedState.length` 交给 `CapabilityBudget`。超限必须保留 exact stage/code/limitId、零 store write、
  零 RenderDocument、零 Engine command；已线性化 replay 不重复建立或收费。
- state wire、fingerprint、encryption、expiry、retry/unknown-commit、runtime restore、Capability SPI 均不改变；
  本票只消除 duplicate guard authority，不重写 T132/T133 行为。

## TDD、验证与边界

- guard test 先引用缺失的 `CAPABILITY_RUNTIME_CAPABILITY_STATE_RECORD_BYTES` 捕获 compile RED，再重放
  `1048575/1048576/1048577`、frozen taxonomy、effective tightening 与不可放宽。
- production admission 回归继续经公开 `Evaluator.evaluate` 证明 effective exact-at 成功、above-limit 在 store 前
  fail closed；补充 frozen `1048576` 最大值来自 catalog 的结构证明，禁止残留重复常量与手写比较。
- focused guard/Evaluator/architecture、完整受影响 reactor、`render` 与 `fast`；无 app wiring/API/Web/migration/
  Profile，不重复 server/full。
- 本票不迁移 cap-044+ result-digest/retry/rejection 轴，不改变 effective vector wire。provider/API Key/真实数据/
  费用/Profile registration/push/tag/PR 均不推进；最高 `automated_verified`、J0。

## Resolution

- TDD 首轮得到唯一预期 compile RED：guard test 引用了尚不存在的
  `CAPABILITY_RUNTIME_CAPABILITY_STATE_RECORD_BYTES`。补入 catalog 并迁移 production admission 后，guard +
  公开 Evaluator focused 103/103 转绿；expanded capability/architecture focused 128/128。未伪造新的行为 RED，
  因为 T132 已有正确 pre-commit admission，本票只做冻结 guard 合流。
- `RenderingPipelineCapacityGuard` 现在独占 state-record frozen id、MAX_INCLUSIVE `1048576`、
  `CAPABILITY_BUDGET_EXCEEDED` 与 public stage `CAPABILITY_STATE`；effective maximum 只能在 `0..1048576` 内
  收紧，尝试放宽到 `1048577` 会失败。`CapabilityBudget` 已删除重复
  `MAX_CAPABILITY_STATE_RECORD_BYTES` 与手写 `recordBytes > limit` 判断，frozen/effective vector 和 admission
  均从同一 catalog 取得权威。
- `CanonicalEvaluator` 的真实顺序保持 runtime establish → opaque `sealedState.length` admission → SaveRequest/store；
  既有公开 seam 继续证明 effective exact-at 成功，above-limit 返回 exact stage/code/limitId 且 `saveCalls=0`。
  replay、retry/unknown-commit、restore、fingerprint、expiry 与 state wire 均未改变。
- 最终受影响 reactor 为 Schema 20/20、Validation 13/13、Template 84/84、Asset 92/92、Rendering 268/268。
  `render` A1 `.sdlc/evidence/20260829-092526-render/`（2/2）与 `fast` A1
  `.sdlc/evidence/20260829-092653-fast/`（3/3）metadata 均 passed；`git diff --check` 通过，重复 authority 搜索为空。
- cap-043 fixture 只要求 exact production guard，本票没有行为路径专属 A2/A3；render gate 中既有独立 replay
  保持绿色但不冒充 T173 证据。J0 pending、J1 未批准；按非 app-wiring 边界未重复 server/full。cap-044+ 仍
  deferred，provider attempts、API Key reads、真实数据、费用、Profile registration、push/tag/PR 均为 0。
- 状态回填后的 `fast` A1 `.sdlc/evidence/20260829-092820-fast/` metadata 为 passed，3/3 steps 全绿；npm 配置
  warning 仅写入 stderr，没有改变 step exit code 或 metadata truth。
