# 30 — IOPA-P1-R15：执行并不可变关闭 v49 diagnostic

**What to build:** 在且仅在所有者另行签发 fresh exact scoped J1 后，对票 29 准备的单 case 执行一次 v49 非计分 diagnostic，并无论成功、拒绝还是运行故障都关闭 authorization、结清 PostgreSQL ledger、形成 payload-free terminal。该运行只能生成待人工审核的 Candidate 或 immutable negative terminal，不能自行进入评分认证。

**Blocked by:** 29 — IOPA-P1-R14：准备 fresh v49 diagnostic authority；以及所有者另行签发、当时仍有效的 fresh exact J1。

**Status:** resolved

- [x] 调用前逐字段验证 J1 的 exact Profile/manifest/evaluator/normalization/provider/model/base URL/data/case/caps/scope/timing；任一不匹配或过期立即停止且 Provider attempts=0。
- [x] agent 不读取、不打印、不复制 API Key；runtime 只能通过既有 secret injection 和 exact approved route/model 发起调用。
- [x] 只执行获批的一个 non-scoring diagnostic run；calls、tokens、cost、per-run caps 与时限不得超过 J1，且不授权另一个 cycle 或 run 级自动重跑。
- [x] 每次外传前完成 permit/reservation，每次 attempt 后 append-only settlement；mixed-set breaker、unclassified terminal 或 hard cap 到达时不得发起下一次调用。
- [x] terminal、异常或人工中止后立即关闭 authorization 与 PostgreSQL ledger并核对，最终 OPEN authorization=0、unsettled reservations=0。
- [x] 若结果为 `REVIEW_REQUIRED`，只生成隔离 owner review pack；Candidate 保持 unapplied、StaticSchema unpublished、Profile uncertified/ungranted，人工接受也只允许另拆 scoring ticket。
- [x] 若结果为 `FAILED`，保留 immutable negative terminal 并停止；任何再次 live 或 Profile/Prompt/pipeline/cap/assignment 变化必须新 source ticket、新 identities/new cycle/fresh J1。
- [x] A1 evidence 只记录 exact identities、attempt/stage/fixed-code sets、聚合 token/费用、breaker/ledger/terminal 状态和 digests，不记录图片、完整 request/response、RootDocument 或 chain-of-thought。
- [x] diagnostic 不计入 5/20/60，不解锁 scoring canary、P1-02/P1-03、grant、Candidate apply、StaticSchema 发布或生产部署。
- [x] live 结束后运行 Provider-zero post-close gate，独立核对 CLOSED J1、terminal、live summary digests 与历史 immutability；不得 git commit 或 push。

## Evidence

- exact J1=`20260818-iopa-v49-diagnostic-432fdfeb`，有效窗
  `2026-08-18T04:15:42.6112014Z..06:15:42.6112014Z`；逐字段绑定票 29 identities、单 case、exact
  DashScope route/model、1 run/5 calls/100k tokens/¥3/per-run 5+¥3。未读取或输出 credential。
- 唯一 run=`3f8a063b-1b3d-4cf4-ab58-1ad0de9127d2`，结果=`FAILED`；5 个 OBSERVE rejected attempts，
  合计 67,373 model tokens、¥1.086900、0 unsettled。ordinals 0/3/4 的 exact mixed set 均为 region ID + parent
  ID + repeat-group ID；第三次落账后无第 6 次 reservation/call。中间不同 set 不共享 breaker 计数，但总 hard cap
  未被绕过。
- live A1 negative evidence=`.sdlc/evidence/20260818-121611-image-only-v49-successor-diagnostic-live/`；失败的
  gate exit=1 是预期 terminal assertion（未到 `REVIEW_REQUIRED`），不是授权/ledger 泄漏。payload-free live summary
  SHA=`e8c8cfd44bd5cfd2b40660689a4faa4a78ef3c4bf180cb2fd7c79ae9f456815e`。
- authorization 已 CLOSED，SHA=`3eabfef97ad0a9f1c7fe7d947c527587109be30386bd0702a7cb1d671f90c0fa`；
  immutable terminal SHA=`773463556c05d94d79b4a9d4acad240218971f8d65a56e0e42cac60cd9a2fb4b`。
  post-close A1 gate `.sdlc/evidence/20260818-122337-image-only-v49-diagnostic-postclose/` PASS（verifier 3/3、
  inference 19/19、Testcontainers 3/3）。
- OPEN=0、unsettled=0、0 Candidate/review pack、certification credit=0、next stage=false；未 apply/publish/deploy/
  commit/push。Goal live usage=67,373/1,500,000，remaining=1,432,627；v49 禁止自动重试。
