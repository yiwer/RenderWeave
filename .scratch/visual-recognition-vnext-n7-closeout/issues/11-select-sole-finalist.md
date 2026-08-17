# 11 — 在不启封 HOLDOUT 的前提下选择唯一 finalist

**Parent:** N7 / T6-5

**Anchor:** ticket 02 frozen tie-break、Plus qualification、全部 eligible challenger dispositions

**What to build:** 用冻结规则从已完成的 20-case DEV qualification 中选择唯一 final Profile，或明确没有任何 Profile 可以进入 final 60；本 ticket 不调用 Provider，也不改变 Profile registry。

**Blocked by:** 05 — Plus 20-case DEV qualification；08 — Max qualification 已完成或有可验证的 `NOT_APPLICABLE` disposition；10 — Flash qualification 已完成或有可验证的 `NOT_APPLICABLE` disposition。

**Status:** planned

## Acceptance criteria

- [ ] 只有通过全部 20-case quality/safety gates 的 Profile 才能进入 finalist set。
- [ ] 先按最弱质量 margin 排序；仅在 ticket 02 冻结的非劣区间内比较 cost、latency，最后用稳定 Profile ID 破同分。
- [ ] selection 过程不得读取 HOLDOUT、修改阈值或回看 corpus v2 shadow 指标改变排名。
- [ ] 输出唯一 `FINALIST_SELECTED` 和 exact Profile snapshot，或输出 `NO_FINALIST` 及固定 reason codes。
- [ ] 不修改 Profile bytes、catalog selection 或 certification 状态。
- [ ] `NO_FINALIST` 阻止 ticket 12 live，并直接为 ticket 15 提供 `REMAIN_EXPERIMENTAL` 输入。
- [ ] Provider attempts、reservations、cost 均为 0。

## Required gate/evidence

- [ ] Independent selection replay、tie、missing challenger、threshold boundary 和 tampered report 负例通过。
- [ ] finalist decision 精确绑定 ticket 02 protocol identity 和所有 qualification report identity。

## Guardrails

- 只能选择一份现有 immutable Profile；不得合成新 Profile 或自动跨模型路由。
- 不启封 HOLDOUT，不触发 J1，不打开 ledger。
- 不把 finalist selection 表述为 certified 或 accepted。

