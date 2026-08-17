# R5P-08 — 绑定 hidden experimental Prompt/Profile/pipeline identities

- Status: planned
- Spec: `specs/changes/20260815-r5-paired-product-view-successor.md`
- Blocked by: R5P-07 `R5P_OBSERVE_CONTRACT_READY`
- Provider boundary: attempts/reservations/cost/API-key reads must remain 0

## What it delivers

新增不可变的 inspection Prompt successor、response shape、hidden experimental Profile 与 pipeline identities，
只允许 no-network scripted execution，不进入产品创建选择或 live Provider 路由。

## Acceptance criteria

- [ ] Prompt 12/7/4、Candidate Prompt 5、product-v45 Profile、pipeline 4.28 和 historical response shape 字节不变。
- [ ] successor 精确绑定 action policy、plan v2、最多一次额外 OBSERVE 与资源上限。
- [ ] catalog 明确标记 hidden/experimental/offline-only，product create route 无法选择。
- [ ] live-capable adapter 在 request construction/reservation 前因无 fresh exact J1 失败。
- [ ] snapshot、identity 和 catalog ordering 确定性稳定。

## Required gate/evidence

- Snapshot/catalog/admission negative A1。
- Zero-provider audit 与 historical-byte comparison。
- Terminal code: `R5P_HIDDEN_SEMANTIC_IDENTITIES_READY`。

