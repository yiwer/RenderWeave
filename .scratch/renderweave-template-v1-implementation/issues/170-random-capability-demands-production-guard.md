# T170 — RANDOM Capability demands production guard 合流

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T131, T169 (resolved)

## 目标

物化 Ticket 19 / `RW-T19-S8-027` 与 cap-040：`capabilityRuntime.randomDemands` 使用 MAX_INCLUSIVE `4096`，
observed `4095/4096/4097`，contract stage `CAPABILITY_FIRST_DEMAND`、public stage `MATERIALIZATION`、code
`CAPABILITY_BUDGET_EXCEEDED`、zero boundary `ZERO_DOCUMENT_OUTPUT`。T131 已实现真实 lazy RANDOM admission，
但冻结 probe 要求 exact production guard 且禁止 duplicate guard；本票将该轴合流到唯一
`RenderingPipelineCapacityGuard`，不重复实现 Capability 产品语义。

## seam 与兼容边界

- production guard catalog 成为 RANDOM frozen id/maximum/problem/public-stage 唯一权威；`CapabilityBudget` 删除
  `MAX_RANDOM_DEMANDS` 与本地 RANDOM `wouldExceed` 判断，frozen/effective vector 均从 catalog 取得上限，认证部署
  只能在 `0..4096` 内收紧。
- `reserveDemand` 继续先检查 total，再按实际 kind 仅调用 CLOCK 或 RANDOM guard，随后检查 position；全部 admission
  成功后才提交 total/kind/position counters。RANDOM 超限不提交 total/RANDOM counter、不调用 provider、不产生 result；
  CLOCK demand 不消费 RANDOM 轴。
- alias 重读、Definition memo hit、未选择 branch 与未物化 Definition 仍零 demand。runtime failure 继续通过既有
  closed Expression/Materializer 边界投影 exact `CAPABILITY_BUDGET_EXCEEDED` / `MATERIALIZATION` / limitId；HMAC
  派生、CapabilityState、result digest 与 fingerprint 行为不变。

## TDD、验证与边界

- guard test 先引用缺失的 `CAPABILITY_RUNTIME_RANDOM_DEMANDS` 捕获 compile RED，再重放 `4095/4096/4097`、
  frozen taxonomy、effective tightening 与不可放宽。
- production tracker test 锁定 4096 RANDOM exact-at、第 4097 个 RANDOM 返回 random limitId；失败后 4096 CLOCK 仍可
  将 total 精确推至 8192，证明失败 RANDOM 未部分提交 total。公开 Evaluator 既有 RANDOM effective max `1` 回归继续
  证明第二 demand 前失败且 provider 只调用一次。
- focused guard/CapabilityValues/Evaluator/architecture、完整受影响 reactor、`render` 与 `fast`；无 app wiring/API/
  Web/migration/Profile，不重复 server/full。
- 本票不迁移 cap-041+ position/result/rejection 轴，不改变 effective vector wire、Capability SPI/state store/provider。
  provider/API Key/真实数据/费用/Profile registration/push/tag/PR 均不推进；最高 `automated_verified`、J0。

## Resolution

- 首个有效 TDD run 得到唯一预期 compile RED：guard test 引用了尚不存在的
  `CAPABILITY_RUNTIME_RANDOM_DEMANDS`。补入 catalog 并迁移 production tracker 后，guard + CapabilityValues focused
  45/45 转绿；未伪造新的行为 RED，因为 T131 已有正确 lazy first-demand 产品语义，本票只做冻结 guard 合流。
- `RenderingPipelineCapacityGuard` 现在独占 RANDOM frozen id、MAX_INCLUSIVE `4096`、problem 与 public stage；
  effective maximum 只能在 `0..4096` 内收紧，尝试放宽到 `4097` 会失败。`CapabilityBudget` 已删除重复
  `MAX_RANDOM_DEMANDS` 与本地 RANDOM `wouldExceed` 判断，frozen/effective vector 均从 guard catalog 取得上限。
- request tracker 保持 total guard 先于 kind guard，随后才验证 position；全部 admission 成功后才提交
  total/kind/position counters。4096 RANDOM exact-at 成功，第 4097 个 RANDOM 返回
  `capabilityRuntime.randomDemands`；失败后 4096 CLOCK 仍全部成功并将 total 精确推至 8192，下一 CLOCK 才以 total
  limitId 失败，证明 RANDOM 拒绝没有部分提交 total。公开 Evaluator 既有 effective RANDOM maximum `1` 回归继续
  证明第二 demand 前失败且 provider 只调用一次。
- 最终 expanded focused 123/123；受影响 reactor 为 Schema 20/20、Validation 13/13、Template 84/84、Asset 92/92、
  Rendering 263/263。`render` A1 `.sdlc/evidence/20260829-090506-render/`（2/2）与 `fast` A1
  `.sdlc/evidence/20260829-090556-fast/`（3/3）metadata 均 passed；`git diff --check` 通过。
- cap-040 fixture 只要求 exact production guard，本票没有行为路径专属 A2/A3；render gate 中既有独立 replay 保持
  绿色但不冒充 T170 证据。J0 pending、J1 未批准；按非 app-wiring 边界未重复 server/full。cap-041+ 仍 deferred，
  provider attempts、API Key reads、真实数据、费用、Profile registration、push/tag/PR 均为 0。
- 状态回填后的 `fast` A1 `.sdlc/evidence/20260829-090701-fast/` metadata 为 passed，3/3 steps 全绿；npm 配置
  warning 仅写入 stderr，没有改变 step exit code 或 metadata truth。
