# Spec Delta：IMAGE_ONLY v49 Mixed-Region Fallback Successor

- 状态：**APPROVED**
- Triage：ready-for-agent
- 日期：2026-08-18
- 批准权威：所有者于 `2026-08-18T09:54:40+08:00` 对 source ticket 24 的 Q1–Q4 回复
  “全部按推荐”
- Source ticket：
  `.scratch/image-only-schema-production-admission/issues/24-freeze-v49-mixed-region-fallback-recovery.md`
- 基线 delta：`specs/changes/20260818-image-only-v48-region-recovery-successor.md`
- 非授权声明：本 delta 只批准 Provider-zero recovery contract。它不是 paid live J1，不授权创建 OPEN
  authorization、读取 API Key、外传图片、认证计分/grant、Candidate apply、StaticSchema 发布或生产部署

## Problem Statement

v48 non-scoring diagnostic 已以 immutable `TERMINAL_CLOSED/FAILED` 结束。唯一 Provider attempt 返回 legacy
`VISUAL_GROUNDING_REGION_INVALID`；按 Prompt 14 与 pipeline 4.30 的批准策略，该 generic code 在 attempt 落账后
立即 terminal，未签发下一次调用。payload-free terminal 只能证明 generic fallback 被触发，不能证明具体字段、字段
值、region 数量或模型根因，也不能安全构造新的 correction hypothesis。

v48 已能对单一、确定的 region field seam 产生七类 field-specific code，但多个已知 field family 同时失败仍会被
generic wrapper 压平；collection/constructor/未知异常也落入同一个 generic。恢复必须在不读取历史或新模型 payload
的前提下，把“可行动的已知 mixed set”与“无法安全分类”分开，并让 bounded detail 从 codec 贯通到 retry、breaker、
append-only PostgreSQL ledger 与 payload-free evidence。v48 及更早 Profile、CLOSED J1、ledger、terminal 和证据保持
不可变。

## Solution

创建 successor-only mixed rejection envelope。两个及以上已知 region field family 同时失败时，primary 为
`VISUAL_GROUNDING_REGION_FIELDS_INVALID`，detail 为七类既有 field-specific code 中 2..7 个成员组成的去重、稳定
排序集合；其余无法安全分类的 fallback 使用 `VISUAL_GROUNDING_REGION_UNCLASSIFIED`，且不携带 detail。

只有 mixed primary 且 detail 的每个成员均由新 Prompt 明确覆盖时，才可形成 bounded correction。相同 canonical
detail set 在同 run/stage 第三次 rejected attempt 落账后 terminal，不 reserve 或签发下一次 Provider permit；不同
canonical set 分别计数。unclassified、未知、未列或 malformed envelope 第一次落账后 terminal。Profile 与当次
authorization 的 call/token/cost/time hard caps 永远优先。

由新的 hidden experimental v49 Profile 承载该语义。Provider-zero gates 全绿后，才可为同一已知失败
ordinary-design artifact 准备 fresh、非计分 diagnostic，并停在 fresh exact paid-live J1 门前。

## Acceptance Criteria

