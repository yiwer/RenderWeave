# 08 — 条件执行 Max 20-case DEV qualification

**Parent:** N7 / T6-5

**Anchor:** ticket 07 PASS、ticket 02 frozen 20-case assignments、immutable Max product-v45 Profile

**What to build:** 在与 Plus 完全相同的 20 DEV assignments 和 scorer 上完整重跑 Max，产生可进入唯一 finalist selection 的对齐质量报告。

**Blocked by:** 07 — Max 5-case hard-slice canary PASS；全新的外部 gate `G-LIVE(08)` 必须取得 fresh exact J1 并处于 OPEN。

**Status:** planned

## Acceptance criteria

- [ ] 使用 fresh EvaluationIdentity 完整执行冻结的 20 DEV cases，不复用五例 canary 结果。
- [ ] assignments、corpus、evaluator、slice 和统计规则与 Plus qualification 完全一致；Profile-specific identity 除外。
- [ ] 不读取 HOLDOUT。
- [ ] 全部 quality/safety thresholds 明确 PASS/FAIL；只有通过者可成为 finalist。
- [ ] ledger 在完成或停止后立即 CLOSED；report 不声称 final certification。

## Required gate/evidence

- [ ] 20-case live A1、独立 batch A2、cross-profile assignment equality、Goal/cost/payload/ledger audit。

## Guardrails

- 不根据 Max 结果调整 Plus 报告或选择门。
- 不修改 Prompt、Profile、validator 或 pipeline。
- 不引入任何 R2–R6 实现。

