# ADR-0036：持久类型化状态图、DocumentObservationIR 与有界局部控制回路

- 状态：accepted
- 日期：2026-08-13
- 可移植性修订：2026-08-18；经用户明确批准，把本 ADR 的 R0/R1 protected-byte gate 从不可由本地或
  `origin` 获取的 `19e22854e0be236d0068336a32969356a6befaf8` 重锚到引入本 ADR 的可达提交
  `c12f23d76a6fc76a6a38042ff89bbd166e6012b5`。14 个 protected paths 对新旧预期均 byte-identical；
  Profile、Prompt、corpus、pipeline、Provider/live 与生命周期语义不变。
- 决策来源：用户批准图片识别 vNext successor spec delta，并要求单独记录控制结构
- 关联：P6/T6-5、AC-DOIR-001..012、ADR-0004、ADR-0020、ADR-0022、ADR-0026、ADR-0028、ADR-0034、ADR-0035

## 背景与约束

product-v45 已经拥有 PostgreSQL job、lease、checkpoint、阶段恢复、预算、人工暂停和原子 Apply；IMAGE_ONLY
主干也已稳定为 `OBSERVE → HIERARCHY → ELEMENT_BINDING → LOCAL_MATERIALIZE`。validator 可以把固定问题
路由回最早失败阶段，因此真实控制结构并非一条只能前进的固定流水线，而是一张有类型状态、受控回边和终态的
持久状态图。

当前主要缺口是感知事实过早耦合 RapidOCR DTO、坐标投影与单一 reading order，导致 adapter、view、order、
Prompt 和 semantic verifier 的误差难以分别度量。该问题不需要让模型获得目标规划、任意工具或结束条件，也
没有证据表明现有单节点 PostgreSQL 编排需要迁移到另一套 durable workflow runtime。

决策必须继续满足：历史 pipeline/Profile/run snapshot 不可变；OCR text、图片、Prompt 和模型原文不得进入
常规日志或 evidence；LLM 没有 filesystem、HTTP、SQL、发布或删除能力；Candidate 只能经确定性 materializer、
validator、人工审核和 create-only 原子事务进入 Draft。

## 决策

1. **外层采用现有 PostgreSQL durable typed state graph/FSM。** PostgreSQL job、lease 与 checkpoint 继续是
   运行事实源；代码拥有状态、转换白名单、终态、取消、恢复和事务语义。这里的 Graph 是对现有控制结构的
   明确架构身份，不是引入通用图框架、第二套 history store 或图数据库。
2. **感知层通过 provider-neutral `DocumentObservationIR` 与语义层解耦。** 新增深 Module
   `VisualEvidenceAcquisition`，其主要 Interface 位于唯一主 seam：
   `normalized ArtifactSet + AcquisitionPolicy → DocumentObservationIR/1.0`。Interface 同时包含 strict
   contract、顺序、错误、边界与敏感性不变量，而不只是类型签名。
3. **RapidOCR Adapter 先保留在 Module 的 Implementation 内。** R0 只有一个生产感知实现，因此不为未来
   challenger 提前暴露多个浅 public ports。R2 若形成第二个真实 Adapter，再以同一 IR 和 holdout 证据验证
   内部 seam 是否值得提升为稳定 Interface。
4. **Observation、semantic hypothesis、verified plan 与 Candidate 是不同类型。** IR 只表达规范化 artifact
   上可观察的内容、source-pixel geometry、顺序、confidence 与 provenance；它不能携带 SLOT/GROUP、Entity、
   Relationship、Field 或 Schema 结论。OCR text 是 untrusted ephemeral data，IR 不成为持久 checkpoint。
5. **Provider 语义阶段保持因果串行。** `OBSERVE → HIERARCHY → ELEMENT_BINDING` 不并行推测或竞争写入；
   accepted checkpoint 不重放。无副作用的图片、tile、几何或未来 shadow Adapter 可以在 perception
   Implementation 内并行，但必须确定性归并，且晋级前不得影响产品 Candidate。
