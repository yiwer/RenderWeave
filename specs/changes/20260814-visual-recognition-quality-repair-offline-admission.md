# Change Spec：图片识别质量修复的离线准入与条件式 R2 Shadow

- 状态：approved
- 日期：2026-08-14
- 批准人：用户已确认单一离线 decision seam、条件式 R2、R3/R5 证据触发、R4 当前否决及全新 live ticket/J1 边界
- 触发任务：P6/T6-5/N7 successor；N7-04 Plus 5-case canary 已权威判定 `FAIL`
- 基线 revision：`604849e9b400abf98bca9c12951a50b1488f043b`
- 触发证据：N7-04 `evidence-authority.json` SHA-256
  `e2cb4a0455f712b35618f8239e369e3a92bbd50a5a274d24a6eb39ee6734b78f`；权威 `audit.json` SHA-256
  `e1f550b28e7c57fd4944c3b83297e8c85a167ba147683e4aff655b00f0a59655`
- 继承关系：本 delta 是 `specs/renderweave-v1.md`、`20260810-visual-recognition-vnext.md`、
  `20260813-document-observation-ir-layered-evaluation.md`、N7、N8/R0、N9/R1、product-v45、ADR-0036 与
  ADR-0038 的 additive successor
- 权威关系：`specs/renderweave-v1.md` 继续是 v1 权威源；本 delta 不 supersede、不改写任何历史 Profile、Prompt、
  pipeline、run snapshot、corpus、authorization、ledger、ticket 或 evidence
- 影响 AC/规则：新增 AC-VRQ-001..018；细化 AC-VR-001、004、005、007、010、AC-021 与
  AC-DOIR-001..012；RULE-ANCHOR-001、RULE-AUT-001、RULE-VAL-001、RULE-EVD-001、RULE-STATE-001
- 再锚定关系：本 delta 一经批准，即成为 `RULE-ANCHOR-001` 的对照基准之一；后续 ticket 必须绑定本 delta、
  exact clean revision、不可变 EvaluationIdentity 与其实际执行的离线 capability

## Problem Statement

RenderWeave 已通过 R0 与 R1 建立 provider-neutral `DocumentObservationIR/1.0`、冻结的 corpus v2、分层指标和
独立 verifier，也已证明 RapidOCR 兼容路径在完整 IMAGE_ONLY scripted replay 中保持 product-v45 行为等价。
这些结果证明了感知 seam 和评测基础设施，但没有证明真实模型能够可靠地产生可审核 Schema。

N7-04 使用冻结的 Plus Profile、五个预声明 DEV case、exact EvaluationIdentity 与一次性 exact J1 完成了真实
canary。权威结果为五例中两例到达 `REVIEW_REQUIRED`、三例 `FAILED`，Bundle contract 与 DAG validity 均为
4000 bps、evidence coverage 为 4925 bps、存在 33 个 critical hallucinations/blockers，且没有 Candidate 通过。
其 authorization 已 `CLOSED`，N7-04 的决定是不可变 `FAIL`。该结果不能通过重开 ledger、复用 J1、修改 ticket
前置条件或在同一 canary 内调整 Prompt/Profile/validator/pipeline 来重试。

N7-05 明确依赖 N7-04 `PASS`，所以它不能被现有证据解除阻塞。继续原 N7 live DAG 会把已失败的 tracer bullet
伪装成可继续的 qualification，并再次消费外部调用，却仍不能回答失败主要来自 OCR/layout、reading order、
repeat projection、shape/codec、静态 view，还是语义拓扑与 grounding。

当前需要的不是再调用一次模型，而是一条可独立重放、payload-safe、零 Provider 的质量修复准入流程。该流程必须
先冻结 N7-04 失败权威，逐项判定 R2–R5 的触发谓词，只让有证据支持的最小修复路径进入 shadow qualification；
只有新的离线 tickets 全部通过后，才可以提出一份绑定全新 ticket、revision、Profile、EvaluationIdentity、数据
分类、次数、费用与时限的 fresh exact J1 申请。

## Solution

新增一个离线质量决策能力。它不进入产品请求路径，也不成为第二套 durable workflow。它把已冻结的权威证据、
corpus v2 分层基线、route-specific 离线 probes、shadow qualification 和完整 scripted workflow replay 组合成
唯一的新高层 decision seam：

> `FrozenQualityEvidencePack → R2R5TriggerDecision/1.0`

感知的主技术 seam 保持不变：

> `normalized ArtifactSet + exact AcquisitionPolicy → DocumentObservationIR/1.0`

