# R5P-02 — 证明产品 normalization 与完整 static-plan harness

- Status: planned
- Spec: `specs/changes/20260815-r5-paired-product-view-successor.md`
- Blocked by: R5P-01 `R5P_AUTHORITY_LOCKED`
- Provider boundary: attempts/reservations/cost/API-key reads must remain 0

## What it delivers

以无质量含义的自描述 repository raster fixtures 纵向贯通：

`raw fixture → product InputNormalizer → scoped BlobStore → normalized ArtifactSet → complete VisualViewPlan/1.0 → local acquisition`

该结果只证明测量 harness 忠实，不读取 corpus gold，也不作质量判断。

## Acceptance criteria

- [ ] baseline acquisition 严格覆盖 complete plan 的全部 provider images，而不是 overview-only。
- [ ] descriptor、顺序、artifact identity、dimensions、encoded bytes 与实际 acquisition artifact 一一对应。
- [ ] overview-only、漏 tile、调换 view、替换 bytes、伪造 dimensions 或追加未声明 view 均被拒绝。
- [ ] transform/action 只能读取 normalized blob bytes；raw fixture、scene、oracle image 与 gold 无法绕过 normalization。
- [ ] plan/acquisition summary payload-safe，且两次 conformance run 确定性一致。

## Required gate/evidence

- Product-normalization/static-plan integration A1。
- 独立 plan/acquisition coverage reconstruction。
- Terminal code: `R5P_HARNESS_CONFORMANT`。

