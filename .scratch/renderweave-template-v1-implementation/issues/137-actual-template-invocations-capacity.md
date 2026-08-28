# T137 — 强制 actual Template invocations 容量

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T136 (resolved)

## 目标

物化 Ticket 19 已冻结的 `closureAndExpansion.actualTemplateInvocations` 动态容量轴。一次 Evaluation 的 root
invocation/frame 计一份；每个通过 `render` 与 `contextSelector/contextAbsentPolicy`、fills 成功并即将创建 child
frame 的实际 TemplateUse occurrence 再计一份。`render:false`、false/empty structure 与 `SKIP` 不建立 invocation，
因此不消费本轴；shared snapshot 仍按每个实际 occurrence 独立计数。

MAX_INCLUSIVE `256`：observed `255/256` 成功，创建第 `257` 个 invocation 前必须以公开 stage
`MATERIALIZATION`、code `EVALUATION_BUDGET_EXCEEDED`、limitId
`closureAndExpansion.actualTemplateInvocations` fail closed，零 RenderDocument/Engine/output。该轴不是 closure freeze
容量，禁止使用 `TEMPLATE_CLOSURE_LIMIT_EXCEEDED`。

## Interface / seam

- 唯一产品测试 seam 为 ADR-0044 已冻结的公开 `Evaluator.evaluate(EvaluationCommand)`；不新增 public API、SPI、
  配置档位、内部计数探针或专用 capacity executor。
- 实现深化既有 Rendering-owned `Materializer` request-local counter：root frame 前预留一次；child fills 成功后、
  child `InvocationScope` 创建前预留。失败原子丢弃 materialization builder，不进入 seal。
- 公开 boundary fixture 以同一 child snapshot 的 unique TemplateUse occurrences 隔离本轴：root+254/255/256 child
  分别形成 observed `255/256/257`；256 child compositionViewports 恰好 at-limit，authored edges 恰好 256，均不抢先。

## TDD 与验证

- 第一 tracer：root+256 child 当前会错误成功；固定 exact stage/code/limitId 后取得 behavioral RED，再最小加入 root
  reservation 并纠正 stable code。
- 第二 tracer：大量 `contextAbsentPolicy: SKIP` occurrence 加一个实际 child 当前会错误消费 invocation budget；取得
  RED 后把 child reservation 移到 fills 成功与 frame 创建之间。随后补 below/at success 回归。
- focused Evaluator，再跑完整 Rendering、`render`、`fast`、顺序 `server`；无 API/OpenAPI/Web/migration/Profile
  变化时不重复发布级 `full`。
- 正式 Ticket 19 records/executor、invocation depth/compositionViewport/Repeat/其他 materialization 容量轴、
  provider/API Key/真实数据/Profile registration/push/tag/PR 均不在本票。最高 `automated_verified`；A1、J0。

## Resolution

- `Materializer` 现于 root `InvocationScope` 前及每个 surviving child `InvocationScope` 前调用同一 request-local
  reservation；MAX_INCLUSIVE `256`，第 257 次返回 `MATERIALIZATION / EVALUATION_BUDGET_EXCEEDED /
  closureAndExpansion.actualTemplateInvocations`，不进入 seal/Engine/output。
- child reservation 位于 selector 非 SKIP、fills 成功之后。同步纠正 canonical DSL 的
  `contextSelector.contextAbsentPolicy` 读取位置，使 SKIP 真正短路且不消费 invocation budget。
- 公开 `Evaluator.evaluate` TDD：首个 48-test run 以 above-limit 错误 seal 形成 1 个真实 RED；最小 root/code
  修复后 48/48。第二 tracer 先暴露 nested SKIP policy 读取错误，纠正后精确形成 invocation-budget RED；后移
  reservation 后 49/49，补 below/at 后最终 51/51。
- 受影响 reactor：Schema 20、Validation 13、Template 84、Asset 92、Rendering 176，全部零失败；
  `git diff --check` 通过。
- A1：`render` `.sdlc/evidence/20260829-023216-render/`、`fast`
  `.sdlc/evidence/20260829-023305-fast/`、顺序 `server`
  `.sdlc/evidence/20260829-023333-server/` metadata 均 `passed`。server 8-module reactor BUILD SUCCESS，App
  372 tests / 0 failures / 0 errors / 15 controlled skips；未跑发布级 `full`。
- 无 T137-specific formally issued record，故不声明 ticket-specific A2；A3 无，J0 pending、J1 未批准。
  API/OpenAPI/Web/migration/Profile 变化、provider attempts、API Key reads、真实数据、push/tag/PR 均为 0。
- 状态回填后的 resolution `fast` `.sdlc/evidence/20260829-025035-fast/` metadata 仍为 `passed`，3/3 steps
  全绿。
