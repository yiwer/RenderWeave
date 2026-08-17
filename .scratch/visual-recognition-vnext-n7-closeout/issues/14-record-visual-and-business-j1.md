# 14 — 分别记录视觉与业务 J1

**Parent:** N7 / T6-5

**Anchor:** ticket 13 final A2 report、现有 Candidate review 与受控本地 visual evidence 边界

**What to build:** 在技术 A2 通过后组织同一 revision/evaluation identity 的人工验收，分别记录视觉判断和业务判断；本 ticket 不替代 policy decision。

**Blocked by:** 13 — 独立重建 final evaluation A2，且技术 evidence 可供人工审核。

**Status:** planned

## Acceptance criteria

- [ ] `visualDecision` 独立检查 region、bbox、reading order、repeat/item、direct evidence 对齐和视觉可读性。
- [ ] `businessDecision` 独立检查 Schema 拓扑、实体/关系、字段归属、重复项建模、低置信 blocker 和实际审核价值。
- [ ] 两个决定分别记录 J0/J1、reviewer、time、fixed revision、EvaluationIdentity、抽样/全量范围和固定 reason codes。
- [ ] 任一 decision 为 J0、拒绝或范围不足时，整体不得表述为 human accepted 或 certified。
- [ ] 图片和 Candidate 细节只在 allowlisted 本地 review surface 中展示，不复制到普通 evidence、日志或 tracker 文本。
- [ ] 本 ticket 不修改 Profile、Prompt、validator、pipeline 或 policy 状态。

## Required gate/evidence

- [ ] 两份可区分的人工 disposition：visual J1/J0 与 business J1/J0。
- [ ] 自动 A2、视觉 J1、业务 J1 在 evidence matrix 中分栏记录。
- [ ] Payload-safe review manifest 只保存身份、范围、计数和决定，不保存原始载荷。

## Guardrails

- Agent 或自动截图检查不能冒充人类 J1。
- 人工接受不自动修改 Profile certification 或产品默认选择。
- 不引入 Template、RootDocument connect、数据适配、发布或外部工具能力。

