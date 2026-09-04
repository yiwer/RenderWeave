# Spec Delta：IMAGE_ONLY v47 Profile Certification Successor

- 状态：**APPROVED**
- Triage：`ready-for-agent`
- 日期：2026-08-17
- 批准权威：所有者于 2026-08-17T20:14:05+08:00 对 source ticket 17 的 Q1–Q3 回复“全部按推荐”
- Source ticket：
  `.scratch/image-only-schema-production-admission/issues/17-freeze-v46-canary-failure-successor.md`
- 基线 delta：`specs/changes/20260817-image-only-production-admission.md`
- 影响：替换基线 delta 中“继续认证 v46”的未来路径；不修改其 P2–P5 数据保护、API、release、restore 或
  guarded-pilot 合同，也不改变任何历史 Profile/cycle/J1/ledger/evidence
- 非授权声明：本 delta 只授权 Provider-zero 实现。它不是 live J1，不授权读取 API Key、外传图片、生产部署、
  Candidate apply 或 StaticSchema 发布

## Problem Statement

v46 首个 `CANARY_5` 已以 immutable `TERMINAL_CLOSED/FAILED` 结束。失败 case 的 payload-free attempt
telemetry 显示 12 次 OBSERVE 中 10 次在 8192 output tokens 处截断，另 2 次违反 parent containment；pipeline
4.28 又会在总 call budget 内继续相同 stage hypothesis。继续运行旧 cycle、扩大旧授权、原地改 v46 或把最后一跳
containment code 当成唯一根因，都会覆盖负面终态或扩大未经证明的 Provider capability。

因此，v46 继续是 hidden、uncertified 的历史 candidate；后续质量义务必须由新的 immutable v47、明确的输出
收缩 Prompt、无信息量拒绝熔断、非计分回归 diagnostic 和全新评分 canary 承接。

## Solution

创建 `dashscope-qwen38-max-product-v47-hybrid-generic`。它保持 Max 的 exact route/model、8192 output-token
fail-closed 边界、12-call/¥6 run hard caps、OCR capability、Candidate contract、threshold 与 pricing snapshot；
只将 element Prompt 升到 13.0，并将 pipeline 升到 4.29。Prompt 13 用 bounded representative observation
避免密集重复版面枚举耗尽输出；pipeline 4.29 在同 stage 同一 diagnostic 第 3 次 rejected attempt 后终止，
不再签发下一次 Provider permit。

Provider-zero 全绿后，先对旧失败 artifact 建立独立、非计分 `PROFILE_SUCCESSOR_DIAGNOSTIC_1`。它通过且人工
接受后，才可用五张全新 ordinary-design 图片建立 v47 的评分 `CANARY_5`。两者使用不同 cycle/manifest/J1；
旧 v46 assignment、未启动 case、authorization 与 ledger 均不可复用。

## Acceptance Criteria

| AC | 可观察行为 | 最低证据 |
|---|---|---|
| AC-IOPA-S001 | v46 Profile bytes、failed cycle、CLOSED J1/ledger 与 terminal record 不变；v46 不能被 grant 或继续调度 | A1 prohibited-reuse/negative-terminal tests |
| AC-IOPA-S002 | v47 只相对 v46 改 `profileId`、`pipelineVersion=renderweave-inference-pipeline/4.29`、`elementPromptVersion=renderweave-visual-elements-prompt/13.0`；canonical SHA-256 从实际资源计算 | A1 exact byte-diff + independent hash replay |
| AC-IOPA-S003 | Prompt 13 要求 snake_case、每个强重复序列最多 3 个代表 ITEM、regions≤32、elements≤32；截断 retry 优先保留高置信 reusable structure 并折叠不确定分支 | A1 prompt identity/contract tests |
| AC-IOPA-S004 | pipeline 4.29 在同一 run/stage 同一 diagnostic 第 3 次 REJECTED attempt 已落账后，以该原始 code 终止且没有第 4 次 reservation/Provider call | fake Provider A1 + Testcontainers ledger A1 |
| AC-IOPA-S005 | 熔断计数跨 stage 隔离；不同 code 分别计数；stage 成功后不影响后续 stage；历史 pipeline 4.28 仍按其 immutable snapshot 行为 | focused/property regression A1 |
| AC-IOPA-S006 | `PROFILE_SUCCESSOR_DIAGNOSTIC_1` 是非计分独立 cycle，只允许旧失败 artifact 的 fresh manifest，caps=1 run/5 calls/100,000 model tokens/¥3/2h；缺 fresh exact J1 时 Provider attempts=0 | preflight/authorization/PG A1 + live J1 才可执行 |
| AC-IOPA-S007 | Diagnostic 只有到 `REVIEW_REQUIRED` 且人工接受才 PASS；其结果不计入 5/20/60、不签发 certification、不解锁 DEV/final | lifecycle tests + J1 review |
| AC-IOPA-S008 | v47 评分 `CANARY_5` 使用五张全新 `USER_PROVIDED+ORDINARY_DESIGN` 图片、新 manifest/cycle/J1；不得引用旧 v46 manifest、assignment、ledger 或三个未启动 case | manifest/prohibited-reuse A1 + exact J1 |
| AC-IOPA-S009 | v47 diagnostic 或评分 stage 的失败都是 immutable negative terminal；任何 Prompt/Profile/pipeline/cap/assignment 变化需要新 source ticket 与 identity | lifecycle/tamper A1 |
| AC-IOPA-S010 | 默认 gate、测试、启动与 preflight 保持 Provider-zero，不读取/输出 Key，不 apply Candidate，不发布 StaticSchema | A1 route/payload/static-schema scan |

