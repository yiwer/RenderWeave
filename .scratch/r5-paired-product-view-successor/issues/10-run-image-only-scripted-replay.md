# R5P-10 — 完成 IMAGE_ONLY scripted product replay

- Status: planned
- Spec: `specs/changes/20260815-r5-paired-product-view-successor.md`
- Blocked by: R5P-09 `R5P_DURABLE_INSPECTION_READY`
- Highest seam: complete IMAGE_ONLY replay through PostgreSQL to `REVIEW_REQUIRED`
- Provider boundary: attempts/reservations/cost/API-key reads must remain 0

## What it delivers

通过 no-network scripted adapter 执行：

`OBSERVE(request) → local inspection → OBSERVE(grounding) → HIERARCHY → ELEMENT_BINDING → LOCAL_MATERIALIZE → REVIEW_REQUIRED`

## Acceptance criteria

- [ ] workflow 使用现有 PostgreSQL durable authority 与 serial semantic stages。
- [ ] inspection 只发生一次，最多两个 local views；accepted stage zero replay。
- [ ] Candidate evidence 只引用 original normalized artifact identity/coordinates，不含 inspected view ID。
- [ ] terminal 精确为 `REVIEW_REQUIRED`，Candidate 可审核且不自动 Apply、接受或发布。
- [ ] recovery、duplicate、exhaustion、cancel 与 lease cases 均维持同一 terminal policy。
- [ ] Provider/API-key/ordinary payload 使用均为 0。

## Required gate/evidence

- Testcontainers workflow/E2E A1。
- Payload-safe terminal summary 与独立 transition-count reconstruction。
- Terminal code: `R5P_SCRIPTED_REPLAY_READY`。

