# 建立 Template v1 实施权威与反馈闭环

Type: task
Status: resolved
Claimed by: Codex /root
Blocked by: none

## Question

如何在不触碰 dirty main 的前提下，以本地 main 已提交锚点 `7848c821...` 创建相邻独立实施 branch/worktree，整合 Template 规格提交与 7 份已核验 LF registry 修复，并用新增的实施 spec delta、治理边界、阶段/DAG、`template` gate canary 和仓库既有 gate 形成可重复反馈闭环？本票只建立实施权威与验证基础，不实现 DesignDSL、Template、Asset、Evaluator、Editor 或 Renderer 产品能力；完成全部相应 gate、差异审计与 worktree 核验后，按用户授权在实施分支形成一个可审计提交，但不 push、tag 或建 PR。

## Answer

以 `main@7848c821aa9b809dd8cadb2b5e28f40f6947a90e` 建立相邻
`feature/template-v1` worktree，并设置 worktree-local `core.autocrlf=false`；dirty main 的 branch、HEAD 与用户
差异保持不动。实施分支已整合 `b14c2d7`、`0b485f4` 和 7 份与 spec worktree byte-identical 的 LF registry
repair，随后用 approved additive spec delta 明确：历史 Schema/Inference v1 不变，AC-025 不再是本 Template
effort 的仓库全局永久禁令。

新增 `template` gate 并纳入 `full`。它把冻结 authority 复制到系统 temp 的 GUID 目录，重放 Editor
generator/independent/A2 与 SPEC_REGISTRY target/Node/Python/A2，按完整 tree manifest 证明 replay diff=0，
并断言 frozen counts、Profile 1.0/1.1 边界、唯一 Editor content source、Ticket 19 LF blob 与 not-ready 边界。
它从不回写 authority，运行结束只删除已验证的临时目录。

验证结果：Template gate PASS（`.sdlc/evidence/20260817-185121-template/`）；Web gate PASS（14 files/76 tests，
`.sdlc/evidence/20260817-185519-web/`）；fast PASS（`.sdlc/evidence/20260817-185601-fast/`）。首次 fast 因新
worktree 缺 `node_modules` 失败，首次 Web 工具链下载超时均保留为负面/不完整证据，随后按标准 gate 恢复，未
跳过检查。完整说明见 `plans/logs/TV1-T01.md`。

本票没有修改 Java/Web/Rust 产品源码、OpenAPI、migration 或产品页面/route/table；没有浏览器、Web 服务、
provider、真实数据、API Key、J1 或生产副作用。Ticket 19 仍 open，Template/Editor/Renderer 仍未 READY。
