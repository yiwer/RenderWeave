# T138 — 强制 Template invocation depth 容量

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T137 (resolved)

## 目标

物化 Ticket 19 / `RW-T19-S7-074` 已冻结的
`closureAndExpansion.invocationDepth` 动态容量轴。root invocation frame 的 depth 为 `1`；每次真正进入
TemplateUse child invocation frame 时沿当前路径 `+1`。Repeat loop frame 不增加 Template invocation depth，
同层 sibling invocation 也不累计；`render:false`、结构剪枝与 `contextAbsentPolicy: SKIP` 不创建 child frame，
因此不观察更深路径。

机器 capacity coverage 固定 comparator `MAX_INCLUSIVE`、limit `16`：observed `15/16` 成功，准备创建
depth `17` 的 child invocation/frame 前必须以公开 stage `MATERIALIZATION`、code
`EVALUATION_BUDGET_EXCEEDED`、limitId `closureAndExpansion.invocationDepth` fail closed，零
RenderDocument/Engine/output。该轴与 stage 2 的 `closureAndExpansion.closureDepth` 独立，不得改写为
`TEMPLATE_CLOSURE_LIMIT_EXCEEDED`。

## Interface / seam

- 唯一产品测试 seam 沿用 ADR-0044 的公开 `Evaluator.evaluate(EvaluationCommand)`；不新增 public API、SPI、
  配置档位、内部计数探针或专用 executor。
- `Materializer.InvocationScope` 携带不可变、path-local 的正整数 invocation depth：root 为 `1`，
  `withLoopFrame` 原样保留，surviving TemplateUse 在 child `InvocationScope` 创建前检查 `current + 1`。
- above-limit fixture 由 scripted `TemplateClosureAuthority` 系统边界返回 root-counted 17 层 frozen chain，隔离
  SERIAL_MATERIALIZATION guard，避免独立 closure-freeze depth guard 抢先；15/16 层验证 below/at。
- T137 已有 255 个 sibling TemplateUse 的公开回归继续证明本轴不是 request-global invocation 计数器。

## TDD 与验证

- 第一 tracer：17 层 frozen chain 当前会错误 seal；先固定 exact stage/code/limitId 取得真实 behavioral RED，
  再最小加入 path-local guard。
- 随后补 15/16 层成功回归，并复跑 T137 sibling/SKIP 用例，确认 Repeat、sibling、SKIP 不改变路径深度。
- focused Evaluator 后跑受影响 reactor、`render`、`fast`、顺序 `server`；无 API/OpenAPI/Web/migration/Profile
  变化时不重复发布级 `full`。
- 正式 Ticket 19 records/executor、其他 closure/materialization 容量轴、provider/API Key/真实数据/Profile
  registration/push/tag/PR 均不在本票。最高 `automated_verified`；当前 A1/J0 待验证。

## Resolution

- `Materializer` 现将 root `InvocationScope` 固定为 depth `1`，surviving TemplateUse 在 child frame 创建前计算
  `current + 1`；`withLoopFrame` 原样保留当前 depth。depth guard 与 T137 request-total reservation 收口在同一
  `reserveTemplateInvocation(depth)`，先拒绝超深路径、再提交总 invocation 计数。
- MAX_INCLUSIVE `16`；depth `17` 返回 exact `MATERIALIZATION / EVALUATION_BUDGET_EXCEEDED /
  closureAndExpansion.invocationDepth`，builder 不进入 seal/Engine/output。宽 sibling、SKIP 与 Repeat 既有回归
  保持绿色，确认不是 request-global 累计。
- 公开 `Evaluator.evaluate` TDD：生产代码未改时，depth 17 tracer 得到 `SealedDocument` 而非 `Rejected` 的真实
  behavioral RED；最小实现后该 tracer GREEN，补 depth 15/16 后 focused Evaluator 54/54。
- 受影响 reactor：Schema 20、Validation 13、Template 84、Asset 92、Rendering 179，全部零失败；
  `git diff --check` 通过。
- A1：`render` `.sdlc/evidence/20260829-025933-render/`、`fast`
  `.sdlc/evidence/20260829-030020-fast/`、顺序 `server`
  `.sdlc/evidence/20260829-030048-server/` metadata 均 `passed`。server 8-module BUILD SUCCESS，App 372 tests /
  0 failures / 0 errors / 15 controlled skips；未跑发布级 `full`。
- frozen candidate oracles `ORC::CAPACITY::000184..000186` 不是正式 issued product executor 记录，故不声明
  ticket-specific A2；A3 无，J0 pending、J1 未批准。API/OpenAPI/Web/migration/Profile 变化、provider attempts、
  API Key reads、真实数据、push/tag/PR 均为 0。
- 状态回填后的 resolution `fast` `.sdlc/evidence/20260829-031714-fast/` metadata 仍为 `passed`。
