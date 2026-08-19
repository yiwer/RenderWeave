# 07 — 冻结 corpus v2 标注、评测记录与手算 golden 合同

**Parent:** N9 / R1

**Anchor:** approved successor spec、ADR-0036、commit c12f23d76a6fc76a6a38042ff89bbd166e6012b5

**What to build:** 定义 R1 的 strict annotation、ephemeral scorer input、payload-safe evaluation record 和 report identity，使 Java 与 Python 可以依据同一合同独立实现。不发布半成品 corpus 2.0 manifest。

**Blocked by:** 06 — 完成 R0 全矩阵行为等价与 durable recovery 门。

**Status:** planned

## Acceptance criteria

- [ ] annotation 合同覆盖 OCR line/token、layout region/kind、SLOT/GROUP/REPEATED_GROUP/ITEM、box/polygon owner、precedence edge、repeat membership/item count、Entity/Relationship/Binding/Candidate 和 abstention。
- [ ] controlled synthetic/CC0 gold 可以保存预期文字；runtime/predicted OCR text 只能作为评测过程中的 ephemeral input。
- [ ] 持久化 evaluation record 只包含 case identity、计数、比率、固定 code、耗时、费用和允许的 aggregate。
- [ ] EvaluationIdentity 覆盖 input-set、annotation、render、policy、adapter/weight、projection/order、shape catalog、Prompt、validator、materializer、evaluator、预算和 decoding mode。
- [ ] 为 CER/WER、AP/IoU、precedence、repeat、binding、tree/topology、ECE/Brier 和空 gold/prediction 建立手算 micro-goldens。
- [ ] unknown member、identity drift、annotation tamper 和 record tamper fail-closed。

## Required gate/evidence

- [ ] annotation/record strict contract tests 通过。
- [ ] metric hand-golden tests 通过。
- [ ] identity drift/tamper negative suite 通过。
- [ ] 形成 A1；Provider attempts、reservations、cost 为 0。

## Guardrails

- 必须继承已完成的 R0 exact IR、policy、projection 和 shape-catalog identity。
- runtime OCR text、用户图片、Prompt、Provider I/O 和 Candidate 原文不成为普通 evaluation record。
- 不创建用户数据集、live ledger、数据库 migration 或产品 API。
- R2–R6 不转成 ticket。
