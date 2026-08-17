# R5P-03 — 实现 offline-only bounded inspection core

- Status: planned
- Spec: `specs/changes/20260815-r5-paired-product-view-successor.md`
- Blocked by: R5P-02 `R5P_HARNESS_CONFORMANT`
- Primary seam: `normalized ArtifactSet + VisualViewPlan/1.0 + decoded InspectionRequest/1.0 + AdaptiveInspectionPolicy/1.0 → InspectionOutcome/1.0`
- Provider boundary: attempts/reservations/cost/API-key reads must remain 0

## What it delivers

以 TDD 在已批准 action seam 上形成一个 offline-only 深 Module，拥有 typed request validation、固定 policy、
`R5ProductRasterTransform/1.0`、完整 plan composition、resource accounting 与
`EXECUTED/REJECTED/EXHAUSTED`。本 ticket 不接入模型、Prompt、Profile、durable workflow 或产品 create route。

## Acceptance criteria

- [ ] 合法的一至两个 region 生成完整 plan v2，required overview、inspected views、optional tiles 顺序正确。
- [ ] 每 run 最多一轮、两个 inspected views；unknown/old view、duplicate/invalid region 和第二轮在副作用前拒绝。
- [ ] 10 views、30 MiB、11,520,000 inspected pixels、12,000 additional visual tokens 与 10,000 ms 上限严格执行。
- [ ] checked arithmetic overflow、required view 超预算与 partial outcome 全部 fail-closed。
- [ ] action outcome 不把 derived bytes/view identity 写入 IR、Candidate、ordinary evidence 或 durable state。
- [ ] app/product/provider 路径对该 Module 保持不可达。

## Required gate/evidence

- Action-seam red/green tests、property/boundary/negative A1。
- Zero-provider and payload-safety scan。
- Terminal code: `R5P_OFFLINE_ACTION_CORE_READY`。

