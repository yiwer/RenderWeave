# Change Spec：R5 有界视觉检查与自适应多尺度感知

- 状态：approved
- 日期：2026-08-14
- 批准人：用户已确认采用 bounded visual-inspection action，不升级 `DocumentObservationIR/1.0`；每个 run
  最多一轮、两个局部视图；先完成 exact 产品 transform 的离线 A2，再讨论 fresh J1
- 触发任务：P6/T6-5/N7 successor；VRQ 离线决策的 R5 `STOP_TO_SPEC_R5`
- 基线 revision：`12103f95fef047d09a76a8f140dac22c7775d815`
- 触发证据：`R2R5TriggerDecision/1.0`
  `renderweave-r2r5-trigger-decision/1.0:8ff04643a792e8f98de41fe36766b73756c39d5ee1ba2882d00b218b6d4a9653`；
  R5 evidence `renderweave-r5-oracle-probe-evidence/1.0:7410c028b3f9b55a2cdfce92a4686fa81813d999272e608565aaf28da61b5a09`
  及其 A2；VRQ-14 terminal disposition `LIVE_J1_REQUEST_NOT_ELIGIBLE`
- 证据 digest：R5 evidence SHA-256
  `a6424634ec281cd9df3fa3e3e381cd7b1146a84d8f9879f5f60a45dc7c2b738d`；R5 A2 SHA-256
  `d3b87b1aa93d72642d4834bf110351dc04680c27459bc49d8543f63d0c47e6b3`；VRQ decision SHA-256
  `71865af06d2fae288b847044b70c7d4b4108236f8a8addc08418c0f962a21585`
- 继承关系：本 delta 是 `specs/renderweave-v1.md`、`20260810-visual-recognition-vnext.md`、
  `20260813-document-observation-ir-layered-evaluation.md`、
  `20260814-visual-recognition-quality-repair-offline-admission.md`、N7、N8/R0、N9/R1、product-v45、
  ADR-0025、ADR-0028、ADR-0036 与 ADR-0038 的 additive successor
- 权威关系：`specs/renderweave-v1.md` 继续是 v1 权威源；本 delta 不 supersede、不改写历史 Prompt、Profile、
  pipeline、view plan、IR、corpus、run snapshot、authorization、ledger、ticket、decision 或 evidence
- 影响 AC/规则：新增 AC-R5I-001..018；细化 AC-VR-004、005、007、008、010、AC-DOIR-001..012 与
  AC-VRQ-014、016、017、018；RULE-ANCHOR-001、RULE-AUT-001、RULE-VAL-001、RULE-EVD-001、
  RULE-STATE-001
- 再锚定关系：本 delta 一经批准，即成为 `RULE-ANCHOR-001` 的对照基准之一；后续 ticket 必须绑定本 delta、
  exact clean revision、不可变 transform/request/policy/plan/Profile/EvaluationIdentity 及零 Provider 离线范围

## Problem Statement

RenderWeave 已经有确定性的静态多尺度 view planner：每个源图先生成最长边 768 的 overview，再加入最长边
1400 的 tile 或基于已验证计划产生的 targeted crop；单次最多 10 个 view、总编码字节最多 30 MiB，并把
view-relative evidence 可逆映射回原规范化 artifact。该设计证明了坐标、恢复和预算边界，但所有 view 都在
Provider 调用前由代码一次性决定。模型若在 OBSERVE 阶段发现某个局部小字或密集区域不可读，只能猜测、遗漏或
返回一个最终会被 validator 拒绝的语义计划，不能声明“请把这个已知 view 的这个局部再看清楚”。

R5 oracle probe 已在两个确定性 run 中覆盖 3 个 DEV 和 1 个 HOLDOUT case。四例的静态基线均存在 line miss；
仓库合成场景以固定 2x、最长边不超过 2400 的 higher-resolution oracle 重新渲染后，每例 matched line 或
character error 均改善，critical hallucination 没有增加，独立 verifier 判定
`R5_ORACLE_DIFFERENTIAL_CONFIRMED`。这满足了 R5 的证据触发门，并使唯一合法下一步成为窄 successor spec。

但该 oracle 重新渲染了仓库中的矢量场景，并不等价于产品对用户已经提交的规范化位图执行 crop/resize。它证明
“静态 view 可读性是实测贡献因素”，没有证明现有位图经过实际产品 transform 后仍有足够信息，也没有证明真实
模型会正确请求 region、使用新 view 或产出更好的 Schema。直接依据 oracle 申请 live 会把诊断证据误报为产品
能力，并违反 VRQ-14 的 `LIVE_J1_REQUEST_NOT_ELIGIBLE` 终态。

同时，R5 不能通过把裁剪能力交给开放式 Agent 来解决。模型不能得到文件、HTTP、SQL、发布、任意图片工具、
自选分辨率、循环次数、预算或结束条件。派生 view 不能进入长期 Blob、常规 evidence 或 Candidate；外层仍须由
现有 PostgreSQL durable typed state machine 掌握 lease、checkpoint、恢复、取消、预算和终态。

