# 12 — 最佳 Profile final 60-case 与 HOLDOUT

**Parent:** N7 / T6-5

**Anchor:** ticket 11 sole finalist、`renderweave-visual-stage-corpus/1.0` authoritative assignments、AC-VR-001..010

**What to build:** 只用唯一最佳 Profile 对 authoritative corpus 1.0 的全部 60 IMAGE_ONLY stage-gold cases 做 final live evaluation，包括 45 DEV 和首次用于本轮 final 的 15 HOLDOUT。

**Blocked by:** 11 — 结果必须为 `FINALIST_SELECTED`；全新的外部 gate `G-LIVE(12)` 必须绑定该 Profile、60 assignments 和 fresh exact J1 并处于 OPEN。

**Status:** planned

## Acceptance criteria

- [ ] final assignments 来自 corpus 1.0 的恰好 60 cases、45 DEV + 15 HOLDOUT；corpus v2 不进入 assignment、identity、scorer 或 certification report。
- [ ] 只有 ticket 11 选中的 exact Profile 可执行；Max/Plus/Flash 其余 Profile 不得进入 final 或 HOLDOUT。
- [ ] 全部 batches≤5、串行执行，每个 case 有唯一 assignment、run 和可恢复终态。
- [ ] 输出 global、DEV、HOLDOUT、domain、difficulty、stage、Candidate、Token、费用和延迟指标。
- [ ] HOLDOUT 结果不得触发本轮阈值调整、模型重选、Prompt/Profile/validator/pipeline 修改或同 identity 重跑。
- [ ] report 只记录评测事实；certification 和 policy 状态保持 pending。
- [ ] 任一算法变化成为必要条件时立即停止、关闭 ledger，并转入新的 `$to-spec`。
- [ ] 全部 live ledger 在完成、失败或停止后立即 CLOSED。

## Required gate/evidence

- [ ] 60-case live A1、corpus/identity lock、Goal guard、journal completeness、payload scan 和 CLOSED probes。
- [ ] 为 ticket 13 提供 immutable、payload-safe journal 与报告输入，但不自我声明 A2。

## Guardrails

- 不修改 Prompt 12/7/4、Profile、validator、pipeline 4.28 或 corpus 1.0。
- OCR text 保持 ephemeral；证据不得包含图片、Prompt、OCR/模型/Candidate 原文。
- Template、RootDocument connect、数据适配和发布能力全部 Out of Scope。

