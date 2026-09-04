# 40 — IOPA-P1-R25：执行并不可变关闭 v51 diagnostic

**What to build:** 只在票 39 exact OPEN J1 有效期内，对唯一获批 artifact 执行一次 v51 non-scoring
diagnostic；无论 `REVIEW_REQUIRED`、classified rejection 或运行故障，都关闭 authorization、结清
PostgreSQL ledger 并形成 payload-free terminal。

**Blocked by:** 39 — IOPA-P1-R24。

**Status:** resolved

- [x] 调用前逐字段验证 Profile/manifest/evaluator/normalization/provider/model/base URL/data/case/caps/scope/timing。
- [x] 每次外传前 reservation/permit、每次 attempt 后 append-only settlement；不得 run 级自动重跑。
- [x] 只允许 1 run / 5 calls / 100k tokens / ¥3 / per-run 5+¥3，并受 Goal aggregate 1.5M model-token cap 约束。
- [x] terminal 后 CLOSED、unsettled=0、OPEN=0；证据只含 identities、fixed codes、聚合 tokens/cost 与 digests。
- [x] classified parent-containment rejection 必须只调用一次并保留 canonical detail code；不得因分类诊断再调用 Provider。
- [x] `REVIEW_REQUIRED` 只创建 owner review pack；FAILED 则冻结 negative terminal 并按 Blueprint 附录 A 新开 source ticket。
- [x] 不计分/grant，不 apply Candidate，不发布 StaticSchema，不部署或 commit/push。

## Resolution evidence

- live A1=`.sdlc/evidence/20260818-143933-image-only-v51-successor-diagnostic-live/`
- live summary SHA-256=`102fb5a99fe8ebf313fef77c1834ee33e81d6cbad8aebd915cf37c079ae602f1`
- post-close A1=`.sdlc/evidence/20260818-144607-image-only-v51-diagnostic-postclose/`
- post-close summary SHA-256=`598c02e3897d67375abecc39b04674ea80a8595c6758db3f4a0d4da4cdac406b`
- result=`FAILED / VISUAL_GROUNDING_PARENT_CONTAINMENT_CLASSIFIED`
- detail=`VISUAL_GROUNDING_PARENT_CONTAINMENT_ITEM_ZERO_COMPATIBLE`
- usage=1 call / 13,845 model tokens / ¥0.228780；second reservation=false；unsettled=0
- Goal usage=121,618/1,500,000，remaining=1,378,382；OPEN=0
- closed authorization SHA-256=`3ba6ef75552f6b259d4ec271b6f2b0c9558a03a94c7d0c8478f074bf1cbab483`
- terminal SHA-256=`1e9825a4f3feb59972d03671c1952e934de915d281759403e933f3cbb75075bb`

FAILED 不解锁认证；按 Blueprint 附录 A 创建 ticket 41，v51 不重跑。