因此需要一个很窄的产品能力：模型只能返回闭合的声明式 `InspectionRequest/1.0`；一个 Java-owned 的深 Module
在唯一新 seam 上完成严格验证、固定 transform、资源预检、确定性 view 规划、原图坐标映射和固定结果；工作流
最多允许一次 `OBSERVE → inspection → OBSERVE` 的局部回边。该能力必须先用 exact 产品 transform 获得离线
A1/A2，再进入完整 scripted workflow；在这些证据产生前，不得申请 fresh J1。

## Solution

新增深 Module `BoundedVisualInspection`。它把 request 合同、policy、crop/resize、view 选择、坐标 lineage、
预算与稳定失败码收在一个 Interface 后面。唯一新高层 seam 是：

> `normalized ArtifactSet + renderweave-visual-view-plan/1.0 + decoded InspectionRequest/1.0 + exact AdaptiveInspectionPolicy/1.0 → InspectionOutcome/1.0`

`InspectionOutcome/1.0` 只能是：

| Disposition | 含义 |
| --- | --- |
| `EXECUTED` | request 合法且全部预算可用，产生一个完整、确定性的 `renderweave-visual-view-plan/2.0` |
| `REJECTED` | request 的 ID、几何、成员、枚举、重复或状态非法；返回固定 reason code，不生成局部 view |
| `EXHAUSTED` | round、view、像素、字节、视觉 token、费用、调用或时间预算不足；在外部副作用前停止 |

本 delta 选择 bounded acquisition action，而不是 additive `DocumentObservationIR`。现有主感知 seam 保持：

> `normalized ArtifactSet + exact AcquisitionPolicy → DocumentObservationIR/1.0`

R5 改变的是向视觉语义模型暴露的像素视图，不增加 OCR/layout observation 的事实类型。IR/1.0、
`VisualEvidenceAcquisition`、RapidOCR capability、compatibility projection 与 R0/R1 identity 均不改变；局部 view
也不能被伪装成新的持久 observation contract。

### 离线优先的门序

1. `R5I-0 AUTHORITY_LOCK` 精确绑定本 delta 的基线 revision、VRQ decision、R5 evidence/A2、R5
   `TRIGGERED`、R2/R3/R4 的既有 disposition 以及 VRQ-14 `LIVE_J1_REQUEST_NOT_ELIGIBLE`。任一漂移均停止。
2. `R5I-1 PRODUCT_TRANSFORM` 只实现足以执行真实产品 crop/resize 与 view projection 的最小 Module
   Implementation，并用冻结 request assignment 对相同 3 DEV + 1 HOLDOUT 做两次零 Provider 实际运行。
   输入必须是 corpus v2 的规范化 raster bytes，不得使用矢量场景重新渲染、gold text 或 oracle image。
3. `R5I-2 TRANSFORM_A2` 独立 verifier 重算 assignment、policy、几何、尺寸、view identity、资源计数、
   分层指标和 disposition。若 exact 产品 transform 未通过，终态固定为
   `R5_PRODUCT_TRANSFORM_NOT_QUALIFIED`，不得继续 Prompt、Profile、workflow 或 live 工作。
4. `R5I-3 ACTION_CONTRACT` 只有 transform A2 通过后，才实现 request/outcome/plan v2、严格 decoder、固定
   reason code、预算、checkpoint 子状态和 additive offline-only successor Prompt/Profile/pipeline identity。
5. `R5I-4 DURABLE_REPLAY` 通过 Testcontainers PostgreSQL 执行完整 IMAGE_ONLY scripted replay，并覆盖
   crash、lease、取消、重复 request、预算耗尽、invalid ID 和 accepted-stage zero replay。
6. `R5I-5 COMPATIBILITY` 复跑现有无 inspection 的 product-v45/R0 路径，证明历史 Profile、Prompt、pipeline、
   Candidate、evidence、blocker 与 `REVIEW_REQUIRED` 行为等价。
7. `R5I-6 INDEPENDENT_ADMISSION` 独立 verifier 重算 transform、request、plan、checkpoint、终态、指标、
   identity、payload scan 与外部 Provider 零使用。所有门通过后才可输出
   `R5_LIVE_J1_REQUEST_ELIGIBLE`；该结果不是 authorization，也不自动创建 J1。

当前仅有 R5 trigger 证据，没有 exact 产品 transform A2、action contract、durable replay 或 independent
admission，故当前 disposition 继续是 `LIVE_J1_REQUEST_NOT_ELIGIBLE`。

### Acceptance Criteria