外层继续使用现有 PostgreSQL durable typed state graph/FSM。Provider 语义阶段继续因果串行；validator 驱动的
有界局部 control loop、确定性 materializer、RenderWeave validator、人工 `REVIEW_REQUIRED` 和 create-only
Apply 边界均不改变。

### 离线准入流程

1. `OFG-0 AUTHORITY_LOCK` 冻结基线 revision、N7-04 evidence digests、`FAIL`、CLOSED authorization 和
   N7-05 blocked 状态。任何 hash、identity、terminal decision 或授权状态漂移都 fail-closed。
2. `OFG-1 CAUSAL_ATTRIBUTION` 使用 exact RapidOCR identity 对 corpus v2 做确定性重放，并把缺陷归因到
   observation omission、layout、order/repeat projection、shape/codec、semantic topology/grounding、static
   view readability、materializer 或 scorer。相关实际运行必须重复两次且 byte/canonical 等价。
3. `OFG-2 ROUTE_PROBES` 对 R2–R5 逐条产生 predicate-level 证据。一个最终失败不能同时被多个路线重复计作
   成功触发证据；无法排除混杂因素时必须记录 `MISSING`，不得主观归因。
4. `OFG-3 SHADOW_QUALIFICATION` 只允许全部触发谓词通过的单一路线进入资格评测。R2 可以在本 delta 下执行
   条件式 shadow bake-off；R3 与 R5 只收集触发证据；R4 当前不得进入实现或 Provider probe。
5. `OFG-4 PRODUCT_COMPATIBILITY` 让唯一最佳、冻结的离线配置通过完整 IMAGE_ONLY scripted replay，证明它仍
   使用现有 PostgreSQL 状态机、三阶段语义流程、validator、materializer 和 `REVIEW_REQUIRED` 边界。
6. `OFG-5 INDEPENDENT_ADMISSION` 由独立 verifier 重算 identity、指标、route decision、payload scan、资源
   边界和 Provider 零调用事实。只有所有门通过，最终决定才可为 `LIVE_J1_REQUEST_ELIGIBLE`。

### 决策结果

每个 R2–R5 route 同时包含布尔值 `triggerSatisfied` 和以下 disposition 之一：

| Disposition | 含义 |
| --- | --- |
| `TRIGGERED` | 该路线的所有既定触发谓词都有可重放证据，允许进入本 delta 明确授权的下一阶段 |
| `EVIDENCE_REQUIRED` | 当前没有足够证据证明或否定该路线；只允许执行已批准的离线证据 ticket |
| `REJECTED_BY_CURRENT_EVIDENCE` | 当前权威证据与该路线的必要前提冲突；不得为该路线实施或申请 live |

`R2R5TriggerDecision/1.0` 的 overall disposition 只能为：

- `OFFLINE_EVIDENCE_REQUIRED`：至少一条可能路线仍缺证据，尚不能实施或申请 live；
- `R2_SHADOW_ALLOWED`：R2 全部触发门通过，只允许条件式 shadow bake-off；
- `OFFLINE_REPAIR_QUALIFIED`：唯一 R2 配置通过 DEV、HOLDOUT、资源与完整 scripted replay；
- `LIVE_J1_REQUEST_ELIGIBLE`：所有离线和独立门通过，可以另行提出 fresh exact J1 请求；
- `STOP_TO_SPEC_R3` 或 `STOP_TO_SPEC_R5`：相应路线被触发，但合同变化必须进入新的窄 spec；
- `STOP_TO_SPEC_MULTIPLE`：两个以上路线必须组合才能成立，禁止临时组合修复；
- `NO_REPAIR_ROUTE`：所有路线均被证据否决，继续 live 没有合理依据。

输出 `LIVE_J1_REQUEST_ELIGIBLE` 不创建、打开或暗示批准任何 authorization。真实调用仍需要用户对新的 exact
J1 明确授权。

### 当前入口判断

| 路线 | `triggerSatisfied` | 入口 disposition | 当前事实 |
| --- | ---: | --- | --- |
| R2 shadow 感知 bake-off | `false` | `EVIDENCE_REQUIRED` | R0/R1 已完成，RapidOCR exact baseline 已显示稳定 OCR/layout slice 缺口；但 challenger 的完整许可、hash、资源、部署和供应链审查及 shadow 净收益尚未形成 |
| R3 reading order/repeat 深化 | `false` | `EVIDENCE_REQUIRED` | 已有 order/repeat 症状与分层计数，但尚未在不少于 3 DEV + 1 HOLDOUT 中完成因果排除，不能证明线性 projection 是下游拓扑错误的原因 |
| R4 strict structured output | `false` | `REJECTED_BY_CURRENT_EVIDENCE` | N7-04 主要失败是 semantic topology、relationship、grounding 与 evidence；当前没有证据证明 shape/codec rejection 是主瓶颈 |
| R5 有界自适应感知 | `false` | `EVIDENCE_REQUIRED` | dense/small-text 缺口存在，但没有 oracle crop/higher-resolution 改善、无 hallucination 退化和静态 view 因果证明 |

