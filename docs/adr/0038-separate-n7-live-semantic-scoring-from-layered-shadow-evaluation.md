# ADR-0038：分离 N7 live 语义评分与 R1 layered shadow 评估

- 状态：accepted
- 日期：2026-08-13
- 决策来源：N7-04 Provider 调用前的 exact corpus/evaluator re-anchor；用户授权按推荐方案决策并以 ADR 记录
- 关联：P6/T6-5/N7、N7-03、N7-04、ADR-0036、ADR-0037、corpus v2、product-v45

## 背景

N7-04 的首次 exact J1 已绑定 corpus v2 与 R1 layered evaluator，但运行前审计发现 live harness 的实际输入和
输出边界并不是 `LayeredVisualPrediction`：它把 `LayeredVisualCorpus` 的 render case 送入现有 product-v45
串行图片流程，最终取得 PostgreSQL durable run 的 `VisualStageSnapshot`。原 harness 随后仍用 v1
`VisualStageEvaluator`、v1 report 和 v1 journal 记录结果。直接执行会出现两种不可接受情况之一：要么 corpus
版本检查 fail-closed，要么证据声称执行了一个实际上从未接收 live checkpoint 的 layered evaluator。

R1 layered scorer 的职责是对 `DocumentObservationIR/1.0` 及分层 gold 做零 Provider shadow diagnostic；它没有
被批准成为新的产品 checkpoint、Candidate projection 或 live 输出协议。为让它消费 product-v45 checkpoint
而新增预测适配器，会在 N7 内引入尚无 spec 的算法与语义投影，并可能把 gold 信息泄漏进被评分路径。

发现问题时尚未建立 Provider reservation，也没有发生外部调用、token 或费用。首次授权因此 CLOSED，不能通过
改写 identity 或重开 ledger 复用；修复后的 revision 必须使用 successor authorization ID 和 fresh exact J1。

## 决策

1. **corpus v2 是 N7 canary/qualification 的精确 render authority。** live harness 必须执行冻结 assignment 对应的
   `LayeredVisualCorpus.renderCase`，不得退回同 case ID 的 v1 默认 render。corpus v2 仍是 shadow diagnostic，
   不静默替换 AC-021 最终 60-case 的 v1 权威 corpus。
2. **live 评分以实际 durable checkpoint 为输入。** 新增 additive `N7LiveSemanticEvaluation/1.0`，只把
   product-v45 的 `VisualStageSnapshot` 与同一 case 的冻结 semantic gold 做现有 v45 语义评分；其 identity 明确
   绑定 durable checkpoint、v2 render authority、v1 semantic gold、Candidate contract、聚合和整数舍入规则。
3. **R1 layered evaluator 保持独立的零 Provider shadow seam。** 它继续衡量 OCR、layout、order、repeat 与
   semantic 分层指标，但 N7 live report 不声称执行过它，也不把 layered score 用作本次 Provider 输出。
4. **N7 使用独立、payload-free、可重算的 evidence contract。** journal v2 在既有 assignment、run、attempt、
   usage 与固定 problem code 外记录 durable terminal state；完成态只允许 `REVIEW_REQUIRED`、`FAILED` 或
   `CANCELLED`。canonical report 同时绑定 authorization、repository EvaluationIdentity、Profile snapshot、
   protocol、assignment、corpus 和 evaluator identity，并用内容哈希防篡改。
5. **N7-04 的最高门保持产品行为而不是 shadow 指标冒充。** 五个冻结 DEV cases 必须 5/5 到达
   `REVIEW_REQUIRED`，Bundle contract、evidence coverage、DAG validity 均为 100%，critical hallucination 为 0；
   独立 Python verifier 必须从 CLOSED ledger、successor Goal reservations、journal 和 report 重建上述结论。
6. 本变更只修正 evaluation infrastructure 与证据语义；不修改 Prompt 12/7/4、product-v45 Profile、pipeline
   4.28、validator、PostgreSQL durable state machine、Candidate 生成算法或产品发布边界。

## 未选择的方案

- **把 `VisualStageSnapshot` 临时适配成 `LayeredVisualPrediction`**：会新增未规格化的 observation/checkpoint
  projection，既无法证明与 R1 主 seam 等价，也增加 gold leakage 风险。
- **继续写 v1 report，但在 contract 中保留 layered evaluator identity**：证据 identity 与实际执行路径不符，
  无法形成 A1，更不能独立重放为 A2。
- **只把 contract 改回 v1 corpus/evaluator**：会违反 N7-01/N7-02 已冻结的 corpus v2 exact re-anchor 与
  assignment，掩盖已发现的 render drift。
- **让 corpus v2 立即成为最终 60-case 权威 corpus**：N7 约束明确要求 v2 先作为 shadow diagnostic；最终权威
  替换需要新的 spec 与证据触发门。

## 后果与验证

- 正向后果：输入 render、实际 checkpoint、评分器和报告 identity 一一对应；R1 shadow 指标与 N7 产品 canary
  各自保持真实含义；终态、usage 和质量门可以在不读取 payload 的情况下独立重算。
- 代价：N7 需要维护一个 additive semantic report/journal 版本，并在未来 qualification/final ticket 中明确
  选择 live semantic 证据或 layered shadow 证据，不能混用名称或 identity。
- 验证：Java contract/codec/journal 正负测试；exact render 注入测试；独立 Python 对 terminal、reservation
  usage、metric、identity、payload 和 budget 的 tamper tests；clean fixed revision 的 full 与 Document Vision
  gate；fresh exact J1 后才允许五例 Plus canary。
- 保证等级：修复与零 Provider gate 上限 A1/A2；真实 Provider 质量只在 CLOSED ledger 与独立 evidence replay
  通过后达到 A2。任何自动证据都不替代后续视觉/业务 J1。
