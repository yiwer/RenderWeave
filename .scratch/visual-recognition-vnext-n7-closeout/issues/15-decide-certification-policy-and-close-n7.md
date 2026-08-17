# 15 — 作出 certification policy decision 并如实关闭 N7

**Parent:** N7 / T6-5

**Anchor:** ticket 13 final A2、ticket 14 visual/business J1、AC-VR-010、v1 AC-021、RULE-STATE-001

**What to build:** 将技术 A2、视觉 J1 和业务 J1 转成独立、可审计的产品 policy disposition，并在全部账本与门控闭合后如实更新 N7 生命周期；不得把自动验证、人工接受和生产政策合并成一个结论。

**Blocked by:** 13 — final A2；14 — visual/business J1。若 ticket 11 为 `NO_FINALIST`，可跳过 12–14，直接以该终止证据进入本 ticket 的 `REMAIN_EXPERIMENTAL` 路径。

**Status:** planned

## Acceptance criteria

- [ ] policy 输出只能为 `CERTIFICATION_ELIGIBLE`、`REMAIN_EXPERIMENTAL` 或 `STOP_TO_SPEC`，并逐项引用 AC-VR-010 与 AC-021 evidence。
- [ ] 任一 required AC、A2、visual J1、business J1、mode-slice 或 HOLDOUT 证据未满足时，必须为 `REMAIN_EXPERIMENTAL`。
- [ ] 不修改既有 Prompt、Profile bytes、validator 或 pipeline；如需创建新的 immutable certified Profile、改变默认选择或调整算法，后续动作必须进入新的 `$to-spec`。
- [ ] 所有 live ledger CLOSED，Goal guard 未 breached，attempt/token/cost 可独立重建，secret/payload scan 通过。
- [ ] 在最后一次允许的代码或治理变化后，fixed final revision 的受影响 gate 与 full gate 绿色。
- [ ] N7、T6-5 和 AC evidence matrix 按真实状态更新；自动证据通过但 J1/policy 未完成时不得报告 accepted 或 complete。
- [ ] 不创建 tag、不发布、不把 Template、RootDocument connect、数据适配或发布能力纳入 N7。

## Required gate/evidence

- [ ] 独立 policy J1，与 ticket 14 的视觉/业务 J1 分开记录。
- [ ] Final full A1、final A2、human dispositions、policy decision 和全部 ledger closure 可相互追溯。
- [ ] Scope inventory 证明无开放式 Agent、通用工具执行器、LangGraph、Temporal 或 Out-of-Scope 能力。

## Guardrails

- `CERTIFICATION_ELIGIBLE` 只是本次证据允许 policy 前进；任何需要改变产品资源的动作仍遵守 additive immutable 规则和新的规格锚点。
- 不因剩余 token、费用或时间额度而继续调用 Provider。
- 常规证据不得包含图片、Prompt、OCR/模型/Candidate 原文。
