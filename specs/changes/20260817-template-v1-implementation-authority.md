# Spec Delta：Template v1 Implementation Authority

- 状态：**APPROVED**
- Triage：`ready-for-agent`
- 日期：2026-08-17
- 批准权威：所有者明确要求继续 `template-v1`、实现冻结规格，并授权按推荐推进与在独立实施分支按已验证票据提交
- 本地代码基线：`main@7848c821aa9b809dd8cadb2b5e28f40f6947a90e`
- 冻结规格 checkpoint：`0b485f4a13de9d754a81d07f464730776e13c14b`
- 决策前置：`b14c2d7`（authoring workflow 方案 B）
- 实施 tracker：`.scratch/renderweave-template-v1-implementation/map.md`
- 实施计划：`plans/renderweave-template-v1-plan.md`
- 影响 AC：历史 AC-025 只继续约束 Schema/Inference 原计划不得偷偷预建 Template；本 delta 为新增 Template effort
  建立 AC-TV1-001..006

## Problem Statement

`specs/renderweave-v1.md` 是已经交付的 Schema/Inference v1 历史权威，并明确把 Template、数据适配与图片渲染
列为非目标。另一方面，冻结 checkpoint 已经通过 Tickets 04–19、3,651 条 atomic requirements、容量矩阵和
SPEC_REGISTRY 定义了一个独立的 Template v1 产品合同。若仍把历史 AC-025 解释为仓库全局永久禁令，任何
Template 实施都会与治理文件冲突；若直接改写历史规格，又会破坏已完成 Schema/Inference 证据的可追溯性。

此外，用户的 `main` worktree 含未提交改动，Template checkpoint 后还有 7 份已经独立核验、必须保持 LF bytes
的 Ticket 19 registry 修复。实施必须在隔离 worktree 中建立可重复反馈门，不能靠覆盖 main、静默重生成权威
或把静态 fixture replay 说成产品执行。

## Solution

把 Template v1 建立为 additive、单 writer 的 Project Mode effort。Schema/Inference 行为仍由
`specs/renderweave-v1.md` 及其既有 approved deltas 管辖；Template 语义由本 delta、冻结 checkpoint、
`.scratch/renderweave-template-v1/issues/04..19`、`requirements-v1.json` 与 acceptance registry 管辖。
二者冲突时只在各自 scope 内解释，不能用 Template 实施反向改写已提交的 Schema/Inference 历史。

实施从本地 main 已提交锚点创建相邻 `feature/template-v1` worktree，整合两个规格提交和 7 份 LF repair。
新增离线 `template` gate，在系统临时目录复制冻结 authority，完整重放 HANDOFF §8 的 Editor static 与
SPEC_REGISTRY primary/independent/A2 writers，再按全树路径、长度与 SHA-256 证明重放前后 byte-identical。
该 gate 被纳入 `full`，但它当前只证明规格/登记可重复，不证明任何 Java、Web、Rust 产品能力。

后续产品实现严格按 Wayfinder frontier 推进：先由 Ticket 02 冻结 deep module interface 和依赖方向，再由
Ticket 03 以 TDD 实现最小 canonical kernel。任何 DB、API、UI、migration 或 Renderer seam 只在对应票据形成
真实纵切时进入仓库；不得为降低 pending 数量创建 placeholder 或 test-only bypass。

## Authority Precedence

1. Schema Draft、StaticSchema、Validation 与 Inference：`specs/renderweave-v1.md` 加该范围已批准的 delta。
2. Template/DesignDSL/Asset/Rendering/Editor/Renderer：本 delta加冻结 checkpoint 的规范性记录与 generated registry。
3. 实施调度与生命周期：`.scratch/renderweave-template-v1-implementation/` 的单一 claim 状态和
   `plans/renderweave-template-v1-plan.md`。
4. 若实现发现产品语义冲突，停止受影响 ticket 并形成可定位 delta/ADR；不得用测试或代码静默选择语义。
5. generated artifact 只可由已登记 generator 从权威输入重建；相同输入出现 diff 即失败，不自动覆盖 authority。

## Acceptance Criteria