| ID | 行为验收 | 最低证据 |
| --- | --- | --- |
| AC-R5I-001 | 输入精确绑定基线 revision、VRQ decision、R5 evidence/A2 与 VRQ-14 terminal；任一 identity、digest、trigger 或 terminal 漂移都 fail-closed | authority/tamper A1；独立重算 A2 |
| AC-R5I-002 | 唯一新 seam 接受 normalized ArtifactSet、现有 plan v1、decoded request 与 exact policy，并只返回闭合 `InspectionOutcome/1.0`；caller 不直接操作 crop、resize、view selection 或预算 | contract/property A1 |
| AC-R5I-003 | `DocumentObservationIR/1.0`、AcquisitionPolicy、RapidOCR、R0/R1 projection 与 shape identity 均不修改；inspection view 不进入 IR、Candidate 或长期 observation store | snapshot/architecture negative tests A1 |
| AC-R5I-004 | OBSERVE successor 输出只能是完整现有 grounding contract或完整 `InspectionRequest/1.0` 二选一；混合成员、未知成员、duplicate key、trailing token、null primitive、float-as-int、scalar coercion 与数字 enum 均拒绝 | codec/schema parity A1/A2 |
| AC-R5I-005 | request 只含 1..2 个 region；每个 region 只引用当前 verified `baseViewId`、严格非退化 view-relative box、固定 margin preset 与固定 resolution preset；无自由文本、路径、URL、工具名、模型名或预算字段 | contract/tamper A1 |
| AC-R5I-006 | 每个 run 最多一轮、最多两个 inspected views；第二轮、重复 region、未知/历史 view、越界 box、错误 source lineage 或 consumed request 均在 view/provider 副作用前以固定 reason code 停止 | truth-table/property A1 |
| AC-R5I-007 | v1 policy 只允许 margin `TIGHT_0000_BPS`、`CONTEXT_0500_BPS`，resolution `DETAIL_LONG_EDGE_1400`、`INSPECT_LONG_EDGE_2400`；模型不能提供任意 margin、像素、scale 或插值算法 | enum/negative A1 |
| AC-R5I-008 | inspected attempt 仍最多 10 views、30 MiB；两个 inspected views 合计最多 11,520,000 pixels、按 exact estimator 最多 12,000 additional visual tokens，local transform 最多 10,000 ms；所有 checked arithmetic 溢出 fail-closed | limit boundary/property A1 |
| AC-R5I-009 | plan v2 先保留每个 source 的 required overview，再放 canonical inspected views，最后按现有稳定顺序填充 static tiles；required view 超预算则整体拒绝，不静默丢弃 request | golden/property A1 |
| AC-R5I-010 | plan v2 的 view bytes、descriptor、排序、identity 与 view-relative→原 artifact 坐标映射在同一冻结环境的两次运行中一致；最终 Candidate evidence 仍只含原 artifact identity/坐标，不含 inspection view ID | transform/coordinate A1；A2 replay |
| AC-R5I-011 | product transform 门使用冻结的 `transit-board-v3`、`restaurant-menu-v3`、`hospital-schedule-v3` DEV 与 `transit-board-v5` HOLDOUT request assignment；参数在首个结果前冻结，HOLDOUT 结果不得触发 retuning | assignment/access A1；A2 audit |
| AC-R5I-012 | exact 产品 raster transform 的两次运行必须 4/4 deterministic；每例 matched line 增加或 character error 减少，四例 aggregate line recall 至少改善 500 bps且 character error 总数下降，任何 case 的 hallucination 不增加 | layered report A1；独立指标重算 A2 |
| AC-R5I-013 | 若 AC-R5I-011/012 任一不成立，终态只能为 `R5_PRODUCT_TRANSFORM_NOT_QUALIFIED`；不得通过改 Prompt、换模型、修改 validator、读取 HOLDOUT 调参或申请 live 来补救 | stop-policy negative tests A1/A2 |
| AC-R5I-014 | 合法 request 被原子记录为 OBSERVE 的 bounded inspection 子状态；crash 后从原 blob 与 canonical request 重建，不持久化派生 view；相同 request 不重复执行，不同第二 request 以 loop exhausted 停止 | Testcontainers crash/lease A1 |
| AC-R5I-015 | 最高产品 seam 从 IMAGE_ONLY scripted 首次 OBSERVE request，经本地 inspection、第二次 OBSERVE grounding、HIERARCHY、ELEMENT_BINDING、LOCAL_MATERIALIZE 到 `REVIEW_REQUIRED`；无发布、Apply 或自动接受 | PostgreSQL workflow A1；terminal A2 |
| AC-R5I-016 | 无 inspection 的历史 product-v45 完整 replay 保持 Candidate/evidence/blocker/terminal 行为等价；Prompt 12/7/4、Candidate Prompt 5、pipeline 4.28 与历史 Profile snapshot 字节不变 | regression/snapshot/full A1；A2 comparison |
| AC-R5I-017 | 所有离线 gate 的 external Provider attempts、reservations、cost 与 API-key reads 均为 0；日志、evidence、tracker summary、异常与对象字符串不含图片、Base64、完整 bbox list、OCR、Prompt、模型或 Candidate 原文 | zero-provider + payload scan A1/A2 |
| AC-R5I-018 | 只有 clean revision、fresh EvaluationIdentity、transform A2、action/recovery、完整 scripted replay、v45 equivalence、full gate 与 independent admission 全绿时，才可产出 `R5_LIVE_J1_REQUEST_ELIGIBLE`；该 disposition 不创建、打开或批准 J1 | admission truth table A1/A2；fresh exact J1 仍为 J0 |