当前没有任何路线已经 `TRIGGERED`，因此当前 overall disposition 是 `OFFLINE_EVIDENCE_REQUIRED`。

### Acceptance Criteria

| ID | 行为验收 | 最低证据 |
| --- | --- | --- |
| AC-VRQ-001 | 决策输入精确绑定 revision `604849e`、N7-04 两个权威 digest、CLOSED authorization、FAIL decision 与 N7-05 blocked；任一漂移 fail-closed | contract/tamper A1；独立重算 A2 |
| AC-VRQ-002 | N7-04/N7-05 不被修改、重开、重试、克隆为同一授权或解释为满足后续 live 前置条件 | repository/evidence diff A1；policy verifier A2 |
| AC-VRQ-003 | 唯一新高层 seam 对同一 `FrozenQualityEvidencePack` 产生 canonical、byte-stable 的 `R2R5TriggerDecision/1.0` | golden/property A1 |
| AC-VRQ-004 | 每个 route 的每个触发谓词都有 `PASS`、`FAIL` 或 `MISSING`、固定 reason code 与 content-addressed evidence reference；`MISSING` 不得折叠为 `PASS` | contract + negative tests A1 |
| AC-VRQ-005 | 两次 exact RapidOCR/corpus-v2 实际运行在 observation 与规定指标上确定性一致；历史 R1 绿色证据只有 identity 未变时才可复用 | document-vision/eval A1；A2 replay |
| AC-VRQ-006 | 因果归因分别报告 observation、layout、order/repeat、shape/codec、semantic topology/grounding、static view、materializer 与 scorer，不用单一最终失败重复证明多个原因 | attribution goldens A1；independent audit A2 |
| AC-VRQ-007 | R2 在 dependency/model capability manifest 完整前保持 `EVIDENCE_REQUIRED`；许可、包/权重 hash、CPU/GPU/内存、Windows/部署、网络与供应链任一未决即不运行 challenger | manifest verifier A1；人工许可结论 J1 |
| AC-VRQ-008 | R2 shadow 输出只进入 `DocumentObservationIR/1.0` 与评测；不能写产品 Candidate、修改 Prompt/Profile、访问 Provider 或成为默认 AcquisitionPolicy | architecture/negative tests A1 |
| AC-VRQ-009 | R2 首个 challenger 为 PP-StructureV3，Tesseract 为独立 CPU baseline；第三路只能在 DEV 前从 docTR/PaddleOCR-VL 中预声明一个；Surya 未完成独立权重许可审查时不可运行 | frozen manifest + identity A1 |
| AC-VRQ-010 | 只有一个通过 DEV 预声明门的最佳 R2 配置可访问冻结 HOLDOUT；未入选配置、调参代码与人工观察者不得读取 HOLDOUT gold | assignment/access negative tests A1；A2 audit |
| AC-VRQ-011 | R2 晋级必须同时产生结构感知改善、gold-scripted downstream 改善、零 critical hallucination 增量，以及失败率、启动、资源和许可净收益；只改善 CER 不得晋级 | layered + workflow report A1/A2 |
| AC-VRQ-012 | R3 只有在不少于 3 DEV + 1 HOLDOUT、两次重跑、独立 gold/scorer 通过且排除 OCR omission、Prompt shape failure、materializer 后才可 `TRIGGERED`；触发结果只能 `STOP_TO_SPEC_R3` | causal probe A1/A2 |
| AC-VRQ-013 | R4 当前固定为 `REJECTED_BY_CURRENT_EVIDENCE`；离线协议资料或 parse-rate 改善不能推翻 semantic bottleneck，真实 Provider contract probe 不在本 delta 授权内 | fixed decision/tamper tests A1 |
| AC-VRQ-014 | R5 只有在不少于 3 DEV + 1 HOLDOUT 中证明静态 view 不可读，且 oracle crop/higher-resolution 在不增加 critical hallucination 时改善目标 slice，才可 `TRIGGERED`；触发结果只能 `STOP_TO_SPEC_R5` | oracle differential A1/A2 |
| AC-VRQ-015 | 最佳 R2 配置通过完整 IMAGE_ONLY scripted workflow 到 `REVIEW_REQUIRED`；RapidOCR baseline 继续保持 v45 行为等价，R2 差异只允许来自冻结的 observation/acquisition identity | Testcontainers workflow A1 |
| AC-VRQ-016 | 所有离线 ticket 的外部 Provider attempts、reservations 与 cost 均为 0；不得读取 API Key；OCR text 始终 ephemeral | zero-provider/payload scan A1/A2 |
| AC-VRQ-017 | 只有 clean revision、fresh EvaluationIdentity、全部离线门、独立 A2 与 payload scan 通过时才能输出 `LIVE_J1_REQUEST_ELIGIBLE`；该结果本身不创建 J1 | admission positive/negative A1/A2 |
| AC-VRQ-018 | 后续 live 必须使用全新 successor ticket、authorization ID、contract identity、EvaluationIdentity、Profile snapshot、数据分类、case assignment、attempt/token/费用上限和有效期；任何 N7-04/N7-05 identity 复用都 fail-closed | request-envelope verifier A1；用户 exact J1 |

