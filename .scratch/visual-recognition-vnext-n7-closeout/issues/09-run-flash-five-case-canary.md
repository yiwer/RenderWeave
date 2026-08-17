# 09 — 条件执行 Flash 5-case non-inferiority canary

**Parent:** N7 / T6-5

**Anchor:** ticket 06 `FLASH_ELIGIBLE` disposition、ticket 02 frozen assignments、approved exact Flash identity

**What to build:** 仅在 Plus 已满足质量门时，用 Flash 对五个对齐 DEV cases 验证质量非劣性；更低费用本身不能构成扩大评测的理由。

**Blocked by:** 06 — 结果必须为 `FLASH_ELIGIBLE`；外部 gate `G-LIVE(09)` 必须取得 Flash-specific fresh exact J1 并处于 OPEN。

**Status:** planned

## Acceptance criteria

- [ ] 只执行 ticket 02 冻结的五个 DEV assignments，batch≤5，不访问 HOLDOUT。
- [ ] 5/5 到达 `REVIEW_REQUIRED`；Bundle contract、evidence coverage、DAG validity 100%，critical hallucination=0。
- [ ] 质量位于 ticket 02 冻结的 Plus 非劣区间内；成本或延迟优势不能抵消任何质量失败。
- [ ] Flash model/Profile identity 与 exact J1 完全一致，不在 generic alias 与 pinned snapshot 之间静默切换。
- [ ] ledger 无论结果如何立即 CLOSED；FAIL 后 ticket 10 标记 `NOT_APPLICABLE`，Plus 保持 finalist。

## Required gate/evidence

- [ ] Flash exact J1、live A1、batch A2、paired Plus comparison、Goal/payload/CLOSED audit。

## Guardrails

- 不修改 Flash Profile、Prompt 12/7/4、validator 或 pipeline。
- 不扩大 case 数，不读取 HOLDOUT，不自动改走 Max。
- 常规证据不包含图片、Prompt、OCR/模型/Candidate 原文。

