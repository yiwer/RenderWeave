# R5P-09 — 实现 PostgreSQL typed inspection substate 与恢复

- Status: planned
- Spec: `specs/changes/20260815-r5-paired-product-view-successor.md`
- Blocked by: R5P-08 `R5P_HIDDEN_SEMANTIC_IDENTITIES_READY`
- Provider boundary: attempts/reservations/cost/API-key reads must remain 0

## What it delivers

将一次 bounded inspection 纳入现有 PostgreSQL durable typed state machine。protected checkpoint 只保存恢复需要的
canonical validated request、identities 与 consumed counters；derived views 从 original blobs 确定性重建。

## Acceptance criteria

- [ ] valid first OBSERVE request 原子进入 typed inspection substate，而不是 accepted semantic result。
- [ ] crash before/after checkpoint、during transform、after plan generation 均恢复到相同 outcome。
- [ ] identical duplicate request 不重复 transform/call；不同第二 request 返回 loop exhausted。
- [ ] cancellation、lease loss、deadline 和 changed policy/base plan 在副作用前 fail-closed。
- [ ] accepted inspected OBSERVE 不重放，既有 HIERARCHY/BINDING accepted stages 不回退。
- [ ] checkpoint/log/evidence 不含 derived image、OCR、Prompt 或 raw model output。

## Required gate/evidence

- Testcontainers PostgreSQL crash/lease/cancel/idempotency A1。
- Checkpoint schema/redaction negative tests。
- Terminal code: `R5P_DURABLE_INSPECTION_READY`。