## User Stories

1. As a RenderWeave product owner, I want N7-04 preserved as an immutable failed experiment, so that a later repair cannot rewrite historical product evidence.
2. As a RenderWeave product owner, I want N7-05 to remain blocked by its original failed predecessor, so that a qualification run cannot bypass the tracer-bullet gate.
3. As a product owner, I want a future live attempt to use a new successor ticket, so that its scope and result are distinguishable from N7-04 and N7-05.
4. As a product owner, I want offline evidence to precede another paid call, so that budget is spent only on a repair with a falsifiable rationale.
5. As an evaluation owner, I want the quality decision anchored to exact evidence hashes, so that a changed audit cannot silently alter the route decision.
6. As an evaluation owner, I want every trigger predicate reported separately, so that one attractive aggregate metric cannot hide a missing prerequisite.
7. As an evaluation owner, I want missing evidence represented explicitly, so that uncertainty is not reported as either success or definitive rejection.
8. As an evaluation owner, I want failure attribution separated by perception, ordering, contract, semantics and materialization, so that the chosen repair addresses the observed bottleneck.
9. As an evaluation owner, I want two deterministic RapidOCR reruns, so that a transient local OCR outcome does not trigger a new dependency.
10. As an evaluation owner, I want global, DEV, HOLDOUT, domain, difficulty and failure-slice metrics, so that a local gain cannot be hidden inside a global average.
11. As an independent verifier, I want to recompute trigger decisions without calling product Java scoring code, so that the implementation cannot certify itself.
12. As an independent verifier, I want tampered revisions, identities, manifests and reports rejected, so that evidence cannot be borrowed across experiments.
13. As a security reviewer, I want images, OCR text, prompts and model outputs absent from ordinary evidence, so that quality investigation does not create a new sensitive-data store.
14. As a security reviewer, I want offline tickets to clear Provider credentials and prove zero attempts, reservations and cost, so that a diagnostic command cannot accidentally call a paid model.
15. As a dependency reviewer, I want code and model-weight licenses reviewed separately, so that an open-source repository license is not mistaken for permission to use its weights.
16. As a dependency reviewer, I want exact package and weight hashes in the capability identity, so that a model upgrade cannot inherit an earlier result.
17. As an operator, I want CPU, GPU, memory, startup, Windows deployment and network assumptions measured before qualification, so that a quality gain does not introduce an unusable runtime.
18. As an operator, I want local model startup to fail closed when a manifest or weight is missing, so that the product never falls back to an unpinned download.
19. As a perception engineer, I want every challenger projected through the same `DocumentObservationIR/1.0` contract, so that library-specific DTOs do not become domain truth.
20. As a perception engineer, I want PP-StructureV3 evaluated first and Tesseract retained as an independent CPU baseline, so that comparisons include both a strong layout challenger and a different error source.
21. As a perception engineer, I want at most one predeclared third challenger, so that the experiment remains bounded and does not become an open-ended model search.
22. As a perception engineer, I want a challenger kept shadow-only until qualification, so that experimental observations cannot change product Candidates.
23. As a perception engineer, I want RapidOCR to remain available as the immutable baseline, so that a challenger can be disabled without rewriting product history.
24. As a Schema evaluator, I want structural perception and downstream scripted metrics to improve together, so that lower CER alone is not mistaken for better Schema extraction.
25. As a Schema evaluator, I want critical hallucinations to remain non-increasing, so that recall is not purchased by inventing unsupported structure.
26. As a Schema evaluator, I want only the best frozen DEV configuration to enter HOLDOUT, so that holdout results remain meaningful.
27. As a holdout custodian, I want configuration selection completed before HOLDOUT access, so that test labels cannot guide tuning.
28. As a workflow maintainer, I want the best repair replayed through the existing PostgreSQL state machine, so that a perception gain does not bypass durable recovery and checkpoint semantics.
29. As a workflow maintainer, I want the three semantic stages to remain serial, so that the repair does not introduce speculative competing writes.
30. As a validator maintainer, I want semantic validation to remain authoritative, so that a new OCR/layout provider cannot create fields, arrays or relationships directly.
31. As a recovery engineer, I want crash, timeout and duplicate execution tests around offline acquisition, so that a challenger cannot create non-deterministic evidence or repeated work.
32. As a reviewer, I want successful scripted runs to end at `REVIEW_REQUIRED`, so that no quality experiment gains automatic Draft or publish authority.
33. As a reviewer, I want the RapidOCR compatibility path to remain behavior-equivalent to v45, so that adding a shadow path does not regress the existing product contract.
34. As a prompt maintainer, I want Prompt 12/7/4 unchanged during offline repair, so that a perception experiment does not confound prompt changes with adapter changes.
35. As a Profile maintainer, I want Product Profile and Provider routing unchanged, so that local capability identity remains separate from model authorization.
36. As a route owner, I want R3 to stop at a new narrow spec when triggered, so that an additive ordering IR is designed explicitly rather than smuggled into R2.
37. As a route owner, I want R5 to stop at a new narrow spec when triggered, so that adaptive crop actions receive their own limits, recovery and authorization design.
38. As a route owner, I want R4 rejected while shape/codec is not the measured bottleneck, so that structured-output enthusiasm does not distract from semantic failures.
39. As a governance owner, I want multiple simultaneously required routes to stop for a composition spec, so that independent experiments are not silently fused into an unreviewed pipeline.
40. As a governance owner, I want `LIVE_J1_REQUEST_ELIGIBLE` separated from J1 approval, so that automated evidence never grants external-call authority.
41. As a ticket author, I want each future offline ticket to have a fresh-context vertical result, so that evidence can be independently verified and recovered.
42. As a ticket author, I want all live work blocked on the completed offline DAG, so that no ticket can skip causal attribution, shadow qualification or A2 admission.
43. As a future Agent, I want fixed reason codes and immutable identities instead of prose-only decisions, so that I can continue safely from a fresh context window.
44. As a RenderWeave user, I want image recognition quality improved without exposing my image or inferred content, so that better automation does not weaken privacy.

