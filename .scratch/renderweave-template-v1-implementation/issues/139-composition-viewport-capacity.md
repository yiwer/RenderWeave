# T139 — 强制 compositionViewport 容量与生产 guard seam

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T137, T138 (resolved)

## 目标

物化 Ticket 19 / `RW-T19-S7-075` 已冻结的
`closureAndExpansion.compositionViewports` 动态容量轴。每个通过结构惰性、selector 与 fills，并准备把实际
TemplateUse 降低为静态 `compositionViewport` 的 occurrence 消耗一份 request-local 预算；SKIP、结构剪枝与
`render:false` 不消费。MAX_INCLUSIVE `256`，observed `255/256` admitted，`257` 必须以公开 stage
`MATERIALIZATION`、code `EVALUATION_BUDGET_EXCEEDED`、完整 limitId fail closed，零 RenderDocument/Engine/output。

该轴在完整 v1 Evaluation 中会被同为 `256`、且 root-counted 的 `actualTemplateInvocations` 更早拒绝；冻结机器
夹具因此明确使用 `RENDERING_PIPELINE_CAPACITY_GUARD` 隔离 entrypoint，且声明本 probe 不执行
Evaluator/Sealer。禁止构造跨轴非法 payload、放宽 invocation 上限或把被支配关系误写为无需生产 guard。

## Interface / seam

- 物化 package-internal、无 I/O 的 `renderweave-rendering-pipeline-capacity-guard/1.0` 深模块；其窄 Interface 接收
  closed limit identity 与 authoritative observed value，返回 admitted 或 exact public problem。它不是产品 API、
  SPI、配置档位、测试后门或正式 Ticket 19 executor。
- `Materializer` 保留 authoritative request-local counter，在创建下一 viewport 前把 observed value交给同一 guard。
  已有 actual-invocation 与 invocation-depth 判断机械迁入该 guard，避免同一 limit 并存重复实现；计数来源、顺序与
  产品终态不变。
- 隔离边界测试直接跨冻结 guard seam 重放 `255/256/257`；产品 `Evaluator.evaluate` 回归证明现有可达的 255 个
  viewport、SKIP 与 invocation/depth 终态不漂移。不会新增可绕过其他轴的 Evaluator profile override。

## TDD 与验证

- 先加入 guard contract tracer，在生产 seam 尚不存在时取得 RED，再最小实现 exact catalog 与
  compositionViewport limit。
- 随后把 `Materializer` 的 viewport reservation 接到该 seam，并机械迁移 T137/T138 两个既有 guard；复跑 focused
  guard/Evaluator 与完整 Rendering。
- 运行 `render` 与 `fast`；本票无 API/OpenAPI/Web/migration/Profile/app wiring 变化，不重复发布级 `full`，server
  留给周期性容量批次。
- 正式 Ticket 19 records/product executor、其余 Repeat/materialization 轴、route/OpenAPI/Web/Profile、provider/
  API Key/真实数据/push/tag/PR 均不在本票。最高 `automated_verified`；当前 A0、J0。

## Resolution

- 已新增 package-internal `RenderingPipelineCapacityGuard`，以 closed `Limit` catalog 集中保存
  actual-template-invocations `256`、invocation-depth `16` 与 composition-viewports `256`，统一投影 exact
  `MATERIALIZATION / EVALUATION_BUDGET_EXCEEDED / full limitId`。它无 I/O、非 public、非 SPI、非配置或测试
  override。
- `Materializer` 在每个 surviving TemplateUse 的 invocation reservation 之后、viewport materialized-node reservation
  之前累计 authoritative viewport observed value并调用同一 guard；SKIP/render:false 既有短路不进入该位置。
  T137/T138 原内联阈值已机械迁入唯一 catalog，既有计数来源与 failure ordering 保持不变。
- TDD：新 guard tracer 在生产类缺失时以 4 个 javac symbol error 真实 RED；最小实现后 `255/256` admitted、`257`
  返回 exact terminal，1/1 GREEN。随后 guard + Evaluator + architecture 61/61；受影响 reactor Schema 20、
  Validation 13、Template 84、Asset 92、Rendering 180 均零失败，`git diff --check` 通过。
- A1 `render` `.sdlc/evidence/20260829-032705-render/` 与 `fast`
  `.sdlc/evidence/20260829-032818-fast/` metadata 均 `passed`；render 2/2 steps，fast 3/3 steps。无 app wiring/
  API/Web/migration/Profile 变化，按快速迭代约定未重复 server/full。
- isolated candidate fixture 尚无正式 product target/executor replay，故不声明 T139-specific A2；A3 无，J0 pending、
  J1 未批准。provider attempts/API Key reads/真实数据/Profile registration/push/tag/PR 均为 0。
- 状态回填后的 resolution `fast` `.sdlc/evidence/20260829-032929-fast/` metadata 仍为 `passed`，3/3 steps
  全绿。
