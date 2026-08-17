# 02 — 冻结 R2–R5 disposition 与 N7 qualification protocol

**Parent:** N7 / T6-5

**Anchor:** ticket 01 report、approved successor delta 的 Further Notes、AC-VR-001..010、AC-021、commit `b50d04e710f3a176b5e95336f912460809939d89`

**What to build:** 把 RapidOCR shadow diagnostic 转换成不可含糊的 R2–R5 trigger disposition，并在任何 live 调用前冻结 N7 的 case assignments、HOLDOUT 边界、质量门、challenger 路由和最佳 Profile tie-break。

**Blocked by:** 01 — RapidOCR corpus v2 零 Provider exact re-anchor。

**Status:** planned

## Acceptance criteria

- [ ] R2、R3、R4、R5 各自得到 `TRIGGERED` 或 `NOT_TRIGGERED` 结论，并逐项引用 successor delta 已批准的证据触发门和 ticket 01 的 payload-safe fact。
- [ ] 触发 R2–R5 不会自动生成或实施相应方案；R6 也不会进入本 DAG。
- [ ] 若继续 N7 必须改变 Prompt、Profile、validator、pipeline、IR/感知算法或合同语义，结论必须是 `STOP_TO_SPEC`，全部后续 live ticket 保持阻塞。
- [ ] 只有在当前冻结行为足以继续评测时，结论才可为 `CONTINUE_N7_CURRENT_BEHAVIOR`。
- [ ] 冻结 5-case Plus canary 的 DEV assignments；冻结覆盖它们的 20-case DEV qualification assignments，但规定 20-case 使用 fresh identity 完整重跑，不复用 canary 结果。
- [ ] 冻结 final authoritative corpus 为现有 `renderweave-visual-stage-corpus/1.0` 的 60 IMAGE_ONLY stage-gold cases、45 DEV + 15 HOLDOUT；corpus v2 不得出现在 final assignment 或 certification identity 中。
- [ ] 冻结 stage、Candidate、AC-VR 和 AC-021 evidence mapping；v1 的 global、mode-slice 和 HOLDOUT 要求保持权威，不足的证据必须报告 `INCOMPLETE`。
- [ ] 冻结 Max/Flash eligibility 的机器可执行布尔表达式，以及“质量优先、成本/延迟次级、稳定 Profile ID 最后破同分”的唯一 finalist tie-break。

## Required gate/evidence

- [ ] Trigger-decision replay、阈值边界和证据缺失负例通过。
- [ ] corpus v1/v2 authority matrix、case-assignment identity、HOLDOUT isolation 和 deterministic selection goldens 通过。
- [ ] 本 ticket Provider attempts、reservations、cost 均为 0。

## Guardrails

- 不根据已看到的 live 结果回改 case、阈值、eligibility 或 tie-break。
- 不修改 Prompt 12/7/4、Profile、validator 或 pipeline；算法变化只能转入新的 `$to-spec`。
- 不把 R2–R6 转为实现 ticket。
- 常规证据不得包含图片、Prompt、OCR/模型/Candidate 原文。