## Implementation Decisions

### 1. Authority and lifecycle

1. N7-04 is a historical terminal experiment with decision `FAIL`. Its authorization remains CLOSED, and its journal, audit,
   report and evidence-authority records are immutable inputs, not mutable workflow state.
2. N7-05 remains blocked because its declared dependency can never become true. A successor live evaluation must not copy the
   N7-05 identifier or reinterpret its dependency.
3. The offline successor creates no Provider authorization, reservation or cost. It may prepare a payload-safe request envelope
   only after `LIVE_J1_REQUEST_ELIGIBLE`, but opening that envelope remains an explicit external user decision.

### 2. One new decision seam

1. `FrozenQualityEvidencePack → R2R5TriggerDecision/1.0` is the only new high-level seam.
2. The evidence pack is content-addressed and contains only identities, counts, metrics, fixed codes, resource summaries,
   predicate results and evidence digests. Wall-clock timestamps and local absolute paths do not participate in canonical identity.
3. The decision engine is deterministic. It cannot inspect images, OCR text, Prompt/model/Candidate payload, API credentials or
   arbitrary files, and it cannot call a Provider or dependency downloader.
4. Every route predicate has a stable identifier, expected evidence class, result and reason code. Overall disposition is derived
   from those predicates; callers cannot directly set it.

### 3. Existing perception and workflow boundaries

1. R2 implementations remain behind `VisualEvidenceAcquisition` and produce strict `DocumentObservationIR/1.0`.
2. If a challenger needs partial-order edges, column/group hypotheses, adaptive inspection requests or another semantic concept
   that cannot be represented without changing IR/1.0, it must stop; it cannot extend the contract inside the R2 ticket.
3. `DocumentObservationIR` remains run-local and non-persistent. OCR text remains untrusted ephemeral stage context.
4. PostgreSQL job, lease, checkpoint, cancellation, recovery and terminal-state semantics remain the only durable control truth.
5. Provider stages remain `OBSERVE → HIERARCHY → ELEMENT_BINDING → LOCAL_MATERIALIZE`; no Agent planner or general tool
   executor is introduced.

### 4. R2 conditional shadow path

1. R2 starts with a written capability preflight. It locks code license, weight license, package versions, model/manifest hashes,
   inference backend, preprocessing/postprocessing identity, expected CPU/GPU/RAM/disk, Windows support, deployment form,
   runtime network policy and supply-chain provenance.
