# T135 — 校正 CapabilityState initializationAttempts exact profile

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T125, T129, T131, T133, T134 (resolved)

## 目标

纠正 T133 对冻结容量轴的错误解释。机器权威
`.scratch/renderweave-template-v1/conformance-capacity-coverage-v1.json` 将
`capabilityRuntime.initializationAttempts` 固定为 comparator `EXACT`、value `3`；Ticket 14 §6–7 同时禁止
caller/Template/deployment 形成未版本化收紧。因此 fingerprint-bound effective budget profile 只接受 canonical
integer `3`，`2/4` 均 fail closed；初始化持续瞬时失败时仍恰好允许三次 establish，之后返回公开 stage
`CAPABILITY_STATE`、code `CAPABILITY_STATE_UNAVAILABLE` 与 exact limitId，并保持零 RenderDocument、零 Engine
command。

## Interface / seam

- 唯一产品行为测试 seam 继续是公开 `Evaluator.evaluate(EvaluationCommand)`；既有
  `RenderingCapabilityRuntime`/`CapabilityStateStore` 只作为 seam 后的 system-boundary adapter，不新增公开方法。
- 深化既有 Rendering-internal `CapabilityBudget`：`initializationAttempts` 与 T134 的
  `randomRejectionAttempts` 一样是 exact profile invariant，不再复用 deployment-tightenable maximum parser。
- T133 已实现的首次 load-before-sample、precommit retry、unknown-commit query-before-resample、固定
  issuedAt/expiresAt、deadline 检查与 fingerprint conflict 语义保持不变。

## TDD 与验证

- 先补公开 Evaluator 构造/执行 seam tracer：effective profile `2/4` 必须 fail closed；其中 `2` 在当前实现会被接受，
  形成真实 behavioral RED。
- 将初始化耗尽 tracer 固定在 exact profile `3`，脚本连续三次 transient establish failure，断言 exact
  stage/code/limitId、`establishCalls=3`、`saveCalls=0` 与零 sealed document；保留第三次成功路径。
- focused Rendering + app assembly/architecture，随后运行 `render`、`fast`、顺序 `server`；无
  API/OpenAPI/Web/migration/Profile 变化时不重复发布级 `full`。最高 `automated_verified`，A3/J1/READY 不推进。
- 不改写 T133 历史提交；不发行正式 Ticket 19 Case/Oracle，不实现 fault-schedule executor，不运行 provider、不读取
  API Key、不发送真实数据，不 push/tag/PR。

## Resolution

- `CapabilityBudget` 现在以现有 exact-profile authority 解析 `initializationAttempts`，只接受 canonical integer
  `3`；`2/4` 均在 Evaluator 装配时 fail closed。T133 的 runtime/store SPI、fingerprint、load-before-sample、
  unknown-commit query、fixed expiry 与 deadline 状态机未改变。
- 初始化持续 transient failure 时恰好执行三次 establish；第四次前返回 stage `CAPABILITY_STATE`、code
  `CAPABILITY_STATE_UNAVAILABLE`、limitId `capabilityRuntime.initializationAttempts`，且 `saveCalls=0`、零
  RenderDocument/Engine。第三次成功路径继续成立。
- 公开 seam TDD 先取得真实 RED：46 tests 中仅 value `2` 未抛 `IllegalArgumentException`；最小一行生产改动后
  Evaluator 46/46、完整 Rendering 171/171、生产 app assembly/architecture 12/12 全绿。`git diff --check` 通过。
- A1 gates：`render` `.sdlc/evidence/20260829-012706-render/`、`fast`
  `.sdlc/evidence/20260829-012758-fast/`、顺序 clean `server`
  `.sdlc/evidence/20260829-012836-server/` metadata 均为 `passed`。server 8-module reactor BUILD SUCCESS，App
  372 tests / 0 failures / 0 errors / 15 skipped；本票无 API/OpenAPI/Web/migration/Profile 变化，故不重复发布级
  `full`。
- A2 仅来自 `render` 中未变 Renderer 轴的独立 replay，无 T135-specific issued record；A3 无，J0 pending、J1
  未批准。provider attempts/API Key reads/reservations/cost/真实数据/Profile registration/push/tag/PR 均为 0。
- 状态回填后的 resolution `fast` `.sdlc/evidence/20260829-014454-fast/` metadata 仍为 `passed`，3/3 steps
  全绿。
