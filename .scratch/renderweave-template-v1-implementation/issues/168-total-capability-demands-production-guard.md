# T168 — total Capability demands production guard 合流

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T131, T167 (resolved)

## 目标

物化 Ticket 19 / `RW-T19-S8-025` 与 cap-038：`capabilityRuntime.totalDemands` 使用
MAX_INCLUSIVE `8192`，observed `8191/8192/8193`，contract stage `CAPABILITY_FIRST_DEMAND`、public stage
`MATERIALIZATION`、code `CAPABILITY_BUDGET_EXCEEDED`、zero boundary `ZERO_DOCUMENT_OUTPUT`。T131 已实现真实
lazy first-demand admission，但冻结 probe 要求 exact production guard 且禁止 duplicate guard；本票将 total-demand
轴合流到 T167 已建立的唯一 `RenderingPipelineCapacityGuard`，不重复实现 Capability 产品语义。

## seam 与兼容边界

- `RenderingPipelineCapacityGuard.Limit` 成为 total-demand frozen id/maximum/problem/public-stage 唯一权威；
  `CapabilityBudget` 删除重复 `MAX_TOTAL_DEMANDS` 与本地 total `wouldExceed` 判断，但继续解析 fingerprint-bound
  `effectiveBudgetVector`，认证部署只能在 frozen maximum 内收紧。
- 每个首次 demand 按既有确定 consumer 顺序，在 provider/state result 与 Expression 返回前，以 overflow-safe
  `totalDemands + 1` 调用同一 production guard。total、kind 与 position admission 全部成功后才共同提交 counters，
  任一失败不产生 demand/result，也不调用 provider。
- total 轴先于 kind 与 position 轴检查，保持 T131 first-failure 顺序；alias 重读、Definition memo hit、未选择 branch
  与未物化 Definition 仍不进入 `reserveDemand`，不消耗预算。
- runtime failure 继续通过既有 closed Expression/Materializer 边界投影 exact
  `CAPABILITY_BUDGET_EXCEEDED` / `MATERIALIZATION` / limitId；CapabilityState、result digest、Clock/Random 与
  fingerprint 行为不变。

## TDD、验证与边界

- guard test 先引用缺失的 `CAPABILITY_RUNTIME_TOTAL_DEMANDS` 捕获 compile RED，再重放 `8191/8192/8193`、
  frozen taxonomy、effective tightening 与不可放宽。
- production tracker test 锁定 4096 CLOCK + 4096 RANDOM exact-at，下一 demand 在 kind/position/provider 前以 total
  limitId 失败；公开 Evaluator 既有 distinct-alias 用例继续证明 effective maximum `1` 时只调用一次 provider。
- focused guard/CapabilityBudget/CapabilityValues/Evaluator/architecture、完整受影响 reactor、`render` 与 `fast`；
  无 app wiring/API/Web/migration/Profile，不重复 server/full。
- 本票不迁移 cap-039+ kind/position/result dynamic axes，不改变 effective budget vector wire、Capability SPI/state
  store/provider。provider/API Key/真实数据/费用/Profile registration/push/tag/PR 均不推进；最高
  `automated_verified`、J0。

## Resolution

- TDD 首轮得到唯一预期 compile RED：缺失 `CAPABILITY_RUNTIME_TOTAL_DEMANDS`。补入 catalog 并迁移 production
  tracker 后，guard + CapabilityValues focused 41/41 转绿；未伪造新的行为 RED，因为 T131 已有正确 first-demand
  产品语义，本票是冻结 guard 合流。
- `RenderingPipelineCapacityGuard` 现在独占 total-demand frozen id、MAX_INCLUSIVE `8192`、problem 与 public stage；
  effective maximum 只能在 `0..8192` 内收紧，尝试放宽到 `8193` 会失败。`CapabilityBudget` 已删除重复
  `MAX_TOTAL_DEMANDS` 与本地 total `wouldExceed` 判断，frozen/effective vector 均从 guard catalog 取得上限。
- request tracker 在 kind/position/provider 前以 `totalDemands + 1` 调用同一 guard，随后才验证 kind 与 position，
  全部 admission 成功后才提交 total/kind/position counters。4096 CLOCK + 4096 RANDOM exact-at 成功，下一 CLOCK
  demand 先返回 `capabilityRuntime.totalDemands`；公开 Evaluator 既有 effective maximum `1` 回归继续证明第二 alias
  前失败且 provider 只调用一次，memo/lazy 行为未变。
- 最终 expanded focused 119/119；受影响 reactor 为 Schema 20/20、Validation 13/13、Template 84/84、Asset 92/92、
  Rendering 259/259。`render` A1 `.sdlc/evidence/20260829-084840-render/`（2/2）与 `fast` A1
  `.sdlc/evidence/20260829-084928-fast/`（3/3）metadata 均 passed；`git diff --check` 通过。
- cap-038 fixture 只要求 exact production guard，本票没有行为路径专属 A2/A3；render gate 中既有独立 replay 保持
  绿色但不冒充 T168 证据。J0 pending、J1 未批准；按非 app-wiring 边界未重复 server/full。provider attempts、
  API Key reads、真实数据、费用、Profile registration、push/tag/PR 均为 0。
- 最终 overflow-safe counter 与状态回填后的 `fast` A1 `.sdlc/evidence/20260829-085209-fast/` metadata 为 passed，
  3/3 steps 全绿。