2. PP-StructureV3 is the first layout/OCR challenger. Tesseract TSV/hOCR is the independent CPU baseline. At most one of docTR
   and PaddleOCR-VL may be predeclared as a third route before DEV results are visible.
3. Surya is excluded until code and weight licensing are independently accepted. LayoutParser/Detectron2 is not a default
   Windows runtime candidate. Research recommendations do not constitute dependency admission.
4. Challenger execution is repository synthetic/CC0 only, local and shadow. Runtime model download is disabled; exact weights
   must already match the approved capability manifest.
5. Each challenger runs the same frozen normalized artifacts, AcquisitionPolicy semantics, annotations, evaluator and two-run
   determinism checks as RapidOCR. Provider, Prompt, Profile and Product Candidate paths remain unreachable.

### 5. R2 qualification and promotion limits

1. DEV selection thresholds and resource ceilings must be serialized into EvaluationIdentity before the first challenger DEV
   result is read. They cannot be relaxed after observing results.
2. A candidate must improve at least one predeclared stable-gap slice by at least 500 bps absolute in a structural perception
   metric such as layout recall, evidence recall, order comparable coverage or repeat item recall.
3. The same candidate must improve at least one downstream gold-scripted metric, such as supported element/group coverage,
   stage survival, evidence-owner containment or Candidate topology distance. CER improvement alone is insufficient.
4. Global and DEV layout precision, order accuracy, repeat membership and OCR hallucination rate may not regress by more than
   100 bps. Critical hallucination count in gold-scripted Candidate replay must remain zero.
5. Adapter execution failure, malformed IR, identity drift and nondeterministic output rates must not exceed RapidOCR. A config
   with a HOLDOUT adapter failure cannot qualify.
6. Only the single best frozen DEV configuration may be evaluated on HOLDOUT. HOLDOUT must confirm the same improvement
   direction without exceeding the non-regression limits; no HOLDOUT result may cause retuning.
7. Startup, latency and resource use must stay inside the predeclared deployment envelope, and the license decision must permit
   the intended RenderWeave use. A quality winner that fails either condition is not eligible.
8. Qualification creates a new immutable AcquisitionPolicy/capability identity but does not switch the product default. Product
   promotion or removal of RapidOCR requires later live evidence and a separate policy decision.

### 6. R3 and R5 evidence-only routes

1. R3 probes may compare recalled observations, gold precedence edges, repeat membership and compatibility projection, but may
   not add partial-order, column/group or `PerceptionInvariant` fields to IR/1.0.
2. R3 becomes `TRIGGERED` only when its complete 3 DEV + 1 HOLDOUT causal predicate is independently reproduced. The only
   allowed overall result is `STOP_TO_SPEC_R3`.
3. R5 probes may generate local oracle crops or higher-resolution variants only from repository synthetic/CC0 artifacts and
   fixed, predeclared transforms. They do not create an `inspectionRequest` product contract.
4. R5 becomes `TRIGGERED` only when its complete 3 DEV + 1 HOLDOUT causal predicate and no-hallucination improvement are
   independently reproduced. The only allowed overall result is `STOP_TO_SPEC_R5`.

### 7. R4 rejection

1. The N7-04 failure is not presently attributable to shape/codec rejection. R4 therefore remains
   `REJECTED_BY_CURRENT_EVIDENCE` throughout this delta.
2. Official protocol documentation may be recorded as background, but no immutable Profile, strict schema request, grammar
   runtime or real Provider contract probe is implemented.
3. Reopening R4 requires new DEV evidence that shape/codec is the measured bottleneck and a new spec/J1 boundary; a future
   provider feature announcement alone is insufficient.

### 8. Gold isolation and scripted downstream evaluation

1. Challenger acquisition and projection code cannot read annotations, semantic gold or HOLDOUT selection metadata.
2. A separate evaluator may feed frozen gold-scripted semantic responses through the existing codec, validator, materializer and
   PostgreSQL workflow to measure whether challenger observations support downstream contracts. This proves compatibility and
   information sufficiency, not live LLM quality.
3. Corpus v2 remains shadow diagnostic. It does not silently replace the AC-021 final authoritative corpus.
4. The RapidOCR branch must retain the R0 v45 behavior-equivalence proof. A challenger branch may differ only in
   DocumentObservationIR observations and their deterministic compatibility projection; workflow and semantic policy are fixed.

### 9. Privacy, evidence and observability

1. Ordinary logs, traces, metrics, reports and evidence may contain stable case IDs, identities, counts, bps metrics, durations,
   resource summaries, fixed problem codes and hashes.
2. They must not contain source or normalized images, Base64, OCR text, Prompt text, model request/response, Candidate content,
   full bbox lists, Provider request IDs, API keys, RootDocument or chain-of-thought.
