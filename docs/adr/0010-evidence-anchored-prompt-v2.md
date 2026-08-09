# ADR-0010：Prompt v2 以最小证据图和精确字段身份降低幻觉

- 状态：accepted
- 日期：2026-08-09
- 关联：ADR-0004、ADR-0008、ADR-0009、AC-016、AC-021
- 决策来源：Flash 与 pinned Plus 的 `renderweave-live-eval/2.0` 60-case CLOSED evidence

## 背景

Flash 与 pinned Plus 都没有达到 Profile 认证门槛。Plus 将 schema entity F1 从 72.26% 提升到 90.90%，但 60 个 case 中仍有 51 个 critical hallucination，且 field/type/edge 仍明显低于阈值。对 payload-free journal 的 sufficient statistics 重新归因如下：

| Profile | Critical | 多建 Schema | 多建字段 | 多建边 | 无证据/不确定性越界 | 缺字段 | 支持类型未命中 | 缺边 | contract 无效 | DAG 无效 | provider 失败 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Flash v1 | 26 | 4 | 15 | 3 | 4 | 47 | 43 | 11 | 56 | 19 | 18 |
| Plus v1 | 51 | 6 | 31 | 6 | 8 | 20 | 22 | 3 | 23 | 4 | 4 |

其中 `critical = unexpectedEntity + unexpectedField + unexpectedEdge + unsupportedAssertion`；后四项全部可以从已经持久化的逐 case 标量确定性重建，不需要保存模型 Candidate 或原始业务数据。

Prompt v1 还有一个直接的身份错误：它要求所有 `proposedFieldKey` 转换为 lowerCamel。RenderWeave 的正式字段身份是 `SchemaKey + FieldKey`，`FieldKey` 刻意不 trim、不 Unicode normalize，并允许中文、`/` 与 `~`。因此 JSON 属性 `商品名称`、`价格`、`a/b`、`x~y` 必须原样保留；JSON Pointer 的 `~0`/`~1` 转义只属于 evidence pointer，不能泄漏到字段身份。

## 决策

### 1. 版本只新增，不覆盖

- 保留 `renderweave-schema-candidate-prompt/1.0` 及三个既有 live Profile 的字节与语义不变。
- 新增 `renderweave-schema-candidate-prompt/2.0` 和 pinned `qwen3.7-plus-2026-05-26` 的独立实验 Profile `dashscope-qwen37-plus-20260526-prompt-v2`。
- v2 Profile 继续使用相同的 provider protocol、structured JSON、关闭 thinking/tools/remote media、相同的逐调用预算与价格快照；它不继承 v1 的质量结果。

### 2. 输出最小证据图

- 每个 active Schema、字段和 reference 必须有直接 evidence；没有独立数据槽证据的标题、图标、按钮、装饰、单位、样式或推测分组不能成为字段。
- `required` 永远为 `false`，`constraints` 永远为 `{}`；当前 provider 任务没有用户确认通道，不能把有限样本或视觉星号升级为正式规则。
- 每个非根 Schema 必须由根沿一条 reference 路径恰好可达；相同 shape 不合并，不创建 orphan、重复边或额外容器 Schema。
- 多张图片是同一数据结构的多个观测，不默认把每张图的所有文字做 union；只有独立、稳定、可定位的数据槽才可补充字段。

### 3. JSON 拓扑采用确定性 truth table

`jsonStructuralProfile` 决定 JSON_ONLY 与 COMBINED 的字段身份和对象/数组拓扑：

- JSON 属性名按 Unicode code point 原样成为 `proposedFieldKey`；只对 evidence `jsonPointer` 使用 RFC 6901 转义。
- object → `REFERENCE` + 独立 CandidateSchema；object array → `ARRAY:REFERENCE` + 独立 CandidateSchema。
- empty array 与 nested array → `ARRAY:UNRESOLVED`，不生成嵌套 ARRAY。
- heterogeneous array → `ARRAY:CONFLICT`；object/scalar 或 object/array 混合 → `CONFLICT`。
- all-null → `UNRESOLVED`；普通 scalar 混合按现有 deterministic profiler 降级为 `TEXT`；单一 concrete scalar 保持 `TEXT` / `DECIMAL` / `BOOLEAN`。
- JSON 节点自带的 sampleIndex/jsonPointer 是 evidence 的权威位置，不猜测不存在的位置。
- 运行时从 `jsonStructuralProfile` 构造允许的 `(sampleIndex, jsonPointer)` catalog；provider 输出的每条 JSON evidence 必须既存在于 catalog，又与该 CandidateSchema/CandidateField 的确定性节点路径一致。只有语法合法但位置不存在或错配，仍是 blocker。

