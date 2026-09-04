# Spec Delta：IMAGE_ONLY v48 Region-Recovery Successor

- 状态：**APPROVED**
- Triage：ready-for-agent
- 日期：2026-08-18
- 批准权威：所有者于 2026-08-18T01:32:48+08:00 对 source ticket 18 的 Q1–Q4 回复
  “全部按推荐”
- Source ticket：
  .scratch/image-only-schema-production-admission/issues/18-freeze-v48-bounded-region-recovery.md
- 基线 delta：specs/changes/20260817-image-only-v47-certification-successor.md
- 非授权声明：本 delta 只授权 Provider-zero 实现。它不是 paid live J1，不授权读取 API Key、外传图片、
  Candidate apply、StaticSchema 发布或生产部署

## Problem Statement

v47 的非计分 successor diagnostic 已以 immutable TERMINAL_CLOSED/FAILED 结束。三个 OBSERVE attempts
均只留下 VISUAL_GROUNDING_REGION_INVALID；该 generic code 包住 region 字段构造和 evidence/view mapping，
而 forest、containment、reading order、repeat semantics 与 JSON shape/enum 已有更细 code。现有 payload-free
证据因此不能判定具体字段，也不能为同一 generic code 提供新的 correction hypothesis。pipeline 4.29 仍会把
generic code 重试到第三次 breaker，已被该 diagnostic 证伪。

v47 Profile、cycle、CLOSED authorization、ledger、terminal 和证据保持不可变。恢复只能由新的 field-specific
diagnostic policy、显式 correction allowlist、新 Prompt/pipeline/Profile identities 与 fresh diagnostic 承接。

## Solution

创建 dashscope-qwen38-max-product-v48-hybrid-generic。它相对 v47 只改变 profileId、pipelineVersion 和
elementPromptVersion：pipeline 4.30 为 region construction/evidence failures 提供 successor-only fixed codes，
Prompt 14 为这些 codes 定义 bounded correction，并与一份显式 OBSERVE correction allowlist 绑定。

allowlist 内 code 才可继续纠正；VISUAL_GROUNDING_REGION_INVALID、未知或未列 code 在 rejected attempt 落账
后立即 fail。allowlist code 继续遵守同 run/stage 同 code 第三次 breaker，以及 Profile 与当次 J1 的全部
call/token/cost/time hard caps。

Provider-zero gates 全绿后，为同一已知失败 ordinary-design artifact 创建 fresh、非计分 diagnostic
normalization/manifest/cycle，并停在 fresh exact J1 门前。

## Acceptance Criteria

| AC | 可观察行为 | 最低证据 |
|---|---|---|
| AC-IOPA-V48-001 | v47 Profile bytes/hash、failed cycle、CLOSED J1/ledger、terminal 与证据不变；当前 OPEN authorization=0 | A1 immutable identity/digest checks |
| AC-IOPA-V48-002 | pipeline 4.30 对 region entry、regionId、parentRegionId、multiplicity、readingOrder、repeatGroupId、evidence 分别给出冻结 fixed code；无法安全分类回退 generic | synthetic codec contract A1 |
| AC-IOPA-V48-003 | field-specific policy 只对 v48 opt in；相同 synthetic invalid response 在 v47 仍给出 legacy generic code | compatibility regression A1 |
| AC-IOPA-V48-004 | Prompt 14 对七类新 code 给出 payload-free bounded correction；correction allowlist 与 Prompt identity 一起测试冻结 | prompt/policy identity A1 |
| AC-IOPA-V48-005 | allowlist code 可驱动完整 OBSERVE retry；generic、未知和未列 code 在 attempt 落账后立即 terminal，且无下一次 reservation/Provider permit | fake Provider + Testcontainers ledger A1 |
| AC-IOPA-V48-006 | allowlist code 同 run/stage 同 code 第三次落账后熔断；不同 code 分别计数，跨 stage 隔离，全部 budget/authorization gates 仍 fail closed | worker/property/PG A1 |
| AC-IOPA-V48-007 | v48 只相对 v47 改 profileId、pipelineVersion=renderweave-inference-pipeline/4.30、elementPromptVersion=renderweave-visual-elements-prompt/14.0；canonical SHA 从实际 resource 计算 | exact byte-diff + hash replay A1 |
| AC-IOPA-V48-008 | v48 保持 hidden、experimental、ungranted、uncertified；exact route/model、8192 tokens、12 calls/¥6、360s、OCR、其他 Prompts、Candidate/evaluator/pricing 均不变 | registry/profile/certification A1 |
| AC-IOPA-V48-009 | fresh PROFILE_SUCCESSOR_DIAGNOSTIC_1 只含已知失败 case、certification credit=0，caps≤1 run/5 calls/100,000 tokens/¥3/2h；无 fresh exact J1 时 Provider attempts=0 | manifest/preflight/authorization A1 |
| AC-IOPA-V48-010 | diagnostic 只可形成 REVIEW_REQUIRED owner review pack 或 immutable FAILED terminal；不自动 apply/publish、不计入 5/20/60、不解锁生产路径 | lifecycle A1 + later J1 |
| AC-IOPA-V48-011 | 日志与证据只记录 identities、fixed codes、计数、聚合 token/费用和状态；不含图片、完整 request/response、RootDocument 或 chain-of-thought | payload scan A1 |