| AC | 可观察行为 | 最低证据 |
|---|---|---|
| AC-IOPA-V49-001 | v48 Profile bytes/hash、failed cycle、CLOSED J1/ledger、terminal/live summary 与证据不变；当前 OPEN authorization=0 | A1 immutable identity/digest checks |
| AC-IOPA-V49-002 | synthetic/provider-zero 矩阵穷举 v48 generic wrapper 的确定性 escape seams，并把两个及以上已知 field family 与 collection/constructor/未知异常安全分开 | exhaustive taxonomy A1 + independent coverage replay |
| AC-IOPA-V49-003 | known mixed 只产生 `VISUAL_GROUNDING_REGION_FIELDS_INVALID` 与 cardinality 2..7 的封闭、去重、稳定排序 detail set；detail 成员只来自冻结七码枚举 | codec/property A1 |
| AC-IOPA-V49-004 | 无法安全分类只产生 `VISUAL_GROUNDING_REGION_UNCLASSIFIED` 且无 detail；不得从异常 message、字段值或 payload 猜测 provenance | negative taxonomy + payload scan A1 |
| AC-IOPA-V49-005 | primary/detail envelope 在 codec、worker、attempt、Testcontainers PostgreSQL ledger、terminal 与 evidence 投影中一致；旧单-code行为不变 | scripted Provider + PG A1 |
| AC-IOPA-V49-006 | 只有 Prompt-covered mixed set 可纠正；相同 canonical set 第三次 rejected attempt 落账后熔断，不同 set 分别计数；unclassified/未知/未列首次落账后 terminal，且所有 hard caps 优先 | worker/property/PG A1 |
| AC-IOPA-V49-007 | v49 相对 v48 只改 profileId、pipelineVersion=`renderweave-inference-pipeline/4.31`、elementPromptVersion=`renderweave-visual-elements-prompt/15.0`；canonical SHA 从最终 resource bytes 重算 | exact byte-diff + hash replay A1 |
| AC-IOPA-V49-008 | v49 保持 hidden、experimental、ungranted、uncertified、非 product-live；exact route/model、8192 tokens、12 calls/¥6、360s、OCR、其他 Prompts、Candidate/evaluator/pricing 均不变 | registry/profile/certification A1 |
| AC-IOPA-V49-009 | fresh diagnostic 只含已知失败 case、certification credit=0，caps≤1 run/5 calls/100,000 tokens/¥3、每 run 5 calls/¥3、authorization window≤2h；无 fresh exact J1 时 Provider attempts=0 | normalization/manifest/preflight A1 |
| AC-IOPA-V49-010 | diagnostic 只可形成 `REVIEW_REQUIRED` owner review pack 或 immutable `FAILED` terminal；不自动 apply/publish、不计入 5/20/60、不 grant 或解锁 production path | lifecycle A1 + later J1 |
| AC-IOPA-V49-011 | 日志与证据只记录 identities、stage、fixed codes/detail cardinality、计数、聚合 token/费用和状态；不含图片、完整 request/response、RootDocument、异常 message 或 chain-of-thought | payload scan A1 |

## Frozen Identities and Behaviour

### v49 Profile boundary

| 字段 | 值 |
|---|---|
| profileId | dashscope-qwen38-max-product-v49-hybrid-generic |
| provider/model | DASHSCOPE / qwen3.8-max |
| provider base URL | https://dashscope.aliyuncs.com/compatible-mode/v1 |
| endpoint | https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions |
| pipeline | renderweave-inference-pipeline/4.31 |
| element Prompt | renderweave-visual-elements-prompt/15.0 |
| output/call/cost/time | 8192 tokens / 12 calls / ¥6 aggregate / 360s |
| canonical SHA-256 | 从最终 canonical resource bytes 计算；本 delta 不预写 |

除 profileId、pipelineVersion、elementPromptVersion 外，v49 canonical Profile JSON 与 v48 逐字段相同。diagnostic
identity families 冻结为 `renderweave-image-only-fresh-normalization/1.0:<final-sha256>`、
`renderweave-image-only-profile-successor-diagnostic/1.0:<final-sha256>` 与
`renderweave-image-only-profile-successor-diagnostic-evaluator/1.0:<canonical-sha256>`；evaluator 语义与 bytes 未变时
不得制造虚假 hash drift。Provider-zero verifier report version 必须 bump 为
`renderweave-image-only-v49-successor-provider-zero/1.0`。normalization、manifest、cycle、authorization/ledger 与
verifier evidence 的 exact digests 必须从最终 canonical artifacts 计算并冻结；不得复用 v48 对应对象或预写猜测 hash。

### Closed detail enum and canonical form

detail 成员只可来自下列冻结顺序：

