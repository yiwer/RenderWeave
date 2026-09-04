# 23 — IOPA-P1-R08：执行并不可变关闭 v48 diagnostic

**What to build:** 在且仅在所有者签发 fresh exact scoped J1 后，对获批单 case 执行一次 v48 非计分 diagnostic，并无论成功、拒绝还是运行故障都关闭 authorization、结清 ledger、形成 payload-free terminal。该运行只能生成待人工审核的 Candidate 或不可变 negative terminal，不能自行进入评分认证。

**Blocked by:** 22 — IOPA-P1-R07：准备 v48 单例非计分 diagnostic authority；以及所有者另行签发、当时仍有效的 fresh exact J1。

**Status:** resolved

At the Provider-zero checkpoint on 2026-08-18, prepared authorization
`20260818-iopa-v48-diagnostic-4e1f41b7` remained `PENDING_J1`; no OPEN authorization existed and no
Provider call was permitted until the owner signed the exact scope.

Owner exact J1 was received for the prepared scope. The immutable authorization window is
`2026-08-18T00:46:36.8347501Z` through `2026-08-18T02:46:36.8347501Z`; execution remains limited to
one non-scoring run, five Provider calls, 100,000 model tokens, and CNY 3.00.

Executed once on 2026-08-18 and closed as an immutable negative terminal. The only Provider
attempt was OBSERVE/REJECTED with `VISUAL_GROUNDING_REGION_INVALID`; because this generic code is
not a Prompt 14 field-specific allowlist code, the approved policy denied another reservation.
Ledger totals: 1 run, 1 Provider call, 13,394 model tokens, CNY 0.218928, and 0 unsettled
reservations. No Candidate was produced or applied; no StaticSchema was published; no scoring
credit or next-stage grant was awarded.

Live A1 evidence is `.sdlc/evidence/20260818-084802-image-only-v48-successor-diagnostic-live/`;
summary SHA-256 is `ee1cdee1506ae0301aa7985b26c8728819f2f9d42a3606ee76e208ccb3f80ae1`.
Closed authorization SHA-256 is `6f102c53c6192fea00ef02f1a72256f85f73c0a6abb8faee67bf80100118437b`;
terminal SHA-256 is `316029ebdf55bb5cb1dabe193f4f44b2b87dc971cd145e564d1b0c3006df811c`.
Provider-zero post-close evidence `.sdlc/evidence/20260818-085324-image-only-v48-successor/`
passed with verifier 4/4, inference 86/86, PostgreSQL 9/9, and no additional Provider usage.

- [x] 调用前逐字段验证 J1 的 exact Profile/manifest/evaluator/normalization/provider/model/base URL/data/case/caps/scope/timing；任一不匹配或过期立即停止且 provider attempts 为 0。
- [x] agent 不读取、不打印、不复制 API Key；runtime 只能通过既有 secret 注入和 exact route 发起授权范围内的调用。
- [x] 只执行获批的一个 diagnostic run；calls、model tokens、费用、per-run cap 和时限均不得超过 J1，且不授权另一个 cycle、自动 rerun 或 scoring credit。
- [x] 每次外传前完成 permit/reservation；每次 attempt 后 append-only 结算。breaker 或 hard cap 到达时不得发起下一次调用。
- [x] terminal、异常或人工中止后立即把 authorization 与 PostgreSQL ledger 关闭并核对，最终 OPEN authorization 数为 0、unsettled reservation 为 0。
- [x] 若结果为 REVIEW_REQUIRED，只生成 payload-free owner review pack；Candidate 保持 unapplied，StaticSchema 保持 unpublished，Profile 保持 uncertified/ungranted。
- [x] 若结果为 FAILED，保留 immutable negative terminal 并停止；任何后续 Profile/Prompt/pipeline/cap/assignment 变化或再次 live 必须新 source ticket、新 identities、新 cycle 和 fresh J1。
- [x] A1 evidence 只记录 exact identities、attempt/stage/fixed-code 序列、聚合 token/费用、breaker/ledger/terminal 状态和 digest，不记录原始图片、完整请求/响应、RootDocument 或 chain-of-thought。
- [x] diagnostic 不计入 5/20/60，不解锁评分 canary、P1-02/P1-03、Candidate apply、StaticSchema 发布或生产部署；只有 REVIEW_REQUIRED 后的所有者人工接受，才允许另行拆评分认证票。
- [x] live 结束后运行 provider-zero post-close gate，并记录 J1、terminal 与证据 digest 的一致性；不得 git commit 或 push。
