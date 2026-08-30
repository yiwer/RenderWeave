# RenderWeave 项目宪章

本文件只保存跨任务稳定、无法仅从代码可靠推断的工程决定。具体实现以批准的 spec、领域文档、ADR 和当前 ticket 为准。

## 工程原则

- Spec first：`specs/renderweave-v1.md` 是 Schema/Inference v1 权威；additive Template v1 由批准的 implementation authority、冻结 source records 与相关 ADR 管辖。
- Tracer bullets：按可观察的纵向能力拆票，避免仅搭骨架、占位接口或按技术层横切。
- Test at the seam：在稳定公共 seam 或真实行为边界测试；领域不变量优先建立失败测试。DSL/编译/验证使用 unit、golden、property，PostgreSQL 行为使用 Testcontainers。
- Deep modules：模块通过小而完整的 public interface 协作；不得跨上下文共享聚合、表事务或内部包。
- Decisions, not diaries：真正影响身份、不可变发布、编译权威、模块边界、权限或恢复模型的取舍写 ADR；普通实现细节留在代码、测试和 ticket 中。

## Skills-first 工作流

RenderWeave 采用 Matt Pocock engineering skills 的组合式工作流：

1. `grill-with-docs` 澄清未知项和取舍。
2. `to-spec` 把已达成的产品与技术决定固化为可审阅规格。
3. `to-tickets` 拆成带依赖的纵向票据。
4. `implement` 一次完成一张 ready ticket，在约定 seam 测试先行并运行受影响验证。
5. 按 `plans/execution-protocol.md` 的 fixed-point 顺序运行 `code-review`，修复 Standards 与 Spec 阻断项后完成该票。

本地 tracker 与领域文档入口见 `docs/agents/issue-tracker.md` 和 `docs/agents/domain.md`。旧 Agent SDLC 的 Phase、A0–A3、J0–J1、claim、checkpoint 和生命周期标签只保留在历史记录中，不再控制未来工作，也不要求继续维护。
当批准的历史 spec 同时包含产品语义和旧工作流条款时，只保留其产品/领域语义；工作流、branch、tracker、claim、证据等级与提交节奏以当前 `AGENTS.md`、本宪章和 `docs/agents/` 为准。

## 验证

- 开发时运行最短相关检查；票据结束运行受影响 gate。
- 共享合同、数据库迁移、app wiring、跨语言协议或发布面变化时扩大到相应集成 gate；风险确实覆盖整个产品时才运行 `full`。
- 自动测试结果只说明对应命令和输入通过，不替代未执行的人工体验、生产认证或外部授权。
- 失败应产生新的假设或修正；不要为了状态标签重复运行没有新信息的门控。

## 自主与授权边界

- 已批准 goal 内的可逆代码、测试、文档和本地验证可以持续推进；局部阻塞时优先处理其他安全 frontier。
- 改变产品语义、使用付费服务、发送真实数据、执行生产操作、扩大外部权限或实施难恢复破坏性动作，必须获得明确授权。
- live 模型调用默认关闭；授权必须绑定精确 Profile、evaluation identity、数据分类、次数、费用与时限。API Key 不得被读取、记录或输出。
- Git 源码恢复、数据库恢复和外部副作用补偿分别建模；源码回退不能冒充数据、生产或费用恢复。

## 版本控制

- 当前 Template v1 goal 在 `main` 上按完成票据独立提交。
- 保护用户现有 dirty work，不 reset、checkout、覆盖、清理或夹带无关修改。
- 未经明确授权，不 push、不创建 tag 或 PR。

## 稳定产品约束

- 历史 Schema/Inference v1 与 additive Template v1 的边界不可被后者反向改写。
- 服务端为 Java 21 / Spring Boot modular monolith；Web 为 React + TypeScript strict；正式 Node 基线为 24 LTS。
- PostgreSQL 是唯一数据库语义来源；生产代码与数据库测试不依赖 H2/SQLite。
- OpenAPI 是 HTTP 合同源；Java 服务端做合同验证，TypeScript SDK 使用固定生成器版本生成。
- Schema DSL 是事实源；通用 JSON Schema validator 不能替代 RenderWeave validator。
- StaticSchema 与已编译 JSON Schema 不 UPDATE、DELETE 或重编译；正式 Schema 不引入 `fieldId`。
- 不把原始图片、完整 RootDocument、完整模型输入输出或 chain-of-thought 写入常规日志。

## 修订

版本 2.0，2026-08-31：废弃 Agent SDLC 状态机与证据分级，迁移到 Matt Pocock skills-first 工作流；保留既有工程、安全和产品边界。
