# T167 — static Capability sources production guard 合流

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T131, T166 (resolved)

## 目标

物化 Ticket 19 / `RW-T19-S8-024` 与 cap-037：`capabilityRuntime.staticCapabilitySources` 使用
MAX_INCLUSIVE `4096`，observed `4095/4096/4097`，contract stage `CAPABILITY_STATIC_ADMISSION`、public stage
`TEMPLATE_CLOSURE`、code `CAPABILITY_BUDGET_EXCEEDED`、zero boundary `ZERO_EVALUATION_DOCUMENT_OUTPUT`。
T131 已实现真实 closure static-source admission，但冻结 probe 要求 exact production guard 且禁止 duplicate guard；
本票将该轴合流到唯一 `RenderingPipelineCapacityGuard`，不重复实现已有产品语义。

## seam 与兼容边界

- `RenderingPipelineCapacityGuard.Limit` 成为 frozen id/maximum/problem/public-stage 唯一权威；
  `CapabilityBudget` 删除重复 `MAX_STATIC_SOURCES` 与本地 `>` 判断，但继续解析 fingerprint-bound
  `effectiveBudgetVector`，允许认证部署在 frozen maximum 内收紧本轴。
- production guard 提供经 frozen maximum 校验的 effective maximum admission；默认 fixture 路径仍按 `4096`
  重放，产品路径按已绑定 effective maximum 检查，调用方不能放宽到 frozen maximum 以上。
- `CapabilityDeclarations.scan` 按 closure snapshot / definition / input 的既有确定顺序，在把下一 capability source
  加入 contracts/sourceCount 前逐项调用同一 admission；第一个超限 source 不进入声明聚合，且 Input、Asset、
  CapabilityState、Materialization、Document、Engine 均未开始。
- declaration semantic fault 仍折叠 `RENDER_INTERNAL_ERROR`；capacity fault 保留 frozen
  `CAPABILITY_BUDGET_EXCEEDED` / `TEMPLATE_CLOSURE` / exact limitId。T131 的 lazy demand、state 与 fingerprint
  行为不变。

## TDD、验证与边界

- guard test 先引用缺失的 `CAPABILITY_RUNTIME_STATIC_CAPABILITY_SOURCES` 捕获 compile RED，再重放
  `4095/4096/4097` 与 frozen taxonomy。
- CapabilityDeclarations product test 使用两个静态 source 与 effective maximum `1`，证明第二项在 declaration
  aggregation 前失败；公开 Evaluator 继续证明 tightening `0` 时 state/runtime work 为零。
- focused guard/CapabilityDeclarations/CapabilityBudget/Evaluator/architecture、完整受影响 reactor、`render` 与
  `fast`；无 app wiring/API/Web/migration/Profile，不重复 server/full。
- 本票不迁移 cap-038+ dynamic demand axes，不改变 effective budget vector wire、fingerprint、Capability SPI/state
  store/provider。provider/API Key/真实数据/费用/Profile registration/push/tag/PR 均不推进；最高
  `automated_verified`、J0。

## Resolution

- TDD 首轮先得到 5 个预期 compile RED：缺失 static-source limit、三参 `scan` 与 capacity outcome。补入 guard 后，
  guard 29/29 已绿，但刻意保留的 post-scan 实现产生唯一行为 RED（期望只解释 2 个 snapshot，实际解释 3 个），
  从而证明 total-after-scan 不是冻结 reservation point。
- `RenderingPipelineCapacityGuard` 现在独占 static-source frozen id、MAX_INCLUSIVE `4096`、problem 与 public stage；
  effective maximum 只能在 `0..4096` 内收紧，尝试放宽到 `4097` 会失败。`CapabilityBudget` 已删除重复
  `MAX_STATIC_SOURCES` 和本地总量比较，继续从 fingerprint-bound `effectiveBudgetVector` 解析部署收紧值。
- `CapabilityDeclarations` 在每个已验证 source 加入 aggregate 前调用同一 production guard。effective maximum `1`
  下第一项成功、第二项返回 exact `CAPABILITY_BUDGET_EXCEEDED` / `TEMPLATE_CLOSURE` /
  `capabilityRuntime.staticCapabilitySources`，第三个 snapshot 不再解释；Evaluator 的 maximum `0` 用例继续证明
  state/runtime work 均为零。
- 最终扩展 focused 120/120；受影响 reactor 为 Schema 20/20、Validation 13/13、Template 84/84、Asset 92/92、
  Rendering 257/257。`render` A1 `.sdlc/evidence/20260829-083706-render/`（2/2）与 `fast` A1
  `.sdlc/evidence/20260829-083756-fast/`（3/3）metadata 均 passed；`git diff --check` 通过。
- cap-037 fixture 只要求 exact production guard，本票没有可独立执行的行为路径专属 A2/A3；render gate 中既有
  RenderDocument 独立 replay 83/83 保持绿色，但不冒充 T167 专属 A2。J0 pending、J1 未批准；按非 app-wiring
  边界未重复 server/full。provider attempts、API Key reads、真实数据、费用、Profile registration、push/tag/PR 均为 0。
- 状态回填后的 `fast` A1 `.sdlc/evidence/20260829-084134-fast/` metadata 为 passed，3/3 steps 全绿。