## User Stories

1. As a RenderWeave user, I want a dense or small-text region to be inspected more closely, so that important fields are less likely to be omitted.
2. As a RenderWeave user, I want the result to remain a reviewable Schema Candidate, so that better perception does not bypass my approval.
3. As a product owner, I want R5 work anchored to the triggered offline decision, so that implementation starts from measured evidence rather than enthusiasm for tool use.
4. As a product owner, I want the product bitmap transform proven before another model call, so that live budget is not spent on an ineffective crop/resize path.
5. As a product owner, I want a failed transform gate to stop the route, so that Prompt tuning cannot disguise a perception failure.
6. As a product owner, I want existing product-v45 behavior preserved when no inspection is requested, so that the successor is additive.
7. As a perception engineer, I want one deep Module to own request validation, transforms, plan selection and limits, so that the rules do not drift across callers.
8. As a perception engineer, I want the action seam to consume normalized artifacts and an existing view plan, so that orientation and normalization remain authoritative.
9. As a perception engineer, I want the action to return a complete outcome, so that callers never assemble a partially valid view plan.
10. As a perception engineer, I want fixed margin presets, so that a model cannot select arbitrary crop expansion.
11. As a perception engineer, I want fixed resolution presets, so that a model cannot create unbounded pixel work.
12. As a perception engineer, I want at most two local views, so that inspection remains a bounded information request rather than a search strategy.
13. As a perception engineer, I want the exact runtime transform evaluated on raster inputs, so that vector oracle evidence is not mistaken for product evidence.
14. As a perception engineer, I want deterministic view identity and ordering, so that retries and independent replay observe the same plan.
15. As a perception engineer, I want view evidence mapped back to the normalized source artifact, so that derived view IDs never become domain evidence.
16. As an IR maintainer, I want `DocumentObservationIR/1.0` unchanged, so that R5 does not smuggle a new observation vocabulary into a view action.
17. As an IR maintainer, I want inspection images excluded from IR and compatibility projection, so that OCR provenance remains truthful.
18. As an LLM contract maintainer, I want OBSERVE to return either grounding or an inspection request, so that ambiguous mixed responses fail closed.
19. As an LLM contract maintainer, I want request JSON closed and strictly typed, so that parser permissiveness cannot grant action authority.
20. As an LLM contract maintainer, I want requests limited to known view IDs and bounded boxes, so that the model cannot address arbitrary data.
21. As a prompt maintainer, I want Prompt 12 immutable, so that historical Profile snapshots remain reproducible.
22. As a prompt maintainer, I want any R5 OBSERVE instruction to use an additive immutable successor identity, so that the behavior can be evaluated independently.
23. As a Profile maintainer, I want the R5 Profile hidden and experimental until offline admission, so that an unqualified action cannot appear in product selection.
24. As a workflow maintainer, I want PostgreSQL to remain the durable control truth, so that inspection does not introduce a second workflow runtime.
25. As a workflow maintainer, I want one typed OBSERVE inspection substate, so that the loop is recoverable without creating an open-ended planner.
26. As a workflow maintainer, I want successful semantic stages never replayed, so that inspection does not erase accepted work.
27. As a recovery engineer, I want derived views regenerated from original blobs, so that crash recovery does not require persisting sensitive image derivatives.
28. As a recovery engineer, I want duplicate requests idempotently recognized, so that redelivery cannot consume the action twice.
29. As a recovery engineer, I want cancellation and lease loss checked before transform and provider work, so that stale workers cannot continue.
30. As a validator maintainer, I want fixed reason codes for invalid IDs, geometry, limits and state, so that failure routing is deterministic and payload-safe.
31. As a validator maintainer, I want a second request rejected after the single round, so that model output cannot extend the loop.
32. As a security reviewer, I want the model unable to name tools, paths, URLs, models or budgets, so that inspection cannot become a general executor.
33. As a security reviewer, I want images, OCR, Prompt and model output absent from ordinary evidence, so that offline qualification does not expand the sensitive-data surface.
34. As a security reviewer, I want parsed request state separated from raw model output, so that recovery stores only the minimum validated action intent.
35. As a cost owner, I want view, pixel, byte, token, call, cost and time limits checked before an irreversible call, so that one region cannot escape the budget.
36. As a cost owner, I want the local transform itself to use zero Provider attempts, so that its qualification is free of paid-call ambiguity.
37. As an evaluation owner, I want the four trigger cases run twice through the exact product transform, so that the implementation is compared with the oracle hypothesis.
38. As an evaluation owner, I want improvement in every target case and at aggregate level, so that one large win cannot hide a failed case.
39. As an evaluation owner, I want hallucination non-increase enforced per case, so that magnification does not buy recall by inventing text.
40. As a holdout custodian, I want transform parameters frozen before HOLDOUT execution, so that known HOLDOUT results do not become a tuning loop.
41. As an independent verifier, I want to recompute assignment, policy, geometry, identities, metrics and terminal decision, so that the production Module cannot certify itself.
42. As an independent verifier, I want Java/Python evidence contracts to reject the same unknown members and numeric confusions, so that A2 cannot accept a payload Java would reject.
43. As a reviewer, I want a complete scripted IMAGE_ONLY replay to end at `REVIEW_REQUIRED`, so that an isolated transform test is not mistaken for product compatibility.
44. As a governance owner, I want `R5_LIVE_J1_REQUEST_ELIGIBLE` separated from J1 approval, so that automated evidence never authorizes an external call.
45. As a governance owner, I want N7-04/N7-05 identities permanently excluded from a future R5 authorization, so that failed historical experiments cannot be reopened by renaming the route.
46. As a future Agent, I want stable identities, reason codes and gate order, so that I can implement each tracer bullet from a fresh context without inventing policy.
47. As a future Agent, I want a failed product transform to terminate remaining tickets, so that goal persistence does not override evidence.
48. As a Schema maintainer, I want semantic validation and deterministic materialization unchanged, so that closer visual inspection cannot directly create fields, arrays or relationships.

