# 35 — IOPA-P1-R20：执行并不可变关闭 v50 diagnostic

**What to build:** 只在票 34 exact OPEN J1 有效期内，对唯一获批 artifact 执行一次 v50 non-scoring diagnostic；无论
`REVIEW_REQUIRED`、rejection 或运行故障，都关闭 authorization、结清 PostgreSQL ledger 并形成 payload-free terminal。

**Blocked by:** 34 — IOPA-P1-R19。

**Status:** pending

- [ ] 调用前逐字段验证 Profile/manifest/evaluator/normalization/provider/model/base URL/data/case/caps/scope/timing。
- [ ] 每次外传前 reservation/permit、每次 attempt 后 append-only settlement；不得 run 级自动重跑。
- [ ] 只允许 1 run / 5 calls / 100k tokens / ¥3 / per-run 5+¥3，并受 Goal aggregate 1.5M model-token cap 约束。
- [ ] terminal 后 CLOSED、unsettled=0、OPEN=0；证据只含 identities、fixed codes、聚合 tokens/cost 与 digests。
- [ ] `REVIEW_REQUIRED` 只创建 owner review pack；FAILED 则冻结 negative terminal 并另开 source ticket。
- [ ] 不计分/grant，不 apply Candidate，不发布 StaticSchema，不部署或 commit/push。