3. Controlled visual diff remains local-only for allowlisted repository synthetic/CC0 artifacts and is not copied into ordinary
   evidence or tracker issues.
4. Exceptions, process stderr, object stringification, tamper output and failure summaries are covered by the same payload scan.

### 10. Fresh live admission

1. `LIVE_J1_REQUEST_ELIGIBLE` requires a unique frozen R2 configuration, all six offline gates, clean revision, fresh evaluation
   identity, successful full scripted replay, independent A2 and payload scan.
2. A future live request must bind a new successor ticket, authorization ID, contract identity, evaluation identity, exact
   Provider/model/Profile snapshot, Prompt 12/7/4, Candidate Prompt 5 if still applicable, pipeline 4.28, repository-only data
   classification, exact case IDs, serial/batch limits, attempts, tokens, cost and expiry.
3. No field may inherit from N7-04 or N7-05 by omission. An absent or equal historical identity is a hard rejection.
4. The new J1 request is reviewed only after offline completion; this spec does not promise that it will be approved or executed.

## Testing Decisions

### 1. Test seams

The primary technical seam remains:

> `normalized ArtifactSet + exact AcquisitionPolicy → DocumentObservationIR/1.0`

The only new high-level decision seam is:

> `FrozenQualityEvidencePack → R2R5TriggerDecision/1.0`

The highest offline product seam is a complete IMAGE_ONLY scripted replay through the existing PostgreSQL durable state machine
to `REVIEW_REQUIRED`. A good test asserts observable contracts, terminal state, identity, metrics, failure codes, redaction and
recovery. It does not assert Paddle/RapidOCR internal objects, Python function calls, Jackson traversal or class collaboration.

### 2. Authority and decision contract tests

1. Positive golden for the exact N7-04 authority/audit pair and baseline revision.
2. Negative vectors for changed digest, OPEN authorization, changed terminal decision, missing ticket dependency, historical
   authorization reuse and fabricated N7-04 PASS.
3. Hand-written predicate truth tables covering `PASS`/`FAIL`/`MISSING`, every route disposition and every overall disposition.
4. Property tests proving canonical decision output is insensitive to input ordering and wall-clock/local-path metadata.
5. Tamper tests for route result, evidence reference, threshold, manifest and aggregate metric.

### 3. Causal attribution tests

1. Synthetic goldens with one isolated fault for OCR omission, layout miss, order error, repeat error, shape error, semantic error,
   static-view unreadability, materializer error and scorer error.
2. Ambiguous fixtures with multiple possible causes must return `MISSING` for causal predicates, not choose a preferred route.
3. Two actual RapidOCR 60-case runs must match at the canonical observation and metric layers.
4. Existing R1 evidence may be reused only when corpus, annotation, acquisition, adapter, weight, transform, evaluator and code
   identities match exactly; otherwise rerun.

### 4. R2 capability and adapter tests

1. Manifest positive/negative tests for licenses, package/weight hashes, backend, pre/post-processing, resource envelope,
   Windows deployment and runtime network denial.
2. Black-box adapter tests for missing executable/weight, hash mismatch, timeout, non-zero exit, malformed output, invalid box,
   unsupported confidence, oversized output and deterministic ordering.
3. Contract tests prove all challenger outputs are strict IR/1.0 observations without SLOT/GROUP/Schema/Candidate semantics.
4. Failure tests prove challenger observations cannot enter a product Candidate or Provider request during shadow execution.

### 5. DEV, HOLDOUT and promotion tests

1. Freeze assignments, thresholds and resource limits before first DEV result.
2. Report all required metrics at global, DEV, HOLDOUT, domain, difficulty and failure-slice levels.
3. Test 500-bps improvement and 100-bps non-regression boundaries exactly, including equality and one-bps failures.
4. Prove only the selected DEV winner can access HOLDOUT; inject unauthorized access and changed winner identities.
5. Verify no post-HOLDOUT configuration change can reuse the report or EvaluationIdentity.
6. Compare perception metrics with downstream gold-scripted stage/Candidate metrics; include a CER-only improvement that must fail.

### 6. Workflow and recovery tests

1. Keep the existing RapidOCR scripted replay byte/semantically equivalent to v45.
2. Run the selected R2 policy through normalization, acquisition, serial semantic stages, local materialization, validation and
   PostgreSQL terminal transition to `REVIEW_REQUIRED`.
3. Assert accepted stage zero replay, cancellation precedence, lease expiry, crash recovery, deterministic IR recomputation and
   no persisted OCR text.
4. Assert Profile, Prompt, pipeline, validator, materializer, Candidate compiler and Apply boundaries remain unchanged.
5. Negative test any attempt to introduce LangGraph, Temporal, an open Agent loop or a general tool executor into this path.

