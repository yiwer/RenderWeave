# Spec Delta：IMAGE_ONLY v52 ITEM Parent-Envelope Normalization Successor

- 状态：**APPROVED**
- Triage：ready-for-agent
- 日期：2026-08-18
- 批准权威：所有者持续 Goal 的推荐决策 standing approval（ADR-0047）
- Source ticket：`.scratch/image-only-schema-production-admission/issues/41-freeze-v52-item-parent-envelope-normalization.md`
- ADR：`docs/adr/0052-successor-only-repeated-group-evidence-envelope-normalization.md`
- 基线 delta：`specs/changes/20260818-image-only-v51-parent-containment-provenance-successor.md`
- 非授权声明：本 delta 只批准 Provider-zero successor；不是 paid live wildcard J1

## Problem Statement

v51 one-shot diagnostic 以 1 call / 13,845 model tokens / ¥0.228780 得到 payload-free
`ITEM_ZERO_COMPATIBLE`。该分类证明 current parent 的 kind/reference/repeatGroup equality 成立，但其 evidence 不包含
ITEM，且不存在可安全重接的 compatible containing parent。继续 LLM retry、任意重接或裁剪 ITEM 都缺少证据。

## Solution

v52 pipeline 4.34 在完整 grounding validation 前，对 exact current `ITEM -> REPEATED_GROUP` same-repeatGroup
non-containment links 做 bounded atomic normalization：把 repeated-group direct image box 扩成其自身与 direct ITEM
children 的无 padding 并集。所有更新在副本中完成并接受完整既有 validator；任一失败全量回滚。Prompt 16 与 v51
route/model/caps/canonicalizer/diagnostics 不变。

## Acceptance Criteria

| AC | 可观察行为 | 最低证据 |
|---|---|---|
| AC-IOPA-V52-001 | v51 CLOSED J1/terminal/live/post-close digests 不变，OPEN=0 | independent replay A1 |
| AC-IOPA-V52-002 | 仅 exact linked ITEM/repeated-group same-group non-containment 扩 parent，无 padding | exhaustive fixtures A1 |
| AC-IOPA-V52-003 | wrong kind/group/artifact、missing/self parent、non-ITEM 与 valid containment 不修改 | negative/property A1 |
| AC-IOPA-V52-004 | 多 parent bounded；ancestor/graph/full-plan 失败全量回滚，不部分提交 | atomic fixtures A1 |
| AC-IOPA-V52-005 | v51 及更早 output/normalization/correction/breaker 行为不变 | differential A1 |
| AC-IOPA-V52-006 | telemetry 仅固定 count/code，无 ID/box/path/payload | payload scan A1 |
| AC-IOPA-V52-007 | v52 Profile 相对 v51 仅 profileId/pipelineVersion；Prompt 16/hash 不变 | exact diff/hash A1 |
| AC-IOPA-V52-008 | hidden/EXPERIMENTAL/ungranted/非 product-live；fresh diagnostic≤1/5/100k/¥3/2h | lifecycle + preflight A1 |

## Frozen Boundary

- v52 Profile ID：`dashscope-qwen38-max-product-v52-hybrid-generic`
- pipeline：`renderweave-inference-pipeline/4.34`
- Prompt：`renderweave-visual-elements-prompt/16.0`（bytes/hash 不变）
- 只扩 exact current REPEATED_GROUP box 至 self + direct linked ITEM boxes union；不得递归 ancestor
- full-plan failure atomic rollback；classified primary 继续 non-retryable

## Out of Scope

读取/记录 raw response、ID 或坐标；padding/裁剪/移动 ITEM；重接 parent；扩大 ancestor；删除/创建 region；放宽
validator；复用 v51 J1；计分/grant、Candidate apply、StaticSchema publish、生产部署、commit/push。
