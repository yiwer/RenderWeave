# 12 — 完成 R1 跨语言一致性、payload 安全与最终门控

**Parent:** N9 / R1

**Anchor:** approved successor spec、ADR-0036、commit 19e22854e0be236d0068336a32969356a6befaf8

**What to build:** 形成 N9/R1 的正式完成证据：完整 corpus v2、Java/Python 一致报告、受控 visual diff 和 full gate，同时保持 product-v45 的真实生命周期状态。

**Blocked by:** 06 — 完成 R0 全矩阵行为等价与 durable recovery 门；09 — 输出 Java 分层评测与 payload-safe scorecard；10 — 由 Python 独立重算分层指标并拒绝篡改；11 — 生成 allowlisted synthetic/CC0 本地 visual diff。

**Status:** planned

## Acceptance criteria

- [ ] 60 cases 全部可按 global、partition、domain、difficulty 和 failure slice 重建。
- [ ] Java/Python 对 identity、case accounting 和全部规定指标逐项一致。
- [ ] annotation、identity、record 和 report 篡改负例全部失败。
- [ ] 普通 evidence 对图片、OCR、Prompt、模型 I/O、Candidate 原文和完整 bbox 列表零泄漏。
- [ ] visual diff 只留在 allowlisted 本地评测范围，不进入普通 evidence。
- [ ] Provider attempts、reservations、cost 为 0，历史 product-v45 和 corpus 1.0 bytes 不变。
- [ ] R2–R6 的证据触发门不因 R1 完成而自动满足。
- [ ] product-v45 继续 EXPERIMENTAL；N7、AC-021、AC-VR-010 和最终业务/视觉 J1 均不被自动关闭。

## Required gate/evidence

- [ ] corpus/eval 形成 A1。
- [ ] Java/Python independent replay 形成 A2。
- [ ] independent payload scan 形成 A2。
- [ ] server + full gate 通过。
- [ ] visual diff 保持 J0，不能冒充人工接受。

## Guardrails

- R1 完成只证明评测基础设施与可复算性，不证明产品质量晋级。
- 不启动 R2 shadow challenger、R3 reading-order 新合同、R4 structured output、R5 adaptive perception 或 R6 workflow framework。
- 不修改 v1 权威规格、历史 Profile、Prompt、pipeline、run snapshot、corpus 1.0 或 StaticSchema。
- 不授权 live、费用、真实数据、Template、RootDocument connect、数据适配或发布能力。