1. `VISUAL_GROUNDING_REGION_ENTRY_INVALID`
2. `VISUAL_GROUNDING_REGION_ID_INVALID`
3. `VISUAL_GROUNDING_REGION_PARENT_ID_INVALID`
4. `VISUAL_GROUNDING_REGION_MULTIPLICITY_INVALID`
5. `VISUAL_GROUNDING_REGION_READING_ORDER_INVALID`
6. `VISUAL_GROUNDING_REGION_REPEAT_GROUP_ID_INVALID`
7. `VISUAL_GROUNDING_REGION_EVIDENCE_INVALID`

known-mixed detail 必须是该枚举中 2..7 个不同成员，并按上述顺序 canonicalize。零个、一个、重复、未知、超界或
非 canonical 顺序全部 fail closed。fixed code 不得由字段值、异常 message、坐标、局部 ID 或 payload 动态拼接。

### Classification envelope

1. 已知 mixed：primary=`VISUAL_GROUNDING_REGION_FIELDS_INVALID`，detail=canonical closed-enum set。
2. 无法安全分类：primary=`VISUAL_GROUNDING_REGION_UNCLASSIFIED`，detail absent。
3. terminal `failureCode` 只使用 primary；detail 只以封闭 fixed codes/cardinality 进入批准的 payload-free attempt 与
   evidence 投影，不拼接到异常文本。
4. v48 pipeline 4.30 及更早 snapshots 对相同 synthetic 输入保持历史行为。

### Correction and breaker

1. correction eligibility 要求 mixed primary，且 detail 的每个成员均在 Prompt 15 显式 allowlist 中。
2. rejected attempt 必须先 append-only 落账，再以同 run/stage 的 canonical detail set 计数；第三次相同 set 落账后
   terminal，不产生下一次 reservation/Provider permit。
3. 不同 canonical set 分别计数，但不扩大总预算；call/token/cost/time hard caps 始终优先。
4. unclassified、未知、未列或 malformed envelope 在第一次 rejected attempt 落账后 terminal。

### Fresh diagnostic

- 同一已知失败 artifact 只作为非计分 regression probe，不作为训练、评分或 grant 数据。
- fresh normalization、manifest、cycle、authorization 和 ledger；不复用 v48 对应对象。
- caps 不宽于 maximumRuns=1、maximumProviderCalls=5、maximumModelTokens=100000、
  maximumCostMicrosCny=3000000、maximumProviderCallsPerRun=5、maximumCostPerRunMicrosCny=3000000、
  authorization window≤2h。
- 只有 `REVIEW_REQUIRED` 且所有者人工接受才可另行拆 scoring ticket；本 delta 不预先批准任何 paid live 或
  certification stage。

## Verification

按 exhaustive synthetic taxonomy → codec/envelope/property → scripted-provider worker → Testcontainers PostgreSQL
ledger/lifecycle → registry/profile exact diff → independent verifier → fast → server 逐级扩大。全部自动验证保持 live
disabled、authorization absent/closed、Provider attempts/reservations/cost/API-key reads=0；证据必须 payload-free。

## Out of Scope

- 查看、恢复、推测或记录 v48 的原始模型 input/output，或修改、重开、重跑、删除任何历史 Profile/cycle/J1/
  ledger/terminal/evidence。
- 扩大 route/model/output tokens/calls/cost/time，改变 OCR、其他 Prompts、Candidate/evaluator/pricing 或增加 Provider
  fallback。
- 在 fresh exact Profile/manifest/evaluator/normalization/case/caps/timing 尚未绑定并另获 paid-live J1 前调用 Provider。
- 将 diagnostic 计入 5/20/60、认证/grant Profile、自动 apply Candidate、发布 StaticSchema 或部署生产。
- commit 或 push。

## Decision

- 批准人：RenderWeave 所有者
- 批准时间：`2026-08-18T09:54:40+08:00`
- 结论：Q1–Q4 全部按推荐批准；ticket 24 resolved，下一执行前沿为 ticket 25。tickets 25–29 是 Provider-zero
  dependency chain；ticket 30 仍由 fresh exact paid-live J1 阻断。本批准本身不授权任何 Provider 调用。