COMBINED 中 JSON 仍拥有结构和 concrete 数值/布尔类型；视觉只补充 displayName、直接支持的视觉字段与 evidence。唯一允许的 concrete refinement 是：JSON `TEXT` 在清晰标签与标准值格式共同支持时精化为 `DATE(yyyy-MM-dd)` 或 `TIME(HH:mm:ss)`。视觉不能把 conflict、all-null、empty array、nested array 或 heterogeneous array 武断具体化。

### 4. Provider 输出与人工审核使用不同信任边界

- live provider 初始输出的 Schema/Field 必须是 `source=AI`、`inferred=true`，resolution 只能是 `NOT_REQUIRED` 或 `UNRESOLVED`；`USER`、`CONFIRMED`、`RESOLVED_BY_EDIT`、`REMOVED` 只允许由 Candidate review service 产生。
- live provider、可信 deterministic replay 和用户审核分别携带显式 validation origin。严格的初始 provenance/disposition 约束只作用于不可信 live provider；JSON evidence catalog 对 provider/replay 都生效；用户审核继续执行通用 evidence existence 和 Candidate 合约校验。
- evaluator 独立把 provider 伪造的 provenance/disposition 计为 contract failure 与 critical unsupported assertion，不能依靠模型自报人工确认而获得认证。

### 5. Repair 是完整重建，不是局部补丁

REPAIR 阶段根据稳定 `repairProblemCodes` 重新输出完整 Candidate。可解析但包含 exact-key、UUID、provider provenance、evidence、array/reference graph、required/constraint 等确定性 blocker 的 Candidate 也会进入 REPAIR；不再只有 JSON parse failure 才可达。`LOW_CONFIDENCE_UNRESOLVED`、`CANDIDATE_TYPE_UNRESOLVED` 与 `CANDIDATE_TYPE_CONFLICT` 等需要人工判断的 blocker 不进入模型自决。Repair 不依赖上一份被拒 Candidate，也不把 model payload 写入 evidence。

### 6. 诊断新增派生标量，不改旧 journal schema

`LiveEvaluationResult` 暴露缺失/额外 entity、field、edge、支持类型未命中和 unsupported assertion 的确定性派生值；`LiveEvaluationReporter` 在每个 global/mode/partition slice 汇总这些诊断，并将 certification summary 版本提升到 `1.1`。

旧 `state.json` 已经包含全部 sufficient statistics，因此无需迁移或重写 CLOSED evidence；新实现只增加派生视图，并校验 critical decomposition 不可能为负。

## 非目标与后续边界

- 本节点不构建完整的 deterministic JSON skeleton 与视觉 LLM merger。视觉可以合法补充 COMBINED 字段并精化日期/时间，合并算法需要独立的行为规格和 corpus tracer，不能在一次 prompt 调整中暗改产品语义。
- 不降低 AC-021 的认证阈值；critical hallucination 仍必须为 0。
- 不复用旧 Profile 的 ledger、identity 或 live 结果。新 Profile 只有在最终 tracked tree 冻结后才形成 `PROPOSED` synthetic-only ledger；未获得新的精确 J1 时不得 OPEN 或调用 provider。

## 后果

- 正向：字段身份与正式 Schema 语义一致；Plus 的主要幻觉来源有可机读、可重建的归因；Prompt/Profile 可以并排回放且不会覆盖历史证据。
- 代价：Profile 列表增加一个实验项，OpenAPI 与生成客户端必须同步；Prompt v2 的真实收益仍需新的 60-case J1 live 复验才能证明。
- 恢复：代码和新 Profile 可通过本节点独立提交 revert；`PROPOSED` ledger 不产生费用或外部副作用。任何未来 provider 调用不可撤销，必须由新 J1、费用预留与 CLOSED 生命周期约束。
