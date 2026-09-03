# RenderWeave 项目宪章

每个任务开始先读本文件。本文件只保存跨任务稳定、无法仅从代码可靠推断的工程决定。
具体实现以批准的 spec、领域文档（入口 `CONTEXT-MAP.md`）、ADR 索引 `docs/adr/README.md` 和当前 ticket 为准。

## 工程原则

- Spec first：`specs/renderweave-v1.md` 是 Schema/Inference v1 权威；additive Template v1 由批准的
  implementation authority、冻结 source records 与相关 ADR 管辖。实现发现规格冲突时先记录冲突；
  能由现有权威唯一决定则直接修正并在同票更新过时文档，确需改变产品语义时再请求决定并更新 spec。
- Tracer bullets：按可观察的纵向能力拆票，只实现真实纵切；禁止仅搭骨架、占位页面/表/字段/接口/
  route/module/Profile registration 或 test-only bypass。Workspace 不在当前批准范围。
- Test at the seam：在稳定公共 seam 或真实行为边界测试；领域不变量优先建立失败测试。
  DSL/编译/验证使用 unit、golden、property，PostgreSQL 行为使用 Testcontainers。
- Deep modules：模块通过小而完整的 public interface 协作；不得跨上下文共享聚合、表事务或内部包。
- Decisions, not diaries：真正影响身份、不可变发布、编译权威、模块边界、权限或恢复模型的取舍写 ADR；
  普通实现细节留在代码、测试和 ticket 中。进度状态只进 ticket 与 git，不写回领域文档。

## Skills-first 工作流

采用 Matt Pocock engineering skills 的组合式工作流：`grill-with-docs → to-spec → to-tickets →
implement → code-review`（fixed-point 顺序）。每票的读取顺序、frontier 规则、提交节奏与授权停点
以 `plans/execution-protocol.md`、`docs/agents/issue-tracker.md` 为准，本文件不复述。

旧 Agent SDLC 的 Phase、A0–A3/J0–J1、claim、checkpoint 和生命周期标签只保留在历史记录
（`docs/history/`）中，不再控制未来工作，也不要求继续维护；gate 兼容历史脚本继续写入
`.sdlc/evidence/`，该目录仅作本地构建日志，不分级、不登记、不作为人工批准替代品。
当批准的历史 spec 同时包含产品语义和旧工作流条款时，只保留其产品/领域语义；工作流、branch、
tracker、claim、证据等级与提交节奏以当前 `AGENTS.md`、本宪章和 `docs/agents/` 为准。

## 验证

- 开发时运行最短相关检查；票据结束运行受影响 gate。对本仓库而言，覆盖 ticket 全部受影响面的 gate
  就是收尾完整测试，不机械把全仓 `full` 用于每张票。
- 共享合同、数据库迁移、app wiring、跨语言协议或发布面变化时扩大到相应集成 gate；风险确实覆盖整个产品时才运行 `full`。
- 自动测试结果只说明对应命令和输入通过，不替代未执行的人工体验、生产认证或外部授权。
- 失败应产生新的假设或修正；报告真实执行过的命令、结果与仍未验证的风险，不用状态标签代替事实。

## 自主与授权边界

- 已批准 goal 内的可逆代码、测试、文档和本地验证可以持续推进；局部阻塞时优先处理其他安全 frontier。
- 改变产品语义、使用付费服务、发送真实数据、执行生产操作、扩大外部权限或实施难恢复破坏性动作，
  必须获得当次明确授权。
- live 模型调用默认关闭；授权必须绑定精确 Profile、evaluation identity、数据分类、次数、费用与时限。
  API Key 不得被读取、记录或输出。
- 产品内 AI/Inference runtime 默认不能发布、删除、执行任意 SQL、读取任意文件或发起任意 HTTP；
  默认测试禁止外部模型调用。这里不限制 Agent 为当前任务读取仓库或必要的公开文档。
- Git 源码恢复、数据库恢复和外部副作用补偿分别建模；源码回退不能冒充数据、生产或费用恢复。

## 版本控制

- 当前 Template v1 goal 在 `main` 上按完成票据独立提交；每张验证完成的票独立 commit。
- 保护用户现有 dirty work：不 reset、checkout、覆盖、清理或夹带不属于当前 ticket 的修改。
- 未经明确授权，不 push、不创建 tag 或 PR。

## 稳定产品约束

- 历史 Schema/Inference v1 与 additive Template v1 的边界不可被后者反向改写。
- 服务端为 Java 21 / Spring Boot modular monolith；Web 为 React + TypeScript strict；正式 Node 基线为
  24 LTS（Node 20 只能用于临时原型兼容检查，不能满足发布 gate）。
- PostgreSQL 是唯一数据库语义来源；生产代码与数据库测试不依赖 H2/SQLite。
- OpenAPI 是 HTTP 合同源；Java 服务端做合同验证，TypeScript SDK 使用固定生成器版本生成。
- Schema DSL 是事实源；通用 JSON Schema validator 不能替代 RenderWeave validator。
- StaticSchema 内容与已编译 JSON Schema 永不 UPDATE、DELETE 或重编译；正式 Schema 不引入 `fieldId`，
  Candidate 局部 ID 不得泄漏。
- 不把原始图片、完整 RootDocument、完整模型输入输出或 chain-of-thought 写入常规日志。
- `D:\Yiwer\code\hbads-design-v2` 仅作历史参考；冲突时以本仓库 spec 和批准的 delta 为准。

## 修订

- 版本 2.0，2026-08-31：废弃 Agent SDLC 状态机与证据分级，迁移到 Matt Pocock skills-first 工作流。
- 版本 3.0，2026-09-03：渐进式披露重构——本宪章成为稳定决定的唯一归属（原 AGENTS.md 重复条款删除），
  领域文档拆分为 `CONTEXT-MAP.md` + `docs/context/` 分片，日记与历史卡归档至 `docs/history/`。
