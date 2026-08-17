# R5P-07 — 实现严格 OBSERVE inspection union contract

- Status: planned
- Spec: `specs/changes/20260815-r5-paired-product-view-successor.md`
- Blocked by: R5P-06 `R5P_ACTION_IMPLEMENTATION_ALLOWED`
- Provider boundary: attempts/reservations/cost/API-key reads must remain 0

## What it delivers

建立 additive、closed 的 OBSERVE response contract：完整既有 grounding 或完整 `InspectionRequest/1.0` 二选一。
该 ticket 只实现 model-facing decode/validation seam，不调用 Provider。

## Acceptance criteria

- [ ] mixed variant、unknown member、duplicate key、trailing token、null primitive、bool/int、float-as-int、scalar coercion 与 numeric enum 均拒绝。
- [ ] request 只能含一至两个 known-view regions 与固定 margin/resolution presets。
- [ ] free text、path、URL、tool/model name、budget、loop、priority 和 termination instruction 均不可表达。
- [ ] semantic-invalid request 返回固定 payload-safe reason code，不 permissive repair/default。
- [ ] 历史 OBSERVE grounding contract 和 Prompt bytes 不变。

## Required gate/evidence

- Codec/schema parity A1/A2、positive/negative contract vectors。
- Terminal code: `R5P_OBSERVE_CONTRACT_READY`。

