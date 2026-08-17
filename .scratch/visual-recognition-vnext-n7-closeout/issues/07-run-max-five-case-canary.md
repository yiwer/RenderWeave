# 07 — 条件执行 Max 5-case hard-slice canary

**Parent:** N7 / T6-5

**Anchor:** ticket 06 `MAX_ELIGIBLE` disposition、ticket 02 frozen hard/nested assignments、immutable Max product-v45 Profile

**What to build:** 仅在 Plus 暴露预声明 hard/nested 质量缺口时，用 Max 对对齐的五个 DEV cases 做有界 canary，验证它是否值得进入 20-case qualification。

**Blocked by:** 06 — 结果必须为 `MAX_ELIGIBLE`；外部 gate `G-LIVE(07)` 必须取得 Max-specific fresh exact J1 并处于 OPEN。

**Status:** planned

## Acceptance criteria

- [ ] 只执行 ticket 02 冻结的五个 hard/nested DEV assignments，batch≤5，不访问 HOLDOUT。
- [ ] 5/5 到达 `REVIEW_REQUIRED`；Bundle contract、evidence coverage、DAG validity 100%，critical hallucination=0。
- [ ] Max 在预声明 hard/nested slice 满足 ticket 02 的 canary improvement gate；不得用非预声明指标合理化扩量。
- [ ] 全部 stage、attempt、usage、cost、latency 和 terminal state 可从 journal 重建。
- [ ] ledger 无论结果如何立即 CLOSED；FAIL 后 ticket 08 标记 `NOT_APPLICABLE`。

## Required gate/evidence

- [ ] Max exact J1、live A1、batch A2、Goal aggregate、payload scan 和 CLOSED probe。

## Guardrails

- 不修改 Max Profile、Prompt 12/7/4、validator 或 pipeline。
- 不扩大 case 数，不读取 HOLDOUT，不自动改走 Flash。
- 常规证据不包含图片、Prompt、OCR/模型/Candidate 原文。

