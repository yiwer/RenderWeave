# AGENTS.md

RenderWeave：AI-native 设计出图系统。服务端 Java 21 / Spring Boot modular monolith（`renderweave-*`
Maven 模块），Web 为 React + TypeScript strict（`web/`），正式渲染器为独立 Rust 进程（`renderer/`）。

**每个任务必须先读 [`CONSTITUTION.md`](CONSTITUTION.md)**（稳定规则、授权边界、产品约束的唯一归属）；
触碰某个限界上下文前，再按 [`CONTEXT-MAP.md`](CONTEXT-MAP.md) 的路由表只加载命中的
`docs/context/` 分片，不要全量读领域文档。

## 项目命令

- 门控：`powershell -ExecutionPolicy Bypass -File tools/run-gate.ps1 -Gate <name>`。常用名：
  `fast`（默认最短检查）、`server`、`web`、`template`、`asset`、`render`、`e2e`、`runtime`、`full`；
  其余 `image-only-*` 门控服务于停泊中的 admission 工作。
- 构建/打包：`mvn -B -ntp verify`；`npm --prefix web run build`
- UI 本地运行：`powershell -ExecutionPolicy Bypass -File tools/dev-web.ps1`，
  保留的原型入口 `/prototype/schema-studio?variant=A|B|C`
- 环境事实优先看环境本身：`pom.xml`、`web/package.json`、`tools/`、`openapi/`、`compose.yaml`。

## Agent skills

### Issue tracker

本地 Markdown tracker：每张票一个文件，位于 `.scratch/<effort>/issues/`。发布、读取、选择 frontier
和更新状态时遵循 [docs/agents/issue-tracker.md](docs/agents/issue-tracker.md)。不要未经授权创建外部 GitHub Issue。

### Domain docs

单一领域模型：根 `CONTEXT-MAP.md` 按限界上下文路由到 `docs/context/` 分片；决策读
`docs/adr/README.md` 索引后按需命中单份。词汇纪律与 ADR 冲突处理细则见
[docs/agents/domain.md](docs/agents/domain.md)。

### Workflow

- 需求不清或存在高代价取舍 → `grill-with-docs`；已达成共识 → `to-spec`；跨会话或多步工作 →
  `to-tickets`（先向用户展示拆分、取得批准再发布）；推进单张 ready 票 → `implement`；票完成后按
  `plans/execution-protocol.md` 的 fixed-point 顺序 → `code-review`。
- 难复现故障 → `diagnosing-bugs`；明确要求 test-first → `tdd`；探索大图 → `wayfinder`；
  验证设计直觉 → `prototype`；查一手资料 → `research`；术语建模 → `domain-modeling`。
- 已批准 ticket 集合内的长期 goal 连续推进 frontier，不在普通实现票后等待批准；停下询问的停点见
  CONSTITUTION「自主与授权边界」。

## Source of truth

- Schema/Inference v1：`specs/renderweave-v1.md`。
- Template v1（当前主战线）：`specs/changes/20260817-template-v1-implementation-authority.md` +
  冻结 checkpoint + 相关 ADR + 当前票；goal 状态见 `plans/renderweave-template-v1-plan.md`（瘦身后仅指针）。
- IMAGE_ONLY admission（停泊）：`plans/image-only-production-admission-plan-v1.md` 状态头 = 唯一下一入口。
- HTTP 合同：`openapi/renderweave-v1.yaml`。
- Web UI 设计（仅 web 任务）：`design.md`（Hum variant）与 `design-system/`。
- 历史与退役工作流记录：`docs/history/` 只作查证，不是状态机，不驱动任何后续工作。
