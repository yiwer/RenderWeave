# T131 — 强制 Capability demand/position/result-digest 容量

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T125, T128, T129, T130 (resolved)

## 目标

物化冻结 Ticket 14 §6 与 Ticket 19 的 Capability 静态及首次 demand 容量：完整 closure 的
`staticCapabilitySources`，以及实际惰性 demand 的 `totalDemands`、`clockDemands`、`randomDemands`、
`positionCanonicalBytesPerDemand`、`positionCanonicalBytesTotal` 与 `resultDigestStreamingBytes`。每个首次
demand 必须在调用 provider 或向 Expression 返回结果前 fail-closed 预留；memo hit、未选 branch 与未物化
Definition 不计数，超限零 RenderDocument、零 Engine 调用。

## Interface / seam

- 保持公开 `Evaluator.evaluate(EvaluationCommand)` 为唯一测试与产品 Interface；容量数值继续来自已绑定
  fingerprint 的 `effectiveBudgetVector`，请求不能携带或放宽。
- Rendering internal 新建一个深容量 module：一次解析并验证 capability budget 子向量，封装 static admission、
  request-local 原子计数、position bytes 与 result-digest framed bytes 预留；Materializer/DefinitionEngine 不
  接收散落计数器或上限参数。
- `CapabilityValues` 只在首次 provider supply seam 消费 request-local tracker；超限返回 closed runtime failure，
  Materializer 在所有 Binding/Conditional/Repeat/TemplateUse fill 消费路径统一投影为
  `CAPABILITY_BUDGET_EXCEEDED`、stage `MATERIALIZATION` 与 exact `limitId`。
- static source 超限在 state/input/materialization work 前返回 `CAPABILITY_BUDGET_EXCEEDED`、stage
  `TEMPLATE_CLOSURE`、limitId `capabilityRuntime.staticCapabilitySources`。

## TDD 与边界

- 先从 `Evaluator.evaluate` seam 对 static、total、CLOCK、RANDOM、per-position、position-total 与
  result-streaming 边界分别取得 RED；同时证明 at-limit 成功、超限 demand 不调用 provider、first-fail 后不
  继续 Asset/Capability work，以及 memo/lazy 不重复计数。
- 不在本票实现 CapabilityState record bytes、初始化重试、Random HMAC rejection 故障注入、正式 Ticket 19
  records、route/OpenAPI/migration/Profile；不运行 provider，不读取 API Key，不发送真实数据，不
  push/tag/PR。

## 验证

focused Rendering public seam RED→GREEN；`render`、`fast`、顺序 `server`，根据最终影响面决定是否重跑
`asset`/`web`/`full`。最高 `automated_verified`；A3/J1/READY 不推进。

## Resolution

- 新增 Rendering-internal `CapabilityBudget` 深模块：从已进入 evaluation fingerprint 的
  `effectiveBudgetVector` fail-closed 解析七个 exact capability limit，拒绝缺项、非 canonical integer、负值与
  高于冻结上限的配置，同时允许认证部署收紧数值。
- closure declaration scan 后、Input/CapabilityState work 前执行完整 closure static source admission；动态首次
  demand 在 provider 前原子预留 total、kind 与 position bytes，provider 结果在返回 Expression 前预留 exact
  uint64 frame + canonical entry bytes。未选/未物化 source 为零 demand，invocation/loop Definition memo 不重复消费。
- capability capacity failure 以 closed runtime failure 穿过 Definition/Expression，并由 Binding、Conditional、
  Repeat 与 TemplateUse fill 四类 Materializer consumer 统一投影为 stage `MATERIALIZATION`、
  `CAPABILITY_BUDGET_EXCEEDED` 与 exact `limitId`；static over-limit 保持 stage `TEMPLATE_CLOSURE`。
- TDD 经公开 `Evaluator.evaluate` seam 取得 static 与 dynamic 真实 RED 后 GREEN；最终 focused
  `EvaluatorContractTest` 32/32、Rendering module 157/157，生产 Spring assembly 9/9。A1 gates：
  `render` `.sdlc/evidence/20260828-231330-render/`、`fast`
  `.sdlc/evidence/20260828-231420-fast/`、顺序 `server`
  `.sdlc/evidence/20260828-231456-server/` 均为 metadata `passed`。
- T131 未改 route/OpenAPI/Web/migration/Profile/Renderer/Asset；`full` 会重复已完成的 server，而本票已有
  render + fast + 全 reactor clean server verify，因此不追加。provider/API Key/真实数据/push/tag/PR 均为 0；
  A2 仅来自未变 Renderer 轴独立重放，无 T131-specific issued replay；A3 无，J0/J1 未批准。
- 状态回填后的 resolution `fast` `.sdlc/evidence/20260828-233332-fast/` metadata 仍为 `passed`。
