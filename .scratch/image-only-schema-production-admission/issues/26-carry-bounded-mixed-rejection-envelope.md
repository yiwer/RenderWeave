# 26 — IOPA-P1-R11：贯通 bounded mixed-rejection envelope

**What to build:** 让票 25 已可靠分类的 mixed/unclassified rejection 从 codec 一直传到 worker 决策、持久化 attempt、运行终态和操作者可见的 payload-free evidence；mixed detail 不再被压扁丢失，但本票仍保持 fail-closed，不提前授予任何 retry 权限。

**Blocked by:** 25 — IOPA-P1-R10：证明 generic fallback provenance 全覆盖。

**Status:** resolved

- [x] rejection envelope 同时携带一个批准的 primary fixed code、earliest stage 与封闭 bounded detail code-set；空值、重复、未知成员、超界数量和非 canonical 顺序全部拒绝。
- [x] codec、worker、attempt taxonomy、PostgreSQL append-only ledger、terminal/evidence 与现有运行查询投影对同一 envelope 给出一致的 primary/detail/count 事实。
- [x] terminal `failureCode` 只使用 primary fixed code；detail 只出现在批准的 code-count telemetry 中，不拼接成动态 code 或异常 message。
- [x] known-mixed 与 unclassified attempt 均先完整落账再 terminal，本票不 reserve 或签发下一张 Provider permit。
- [x] Testcontainers PostgreSQL 证明事务落账、crash/replay、ledger close 与 0 unsettled reservations；不得用 H2/SQLite 替代。
- [x] 既有单-code rejection、v48 generic terminal、breaker accounting、Candidate materialization 与人工审核语义保持不变。
- [x] 对外与常规证据只含 identities、stage、fixed codes、计数、token/费用聚合和状态；payload/secret scan 必须为零。
- [x] 端到端 scripted-provider 场景可从 synthetic response 观察到 exact persisted envelope，且 Provider accounting 保持 0、OPEN authorization=0。

## Evidence

- A1 dedicated gate：`.sdlc/evidence/20260818-105626-image-only-v49-envelope/`，result=`PASS`。
- 独立 verifier 冻结 closed enum、primary codes、v48 hash、v49/Profile/Prompt absence、OPEN=0 与
  Provider/key usage=0；summary identity 为 `renderweave-image-only-v49-envelope-provider-zero/1.0`。
- Java inference 49/49；Testcontainers PostgreSQL + API 6/6；Node 24 OpenAPI generation/typecheck 与 Web
  77/77 全绿。覆盖 strict codec、malformed DB rejection、append-before-terminal、settled reservation、lease
  crash/replay 不再签发 Provider permit、payload-free API/Web projection。
- 本票未创建 v49 Profile/Prompt 15 或 authorization，未调用真实 Provider，未 apply Candidate、未发布
  StaticSchema、未部署生产、未 commit/push。
