# T169 — CLOCK Capability demands production guard 合流

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T131, T168 (resolved)

## 目标

物化 Ticket 19 / `RW-T19-S8-026` 与 cap-039：`capabilityRuntime.clockDemands` 使用 MAX_INCLUSIVE `4096`，
observed `4095/4096/4097`，contract stage `CAPABILITY_FIRST_DEMAND`、public stage `MATERIALIZATION`、code
`CAPABILITY_BUDGET_EXCEEDED`、zero boundary `ZERO_DOCUMENT_OUTPUT`。T131 已实现真实 lazy CLOCK admission，
但冻结 probe 要求 exact production guard 且禁止 duplicate guard；本票将该轴合流到唯一
`RenderingPipelineCapacityGuard`，不重复实现 Capability 产品语义。

## seam 与兼容边界

- production guard catalog 成为 CLOCK frozen id/maximum/problem/public-stage 唯一权威；`CapabilityBudget` 删除
  `MAX_CLOCK_DEMANDS` 与本地 CLOCK `wouldExceed` 判断，frozen/effective vector 均从 catalog 取得上限，认证部署
  只能在 `0..4096` 内收紧。
- `reserveDemand` 继续先检查 total，再仅对 CLOCK 以 overflow-safe `clockDemands + 1` 调用 CLOCK guard，随后检查
  RANDOM 与 position；全部 admission 成功后才提交 total/kind/position counters。
- CLOCK 超限不提交 total 或 CLOCK counter、不调用 provider、不产生 result；RANDOM demand 不消费 CLOCK 轴。
  alias 重读、Definition memo hit、未选择 branch 与未物化 Definition 仍零 demand。
- runtime failure 继续通过既有 closed Expression/Materializer 边界投影 exact
  `CAPABILITY_BUDGET_EXCEEDED` / `MATERIALIZATION` / limitId；Clock snapshot、CapabilityState、result digest 与
  fingerprint 行为不变。

## TDD、验证与边界

- guard test 先引用缺失的 `CAPABILITY_RUNTIME_CLOCK_DEMANDS` 捕获 compile RED，再重放 `4095/4096/4097`、
  frozen taxonomy、effective tightening 与不可放宽。
- production tracker test 锁定 4096 CLOCK exact-at、第 4097 个 CLOCK 返回 clock limitId；失败后 4096 RANDOM 仍可
  将 total 精确推至 8192，证明失败 CLOCK 未部分提交 total。公开 Evaluator 既有 CLOCK effective max `1` 回归继续
  证明第二 alias 前失败且 provider 只调用一次。
- focused guard/CapabilityValues/Evaluator/architecture、完整受影响 reactor、`render` 与 `fast`；无 app wiring/API/
  Web/migration/Profile，不重复 server/full。
- 本票不迁移 cap-040+ RANDOM/position/result 轴，不改变 effective vector wire、Capability SPI/state store/provider。
  provider/API Key/真实数据/费用/Profile registration/push/tag/PR 均不推进；最高 `automated_verified`、J0。

## Resolution

- TDD 首轮得到唯一预期 compile RED：guard test 引用了尚不存在的
  `CAPABILITY_RUNTIME_CLOCK_DEMANDS`。补入 catalog 并迁移 production tracker 后，guard + CapabilityValues focused
  43/43 转绿；未伪造新的行为 RED，因为 T131 已有正确 lazy first-demand 产品语义，本票只做冻结 guard 合流。
- `RenderingPipelineCapacityGuard` 现在独占 CLOCK frozen id、MAX_INCLUSIVE `4096`、problem 与 public stage；
  effective maximum 只能在 `0..4096` 内收紧，尝试放宽到 `4097` 会失败。`CapabilityBudget` 已删除重复
  `MAX_CLOCK_DEMANDS` 与本地 CLOCK `wouldExceed` 判断，frozen/effective vector 均从 guard catalog 取得上限。
- request tracker 保持 total guard 先于 CLOCK guard，随后才验证 RANDOM 与 position；全部 admission 成功后才提交
  total/kind/position counters。4096 CLOCK exact-at 成功，第 4097 个 CLOCK 返回
  `capabilityRuntime.clockDemands`；失败后 4096 RANDOM 仍全部成功并将 total 精确推至 8192，下一 RANDOM 才以 total
  limitId 失败，证明 CLOCK 拒绝没有部分提交 total。公开 Evaluator 既有 effective CLOCK maximum `1` 回归继续证明
  第二 alias 前失败且 provider 只调用一次。
- 最终 expanded focused 121/121；受影响 reactor 为 Schema 20/20、Validation 13/13、Template 84/84、Asset 92/92、
  Rendering 261/261。`render` A1 `.sdlc/evidence/20260829-085649-render/`（2/2）与 `fast` A1
  `.sdlc/evidence/20260829-085736-fast/`（3/3）metadata 均 passed；`git diff --check` 通过。
- cap-039 fixture 只要求 exact production guard，本票没有行为路径专属 A2/A3；render gate 中既有独立 replay 保持
  绿色但不冒充 T169 证据。J0 pending、J1 未批准；按非 app-wiring 边界未重复 server/full。cap-040+ 仍 deferred，
  provider attempts、API Key reads、真实数据、费用、Profile registration、push/tag/PR 均为 0。
- 状态回填后的 `fast` A1 `.sdlc/evidence/20260829-085957-fast/` metadata 为 passed，3/3 steps 全绿；npm 配置
  warning 仅写入 stderr，没有改变 step exit code 或 metadata truth。
