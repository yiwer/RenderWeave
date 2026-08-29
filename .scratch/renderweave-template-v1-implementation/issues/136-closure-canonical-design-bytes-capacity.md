# T136 — 强制 closure canonical DesignDSL bytes 容量

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T135 (resolved)

## 目标

物化 Ticket 19 已冻结的 `closureAndExpansion.closureCanonicalDesignBytes` 容量轴。一次完整
closure freeze attempt 中，每份 unique TemplateSnapshot 的 exact canonical DesignDSL bytes 只计一次；
在加入下一份 snapshot 的 byte length 前原子预留，累计 `33,554,432` bytes 为 inclusive success，
`33,554,433` 必须返回 `ClosureLimitExceeded("closureCanonicalDesignBytes")`。diamond 重用不重复计费，
current-drift 新 attempt 从零重建 request-local 计数。

Rendering 必须把 Template-owned suffix key 映射为公开机器权威 limitId
`closureAndExpansion.closureCanonicalDesignBytes`，并以 stage `TEMPLATE_CLOSURE`、code
`TEMPLATE_CLOSURE_LIMIT_EXCEEDED`、零 CapabilityState/RenderDocument/Engine fail closed。

## Interface / seam

- 容量实现留在既有 Template-owned deep module `CanonicalTemplateClosureAuthority`；不新增 public API、SPI、
  persistence 字段或可配置档位。
- 权威边界测试经 ADR-0044 已冻结的公开 `TemplateClosureAuthority.freezeClosure` seam；DesignDSL admission
  仅作为外部边界脚本提供 exact 已准入 canonical bytes，以隔离本轴而不让单 snapshot 16 MiB 轴抢先失败。
- 公开问题投影经既有 `Evaluator.evaluate(EvaluationCommand)` seam 验证；Rendering 统一为 closure capacity
  suffix 加 `closureAndExpansion.` namespace，不在 Template module 泄漏 Rendering problem 类型。

## TDD 与验证

- 先固定 below/at/above exact observed values `33,554,431/32/33`；above 在当前实现会错误 freeze 成功，形成
  behavioral RED。另固定 Evaluator 的 exact stage/code/full limitId；当前 suffix 直通形成第二个 RED。
- 最小 GREEN：每个 freeze attempt 持有 overflow-safe `long` 累计器，在 snapshot 加入 closure map 前完成
  inclusive reservation；Rendering 只修正既有 limit mapping。
- focused Template authority + Rendering evaluator，随后运行 `template`、`render`、`fast`、顺序 `server`；无
  app-wiring/API/OpenAPI/Web/migration/Profile 变化时不重复发布级 `full`。
- 正式 Ticket 19 Case/Oracle、fixture executor、其他 closure/materialization 容量轴、provider/API Key/真实数据、
  Profile registration、push/tag/PR 均不在本票；最高 `automated_verified`，A3/J1/READY 不推进。登记时 A0、J0。

## Resolution

- `CanonicalTemplateClosureAuthority` 现在为每次完整 freeze attempt 建立 overflow-safe request-local byte
  budget；每份 unique snapshot 在写入 closure map 前按 exact admitted canonical DesignDSL byte length 预留一次。
  `33,554,432` inclusive success，下一 byte 返回 suffix
  `ClosureLimitExceeded("closureCanonicalDesignBytes")`；diamond reuse 不重复计费，current-drift 新 attempt 从零重建。
- `CanonicalEvaluator` 将所有 Template-owned closure capacity suffix 统一投影到
  `closureAndExpansion.*`；本轴公开失败为 stage `TEMPLATE_CLOSURE`、code
  `TEMPLATE_CLOSURE_LIMIT_EXCEEDED`、limitId
  `closureAndExpansion.closureCanonicalDesignBytes`，且发生在 CapabilityState/RenderDocument/Engine 之前。
- 公开 seam TDD 取得两个真实 RED：Template focused 18 tests 中 above-limit 错误 freeze 成功；Evaluator 47 tests
  中 limitId 错误保留 suffix。最小 GREEN 后 Template focused 20/20、Evaluator 47/47；受影响 reactor 的 Schema
  20、Validation 13、Template 84、Asset 92、Rendering 172 tests 均零失败，`git diff --check` 通过。
- A1 gates：`template` `.sdlc/evidence/20260829-015926-template/`、`render`
  `.sdlc/evidence/20260829-015956-render/`、`fast` `.sdlc/evidence/20260829-020045-fast/`、顺序
  `server` `.sdlc/evidence/20260829-020118-server/` metadata 均为 `passed`。server 8-module reactor BUILD SUCCESS，
  App 372 tests / 0 failures / 0 errors / 15 controlled skips；本票无 API/OpenAPI/Web/migration/Profile 变化，故不跑
  发布级 `full`。
- `template`/`render` 保留未变轴的独立 replay，但无 T136-specific formally issued record，故本票不声明 A2；
  A3 无，J0 pending、J1 未批准。provider attempts/API Key reads/reservations/cost/真实数据/Profile registration/
  push/tag/PR 均为 0。
- 状态回填后的 resolution `fast` `.sdlc/evidence/20260829-021815-fast/` metadata 仍为 `passed`，3/3 steps
  全绿。
