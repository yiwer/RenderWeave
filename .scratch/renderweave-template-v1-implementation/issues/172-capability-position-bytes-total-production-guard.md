# T172 — Capability position bytes total production guard 合流

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T131, T171 (resolved)

## 目标

物化 Ticket 19 / `RW-T19-S8-029` 与 cap-042：`capabilityRuntime.positionCanonicalBytesTotal` 使用
MAX_INCLUSIVE `16777216`，observed `16777215/16777216/16777217`，contract stage
`CAPABILITY_FIRST_DEMAND`、public stage `MATERIALIZATION`、code `CAPABILITY_BUDGET_EXCEEDED`、zero boundary
`ZERO_DOCUMENT_OUTPUT`。T131 已实现真实 cumulative position-byte admission，但冻结 probe 要求 exact production
guard 且禁止 duplicate guard；本票将 total-position 轴合流到唯一 `RenderingPipelineCapacityGuard`。

## seam 与兼容边界

- production guard catalog 成为 position-total frozen id/maximum/problem/public-stage 唯一权威；
  `CapabilityBudget` 删除 `MAX_POSITION_BYTES_TOTAL` 与本地 `wouldExceed` 判断，frozen/effective vector 均从 catalog
  取得上限，认证部署只能在 `0..16777216` 内收紧。
- `reserveDemand` 保持 total→kind→position-per-demand→position-total 顺序；在 per-demand 已证明非负后，以
  overflow-safe projected cumulative bytes 调用 total-position guard，全部 admission 成功后才提交
  total/kind/position counters。position-total 超限不提交任何 counter、不调用 provider、不产生 result。
- frozen maxima 下 `8192 × 2048 = 16777216`，position-total 与 total/per-demand 同时到顶；production first-failure
  原子测试必须通过已绑定 fingerprint 的 effective vector 收紧 total-position，而不是放宽其他轴或发明旁路。
- runtime failure 继续通过既有 closed Expression/Materializer 边界投影 exact
  `CAPABILITY_BUDGET_EXCEEDED` / `MATERIALIZATION` / limitId；canonicalization、CapabilityState、result digest 与
  fingerprint 行为不变。

## TDD、验证与边界

- guard test 先引用缺失的 `CAPABILITY_RUNTIME_POSITION_CANONICAL_BYTES_TOTAL` 捕获 compile RED，再重放
  `16777215/16777216/16777217`、frozen taxonomy、effective tightening 与不可放宽。
- production tracker 使用 total=4、CLOCK=2、RANDOM=2、position-total=3 的有效收紧 vector：先以 1+2 bytes 精确
  到顶，第 3 个非零 demand 以 position-total 失败；随后 CLOCK 0-byte 与 RANDOM 0-byte 仍可把 demand counters
  精确填满，证明失败未部分提交 total/kind/position，下一 demand 再由 total 先失败。公开 Evaluator 既有
  position-total effective max `1` 回归继续证明 provider 调用为零。
- focused guard/CapabilityValues/Evaluator/architecture、完整受影响 reactor、`render` 与 `fast`；无 app wiring/API/
  Web/migration/Profile，不重复 server/full。
- 本票不迁移 cap-043+ state/result/retry/rejection 轴，不改变 effective vector wire、Capability SPI/state store/
  provider。provider/API Key/真实数据/费用/Profile registration/push/tag/PR 均不推进；最高
  `automated_verified`、J0。

## Resolution

- TDD 首轮得到唯一预期 compile RED：guard test 引用了尚不存在的
  `CAPABILITY_RUNTIME_POSITION_CANONICAL_BYTES_TOTAL`。补入 catalog 并迁移 production tracker 后，guard +
  CapabilityValues focused 49/49 转绿；未伪造新的行为 RED，因为 T131 已有正确 cumulative admission，本票只做
  冻结 guard 合流。
- `RenderingPipelineCapacityGuard` 现在独占 position-total frozen id、MAX_INCLUSIVE `16777216`、problem 与 public
  stage；effective maximum 只能在 `0..16777216` 内收紧，尝试放宽到 `16777217` 会失败。`CapabilityBudget` 已删除
  重复 `MAX_POSITION_BYTES_TOTAL` 与本地 `wouldExceed(positionBytes, ...)` 判断，frozen/effective vector 均从 guard
  catalog 取得上限。
- request tracker 保持 total→kind→per-demand→position-total 顺序；per-demand 已证明非负后，使用 saturating
  projected sum 调用 total-position guard，成功提交复用同一 projected 值，全部 admission 成功后才提交其他 counters。
  有效收紧 vector（total=4、CLOCK=2、RANDOM=2、position-total=3）先以 1+2 bytes 到顶，第 3 个非零 demand 返回
  exact total-position limitId；随后 CLOCK/RANDOM 各一个 0-byte demand 仍精确填满 demand counters，证明拒绝未部分
  提交 total/kind/position，下一 demand 再由 total 先失败。公开 Evaluator effective maximum `1` 回归仍证明 provider
  调用为零。
- 最终 expanded focused 127/127；受影响 reactor 为 Schema 20/20、Validation 13/13、Template 84/84、Asset 92/92、
  Rendering 267/267。`render` A1 `.sdlc/evidence/20260829-091730-render/`（2/2）与 `fast` A1
  `.sdlc/evidence/20260829-091819-fast/`（3/3）metadata 均 passed；`git diff --check` 通过。
- cap-042 fixture 只要求 exact production guard，本票没有行为路径专属 A2/A3；render gate 中既有独立 replay 保持
  绿色但不冒充 T172 证据。J0 pending、J1 未批准；按非 app-wiring 边界未重复 server/full。cap-043+ 仍 deferred，
  provider attempts、API Key reads、真实数据、费用、Profile registration、push/tag/PR 均为 0。
- 状态回填后的 `fast` A1 `.sdlc/evidence/20260829-091922-fast/` metadata 为 passed，3/3 steps 全绿；npm 配置
  warning 仅写入 stderr，没有改变 step exit code 或 metadata truth。