## Frozen Identities and Behaviour

### v48 Profile boundary

| 字段 | 值 |
|---|---|
| profileId | dashscope-qwen38-max-product-v48-hybrid-generic |
| provider/model | DASHSCOPE / qwen3.8-max |
| endpoint | https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions |
| pipeline | renderweave-inference-pipeline/4.30 |
| element Prompt | renderweave-visual-elements-prompt/14.0 |
| output/call/cost/time | 8192 tokens / 12 calls / ¥6 aggregate / 360s |
| canonical SHA-256 | 从最终 canonical snapshot bytes 计算；本 delta 不预写 |

除 profileId、pipelineVersion、elementPromptVersion 外，v48 canonical JSON 与 v47 逐字段相同。

### Successor-only region codes

1. VISUAL_GROUNDING_REGION_ENTRY_INVALID
2. VISUAL_GROUNDING_REGION_ID_INVALID
3. VISUAL_GROUNDING_REGION_PARENT_ID_INVALID
4. VISUAL_GROUNDING_REGION_MULTIPLICITY_INVALID
5. VISUAL_GROUNDING_REGION_READING_ORDER_INVALID
6. VISUAL_GROUNDING_REGION_REPEAT_GROUP_ID_INVALID
7. VISUAL_GROUNDING_REGION_EVIDENCE_INVALID
8. VISUAL_GROUNDING_REGION_INVALID，仅作为无法安全分类的 fail-closed fallback

fixed code 来自确定的验证 seam，不得由字段值、异常 message、坐标、局部 ID 或 payload 拼接。

### Correction and breaker

1. OBSERVE correction allowlist 由 Prompt 14 明确覆盖的既有 codes 加上述七个新 codes组成。
2. allowlist code 的 rejected attempt 先 append-only 落账，再决定 retry；同 run/stage 同 code 第三次落账后
   terminal，不 reserve 下一次调用。
3. generic、未知或未列 code 在第一次 rejected attempt 落账后 terminal。
4. 不同 code 分别计数，跨 stage 不累计；总 hard caps 永远优先，额度不是消费目标。
5. v47 pipeline 4.29 与更早 snapshots 保持原行为。

### Fresh diagnostic

- 同一已知失败 artifact 只作为非计分 regression probe。
- fresh normalization、manifest、cycle、authorization 和 ledger；不复用 v46/v47 对应对象。
- caps 不宽于 maximumRuns=1、maximumProviderCalls=5、maximumModelTokens=100000、
  maximumCostMicrosCny=3000000、maximumProviderCallsPerRun=5、
  maximumCostPerRunMicrosCny=3000000、authorization window≤2h。
- 只有 REVIEW_REQUIRED 且人工接受才可另行拆评分票；本 delta 不预先批准 CANARY_5。

## Verification

按局部 codec/prompt/policy tests → fake-provider worker tests → Testcontainers certification/ledger/preflight →
fast → server 逐级扩大。所有自动验证保持 live disabled、authorization absent/closed、Provider attempts=0；
证据必须 payload-free。

## Out of Scope

- 修改、重开、重跑或删除 v47 及任何历史 Profile/cycle/J1/ledger/terminal。
- 扩大 route/model/output tokens/calls/cost/time，切换 Provider/model 或增加 fallback。
- 在 diagnostic 中计分、grant/certify Profile、自动 apply Candidate 或发布 StaticSchema。
- 在实际 v48/profile/manifest/evaluator/normalization/case/caps/timing 尚未精确绑定前签发或执行 paid J1。
- 生产部署、commit 或 push。

## Decision

- 批准人：RenderWeave 所有者
- 批准时间：2026-08-18T01:32:48+08:00
- 结论：Q1–Q4 全部按推荐批准；允许连续完成 Provider-zero R04–R07，随后停在 fresh exact J1 门前。
