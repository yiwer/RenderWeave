# R5P-04 — 冻结 seen 与 sealed confirmation assignment

- Status: planned
- Spec: `specs/changes/20260815-r5-paired-product-view-successor.md`
- Blocked by: R5P-03 `R5P_OFFLINE_ACTION_CORE_READY`
- Provider boundary: attempts/reservations/cost/API-key reads must remain 0

## What it delivers

在第一条新质量结果产生前，冻结旧四例 `SEEN_DIAGNOSTIC` 与一组互斥的 3 DEV + 1 corpus-v2 HOLDOUT
`SEALED_CONFIRMATION`，并形成新的 assignment/evaluation identity 基础。

## Acceptance criteria

- [ ] seen set 精确等于旧 R5 assignment 四例，且统一标记为 `SEEN_DIAGNOSTIC`。
- [ ] confirmation 精确包含 3 DEV + 1 HOLDOUT，与 seen set 完全不重叠。
- [ ] case、partition、regions、presets、thresholds、normalizer、transform、planner、capability、runtime 和 evaluator identities 全部冻结。
- [ ] 旧 HOLDOUT 不得重命名、重分区或复制进入 confirmation。
- [ ] assignment manifest 在 execution code 读取任何新结果前完成并可独立重算。
- [ ] seen 只能形成 veto，不能贡献 confirmation/HOLDOUT PASS 或 AC-021 claim。

## Required gate/evidence

- Pre-result immutable manifest A1。
- Assignment/access/overlap A2 audit。
- Terminal code: `R5P_ASSIGNMENT_FROZEN`。

