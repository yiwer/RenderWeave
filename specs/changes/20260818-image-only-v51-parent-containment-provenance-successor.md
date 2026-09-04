# Spec Delta：IMAGE_ONLY v51 Parent-Containment Provenance Successor

- 状态：**APPROVED**
- Triage：ready-for-agent
- 日期：2026-08-18
- 批准权威：所有者持续 Goal 的推荐决策 standing approval（ADR-0047）
- Source ticket：`.scratch/image-only-schema-production-admission/issues/36-freeze-v51-parent-containment-provenance.md`
- 基线 delta：`specs/changes/20260818-image-only-v50-local-id-canonicalization-successor.md`
- 非授权声明：本 delta 只批准 Provider-zero provenance successor；不是 paid live wildcard J1

## Problem Statement

v50 单案 diagnostic 在 local-ID canonicalization 后连续三次以
`VISUAL_GROUNDING_PARENT_CONTAINMENT_INVALID` 结束：3 calls、40,400 model tokens、¥0.645，第三次等价拒绝后
无第 4 次 reservation。现有 pipeline 已启用唯一兼容 parent、唯一包含 ROOT ancestor 与完整 plan replay，单一 fixed
code 不能区分无候选、歧义、ITEM/repeated constraint 或原子回滚。它不授权读取 raw box/region/model output，也不足以
证明扩大 box、删除 branch 或改 repetition 是安全的。

## Solution

v51 pipeline 4.33 在内存中对最终 containment failure 做固定分类，仅输出 bounded detail-code set 与计数，不输出
region/local ID、坐标、图片、OCR 或模型 payload。分类 primary 首次即 terminal，不向模型回显或重试。v51 继续使用
Prompt 16 与 v50 的 deterministic local-ID canonicalizer、validator、normalization、route/model/caps；相对 v50 Profile
只改 profileId 与 pipelineVersion。

## Acceptance Criteria

| AC | 可观察行为 | 最低证据 |
|---|---|---|
| AC-IOPA-V51-001 | v50 Profile/CLOSED J1/terminal/live summary digests 不变，OPEN=0 | independent replay A1 |
| AC-IOPA-V51-002 | classifier 只输出 allowlisted fixed categories/counts，绝不输出 IDs/boxes/payload | property + payload scan A1 |
| AC-IOPA-V51-003 | ITEM zero/ambiguous、non-ITEM zero/ambiguous、atomic rollback/unclassified 互斥且排序稳定 | exhaustive fixtures A1 |
| AC-IOPA-V51-004 | classified primary 对 v51 non-retryable；首拒绝后不得发第 2 个 permit | scripted PostgreSQL A1 |
| AC-IOPA-V51-005 | v50 与更早 correction/breaker/normalization bytes 和行为不变 | compatibility A1 |
| AC-IOPA-V51-006 | v51 Profile 相对 v50 只改 profileId/pipelineVersion；Prompt 16/hash 不变 | exact diff/hash A1 |
| AC-IOPA-V51-007 | hidden/EXPERIMENTAL/ungranted/非 product-live；不 apply/publish/deploy | registry + lifecycle A1 |
| AC-IOPA-V51-008 | fresh diagnostic 仍为同 case、non-scoring、≤1/5/100k/¥3/2h；无 exact J1 usage=0 | preflight A1 |

## Frozen Boundary

- v51 Profile ID：`dashscope-qwen38-max-product-v51-hybrid-generic`
- pipeline：`renderweave-inference-pipeline/4.33`
- Prompt：`renderweave-visual-elements-prompt/16.0`（bytes/hash 不变）
- primary：`VISUAL_GROUNDING_PARENT_CONTAINMENT_CLASSIFIED`
- details：仅 allowlisted zero/ambiguous ITEM/non-ITEM、atomic rollback 或 unclassified categories
- classified primary non-retryable；不得改变 parent、box、kind、multiplicity、repeat group、ownership 或 Candidate

## Out of Scope

读取或记录模型输出/坐标/local IDs；扩大/裁剪 evidence box；创建、删除或重接 region；放宽 validator；复用 v50 J1；
计分/grant、Candidate apply、StaticSchema publish、生产部署、commit/push。
