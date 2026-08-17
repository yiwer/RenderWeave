# 05 — Plus 20-case DEV qualification

**Parent:** N7 / T6-5

**Anchor:** ticket 02 frozen 20-case assignments、ticket 04 PASS、immutable Plus product-v45 Profile

**What to build:** 用 fresh identity 对冻结的完整 20-case DEV slice 重新执行 Plus，形成可用于 challenger eligibility 和 finalist 选择的 qualification report，而不是把五例 canary 扩写成质量结论。

**Blocked by:** 04 — Plus 5-case canary PASS；全新的外部 gate `G-LIVE(05)` 必须取得 fresh exact J1 并处于 OPEN。

**Status:** planned

## Acceptance criteria

- [ ] 使用 ticket 02 冻结的恰好 20 个 DEV assignments；建立新 EvaluationIdentity，并完整重跑其中五个 canary cases。
- [ ] 不读取、解密、选择或评测任何 HOLDOUT case。
- [ ] 报告覆盖 stage survival、element/group、grounding、entity/relationship、binding、Candidate、Token、费用和延迟，并按预声明 slice 聚合。
- [ ] ticket 02 冻结的每一项 hard safety gate 和 qualification threshold 均得到明确 PASS/FAIL，不能在看到结果后改阈值。
- [ ] report 明确为 `QUALIFICATION`，不得声称 AC-021 certification 或 production reliability。
- [ ] ledger 在全部 assignments 完成或任一停止条件命中后立即 CLOSED。

## Required gate/evidence

- [ ] 20-case live A1 report。
- [ ] Assignment、journal、usage、cost、slice metrics 和 Goal aggregate 的独立 A2 重算。
- [ ] Payload/secret scan、identity drift 负例和 CLOSED probe 通过。

## Guardrails

- Prompt 12/7/4、Profile、validator 和 pipeline 4.28 字节及语义不变。
- 不用 canary 的五例结果替代 qualification 中的 fresh rerun。
- 常规证据 payload-free；Template、RootDocument connect、数据适配和发布能力不在范围内。

