# 09 — 实现 PostgreSQL inspection substate 与恢复

**What to build:** 将一次 bounded inspection 纳入现有 PostgreSQL durable typed state machine；protected checkpoint 只保存恢复所需的 canonical validated request、identities 与 consumed counters，derived views 始终从 original blobs 确定性重建。

**Blocked by:** R5P2-08 — hidden offline inspection action contracts and identities must be green.

**Status:** ready-for-agent

- [ ] valid first OBSERVE inspection request 原子进入 typed inspection substate，不被误记为 accepted semantic result。
- [ ] crash before/after checkpoint、during transform、after plan generation 与 process restart 均恢复到相同 outcome、identities 与 consumed counters。
- [ ] identical duplicate request 不重复 transform 或 adapter call；不同第二 request 返回固定 loop-exhausted disposition。
- [ ] cancellation、lease loss、deadline、changed policy 或 changed base plan 在新副作用前 fail-closed，并保持 durable authority 一致。
- [ ] accepted inspected OBSERVE 不重放；既有 HIERARCHY 与 ELEMENT_BINDING accepted stages 不回退或重复调用。
- [ ] derived views 从 original normalized blobs 与 frozen plan 重建，不作为新的业务 source、Candidate evidence 或 durable image payload。
- [ ] checkpoint、日志和 evidence 不含 derived image、Base64、OCR、Prompt、raw model output 或完整 RootDocument。
- [ ] 使用 Testcontainers PostgreSQL 覆盖 crash/lease/cancel/idempotency/exhaustion 与 schema/redaction negatives；不以 H2/SQLite 替代 PostgreSQL 语义。
- [ ] 全部测试 no-network，Provider attempts、reservations、cost、API-key reads 均为 0。

