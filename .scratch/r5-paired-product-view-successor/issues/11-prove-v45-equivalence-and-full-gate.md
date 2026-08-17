# R5P-11 — 证明 product-v45 等价并运行完整门控

- Status: planned
- Spec: `specs/changes/20260815-r5-paired-product-view-successor.md`
- Blocked by: R5P-10 `R5P_SCRIPTED_REPLAY_READY`
- Provider boundary: attempts/reservations/cost/API-key reads must remain 0

## What it delivers

复跑 no-inspection product-v45/R0 路径，证明新增 action 在未请求 inspection 时不改变历史 Candidate、evidence、
blocker、stage replay 或 terminal 行为，并在 exact clean revision 上完成 affected/full verification。

## Acceptance criteria

- [ ] no-inspection replay 的 Candidate contract、evidence、blocker set、accepted-stage calls 与 terminal 行为等价。
- [ ] historical Prompt/Profile/pipeline/view-plan/checkpoint/IR snapshots 和 identities 字节不变。
- [ ] successor 仍 hidden/experimental，产品默认与 live routing 未改变。
- [ ] ordinary evidence、stdout/stderr、exceptions、test reports 与 summaries 通过 payload scan。
- [ ] exact clean revision 上 focused、server、document-vision、E2E 与 full gate 全绿。
- [ ] Provider attempts/reservations/cost/API-key reads 均为 0。

## Required gate/evidence

- Behavior-equivalence A1/A2 comparison。
- Exact-revision full evidence manifest。
- Terminal code: `R5P_PRODUCT_PATH_QUALIFIED`；任一失败输出 `R5P_PRODUCT_PATH_NOT_QUALIFIED` 并阻塞 R5P-12 eligibility。