## Implementation Decisions

### 1. Authority and inheritance

1. The accepted R5 trigger is immutable input. The oracle evidence is not replayed as product-transform evidence and cannot be
   relabeled as action qualification.
2. Historical Prompt, Profile, pipeline, view-plan, checkpoint and IR contracts remain readable and byte-stable. New behavior is
   selected only by additive successor identities.
3. R2 remains `EVIDENCE_REQUIRED`, R3 remains `EVIDENCE_REQUIRED`, and R4 remains
   `REJECTED_BY_CURRENT_EVIDENCE`. This delta cannot implement, combine or re-evaluate those routes.
4. Corpus v2 remains a shadow diagnostic corpus. It does not replace the AC-021 authoritative corpus or create a product quality
   certification claim.

### 2. One deep Module and one new seam

1. `BoundedVisualInspection` is the only new high-level Module. Its Interface includes strict inputs, deterministic ordering,
   fixed dispositions, reason codes, limits, sensitivity rules and performance ceilings.
2. Crop generation, resize, margin projection, inspected-view selection, source lineage, identity, checked arithmetic and
   resource accounting remain inside its Implementation. They are not separate public seams.
3. The Module accepts a complete normalized ArtifactSet and the exact current plan, rather than arbitrary bytes or a filesystem
   locator. It returns a complete outcome rather than mutating caller-owned collections.
4. Internal transform or image-codec seams may exist for focused tests, but callers and product tests use the single high-level
   Interface.

### 3. Inspection request contract

1. `InspectionRequest/1.0` has exactly `contractVersion` and `regions`. `regions` contains one or two closed records with exactly
   `baseViewId`, `boundingBox`, `marginPreset` and `resolutionPreset`.
2. A request carries no free-form explanation, tool name, provider/model name, target filename, URI, token count, price,
   priority, loop count or termination instruction.
3. `baseViewId` must be present in the current verified plan v1. The box uses the existing 0..10000 view-relative coordinate
   convention, must be non-degenerate and must remain within its base view before margin expansion.
4. Exact duplicate regions are rejected. Region ordering is canonicalized by source ordinal, base-view order, top, left,
   bottom, right and preset; model-provided order does not create hidden priority.
5. Margin presets are `TIGHT_0000_BPS` and `CONTEXT_0500_BPS`. Context expansion is projected into original source pixels and
   intersected with source bounds using deterministic integer arithmetic.
6. Resolution presets are `DETAIL_LONG_EDGE_1400` and `INSPECT_LONG_EDGE_2400`. Aspect ratio is preserved, dimensions never
   exceed 2400, and the model cannot select interpolation or output format.
7. The decoder is closed, bounded and strict. Syntax-valid but semantically invalid requests return stable rejection codes; no
   permissive repair or defaulting is allowed.

### 4. Plan v2 and product transform

1. The product transform starts from the exact normalized raster bytes used by the product. Evaluation cannot rerender vector
   scenes, substitute oracle images or read annotation text inside the Module.
2. Plan v2 retains required overview views, then adds canonical inspected views, then fills remaining capacity with existing
   static tiles in the existing round-robin order. An inspected view is required once its action is accepted; if required views
   exceed a hard limit, the whole action is rejected.
3. A plan has at most 10 views and 30 MiB encoded bytes. At most two are inspected views; their combined decoded pixels are at
   most 11,520,000. PNG generation, content digest and source-coordinate transform remain deterministic under the frozen runtime
   identity.
4. Inspection policy v1 sets maximum rounds to 1, maximum inspected views to 2, maximum additional visual tokens to 12,000 and
   local transform timeout to 10,000 ms. These values and the estimator identity participate in policy identity.
5. Provider request cost remains Profile/model-specific. A future call must fit both the immutable Profile ceiling and the
   fresh exact J1 remaining attempts, tokens, cost and expiry before a reservation; otherwise the action path stops.
