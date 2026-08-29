# T143 — 强制 request-total Render occurrences 容量

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T142 (resolved)

## 目标

物化 Ticket 19 / `RW-T19-S7-082` 与机器 `cap-012` 已冻结的
`closureAndExpansion.renderOccurrences` 容量轴。合同为 MAX_INCLUSIVE `25000`，observed
`24999/25000/25001`，contract stage `SERIAL_MATERIALIZATION`、公开 stage `MATERIALIZATION`、code
`EVALUATION_BUDGET_EXCEEDED`、zero boundary `ZERO_DOCUMENT_OUTPUT`。第 25001 个 reservation 必须返回完整
limitId，禁止截断或形成 RenderDocument/Engine Command/RenderOutput。

当前 T23 已把最终真实/合成 static occurrence 统一经 `Materializer.reserveMaterializedNode()` 预留；但
`renderOccurrences` 仍由 `Materializer.MAX_RENDER_OCCURRENCES` 与手写比较拥有，并错误投影为
`RENDER_DOCUMENT_LIMIT_EXCEEDED`，违反 Ticket 19 的单一 production guard 与 oracle code。该重复 authority 必须
删除并接入现有 package-internal `renderweave-rendering-pipeline-capacity-guard/1.0`。

## 支配关系与 seam

- `closureAndExpansion.materializedStaticNodes=20000` 是同一实际 materialization path 上更低的独立预算；因此合法
  产品路径会先命中该轴，不能为了直接制造 25001 occurrences 而加入 test override、反射改 counter、跳过 node
  guard 或虚构 placeholder occurrence。
- `cap-012` fixture 的 target contract 明确 `exactProductionGuardRequired=true`、
  `evaluatorOrSealerExecutedByThisProbe=false`。本票按该冻结目标在唯一真实 guard 上隔离重放边界，同时让生产
  `occurrences` counter 调用同一 guard；不把 dominated guard 冒充 full-pipeline A2。
- 保持 T23 已有计数点：每次即将创建真实/合成 materialized static occurrence、进入
  `reserveMaterializedNode()` 时消费一个单位。本票不新增 pre-pruning counter，也不重解释哪些结构最终 surviving；
  `materializedStaticNodes`、generated entries 与 logical operations 继续是独立后续轴。

## TDD 与验证

- 先向 `RenderingPipelineCapacityGuardTest` 加入 `24999/25000/25001` exact contract；生产 catalog 尚无
  `RENDER_OCCURRENCES`，形成可复现 RED。
- 最小增加 closed `RENDER_OCCURRENCES=25000`，删除 `MAX_RENDER_OCCURRENCES` 与手写 code，生产 counter 经
  `capacityFailure(CAPACITY_GUARD.admit(...))` 投影 exact stage/code/limitId。保留较低 static-node first-fail。
- 运行 focused guard/Materializer、完整受影响 reactor、`render` 与 `fast`；无 app wiring/API/Web/migration/
  Profile 变化，不重复发布级 `full`，server 留到周期性容量批次。
- 正式 Ticket 19 records/product executor、后续 materialized/generated/logical/document axes、provider/API Key/
  真实数据/Profile registration/push/tag/PR 均不在本票。最高 `automated_verified`；claim 时 A0、J0。

## Resolution

- 唯一 package-internal `RenderingPipelineCapacityGuard` 已增加 `RENDER_OCCURRENCES=25000`。isolated production
  guard 对 24999/25000 均 admit，25001 返回 exact MATERIALIZATION / EVALUATION_BUDGET_EXCEEDED /
  `closureAndExpansion.renderOccurrences`。
- `Materializer.MAX_RENDER_OCCURRENCES` 与手写 `if` 已删除；request-local `occurrences` counter 在既有
  `reserveMaterializedNode()` 计数点调用唯一 guard，不再错误投影 `RENDER_DOCUMENT_LIMIT_EXCEEDED`。更低的
  `materializedStaticNodes=20000` 检查及其 first-fail 顺序保持不变，无测试绕过或第二份 limit authority。
- TDD RED：只加入 cap-012 exact test 后，test compilation 在三个调用点明确失败于生产 enum 缺少
  `RENDER_OCCURRENCES`；最小 catalog/production 接线后 focused guard/Materializer 19/19。
- 受影响 reactor Schema 20、Validation 13、Template 84、Asset 92、Rendering 198 全绿；`git diff --check` 通过。
  A1 `render` `.sdlc/evidence/20260829-041009-render/`（2/2）与 `fast`
  `.sdlc/evidence/20260829-041059-fast/`（3/3）metadata 均 `passed`。
- cap-012 candidate 明确不执行 Evaluator/Sealer，且尚无正式 product target/executor，故无 T143-specific A2；
  A3 无，J0 pending、J1 未批准。未重复 server/full；provider attempts/API Key reads/真实数据/Profile
  registration/push/tag/PR 均为 0。
- 状态回填后的 resolution `fast` `.sdlc/evidence/20260829-041737-fast/` metadata 为 `passed`，3/3 steps 全绿。
