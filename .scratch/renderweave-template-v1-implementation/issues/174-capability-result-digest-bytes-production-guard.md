# T174 — Capability result-digest streaming bytes production guard 合流

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T131, T173 (resolved)

## 目标

物化 Ticket 19 / `RW-T19-S8-031` 与 cap-044：`capabilityRuntime.resultDigestStreamingBytes` 使用
MAX_INCLUSIVE `16777216`，observed `16777215/16777216/16777217`，contract stage
`CAPABILITY_FIRST_DEMAND`、public stage `MATERIALIZATION`、code `CAPABILITY_BUDGET_EXCEEDED`、zero boundary
`ZERO_DOCUMENT_OUTPUT`。T131 已实现真实 result-digest framed-byte cumulative admission，但冻结 probe 要求 exact
production guard 且禁止 duplicate guard；本票将该轴合流到唯一 `RenderingPipelineCapacityGuard`。

## seam 与兼容边界

- production guard catalog 成为 result-digest frozen id/maximum/problem/public-stage 唯一权威；
  `CapabilityBudget` 删除 `MAX_RESULT_DIGEST_STREAMING_BYTES`、本地 `wouldExceed` 与手写 limitId，
  frozen/effective vector 均从 catalog 取得上限，认证部署只能在 `0..16777216` 内收紧。
- `reserveResultDigestBytes` 继续按 demand encounter order 累计 exact `uint64 frame + canonical entry` bytes；先验证
  非负 increment，再以 overflow-safe projected cumulative bytes 调用 guard，成功后才提交 result-byte counter。
  超限不得 append demand/framed entry，也不得向 Expression 返回 provider result。
- total/kind/position demand 已在 provider 前完成 reservation；result bytes 只能在 provider 产生 closed value 后确定。
  本票保持既有 first-failure 与 `supplyCalls=1` 语义，不退款先前 demand reservation，不改变 digest canonical bytes。
- capability result digest、CapabilityState、fingerprint、provider SPI、memo/lazy 与 error projection 均不改变。

## TDD、验证与边界

- guard test 先引用缺失的 `CAPABILITY_RUNTIME_RESULT_DIGEST_STREAMING_BYTES` 捕获 compile RED，再重放
  `16777215/16777216/16777217`、frozen taxonomy、effective tightening 与不可放宽。
- production tracker 使用 result-digest effective max `3`：先预留 1 byte，4-total 的下一 3 bytes 拒绝，随后
  2 bytes 仍可精确填满，证明失败未部分提交 counter；公开 Evaluator 既有 max `1` 回归继续证明 provider 已调用
  一次但结果未返回、零文档。
- focused guard/CapabilityValues/Evaluator/architecture、完整受影响 reactor、`render` 与 `fast`；无 app wiring/API/
  Web/migration/Profile，不重复 server/full。
- 本票不迁移 cap-045+ initialization/rejection 轴，不改变 effective vector wire。provider/API Key/真实数据/费用/
  Profile registration/push/tag/PR 均不推进；最高 `automated_verified`、J0。

## Resolution

- TDD 首轮得到唯一预期 compile RED：guard test 引用了尚不存在的
  `CAPABILITY_RUNTIME_RESULT_DIGEST_STREAMING_BYTES`。补入 catalog 并迁移 production tracker 后，guard +
  CapabilityValues focused 52/52 转绿；expanded capability/Evaluator/architecture focused 130/130。未伪造新的
  行为 RED，因为 T131 已有正确 cumulative admission，本票只做冻结 guard 合流。
- `RenderingPipelineCapacityGuard` 现在独占 result-digest frozen id、MAX_INCLUSIVE `16777216`、
  `CAPABILITY_BUDGET_EXCEEDED` 与 public stage `MATERIALIZATION`；effective maximum 只能在 `0..16777216` 内
  收紧，尝试放宽到 `16777217` 会失败。`CapabilityBudget` 已删除重复
  `MAX_RESULT_DIGEST_STREAMING_BYTES`、`wouldExceed` 与手写 suffix limitId，frozen/effective vector 和 admission
  均从同一 catalog 取得权威。
- request tracker 先拒绝负 increment，再以 saturating projected sum 调用 guard，成功提交复用同一 projected
  value。effective max `3` 下先接受 1 byte、拒绝下一 3 bytes、再接受 2 bytes 精确到顶，证明失败未部分提交
  counter；公开 Evaluator max `1` 回归继续证明 provider 已调用一次，但超限结果不返回 Expression、零文档。
- 最终受影响 reactor 为 Schema 20/20、Validation 13/13、Template 84/84、Asset 92/92、Rendering 270/270。
  `render` A1 `.sdlc/evidence/20260829-093537-render/`（2/2）与 `fast` A1
  `.sdlc/evidence/20260829-093630-fast/`（3/3）metadata 均 passed；`git diff --check` 通过，重复 authority 搜索为空。
- cap-044 fixture 只要求 exact production guard，本票没有行为路径专属 A2/A3；render gate 中既有独立 replay
  保持绿色但不冒充 T174 证据。J0 pending、J1 未批准；按非 app-wiring 边界未重复 server/full。cap-045+ 仍
  deferred，provider attempts、API Key reads、真实数据、费用、Profile registration、push/tag/PR 均为 0。
- 状态回填后的 `fast` A1 `.sdlc/evidence/20260829-093801-fast/` metadata 为 passed，3/3 steps 全绿；npm 配置
  warning 仅写入 stderr，没有改变 step exit code 或 metadata truth。
