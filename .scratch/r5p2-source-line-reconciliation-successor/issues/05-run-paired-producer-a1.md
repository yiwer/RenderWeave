# 05 — 执行 12-case paired producer A1

**What to build:** 只在所有前置 freeze commit 和 payload guards 全绿后，执行唯一 official offline producer：12 cases、baseline/successor 两个完整 branch、两个 deterministic runs，并形成 content-addressed、payload-safe A1 evidence。

**Blocked by:** R5P2-04 — assignment, fixtures, policies, thresholds and access audit must be frozen.

**Status:** ready-for-agent

- [ ] 运行前重新验证 authority、process conformance、reconciliation conformance、assignment freeze、HOLDOUT access state、no-network 与 zero-provider guards；任一失败不得启动 actual corpus OCR。
- [ ] 两 runs × 12 cases × 2 branches 精确执行 48 个 fresh branch acquisition processes；每个 branch 一个完整 ordered request 和 fresh engine，并另记每 run 一次、总计 2 次 capability probe processes。
- [ ] artifact/view counts、branch process calls 与 probe calls 分账，且每项 resource identity、plan identity 和 request identity 可独立重算；不以 view 调用数冒充 branch acquisition。
- [ ] 两次 run 的 normalized bytes、完整 plans、view bytes、raw/canonical observation identities、reconciled metric-input identities、per-case metrics 与 accounting 在 12/12 cases exact 一致。
- [ ] 每个 historical diagnostic 记录 target-improvement 与 hallucination-non-increase facts，且单列 `transit-board-v3`；每个 sealed confirmation 记录同样的逐例 facts 与 aggregate recall、character error、order、repeat facts。
- [ ] evidence 记录分层 stage identities、cohort summaries、resource counts 与 decision inputs，不在 producer ticket 中绕过独立 replay 宣告最终有效 terminal。
- [ ] 任一 nondeterminism、fixture/access mutation、process/accounting mismatch 或 payload violation立即形成固定负面事实并停止；不得用窄重跑选择性替换失败结果。
- [ ] stdout/stderr、report 与 evidence 不含图片、Base64、完整 bbox、OCR/gold text、Prompt、Candidate 或 RootDocument；Provider attempts、reservations、cost、API-key reads 均为 0。