6. Plan v2 and request identities exclude wall-clock timestamps and local paths. They bind source artifact identities,
   normalized dimensions, base plan identity, canonical request, transform identity and policy identity.

### 5. Product-transform qualification

1. The assignment is the existing R5 3 DEV + 1 HOLDOUT case set, with fixed target boxes and presets frozen into a new
   assignment identity before execution. Gold boxes belong only to the evaluator and scripted request fixture; the Module cannot
   read them.
2. Each static and inspected acquisition is run twice with the exact production transform and frozen RapidOCR capability solely
   as a local readability measurement. OCR text stays ephemeral and is not copied into evidence.
3. The report includes only case/partition identities, transform/request/policy/plan identities, observation and error counts,
   bps metrics, dimensions, timings, resource summaries, fixed codes and external Provider zero-use facts.
4. Admission requires two-run determinism, per-case target improvement, aggregate line-recall gain of at least 500 bps,
   aggregate character-error reduction and per-case hallucination non-increase.
5. The already viewed HOLDOUT case confirms the predeclared causal direction only. It cannot be used to tune transforms or claim
   unbiased AC-021 certification.
6. Failure ends this route. Super-resolution, deblurring, alternate OCR, learned crop selection or different interpolation after
   observing results requires a new `$to-spec`; it cannot be inserted into a repair ticket.

### 6. Durable bounded control

1. The outer PostgreSQL durable typed state machine remains the only workflow authority. No second history store or workflow
   runtime is introduced.
2. A valid first OBSERVE inspection request is not an accepted semantic OBSERVE result. The workflow atomically records the
   provider/scripted attempt and a typed inspection substate containing the canonical validated request, identities and consumed
   counters, while remaining at the OBSERVE stage.
3. Only the parsed, validated action intent needed for recovery may be stored in the protected checkpoint. Raw model output,
   Prompt, request envelope and derived image bytes are not persisted.
4. On the next advance, the worker checks cancellation and lease, regenerates plan v2 from original blobs, rechecks all limits
   and makes at most one additional OBSERVE invocation. HIERARCHY and ELEMENT_BINDING remain causally serial.
5. A repeated identical request in an already consumed round cannot regenerate views or create another invocation. A different
   request after the round is consumed returns loop exhausted. Invalid action state fails closed.
6. Crash before or during local transform is safe because the transform has no external side effect. Crash recovery recreates
   the same plan; once the inspected OBSERVE result is accepted, it is never replayed.
7. Existing deterministic local materialization, RenderWeave validator, `REVIEW_REQUIRED` and create-only Apply transaction are
   unchanged.

### 7. Additive semantic identities

1. Historical OBSERVE Prompt 12, HIERARCHY Prompt 7, ELEMENT_BINDING Prompt 4 and Candidate Prompt 5 bytes remain immutable.
2. An additive R5 OBSERVE Prompt successor may explain the closed request contract and when it is allowed. It cannot describe a
   general tool, recursive search, self-selected budget or hidden chain-of-thought.
3. The OBSERVE response-shape successor is a closed discriminated union between the existing grounding contract and
   `InspectionRequest/1.0`. This is local decode/validation, not R4 Provider-native JSON Schema strict output.
4. An additive pipeline/Profile successor binds the new Prompt, response shape, plan v2, policy and maximum one extra OBSERVE
   call. It remains hidden `EXPERIMENTAL` and unavailable to product creation until a later policy decision.
5. Offline scripted execution uses a no-network Adapter and therefore creates no Provider attempt, reservation, token, cost or
   API-key read.

### 8. Privacy, evidence and observability

1. Protected run state may contain the minimum canonical request geometry required for recovery. Ordinary logs, metrics,
   reports, evidence and tracker summaries may contain only request/plan identities, counts, enum counts, dimensions, pixels,
   tokens, durations, fixed codes and hashes.
2. Ordinary evidence must not contain source or derived images, Base64, full bbox lists, OCR text, Prompt text, model
   request/response, Candidate content, Provider request ID, API key, RootDocument or chain-of-thought.
3. String representations and exceptions redact request geometry and image bytes. Payload scans cover stdout, stderr, test
   reports, exceptions, evidence, tracker body and tamper failures.
4. A local controlled visual diff may inspect only allowlisted repository synthetic/CC0 artifacts and is J0. It is not copied to
   ordinary evidence and does not satisfy product visual acceptance.

### 9. Future live eligibility and authorization

1. Spec approval, implementation completion or an A1 green gate alone cannot change the current
   `LIVE_J1_REQUEST_NOT_ELIGIBLE` disposition.
2. `R5_LIVE_J1_REQUEST_ELIGIBLE` requires all R5I gates, clean revision, fresh EvaluationIdentity, exact product-transform A2,
   full scripted workflow, product-v45 equivalence, full gate, payload scan and independent terminal reconstruction.
3. Eligibility does not create an authorization. A future request must bind a new ticket, authorization ID, contract identity,
   evaluation identity, model, exact immutable Profile snapshot, Prompt/shape/pipeline/policy identities, data classification,
   case assignment, serial/batch rules, maximum inspection rounds/views, attempts, input/output tokens, estimated cost, expiry and
   exact secret name.
