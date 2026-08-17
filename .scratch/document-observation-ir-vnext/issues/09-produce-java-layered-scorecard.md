# 09 — 输出 Java 分层评测与 payload-safe scorecard

**Parent:** N9 / R1

**Anchor:** approved successor spec、ADR-0036、commit 19e22854e0be236d0068336a32969356a6befaf8

**What to build:** Java evaluator 从 acquisition、三个语义阶段和 Candidate 的受控记录生成完整分层 report，使维护者能判断质量损失发生在 OCR、布局、顺序、重复组、语义阶段还是 Candidate。

**Blocked by:** 06 — 完成 R0 全矩阵行为等价与 durable recovery 门；07 — 冻结 corpus v2 标注、评测记录与手算 golden 合同；08 — 生成不可变的 60-case 分层 corpus v2。

**Status:** planned

## Acceptance criteria

- [ ] OCR 报告 CER、WER、empty-reference insertion/hallucination 和完全漏检率。
- [ ] Layout 报告 per-kind precision/recall、AP@[.50:.95] 或锁定等价实现、IoU、evidence recall 和错误 evidence。
- [ ] Order 报告 precedence precision/recall/F1 和 cycle rate。
- [ ] Repeat 报告 group/item recall、item-count absolute error 和 membership accuracy。
- [ ] Semantic/Candidate 报告 SLOT/GROUP、Entity、Relationship、cardinality、binding、owner containment、stage survival、repair yield 和既有 Candidate/topology 指标。
- [ ] Calibration/review/runtime 报告 bucket accuracy、ECE/Brier、unresolved precision、REVIEW_REQUIRED reachability、call/token/cost/latency/recovery/accepted-stage replay。
- [ ] report 同时输出 global、DEV/HOLDOUT、domain、difficulty 和 failure slices。
- [ ] report canonical、byte-stable，且不包含图片、OCR、Prompt、Provider I/O、Candidate 原文或完整 bbox 列表。

## Required gate/evidence

- [ ] 每类指标的 hand-golden 通过。
- [ ] 空集合、边界、重复、cycle 和 identity negative tests 通过。
- [ ] 完整 60-case Java evaluation 形成 A1。
- [ ] payload scan 通过；Provider attempts、reservations、cost 为 0。

## Guardrails

- 不用全局平均隐藏 dense text、multi-column、repeated list 或 prompt-injection slice。
- 不改变既有 semantic/Candidate 指标定义来改善分数。
- visual diff 不进入普通 report。
- 未通过独立 Python 一致性前，不声明 R1 完成。
