# 07 — 作出唯一离线 terminal decision

**What to build:** 从冻结 authority、conformance、assignment、A1/A2、quality predicates、privacy 与 resource accounting 作出唯一 closed offline disposition；任何负面 terminal 都真实关闭后继，只有 `R5P2_ACTION_IMPLEMENTATION_ALLOWED` 解锁新的 product-action 路线。

**Blocked by:** R5P2-06 — independent replay and exact producer/A2 comparison must be complete.

**Status:** ready-for-agent

- [ ] authority、normalization、完整 branch process、reconciliation、assignment、A1/A2 exactness 或 Provider-zero 任一未建立时，唯一结果为 `R5P2_MEASUREMENT_INVALID`，且不得解释质量。
- [ ] measurement 有效后，八例 diagnostic 必须逐例满足 `matchedLineGain > 0` 或 `characterErrorReduction > 0`，并满足 `hallucinationIncrease <= 0`；`transit-board-v3` 必须显式通过，任一失败只产生 `R5P2_PAIRED_VIEW_NOT_QUALIFIED`。
- [ ] 四例 sealed confirmation 必须逐例满足同样 predicates；aggregate 同时要求 line recall gain `>= 500 bps`、character error reduction `>= 1`、order accuracy delta `>= -100 bps`、repeat recall delta `>= -100 bps`。
- [ ] diagnostic 只能 veto，不能补偿 confirmation；confirmation 不能补偿任一 diagnostic failure，且旧 confirmation/历史 diagnostic 不得重新包装成 fresh PASS。
- [ ] 只有 measurement、diagnostic、confirmation、A1/A2、privacy 与 zero-provider 全部通过时才输出 `R5P2_ACTION_IMPLEMENTATION_ALLOWED`；该 disposition 仅解锁 offline product action，不是 product qualification、live eligibility 或 J1。
- [ ] `R5P2_MEASUREMENT_INVALID` 或 `R5P2_PAIRED_VIEW_NOT_QUALIFIED` 立即关闭 R5P2-08..12；窄测试、人工解释、later rerun 或历史绿色 evidence 均不能覆盖。
- [ ] transform、regions、OCR capability、reconciliation policy、threshold、assignment 或 case access 改变时要求新 spec/identity，不允许原地重判。
- [ ] terminal evidence 只含固定 codes、identities、threshold facts、counts 和 digests；payload scan 全绿，Provider attempts、reservations、cost、API-key reads 均为 0。

