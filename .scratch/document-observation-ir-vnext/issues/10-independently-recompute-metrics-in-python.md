# 10 — 由 Python 独立重算分层指标并拒绝篡改

**Parent:** N9 / R1

**Anchor:** approved successor spec、ADR-0036、commit 19e22854e0be236d0068336a32969356a6befaf8

**What to build:** 建立与 Java scorer 独立的 Python verifier，依据稳定合同重新计算 identity 和全部指标。它可以与 ticket 09 独立实现，但只能在 ticket 12 与 Java 结果汇合。

**Blocked by:** 06 — 完成 R0 全矩阵行为等价与 durable recovery 门；07 — 冻结 corpus v2 标注、评测记录与手算 golden 合同；08 — 生成不可变的 60-case 分层 corpus v2。

**Status:** planned

## Acceptance criteria

- [ ] verifier 不导入、不调用、不包装 Java scorer。
- [ ] 独立实现 OCR edit distance、layout/order/repeat、semantic/Candidate、calibration 和 aggregate slice 公式。
- [ ] controlled OCR prediction 只通过 ephemeral verifier input 消费，退出后不持久化。
- [ ] 输出只包含 payload-safe counts、metrics、identity 和固定失败 code。
- [ ] annotation、case assignment、identity、record、report 任一单点篡改必须失败。
- [ ] Python 对 ticket 07 的全部 hand-golden 给出完全相同结果。
- [ ] Java/Python 不一致时必须整体失败，不能择优采用某一结果。

## Required gate/evidence

- [ ] Python unit、golden 和 tamper suite 形成 A1。
- [ ] independence inspection 证明没有调用 Java scorer。
- [ ] 与 Java 全量逐项一致形成的 A2 留到 ticket 12。
- [ ] Provider attempts、reservations、cost 为 0。

## Guardrails

- 不将 OCR text、图片、Prompt、Provider I/O 或 Candidate 原文写入 verifier evidence。
- 不使用网络、真实数据或 live run payload。
- 锁定的第三方 metric library 只进入隔离评测 toolchain，不进入产品 runtime。
- Python verifier 不成为第二套产品事实源。
