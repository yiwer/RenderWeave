# RenderWeave Template v1 Implementation Plan

## 2026-08-31 skills-first migration

- 本文 T01–T210 的执行卡、A0–A3/J0–J1、Phase、claim、evidence 路径与生命周期标签均保留为历史事实，不再驱动后续工作，也不批量改写。
- 新工作遵循 `docs/agents/issue-tracker.md` 与 `plans/execution-protocol.md`：`to-tickets → implement → code-review`，一张票一个纵向增量、受影响验证和独立提交。
- 当前 goal 在 `main` 上持续推进；新票从 T211 起编号（当前 frontier 见 `.scratch/renderweave-template-v1-implementation/issues/`，按 `docs/agents/issue-tracker.md` 的 ready 规则计算，本文不固化票号）。

## 2026-08-29 main integration

- 本次合并保留两条已验证历史。main-line 的 TV1-T123–T136 保持原编号；并行
  `feature/template-v1` 的历史记录以 TV1-F123–F194 作为合并后别名，issue 文件名与历史 commit message
  为可追溯性不改写。新的统一 ticket 从 TV1-T195 继续，避免再次复用编号。
- TV1-F186–F194 的历史 resolution 中关于 Rendering-local guard/parser 的实现措辞已被本次语义合并取代：
  Design/Input/Expression 阈值与 parser 仍由 Template-owned authority 唯一拥有；Rendering 通过真实
  `DesignDslAuthority` 重准入与 immutable semantic AST 做 closure-stage 防御回放，不保留第二套阈值或 parser。
- 合并期间不 claim 新 frontier；完成 merge commit、恢复 main 原有 tracked stash 后重新计算 DAG。TV1-T126a、
  TV1-T136 与 TV1-F123–F194 的历史状态均保留，不能仅凭合并动作升级人工或发布生命周期。


## 历史执行卡

T01–T210 / F123–F194 全部执行卡（含 A0–A3/J0–J1、claim、evidence 路径等退役字段）已归档至 `docs/history/template-v1-execution-cards-T01-T210.md`，只作历史查询，不再生成也不再作为完成条件。新票状态只按 `docs/agents/issue-tracker.md` 的四种状态记录。