### 7. Independent evidence and live-admission tests

1. The independent verifier recomputes identities, metrics, thresholds, trigger decisions, winner selection and Provider-zero
   accounting from versioned records without calling the production scorer.
2. Payload scan covers reports, logs, traces, stderr, exceptions, process output and tracker-safe summaries.
3. The live-admission verifier rejects N7-04/N7-05 ticket IDs, authorization IDs, contract identities and evaluation identities.
4. It rejects dirty revisions, missing A2, missing HOLDOUT isolation, multiple-route composition, non-zero Provider accounting,
   expired bounds and any omitted exact J1 field.
5. Automated evidence is at most A1/A2. Controlled visual diff and future business/visual acceptance remain J0 until a human
   explicitly performs them.

### 8. Gate order

Validation expands according to `RULE-VAL-001`: focused contract/property tests, adapter black-box tests, layered DEV evaluation,
single-winner HOLDOUT, Testcontainers PostgreSQL workflow, affected server/document-vision gates, full gate, independent A2 and
finally live-request eligibility. No later green gate can overwrite an earlier failed authority, privacy or route gate.

## Out of Scope

1. Reopening, retrying, editing, deleting or superseding N7-04.
2. Unblocking, executing, renaming or reusing N7-05.
3. Any real Provider call, reservation, token use, cost, API-key read, authorization creation or ledger OPEN transition.
4. Plus/Max/Flash canary, 20-case qualification, 60-case final evaluation or AC-021 certification.
5. Modifying Prompt 12/7/4, Candidate Prompt 5, Product Profile, Provider routing, pipeline 4.28, semantic validator,
   materializer, Candidate compiler or publication policy.
6. Implementing R3 partial-order/column/group IR, `PerceptionInvariant` or changing `DocumentObservationIR/1.0`.
7. Implementing R5 `inspectionRequest`, crop tool loop, higher-resolution runtime action or new action authorization.
8. Implementing R4 strict JSON Schema/grammar output or running a Provider contract probe.
9. Combining multiple repair routes, ensemble/fusion into product Candidate or automatic model selection/fallback.
10. Promoting a shadow challenger to the default product AcquisitionPolicy or removing RapidOCR.
11. Replacing corpus v1 as AC-021 authority or exposing HOLDOUT gold to adapter/configuration selection.
12. Storing OCR text, images, Prompt/model/Candidate payload, RootDocument or visual overlays in ordinary evidence.
13. Template、Template Agent、Template designer、RootDocument connect/extract、Connector、数据适配、Workspace、
    Schema/Template/StaticSchema 发布或任意 SQL/HTTP/filesystem capability。
14. 引入开放式 Agent、通用工具执行器、LangGraph、Temporal、Step Functions、图数据库或向量数据库。
15. 创建占位页面、API、数据库表或导航来代表尚未通过触发门的能力。

## Further Notes

1. 当前 route priority 是 R2 capability/license preflight，然后才是 bounded shadow bake-off。该优先级来自现有
   RapidOCR 稳定 slice 缺口和 provider-neutral IR seam，不表示 PP-StructureV3 已获依赖或产品批准。
2. R3 与 R5 的离线 probes 是诊断工具。若其触发条件成立，新的窄 spec 必须定义 additive IR 或 bounded
   acquisition action、恢复、预算、回退和授权；本 delta 不能被扩张解释。
3. R4 的当前否决是 evidence-relative，不是永久禁止。未来只有新的 shape/codec bottleneck 实证才能重新进入
   `$to-spec`；Provider 宣传或 JSON parse-rate 单项提升不足以触发。
4. 如果 R2 无 candidate 同时满足结构感知、downstream、non-regression、资源和许可门，overall disposition 为
   `NO_REPAIR_ROUTE` 或基于已满足的 R3/R5 predicate `STOP_TO_SPEC_*`，不得为了继续 Goal 而降低阈值。
5. 新 tickets 必须是 fresh-context tracer bullets，先完成 authority/decision contract，再完成 dependency preflight、
   route probes、R2 shadow qualification、单一 HOLDOUT、workflow replay、A2 verifier 和 live admission；任何 live
   ticket 必须被整个离线 DAG 阻塞。
6. 本 delta 不需要新的工作流架构 ADR；ADR-0036 与 ADR-0038 继续权威。只有 R3、R5 或多路线组合实际触发并
   需要新合同/控制语义时，才记录对应的 successor ADR。
7. 本规格通过后，下一步应使用 `$to-tickets` 拆分离线 tracer bullets；不得直接进入 `$implement` 的 live 节点。
