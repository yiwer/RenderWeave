# AGENTS.md

## 项目命令

- 快速检查：`powershell -ExecutionPolicy Bypass -File tools/run-gate.ps1 -Gate fast`
- 服务端回归：`powershell -ExecutionPolicy Bypass -File tools/run-gate.ps1 -Gate server`
- Web 回归：`powershell -ExecutionPolicy Bypass -File tools/run-gate.ps1 -Gate web`
- Template 静态权威重放：`powershell -ExecutionPolicy Bypass -File tools/run-gate.ps1 -Gate template`
- Renderer 回归：`powershell -ExecutionPolicy Bypass -File tools/run-gate.ps1 -Gate render`
- 实际运行 canary：`powershell -ExecutionPolicy Bypass -File tools/run-gate.ps1 -Gate runtime`
- 完整门控：`powershell -ExecutionPolicy Bypass -File tools/run-gate.ps1 -Gate full`
- 关键路径 E2E：`powershell -ExecutionPolicy Bypass -File tools/run-gate.ps1 -Gate e2e`
- 构建/打包：`mvn -B -ntp verify` 与 `npm --prefix web run build`
- UI 原型：`powershell -ExecutionPolicy Bypass -File tools/dev-web.ps1`，打开 `/prototype/schema-studio?variant=A`

## Agent skills

### Issue tracker

本仓库使用本地 Markdown tracker：每张票一个文件，位于 `.scratch/<effort>/issues/`。发布、读取、选择 frontier
和更新状态时遵循 [docs/agents/issue-tracker.md](docs/agents/issue-tracker.md)。不要未经授权创建外部 GitHub Issue。

### Domain docs

每个任务先读 `CONSTITUTION.md`。本仓库当前使用单一根领域文档（无 `CONTEXT-MAP.md`）；探索或修改前再读根目录 `CONTEXT.md`，并按变更范围读取 `docs/adr/` 中相关决定。细则见
[docs/agents/domain.md](docs/agents/domain.md)。

### Workflow

- 需求不清或存在高代价取舍时使用 `grill-with-docs`；已达成共识后用 `to-spec` 固化规格。
- 跨会话或多步工作使用 `to-tickets` 拆成有依赖关系的纵向 tracer bullets，并按该 skill 要求先向用户展示拆分、取得批准再发布；每张实现票应能在一个专注上下文中完成。
- 使用 `implement` 一次推进一张 ready ticket：先读票据与领域文档，在约定 seam 上测试先行，频繁跑最小检查，结束时跑一次受影响回归。
- 实现完成后按 `plans/execution-protocol.md` 的 fixed-point 顺序运行 `code-review`，修复阻断项后才把票记为 done。
- 难复现故障使用 `diagnosing-bugs`；明确要求 test-first 时使用 `tdd`；需要探索大图时使用 `wayfinder`。
- 已批准 ticket 集合内的长期 goal 连续推进 frontier，不在普通实现票后等待批准。新的 ticket 拆分仍遵循 `to-tickets` 的集中确认；此外只有必须改变产品语义、需要新的外部/付费/生产授权，或执行难恢复破坏性操作时才停下询问。

Template v1 的规格权威仍是 `specs/changes/20260817-template-v1-implementation-authority.md`、冻结 checkpoint 与
相关 ADR。`plans/renderweave-template-v1-plan.md`、`.scratch/renderweave-template-v1-implementation/map.md`、旧 issue
和 `.sdlc/evidence/` 是截至迁移日的历史记录；其 A0–A3、J0–J1、Phase、claim、`automated_verified` 等字段不再是
后续工作的状态机或完成条件，也不要求继续生成。新票从既有未完成需求继续编号，不改写历史票。
旧 implementation authority 中的产品与领域语义继续有效；其中关于 branch/worktree、Wayfinder 调度、single-writer
claim、A/J 分级、checkpoint、生命周期和提交节奏的流程条款，自 2026-08-31 起由本文件、`CONSTITUTION.md` 与
`docs/agents/` 明确取代。

## Source of truth

- Schema/Inference v1：`specs/renderweave-v1.md`。
- Additive Template v1：批准的 implementation authority、冻结 source records、相关 ADR 和当前 ticket。
- 领域语言与模块边界：`CONTEXT.md`；发生冲突时不得静默发明第二套语义。
- 实现发现规格冲突时，先记录冲突；能由现有权威唯一决定则直接修正，确需改变产品语义时再请求决定并更新 spec。

## Verification

- 实现中优先运行最小、最快的相关测试；ticket 收尾运行受影响 gate。
- 共享合同、app wiring、迁移、跨语言协议或发布面变化时扩大到对应集成 gate；只有风险覆盖需要时才运行 `full`。
- 对本仓库而言，覆盖 ticket 全部受影响面的 gate 就是 `implement` 所称的收尾完整测试；不机械把全仓 `full` 用于每张票。
- 不用 H2/SQLite 模拟 PostgreSQL；数据库语义测试使用 Testcontainers PostgreSQL。
- gate 可能因兼容历史脚本继续写 `.sdlc/evidence/`，这些目录仅视为构建日志，不分级、不登记、不作为人工批准替代品。
- 报告真实执行过的命令、结果与仍未验证的风险，不用状态标签代替事实。

## Version control

- 当前 Template goal 在 `main` 上推进；每张完成并验证的 ticket 独立提交，避免混入用户的无关 dirty work。
- 不 reset、checkout、覆盖、清理或提交不属于当前 ticket 的现有修改。
- 未经用户明确授权，不 push、不建 tag、不建 PR。

## 项目特有边界

- 只实现真实纵切；禁止 placeholder 页面、表、字段、接口、route、module、Profile registration 或 test-only bypass。
- Schema DSL 是事实源；通用 JSON Schema 验证器不能替代 RenderWeave validator。
- StaticSchema 内容和已编译 JSON Schema 永不 UPDATE、DELETE 或重编译；正式 Schema 不引入 `fieldId`，Candidate 局部 ID 不得泄漏。
- 产品内 AI/Inference runtime 默认不能发布、删除、执行任意 SQL、读取任意文件或发起任意 HTTP；默认测试禁止外部模型调用。这里不限制 Agent 为当前任务读取仓库或必要的公开文档。
- 付费 live AI、真实数据、生产操作或外部副作用必须获得当次明确授权，并绑定精确 Profile、evaluation identity、数据分类、次数、费用和时限。不得读取或输出 API Key。
- 不把原始图片、完整 RootDocument、完整模型输入输出或 chain-of-thought 写入常规日志。
- `D:\Yiwer\code\hbads-design-v2` 仅作历史参考；冲突时以本仓库 spec 和批准的 delta 为准。
- 正式 Node 基线是 24 LTS；Node 20 只能用于临时原型兼容检查，不能满足发布 gate。