4. N7-04 and N7-05 ticket, authorization, contract and evaluation identities are permanently invalid for that request. No field
   may be inherited from them by omission.
5. No fresh J1 is requested, created, opened or implied by this delta.

## Testing Decisions

### 1. Test philosophy and seams

Good tests cross the same Interface as a caller and assert observable outcomes: disposition, reason code, plan descriptors,
identity, limits, original-coordinate evidence, durable stage, terminal state, metrics and redaction. They do not assert helper
methods, Jackson traversal, image-loop structure, collection implementation or class collaboration.

The primary R0 technical seam remains:

> `normalized ArtifactSet + exact AcquisitionPolicy → DocumentObservationIR/1.0`

The only new R5 seam is:

> `normalized ArtifactSet + renderweave-visual-view-plan/1.0 + InspectionRequest/1.0 + AdaptiveInspectionPolicy/1.0 → InspectionOutcome/1.0`

The highest product seam is a complete IMAGE_ONLY scripted replay through the existing PostgreSQL durable state machine to
`REVIEW_REQUIRED`, including one inspection round and a no-inspection behavior-equivalence branch.

### 2. Contract and policy tests

1. Positive goldens cover one and two requests, both margins, both resolutions, multiple source artifacts and deterministic
   canonical ordering.
2. Negative vectors cover empty/three regions, unknown/old view ID, duplicate region, degenerate/out-of-range box, unknown enum,
   free-form fields, duplicate JSON keys, trailing content, unknown members, null primitive, boolean/int confusion, float-as-int,
   scalar coercion, numeric enum and oversized payload.
3. Boundary tests cover exactly 1 and 2 regions, 1399/1400/1401 and 2399/2400/2401 dimensions, 10/11 views, byte/pixel/token
   limits, timeout equality and one-unit overflow.
4. Checked-add/multiply tests reject integer/long overflow before allocation, identity construction or Provider estimation.
5. Java contract and independent verifier fixtures must accept and reject the same closed shapes and JSON value types.

### 3. Product-transform tests

1. Freeze case IDs, target boxes, presets, normalized artifact identities, transform/runtime/PNG identities, RapidOCR identity,
   thresholds and resource ceilings before the first run.
2. Execute two real local runs over all four cases using raster input and the exact product transform. Compare view bytes/hash,
   dimensions, geometry, observations and metrics across runs.
3. Recompute matched-line recall, character errors and hallucination counts independently. Test exact 500-bps pass, 499-bps
   fail, unchanged/increased character errors and per-case hallucination increase.
4. Include a vector-oracle substitution negative test, a gold-text access negative test and a changed transform/preset identity
   test.
5. A failed transform emits only the terminal rejection evidence and prevents downstream action-contract tickets from claiming
   completion.

### 4. View-plan and coordinate tests

1. Reuse prior multi-scale planner property patterns for overview precedence, stable tile fill, 10-view/30-MiB limits and
   reversible coordinates.
2. Verify inspected views precede optional tiles but never displace required overviews; insufficient capacity rejects the action
   instead of silently dropping an inspected view.
3. Property tests map view corners, one-pixel source edges, context-clipped edges and random valid boxes back into original
   normalized artifact bounds.
4. Verify Candidate and checkpoint semantic plans contain only original artifact evidence and never inspection view IDs or bytes.

### 5. Workflow, recovery and authorization tests

1. Script first OBSERVE as an inspection request and second OBSERVE as valid grounding, then complete HIERARCHY,
   ELEMENT_BINDING, LOCAL_MATERIALIZE and deterministic validation to `REVIEW_REQUIRED`.
2. Fault-inject before checkpoint, after checkpoint/before transform, during transform, after plan generation and after accepted
   inspected OBSERVE. Recovery must preserve the single-round rule and accepted-stage zero replay.
3. Test identical duplicate request, different second request, stale lease, cancellation, expired deadline, missing original
   blob, changed policy, changed base plan and insufficient remaining call/token/cost budget.
4. Offline replay must use a no-network scripted Adapter and prove external Provider attempts, reservations, cost and API-key
   reads are zero.
5. A live-capable Adapter without a fresh exact OPEN J1 must fail before request construction or reservation.

### 6. Compatibility and non-regression tests

1. Re-run the existing product-v45/R0 no-inspection replay and compare Candidate contract, evidence, blocker set, terminal state
   and accepted-stage call counts.
2. Verify historical Prompt/Profile/pipeline/view-plan/checkpoint/shape snapshots and identities are unchanged.
3. Verify IR/1.0 acquisition and compatibility projection produce the same observations and payload-safe summaries.
4. Verify the successor remains hidden `EXPERIMENTAL` and cannot be selected by product create routes.
5. Run focused inference tests, server/document-vision gates, PostgreSQL E2E and the full gate at an exact clean revision.

### 7. Independent evidence tests

