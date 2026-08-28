# T126 — 物化 Rendering closure outcome 精确 problem taxonomy

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T125 (resolved)

## 目标

在现有 `Evaluator` 公共 seam 内保留 Template closure 所属领域的稳定具体 code，不再将
root missing、root deleted 与 dependency invalid 统一伪装为 `EVALUATION_FAILED`，也不将
authority unavailable 误折叠为 `RENDER_INTERNAL_ERROR`。

## Interface / seam

- 唯一观察边界为 `Evaluator.evaluate(EvaluationCommand)` 返回的 closed `EvaluationOutcome`。
- `TemplateClosureAuthority` 仍独占 closure outcome 值类型；Rendering 只做显式、穷尽的边界映射。
- 稳定 code 复用 ADR-0042 已冻结语汇：`TEMPLATE_NOT_FOUND`、`TEMPLATE_DELETED`、
  `TEMPLATE_DEPENDENCY_ERROR`、`TEMPLATE_AUTHORITY_UNAVAILABLE`。Integrity 仍折叠
  `RENDER_INTERNAL_ERROR`，current drift 仍为 `TEMPLATE_CLOSURE_UNSTABLE`。

## TDD 与验证

以 scripted outbound `TemplateClosureAuthority` 作为系统边界，通过 Evaluator 公共 seam 按纵切
RED→GREEN 覆盖四个 outcome，并保留 integrity/unstable 回归。随后运行 focused Rendering、
`fast`、顺序 `server`、Goal `full`、resolution `fast`，回填 A1/A2/A3 与 J0/J1，
独立 commit。不改 OpenAPI/Web/route/migration/Profile，不运行 provider，不 push/tag/PR。

## Resolution

- `ClosureNotFound`、`ClosureDeleted`、`ClosureDependencyInvalid` 与 `ClosureUnavailable`
  分别映射为冻结的 Template 领域 code；integrity 与 current-drift 既有映射保持不变。
- Evaluator 公共 seam 14/14、Rendering 127/127 通过；`render`、`fast` 与顺序 `server`
  分别见 `.sdlc/evidence/20260828-145104-render/`、`20260828-145202-fast/`、
  `20260828-161736-server/`。
- 两次当前 `full` 的非浏览器步骤均通过；浏览器阶段受本机 TCP `ERR_NO_BUFFER_SPACE`
  资源耗尽影响。因本票没有 Web 变更，按 RULE-VAL-001 复用 T125 的绿色 17/17 full
  `.sdlc/evidence/20260828-141041-full/`，形成 A1。
- 状态回填后的 resolution `fast` `.sdlc/evidence/20260828-171548-fast/` 通过。
- ticket-specific A2 未签发；A3 未外部强制；J0。provider attempts、API Key reads、
  real data、Profile registration、push/tag/PR 均为 0。
