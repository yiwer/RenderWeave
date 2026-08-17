# 10 — 条件执行 Flash 20-case DEV qualification

**Parent:** N7 / T6-5

**Anchor:** ticket 09 PASS、ticket 02 frozen 20-case assignments、approved exact Flash identity

**What to build:** 在相同 20 DEV cases 上完整重跑 Flash，先证明质量非劣，再记录成本和延迟优势，为唯一 finalist selection 提供对齐证据。

**Blocked by:** 09 — Flash 5-case non-inferiority canary PASS；全新的外部 gate `G-LIVE(10)` 必须取得 fresh exact J1 并处于 OPEN。

**Status:** planned

## Acceptance criteria

- [ ] 使用 fresh EvaluationIdentity 完整执行冻结的 20 DEV cases，不复用五例 canary 结果。
- [ ] assignments、corpus、evaluator、slice 和统计规则与 Plus qualification 完全一致；Profile-specific identity 除外。
- [ ] 不读取 HOLDOUT。
- [ ] 所有 hard quality/safety gates 先通过，之后才允许比较 Token、费用和延迟。
- [ ] ledger 在完成或停止后立即 CLOSED；report 不声称 final certification。

## Required gate/evidence

- [ ] 20-case live A1、独立 batch A2、Plus/Flash paired comparison、Goal/cost/payload/ledger audit。

## Guardrails

- 成本优势不能补偿 quality/safety failure。
- 不修改 Prompt、Profile、validator、pipeline 或 frozen selection policy。
- 不实现 R2–R6。

