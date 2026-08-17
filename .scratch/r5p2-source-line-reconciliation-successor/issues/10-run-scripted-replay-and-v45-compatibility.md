# 10 — 完成 scripted replay 与 product-v45 compatibility

**What to build:** 通过 no-network scripted adapter 完成 IMAGE_ONLY inspection workflow 到 `REVIEW_REQUIRED`，同时重放 no-inspection product-v45/R0 路线，证明新增 action 在未请求 inspection 时行为等价。

**Blocked by:** R5P2-09 — durable inspection substate and recovery semantics must be green.

**Status:** ready-for-agent

- [ ] 完整 scripted sequence 为 `OBSERVE(request) → local inspection → OBSERVE(grounding) → HIERARCHY → ELEMENT_BINDING → LOCAL_MATERIALIZE → REVIEW_REQUIRED`，使用现有 PostgreSQL durable authority 与 serial semantic stages。
- [ ] inspection 只发生一次且最多两个 local views；accepted-stage replay count 为 0，duplicate/exhaustion/cancel/lease/recovery cases 保持相同 terminal policy。
- [ ] Candidate evidence 只引用 original normalized artifact identity/coordinates，不含 inspected-view identity、crop OCR merge 或 reconciliation payload。
- [ ] workflow terminal 精确停在 `REVIEW_REQUIRED`，Candidate 仅可审核，不自动 Apply、接受、发布或触发其他产品能力。
- [ ] no-inspection product-v45 replay 的 Candidate contract、evidence、blocker set、accepted-stage calls 与 terminal behavior exact 等价。
- [ ] historical Prompt/Profile/pipeline/view-plan/checkpoint/IR snapshots 和 identities 字节不变；successor 仍 hidden/experimental，默认与 live routing 未改变。
- [ ] Testcontainers workflow/E2E evidence 与独立 transition-count reconstruction 一致，输出只含 payload-safe identities、counts 和 dispositions。
- [ ] ordinary evidence、logs、exceptions 与 test reports 通过 decoded/raw payload scan；Provider attempts、reservations、cost、API-key reads 均为 0。

