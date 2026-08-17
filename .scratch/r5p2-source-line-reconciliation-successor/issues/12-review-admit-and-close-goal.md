# 12 — 双轴 review、最终 admission 与 Goal closeout

**What to build:** 在同一 exact revision 并行完成 Standards 与 Spec 两轴 code review，处理发现后重跑失效的 gates，并由独立 verifier 重建完整 authority-to-product terminal，最终以真实 disposition 关闭 R5P2 Goal。

**Blocked by:** R5P2-07 reaching `R5P2_ACTION_IMPLEMENTATION_ALLOWED` and R5P2-11 exact-revision gates passing — either negative path closes this ticket.

**Status:** ready-for-agent

- [ ] Standards review 以 repository documented standards、module boundaries、TDD/evidence rules、privacy 与 operational constraints 审查 exact revision；Spec review 逐项追踪 AC-R5P2-001..024 与 approved ticket DAG。
- [ ] 两轴由并行、相互独立的 review 执行并侧列 findings；不得用一轴结论替代另一轴，也不得把 automated green 表述为人工 acceptance。
- [ ] 任一修复产生新 revision 后，旧 exact full、payload scan、independent terminal 与受影响 review evidence 不得复用；在新 clean revision 重跑后才能继续 admission。
- [ ] final verifier 从 content-addressed evidence 独立重算 authority、conformance、assignment、paired A2、offline decision、action/workflow、v45 equivalence、privacy、full gate、reviews 与 Provider-zero。
- [ ] 任一 upstream failure、missing evidence、dirty revision、payload violation、review finding 或 non-zero Provider usage 输出 `R5P2_PRODUCT_PATH_NOT_QUALIFIED`，如实关闭 Goal，不能被 later green result 覆盖。
- [ ] 只有全部离线门与 final independent terminal 通过才输出 `R5P2_SUCCESSOR_LIVE_REQUEST_ELIGIBLE`；该结果仅为 J0 eligibility，不创建 live ticket、authorization、ledger、reservation 或 J1。
- [ ] Goal closeout summary 只包含 exact revision、identities、gate/review results、fixed terminal codes、zero-use facts 与 evidence digests，不含敏感 payload。
- [ ] Provider attempts、reservations、cost、API-key reads 最终均为 0；任何未来 live 行为必须另行取得 fresh exact J1。

