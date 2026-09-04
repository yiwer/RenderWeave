# Spec Delta：IMAGE_ONLY v50 Lossless Local-ID Canonicalization Successor

- 状态：**APPROVED**
- Triage：ready-for-agent
- 日期：2026-08-18
- 批准权威：所有者持续 Goal 的“推荐决策落 ADR”standing approval，按 ADR-0047 精确实例化
- Source ticket：`.scratch/image-only-schema-production-admission/issues/31-freeze-v50-lossless-local-id-canonicalization.md`
- 基线 delta：`specs/changes/20260818-image-only-v49-mixed-region-fallback-successor.md`
- 非授权声明：本 delta 只批准 Provider-zero recovery；不是 paid live J1，不授权 OPEN、外传、计分/grant、
  Candidate apply、StaticSchema publish 或部署

## Problem Statement

v49 单案 diagnostic 以 5 calls / 67,373 tokens / ¥1.086900 的 immutable `TERMINAL_CLOSED/FAILED` 结束。attempts
0、3、4 的 canonical mixed detail set 完全相同：region ID、parent ID、repeat-group ID；第三次落账后正确停止且没有
第 6 次 permit。按 `VisualRegionFallbackClassifier`，这三类 detail 只表示对应字段未通过 local-ID lexical validator，
不证明其原始 token、关系图、坐标或语义。继续让模型重写同一纯词法集合已经有负证据。

## Solution

为 successor pipeline 增加 fail-closed local-ID canonicalizer。它在严格 grounding construction/validation 前，把非空、
唯一的 region/element declaration token 按数组顺序映射成 `r1..r32` / `e1..e32`，把 repeat-group equality classes
按首次出现映射成 `g1..g32`，并精确重写 parent 与 element-region references。除这些 local-ID fields 外不改变任何
semantic JSON value；unknown/duplicate/ambiguous/type/shape failure 立即拒绝。canonicalized tree 仍经过现有完整
RenderWeave validator，不能借此放宽 containment、forest、evidence、multiplicity、reading order、semantic name 或
Candidate contract。

由 hidden v50 Profile opt in pipeline 4.32 与 Prompt 16；v49 及更早对象保持不可变。Provider-zero gates 全绿后，
才可为同一 ordinary-design regression probe 准备 fresh non-scoring diagnostic 并实例化另一个 exact J1。

## Acceptance Criteria

| AC | 可观察行为 | 最低证据 |
|---|---|---|
| AC-IOPA-V50-001 | v49 Profile/CLOSED J1/terminal/live summary/post-close digests 不变，OPEN=0 | independent digest replay A1 |
| AC-IOPA-V50-002 | 只重命名五类 local-ID fields，semantic tree 的其余字段逐值相同 | property/differential A1 |
| AC-IOPA-V50-003 | region/element IDs 按声明顺序变成 bounded r/e IDs，repeat groups 按 equality class 变成 g IDs；references 精确保持 | exhaustive graph fixtures A1 |
| AC-IOPA-V50-004 | duplicate/null/blank declarations、dangling/ambiguous references、错误 type/shape fail closed | negative property A1 |
| AC-IOPA-V50-005 | canonicalized output 仍经过完整 RenderWeave validator；structural/geometric/semantic invalidity 不被修复或吞掉 | codec + scripted Provider A1 |
| AC-IOPA-V50-006 | raw local IDs 不进入常规日志、attempt/evidence/review projection；只记录 fixed code/count/identity | payload scan A1 |
| AC-IOPA-V50-007 | v50 相对 v49 只改 profileId/pipelineVersion/elementPromptVersion；其余 frozen fields 完全相同 | exact diff/hash A1 |
| AC-IOPA-V50-008 | v49 及更早 correction/breaker/terminal 行为逐项不变；v50 hidden/EXPERIMENTAL/ungranted/非 product-live | compatibility A1 |
| AC-IOPA-V50-009 | fresh diagnostic caps≤1/5/100k/¥3/per-run 5+¥3/≤2h，non-scoring；无 exact J1 时 usage=0 | preflight/PG A1 |
| AC-IOPA-V50-010 | diagnostic 只产生 owner-review Candidate 或 immutable negative terminal；无自动 apply/publish/grant/deploy/rerun | lifecycle A1 |

## Frozen Boundary

- v50 Profile ID：`dashscope-qwen38-max-product-v50-hybrid-generic`
- pipeline：`renderweave-inference-pipeline/4.32`
- element Prompt：`renderweave-visual-elements-prompt/16.0`
- relative v49 diff：只允许 `profileId`、`pipelineVersion`、`elementPromptVersion`
- route/model/base URL/output/call/cost/time/OCR/other prompts/Candidate/evaluator/pricing：与 v49 完全相同
- diagnostic：fresh identities + exact J1；1 run / 5 calls / 100k model tokens / ¥3 / per-run 5 calls + ¥3 / ≤2h

## Verification

synthetic tree properties → strict codec differential → scripted Provider worker → Testcontainers PostgreSQL → exact Profile/
Prompt registry → historical digest replay → independent verifier → fast → server。全部 Provider-zero gate 清空 live selector，
证据 payload-free。

## Out of Scope

- 读取或记录历史/新模型 raw IDs、完整 request/response、图片、OCR text、RootDocument 或 chain-of-thought。
- 猜测 parent、删除 dangling ref、调整 coordinates/forest/multiplicity/evidence/semantic names、创建 region/element 或
  绕过 RenderWeave validator。
- patch/rerun v49，扩大 route/model/output/calls/cost/time，计分/grant、apply/publish/deploy、commit/push。
