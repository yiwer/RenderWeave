# 06 — 机械应用 Max/Flash challenger eligibility matrix

**Parent:** N7 / T6-5

**Anchor:** ticket 02 frozen eligibility matrix、ticket 05 Plus qualification report

**What to build:** 在零 Provider 条件下机械决定是否需要 Max hard/nested challenger、Flash low-cost non-inferiority challenger，或不再调用 challenger；不得为了消耗预算或增加样本量调用模型。

**Blocked by:** 05 — Plus 20-case DEV qualification。

**Status:** planned

## Acceptance criteria

- [ ] 若 Plus 通过全部 qualification quality/safety gates，则 Plus 成为 finalist，只允许 Flash 进入低成本非劣 challenger 路径，Max 标记为 `NOT_APPLICABLE`。
- [ ] 若 Plus 通过 integrity/safety gates，但只在预声明 HARD/NESTED 语义 slice 未达标，则只允许 Max 路径，Flash 标记为 `NOT_APPLICABLE`。
- [ ] 若存在 contract、evidence、DAG、payload、identity、预算或非预声明失败，则 Max/Flash 均不得调用，结论为 `NO_CHALLENGER` 或 `STOP_TO_SPEC`。
- [ ] 输出唯一的 `MAX_ELIGIBLE`、`FLASH_ELIGIBLE` 或 `NO_CHALLENGER`，并提供固定 reason codes。
- [ ] 不得修改 ticket 02 冻结的 predicate、threshold 或 tie-break。
- [ ] Flash 的 exact N7 model/Profile identity 必须与 approved delta 唯一相符；若 pinned snapshot 与当前 generic product Profile 的权威关系无法在现有规格中唯一解析，则 `STOP_TO_SPEC`，不得新建或改写 Profile。
- [ ] Provider attempts、reservations、cost 均为 0。

## Required gate/evidence

- [ ] Deterministic decision replay、threshold boundary、missing metric 和 tampered report 负例通过。
- [ ] 输出 conditional DAG disposition，明确 tickets 07–10 中哪些保持 planned、哪些为 `NOT_APPLICABLE`。

## Guardrails

- Challenger eligibility 是证据路由，不是模型排名或 policy promotion。
- 不读取 HOLDOUT，不触发 J1，不打开 ledger。
- 不修改 Prompt、Profile、validator、pipeline 或任何质量事实源。

