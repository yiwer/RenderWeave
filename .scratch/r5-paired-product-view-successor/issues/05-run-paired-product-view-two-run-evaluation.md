# R5P-05 — 执行 paired product-view 双跑 A1

- Status: planned
- Spec: `specs/changes/20260815-r5-paired-product-view-successor.md`
- Blocked by: R5P-04 `R5P_ASSIGNMENT_FROZEN`
- Main seam: `normalized ArtifactSet + complete VisualViewPlan/1.0 + FrozenPairedViewAssignment/1.0 + PairedViewEvaluationPolicy/1.0 → PairedProductViewOutcome/1.0`
- Provider boundary: attempts/reservations/cost/API-key reads must remain 0

## What it delivers

对 8 cases × baseline/successor 两个完整 plan × 两个 run 执行 32 次确定性本地 acquisition，并生成
payload-safe producer A1。baseline 使用 complete static plan；successor 使用同一 static plan 经 bounded action
形成的 complete inspected plan。

## Acceptance criteria

- [ ] 8/8 cases 的 normalized artifacts、plan/view bytes、temporary observations、projected metric inputs 与结果在两 run 间一致。
- [ ] 每个 plan descriptor 都有且只有一个实际 acquisition artifact；不得 overview-only 或 crop-only。
- [ ] seen 与 confirmation 分区、逐例指标和 aggregate 指标分别报告。
- [ ] 记录 per-case target improvement、hallucination、line recall、character errors、order/repeat 与 resource metrics。
- [ ] 第一条结果后 assignment、transform、policy、threshold、capability 和 evaluator 不得变化。
- [ ] Provider attempts/reservations/cost、API-key reads 均为 0；OCR text 与 images 不进入 evidence。

## Required gate/evidence

- 两次实际运行的 payload-safe paired report、resource summary 与 identities。
- Producer A1 可如实为 PASS 或 FAIL；ticket 完成不等于 quality qualified。
- 下游只接受完整 evidence，不接受手工摘要或局部 rerun。