## Implementation Decisions

### 1. Authority and precedence

1. Source ticket 17 是本恢复决策的唯一细节权威。本 delta 是其 approved 执行视图。
2. 基线 delta 继续约束全部未冲突条款。涉及 sole-finalist certification Profile、future exact Profile identity 或
   P2–P5 对已认证 Profile 的引用时，未来目标由 failed v46 切换为 v47；v46 的历史事实仍由基线 delta 与旧
   terminal 解释。
3. 本变更不把 v47 预先标为 certified、catalog-visible、accepted、released 或 ProductionUsable。

### 2. Exact successor identity

| 字段 | v47 值 |
|---|---|
| profileId | `dashscope-qwen38-max-product-v47-hybrid-generic` |
| provider/model | `DASHSCOPE` / `qwen3.8-max` |
| endpoint | `https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions` |
| pipeline | `renderweave-inference-pipeline/4.29` |
| element Prompt | `renderweave-visual-elements-prompt/13.0` |
| output/call/cost/time | 8192 tokens / 12 calls / ¥6 aggregate / 360s |
| OCR capability | `rapidocr-3.9.2-openvino-2026.0.0-ppocrv6-small-c05805399d7d10b1` |
| canonical SHA-256 | 实际 resource 创建后计算并进入 plan/cycle/preflight；本 delta 不预写 |

除 `profileId`、`pipelineVersion`、`elementPromptVersion` 外，v47 canonical JSON 与 v46 逐字段相同。尤其不得借
successor 修改 model、endpoint、credential selector、schema/hierarchy/binding Prompt、hint pack、Document
Vision、threshold、repair、tokens/bytes、calls、cost 或 pricing。

### 3. Prompt 13 response budget

1. 所有 local ids 与 `proposedKey` 使用 snake_case；不得把可见值复制进 semantic name。
2. 每个 visually certain repeated sequence 只保留 1 个 REPEATED_GROUP、最多 3 个代表 ITEM 与足以表达可复用
   shape 的 SLOT/GROUP；不枚举同构全部可见实例。
3. 整个 response 最多 32 regions、32 elements。达到预算时先删除置信不足、重复或不影响 reusable shape 的
   分支；不能通过扩大 box、伪造 GROUP 或破坏 containment 压缩。
4. 收到 `VISUAL_GROUNDING_OUTPUT_TRUNCATED` 时，重新生成完整但更小的 JSON；不得续写旧输出。其他 v12
   validator/semantic correction、ROOT fallback、OCR-untrusted 与 strong-sequence 规则继续生效。

### 4. Pipeline 4.29 equivalent-reject breaker

1. 计数来源只能是当前 run 的 append-only attempt ledger，筛选同一 stage、status=`REJECTED` 与相同
   diagnostic code；不依赖模型内容、异常 message 或进程内可丢状态。
2. 第 3 次 attempt 先完整 settlement/attempt record，再以该 diagnostic code fail run。不得在第 3 次之后创建
   reservation、call authorization 或 Provider request。
3. 仅 pipeline 4.29/successor opt in；pipeline 4.28 及更早 snapshot 不改变。Provider transport error、ambiguous
   attempt、budget/authorization failure 继续使用既有规则。

### 5. Recovery stage order

```text
Provider-zero gates
  → PROFILE_SUCCESSOR_DIAGNOSTIC_1 + exact J1
  → owner case review
  → fresh v47 CANARY_5 + different exact J1
  → existing 20 DEV / 60 final / production-policy J1 path
```

Diagnostic hard caps 固定为：`maximumRuns=1`、`maximumProviderCalls=5`、
`maximumModelTokens=100000`、`maximumCostMicrosCny=3000000`、authorization window≤2h；per-run caps 同样为
5 calls/¥3。它不是 Profile 的全局 12-call/¥6 边界变更，而是当次 authorization 的更窄限制。

## Verification

按 `RULE-VAL-001` 扩大：prompt/profile focused tests → fake-provider worker tests → Testcontainers certification
ledger/preflight → `fast` → `server`。所有自动验证清空 live enablement/authorization 与 Key selector；证据只记录
identity、fixed codes、attempt/token/cost counts 和 gate output，不保存图片、OCR、完整 request/response 或 Candidate。

首次 paid diagnostic 前必须另行生成并由所有者签发 `plans/live-canary-authorizations/` 下 exact JSON；本 delta 的
批准时间、旧 J1 或会话概括均不能代替它。

## Out of Scope

- 重开、重跑、修改、删除或 supersede v46 failed cycle/terminal/J1/ledger。
- 把 Max output tokens 提到 16384、切 Plus/Flash、第二 Provider、跨模型 fallback 或分块多调用管线。
- 在 diagnostic 中签发 certification、计入 5/20/60、复用旧未启动 case 或自动 apply/publish。
- P2–P5 既有 envelope encryption、OCR sidecar、API/release、restore 与 pilot 实现；它们继续由基线 delta 编排。
- 任何未经 fresh exact scoped J1 的 Provider 调用、真实数据外传、生产部署、commit 或 push。

## Decision

- 批准人：RenderWeave 所有者
- 批准时间：2026-08-17T20:14:05+08:00
- 结论：Q1–Q3 全部按推荐批准；允许连续完成 Provider-zero successor 实现与验证，随后停在
  `PROFILE_SUCCESSOR_DIAGNOSTIC_1` 的新 exact J1 门前。
