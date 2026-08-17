# AGENTS.md

## 项目命令
- 局部/快速检查：`powershell -ExecutionPolicy Bypass -File tools/run-gate.ps1 -Gate fast`
- 服务端回归：`powershell -ExecutionPolicy Bypass -File tools/run-gate.ps1 -Gate server`
- Web 回归：`powershell -ExecutionPolicy Bypass -File tools/run-gate.ps1 -Gate web`
- Template 静态权威重放：`powershell -ExecutionPolicy Bypass -File tools/run-gate.ps1 -Gate template`
- 实际运行 canary：`powershell -ExecutionPolicy Bypass -File tools/run-gate.ps1 -Gate runtime`
- 完整/发布级门控：`powershell -ExecutionPolicy Bypass -File tools/run-gate.ps1 -Gate full`
- 构建/打包：`mvn -B -ntp verify` 与 `npm --prefix web run build`
- 关键路径 E2E：`powershell -ExecutionPolicy Bypass -File tools/run-gate.ps1 -Gate e2e`
- UI 原型：`powershell -ExecutionPolicy Bypass -File tools/dev-web.ps1`，打开 `/prototype/schema-studio?variant=A`

## 工作约定
- 治理与需求见 CONSTITUTION.md 与 specs/；计划与证据见 plans/
- 领域语言与模块边界先读 CONTEXT.md；Schema/Inference v1 权威是 specs/renderweave-v1.md，additive Template v1
  实施权威见 specs/changes/20260817-template-v1-implementation-authority.md 与冻结 checkpoint
- Schema/Inference 历史 Phase 见 plans/renderweave-v1-plan.md；Template 当前 DAG 见
  plans/renderweave-template-v1-plan.md 与 `.scratch/renderweave-template-v1-implementation/`；进度广播见 NOTES.md
- 核心规则见下方受管区块；完整语义见 skill 内 references/core-rules.md（rule ID 对齐）
- fast-track 零落盘；Project Mode 才创建流程文件（RULE-FAST-001）
- 局部阻塞时继续其他安全任务；只有无安全路径时才合并请求用户（RULE-USER-001）
- 不默认创建 tag；P1–P4 保持 record-only，P5/P6 按既有授权提交；Template 已获按 verified ticket 在
  `feature/template-v1` 提交的授权，但不 push、tag 或建 PR

## 项目特有禁区
- 历史 Schema/Inference v1 不实现 Template、数据适配、Workspace 或图片渲染；经批准的 additive Template v1
  effort 只按已 claim ticket 实现真实纵切，仍禁止占位页面、表、接口、route、module 或 Profile registration。
- Schema DSL 是事实源；通用 JSON Schema 验证器不能替代 RenderWeave validator。
- StaticSchema 内容和已编译 JSON Schema 永不 UPDATE/DELETE/重编译。
- 正式 Schema 不引入 fieldId；Candidate 的局部 ID 不允许泄漏进 Draft/Static/编译产物。
- AI 不能获得发布、删除、任意 SQL、任意文件或任意 HTTP 能力；默认测试禁止外部模型调用。
- 未获绑定精确 Profile、evaluation identity、数据分类、次数、费用与时限的当次 J1 时，不运行付费 live AI、不发送真实数据、不读取或输出 API Key。历史 Flash/Plus 账本均已 CLOSED；Prompt v2 当前无 OPEN 账本、provider attempts 必须为 0。
- 不使用 H2/SQLite 模拟 PostgreSQL 语义；数据库测试使用 Testcontainers PostgreSQL。
- 不记录原始图片、完整 RootDocument、完整模型输入输出或 chain-of-thought 到常规日志/证据。
- `D:\Yiwer\code\hbads-design-v2` 仅作历史参考；冲突时以本仓库 spec 和已批准 delta 为准。
- 当前正式 Node 基线是 24 LTS；本机 Node 20 只可用于临时原型兼容验证，不能满足发布 gate。

<!-- agent-sdlc:managed:start ruleset=2.0 hash=d084c42632ac6f348a0107e68571bdb9c086101a7a2e59c83c136c160d537a21 -->
## Agent SDLC 核心规则（受管区块）

- RULE-AUT-001：验证质量决定自主权；能力或证据不足时降低自主或并发，不用散文伪装能力已存在。
- RULE-VAL-001：按局部→受影响→Phase→Goal 逐级扩大验证；输入未变可复用最近绿色证据。
- RULE-EVD-001：证据分 A0 self-reported / A1 tool-captured / A2 independently-replayed / A3 externally-enforced；人工分 J1 approved / J0 pending-or-rejected。
- RULE-USER-001：只有产品语义必变、新外部授权/付费、生产或真实数据、破坏性难恢复且无其他安全任务可继续时，才合并询问。
- RULE-SCOPE-001：影响区域用于估算与写冲突协调，不是权限白名单；漏列文件直接改，差异由 git 提供。
- RULE-STATE-001：生命周期如实报告；自动 gate 过而人工 pending 报 automated_verified，不报 accepted。
- RULE-FAST-001：fast-track 零落盘；风险升高只切 guarded 维度，不膨胀为完整 Project。

项目命令和实例值见 CONSTITUTION.md 与 plans/env-gates-checklist.md。
完整规则见 skill 内 references/core-rules.md（以 rule ID 对齐）。
<!-- agent-sdlc:managed:end -->