1. The A2 verifier reads each file from one immutable byte snapshot, rejects duplicate/trailing/unknown/coerced JSON and scans
   decoded keys/values as well as raw bytes for forbidden payload markers.
2. It recomputes authority, assignment, transform, request, policy, plan, report and terminal identities without calling the
   production decision engine.
3. It independently recomputes metrics, thresholds, resource limits, workflow transition counts, Provider-zero accounting and
   the final eligibility disposition.
4. Tamper tests cover every identity, metric, case partition, enum, bbox-derived summary, attempt/reservation/cost value,
   repository revision, terminal state and supporting evidence reference.
5. Automated verification is at most A1/A2. No A3 or live-quality J1 is claimed.

### 8. Gate order

Validation expands according to `RULE-VAL-001`:

1. authority lock and negative probes;
2. minimal product transform focused tests;
3. exact 3 DEV + 1 HOLDOUT two-run transform report;
4. independent transform A2 and stop/go decision;
5. only on PASS, request/action/plan contract tests;
6. PostgreSQL workflow and recovery;
7. no-inspection v45 behavior equivalence;
8. affected server/document-vision/E2E/full gates on a clean revision;
9. independent R5 offline admission and payload scan;
10. only then a separate decision may consider drafting a fresh exact J1 request.

No later green gate can overwrite a failed transform, privacy, authority, recovery or compatibility gate.

## Out of Scope

1. Any real Provider call, reservation, token use, cost, API-key read, authorization creation, ledger OPEN transition or fresh
   J1 request in the current scope.
2. Reopening, retrying, editing, cloning or superseding N7-04; unblocking, executing, renaming or reusing N7-05.
3. Modifying historical Prompt 12/7/4、Candidate Prompt 5、product-v45 Profile、pipeline 4.28、view-plan 1.0、
   checkpoint snapshot or run evidence.
4. `DocumentObservationIR/1.1`、partial-order/column/group IR、crop OCR merge、OCR text persistence or changing
   `DocumentObservationIR/1.0`.
5. R2 challenger admission/bake-off、R3 reading-order/repeat contract、R4 strict structured output or R6 workflow framework.
6. Super-resolution、deblurring、learned crop selection、agentic search、OCR ensemble/fusion、automatic model switching or
   result-driven transform retuning.
7. More than one inspection round、more than two inspected views、HIERARCHY/BINDING inspection requests or recursive
   inspection.
8. A general image/tool executor、filesystem、arbitrary HTTP、SQL、publish/delete、shell、browser or model-selected tool
   capability.
9. LangGraph、Temporal、Step Functions、开放式 Agent、ReAct planner、图数据库、向量数据库 or a second durable truth.
10. Promoting the successor Profile to product default、removing RapidOCR、Plus/Max/Flash canary、20/60-case live
    qualification or AC-021 certification.
11. Replacing corpus v1 authority、claiming corpus v2 HOLDOUT is pristine after the trigger probe or exposing gold to product
    code.
12. Persisting source/derived images、OCR、Prompt、model/Candidate payload、full bbox lists、RootDocument or visual overlays
    in ordinary logs/evidence/tracker.
13. Template、Template Agent、Template designer、RootDocument connect/extract、Connector、数据适配、Workspace、
    Schema/Template/StaticSchema 发布或任何占位页面、表、接口、导航。
14. Changing deterministic materialization、RenderWeave validator、manual review、create-only Apply or publication policy.

## Further Notes

1. R5 trigger evidence is a causal signal, not a promotion result. Its higher-resolution vector rerender remains useful as an
   upper-bound oracle, while the first successor ticket must answer the narrower falsifiable question: does the exact product
   raster transform retain a measurable gain?
2. The confirmed design intentionally chooses a bounded action contract over IR/1.1. If implementation discovers that crop OCR
   must be merged with provenance into provider-neutral observations, work stops and returns to `$to-spec`; it cannot widen the
   request contract inside a ticket.
3. This delta specializes ADR-0036 without replacing it: the outer PostgreSQL FSM remains authoritative, semantic stages remain
   serial, and the model may only propose one declarative observation action that code validates and bounds.
4. Because R5 adds a new typed action and recovery meaning, an ADR successor should record the durable inspection substate,
   code-owned action authority and one-round limit before product-path implementation. Under the current sequence the expected
   number is ADR-0039; the ADR must not create J1 authority.
5. The first implementation ticket must end with the transform A2 decision. All request/Prompt/Profile/workflow tickets are
   blocked by that PASS and must close as not-run if the transform is not qualified.
6. New tickets should be fresh-context tracer bullets: authority/assignment, minimal product transform, independent transform
   A2, action contract, plan v2, durable replay, compatibility, independent admission and only then live-request eligibility.
7. `R5_LIVE_J1_REQUEST_ELIGIBLE` permits drafting a request for human review; it never means OPEN, approved or executed. The
   user must still grant a fresh exact J1 after seeing the final identities and bounds.
8. No issue or implementation may describe automated A1/A2 as visual/business acceptance. Controlled visual inspection and any
   future live model quality remain distinct J0/J1 decisions.
