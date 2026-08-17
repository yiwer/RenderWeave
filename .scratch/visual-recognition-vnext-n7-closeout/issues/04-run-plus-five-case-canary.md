# 04 — 执行 Plus 5-case canary

**Parent:** N7 / T6-5

**Anchor:** ticket 02 frozen protocol、ticket 03 live admission gate、immutable Plus product-v45 Profile

**What to build:** 用冻结 Plus Profile 对五个预声明 DEV cases 执行第一条真实 tracer bullet，证明同一版本能从图片输入经串行视觉阶段和现有 PostgreSQL durable state machine 到达可审核结果。

**Blocked by:** 03 — 证明 zero-provider live admission；外部 gate `G-LIVE(04)` 必须取得 fresh exact J1 并处于 OPEN。

**Status:** planned

## Acceptance criteria

- [ ] 只执行 ticket 02 冻结的五个 DEV assignments；串行执行且单批不超过 5。
- [ ] 五个 case 均使用同一 exact revision、EvaluationIdentity、Profile snapshot、Prompt 12/7/4、pipeline 4.28 和 evaluator。
- [ ] 5/5 到达 `REVIEW_REQUIRED`，Bundle contract、evidence coverage、DAG validity 均为 100%，critical hallucination 为 0。
- [ ] stage survival、fixed problem codes、attempt、token、cost、latency 和 terminal state 均可从 journal 重建。
- [ ] 不发生 identity、budget、lease、payload、secret 或 unbound-attempt 异常。
- [ ] 无论 PASS、FAIL、Provider refusal 或停止条件命中，ledger 均立即 CLOSED；FAIL 时 ticket 05 及 challenger live 保持阻塞。
- [ ] 失败不得通过修改 Prompt、Profile、validator 或 pipeline 在本 ticket 内重试解决。

## Required gate/evidence

- [ ] Live A1 report 和全部五例 terminal evidence。
- [ ] Batch-only independent ledger/usage/metric replay 形成 A2。
- [ ] Payload scan、Goal guard、process/lease 和 CLOSED negative probe 通过。

## Guardrails

- OCR text 保持 ephemeral；证据不得包含图片、Prompt、OCR/模型/Candidate 原文或 Provider request ID。
- 不访问 HOLDOUT，不扩大到第六个 case。
- 不引入新算法、工具能力或编排框架。