6. **局部失败使用 validator 驱动的有界 control loop。** 每一回路固定为 strict decode → deterministic
   validation → fixed issue code → code-owned `earliestStage` 与白名单 action → budget/authorization check →
   至多一次获准的 stage retry 或确定性观察动作。模型不能自由选择工具、模型、目标、预算或结束条件。
7. **确定性编译和人工写入边界不变。** 只有 verified visual plan 可以进入 LOCAL_MATERIALIZE；Candidate
   validator 与 RenderWeave validator 保持权威。最终仍须进入 `REVIEW_REQUIRED`，由用户逐项审核后经
   create-only PostgreSQL 事务原子创建 Draft Bundle。
8. **不在当前阶段引入开放式 Agent、LangGraph 或 Temporal。** 这些方案只有在真实跨服务 activity、复杂
   长等待/补偿或现有状态迁移已出现可量化维护失败时才重新评估；“流程包含 LLM”本身不是迁移理由。

## 备选方案

| 方案 | 优点 | 代价/风险 | 未选择原因 |
|---|---|---|---|
| 只按固定串行 pipeline 建模 | happy path 最直观 | 无法准确表达最早阶段 rewind、人工暂停和恢复 | 可作为展示视图，不足以成为权威控制模型 |
| 静态 DAG | 适合无副作用 perception 分支与 join | 不表达有界回边、取消、人工暂停或终态事务 | 只作为单次 perception 子图，不作为外层编排 |
| 开放式 ReAct/control-loop Agent | 对未知探索灵活 | 目标、工具、停止、费用、安全与重放不可证明 | Schema 主流程是封闭任务，不需要开放探索 |
| 立即迁移 LangGraph/Temporal | 提供成熟 checkpoint/durable execution 原语 | 重复现有 PostgreSQL 事实源；需迁移历史 run，并重新证明外部调用与敏感 history | 当前规模和故障证据不支持迁移成本 |
| 一个端到端 VLM 直接生成 Candidate | Interface 看似最小 | 感知、语义、DSL 和写入混合；合法但错误的拓扑难定位 | v43–v45 实证已显示必须保持阶段与确定性物化 |
| 为 Observation 使用图数据库/向量库 | 可进行通用图查询或相似检索 | 新持久化面、留存面与运维成本 | 当前是单 run 有界内存图，不存在跨文档知识检索需求 |

## 后果与验证

- 正向后果：控制与感知职责清晰；adapter/view/order/Prompt/verifier 误差可分层定位；现有恢复、预算、
  人工审核和事务边界继续复用；未来 Adapter 只能通过同一 IR 与评测门进入。
- 负向后果/债务：项目继续维护自有状态图及其迁移纪律；新增 IR、AcquisitionPolicy、compatibility
  projection、contract identity 和分层 gold 的版本成本；一个 Adapter 阶段的内部 seam 仍是假设，不能冒充
  已证明的多实现抽象。
- 验证方式：以 `normalized ArtifactSet + AcquisitionPolicy → DocumentObservationIR/1.0` 为主测试 seam；
  以完整 IMAGE_ONLY scripted replay 到 `REVIEW_REQUIRED` 为最高验收 seam。还必须覆盖 strict contract、
  source-pixel 投影、payload scan、Provider=0、PostgreSQL crash/lease recovery、accepted stage 零重放、fixed
  issue/action 和 v45 Candidate/evidence/blocker 行为等价。
- 保证等级：确定性实现与本地门上限 A1；独立 Python 指标重算和 payload scan 在其严格输入范围内可形成 A2；
  不存在 A3。本 ADR 不构成 live、费用、真实数据或质量晋级 J1。
- 重新评估条件：跨越多个独立服务或 worker pool；大量长时人工暂停/补偿；状态迁移或恢复事故已无法通过
  局部重构和故障测试收敛。届时 spike 必须证明 immutable snapshot、LLM side-effect 幂等、payload-free
  history、PostgreSQL Apply 权威、旧 run 恢复、预算与取消语义均不退化，且不能同时保留两套 durable truth。