| AC | 可观察行为 | 最低证据 |
|---|---|---|
| AC-TV1-001 | dirty main 保持原 branch/HEAD/用户差异；Template 只在相邻 `feature/template-v1` worktree 实施，worktree-local `core.autocrlf=false` | A1 Git worktree/branch/status inventory |
| AC-TV1-002 | `b14c2d7`、`0b485f4` 与 7 份指定 LF registry repair 被完整整合；repair 路径、byte length 与 SHA-256 可复核 | A1 byte/hash manifest + A2 registry replay |
| AC-TV1-003 | AGENTS、CONSTITUTION、CONTEXT、主计划与 Template 计划不再把历史 AC-025 误作 additive effort 的永久禁令，同时明确现有 Schema/Inference 语义不变 | A1 authority inventory/diff review |
| AC-TV1-004 | `tools/run-gate.ps1 -Gate template` 离线重放 Editor static 与 SPEC_REGISTRY，输入相同则 authority diff=0；`full` 包含同一步骤 | A1 harness capture；registry 范围内 A2 independent replay |
| AC-TV1-005 | Ticket 01 不新增或修改 Java/Web/Rust 产品模块、OpenAPI route、Flyway migration、表或产品页面，也不登记不完整 Profile available | A1 product-surface inventory |
| AC-TV1-006 | Ticket 01 有 Project Mode 配置、能力上限、DAG、恢复边界、证据 checkpoint 与单票提交；不 push/tag/PR，不宣称 Template/Editor/Renderer READY，不关闭 Ticket 19 | A1 plan/log/Git audit |

## Verification Boundaries

- Editor 当前仍为 108 planning candidates、1,265 assertions、1,146 exact、119 pending；47 个
  `EditorContentSource` slots 中仅 1 exact、46 UNBOUND；Formal Editor Case/Oracle 仍为 0/0。
- SPEC_REGISTRY 当前是 `spec-registry-bootstrap/1.13`、382 artifacts、46 Case/46 Oracle；Node primary
  22,838 checks、Python independent 22,746 checks，均 0 failure。
- Ticket 19 的 LF blob 为 SHA-256
  `ce7335f4b50ad23fb77b018cea1d9d89d94c11e02c72da13e1d392b13a065cae`、74,549 bytes；Capacity
  formal records 仍为 0，Ticket 19 必须保持 open。
- static fixture、A1 gate 与 registry 范围 A2 replay 不等于产品执行、浏览器观察、人工 J1、外部 A3 或
  Renderer 的 hermetic/ELF/tricky-font/双物理 Linux CPU-family 认证。

## Versioning and Recovery

- Template effort 使用 `feature/template-v1` 的 `agent-commit`，每个 resolved/verified ticket 一个可审计提交；
  未经另行授权不 push、tag 或建 PR。
- 源码恢复以该分支提交为边界；规格 repair 可由上述 exact hash manifest 复核。Ticket 01 没有数据库、Blob、
  Provider 或生产副作用，因此没有 migration rollback 或外部费用恢复。
- 临时 replay 目录必须位于操作系统 temp 下的随机 GUID 目录，运行后只删除该已验证目录；仓库 authority 只读。

## ADDED / MODIFIED / REMOVED

### ADDED

- additive Template v1 implementation authority、AC-TV1-001..006、独立实施计划与 `template` static gate。

### MODIFIED

- 历史 AC-025 与“v1 不实现 Template”的解释范围：它们继续保护 Schema/Inference 原计划不预建未来能力，
  但不否定本 approved additive effort。
- isolated workspace 能力对 Template effort 变为 available；independent verification 只在已存在的封闭 replay
  范围内 available，产品实现仍不能外推 A2/A3。

### REMOVED

- 没有删除历史需求、数据或产品行为；只消除治理文件对 additive effort 的矛盾性 blanket ban。

## Out of Scope

- Ticket 01 内实现 DesignDSL、Template、Asset、Evaluator、Editor、Renderer、Connector 或图片输出。
- 创建占位 module、route、API、表、migration、页面、Profile registration 或测试旁路。
- 运行浏览器/Web 服务、付费 provider、读取 API Key、发送真实数据、生产部署或执行 J1。
- 宣称 Template、Editor、Renderer READY，签发 formal product records，关闭 Ticket 19，或把 Windows/WSL
  结果冒充 Renderer 外部认证。

## Decision

- 批准人：RenderWeave 所有者
- 批准日期：2026-08-17
- 结论：批准在隔离实施分支按冻结 Template v1 authority 和 Wayfinder ticket DAG 实现；Ticket 01 只建立
  权威与反馈闭环。
