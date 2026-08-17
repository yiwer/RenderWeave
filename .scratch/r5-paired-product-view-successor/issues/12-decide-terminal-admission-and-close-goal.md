# R5P-12 — 独立 terminal admission 并关闭 replacement Goal

- Status: planned
- Spec: `specs/changes/20260815-r5-paired-product-view-successor.md`
- Blocked by: R5P-06 `R5P_ACTION_IMPLEMENTATION_ALLOWED` and R5P-11 `R5P_PRODUCT_PATH_QUALIFIED`
- Provider boundary: attempts/reservations/cost/API-key reads must remain 0

## What it delivers

独立重建完整 R5P authority chain、paired A2、action/workflow、v45 compatibility、privacy、full gate 与 Provider-zero，
形成 replacement Goal 的唯一 terminal admission decision。该 ticket 不创建 live ticket、authorization 或 J1。

## Acceptance criteria

- [ ] verifier 从 content-addressed evidence 独立重算每个 predecessor identity、gate 与 terminal outcome。
- [ ] 任一上游 failure、missing evidence、dirty revision、payload violation 或 non-zero Provider usage 均不可被后续绿色 gate 覆盖。
- [ ] 只有全部门通过才输出 `R5_SUCCESSOR_LIVE_REQUEST_ELIGIBLE`。
- [ ] eligibility 明确标记为 J0，不创建/打开 authorization、ledger 或 reservation。
- [ ] 负面 terminal 同样如实关闭 replacement Goal，不自动切换算法、OCR、Prompt、Profile 或 route。
- [ ] final summary 只包含 identities、gate results、fixed codes、zero-use facts 和 evidence digests。

## Required gate/evidence

- Independent terminal A2、tamper suite、zero-provider audit 与 exact-revision manifest。
- Replacement Goal 以真实 terminal disposition 完成；任何未来 live 另走全新 `$to-tickets` 与 fresh exact J1。
