# Change Spec：R5 配对产品视图准入与 N7 replacement DAG

- 状态：approved
- 日期：2026-08-15
- 批准状态：用户已于 2026-08-15 接受本规格并确认进入 `$to-tickets`；当前批准不覆盖 implementation、
  replacement Goal、ticket 落盘、J1 或 live
- 触发任务：已关闭的 N7-04/N7-05 DAG replacement；R5 product-transform 首次实验终态
  `R5_PRODUCT_TRANSFORM_NOT_QUALIFIED`
- 基线 revision：`57be4d9b249c0aa06a1c0b32abc634c152a97234`
- 触发权威：`renderweave-r5-product-transform-authority/1.1`，文件 SHA-256
  `a6ef7ee0820ea906cb371371d66a8eaef3ba77ac569ae24d6e4935e144ef4475`
- 触发事实：旧实验 `a2Disposition=NOT_ESTABLISHED`、
  `acceptedAssurance=A1_PRODUCER_REPORT_CONSISTENCY_ONLY`、
  `freshJ1Disposition=LIVE_J1_REQUEST_NOT_ELIGIBLE`；失败 case 为 `transit-board-v3`
- 触发原因码：`NORMALIZED_RASTER_INPUT_NOT_PROVEN`、`PRODUCT_STATIC_ACQUISITION_NOT_PROVEN`、
  `INDEPENDENT_LAYERED_METRICS_NOT_REPLAYED`、`PROVIDER_ZERO_NOT_INDEPENDENTLY_GROUNDED`、
  `PER_CASE_HALLUCINATION_NON_INCREASE`、`PER_CASE_TARGET_IMPROVEMENT`
- 绿色基线：`.sdlc/evidence/20260814-191019-full/metadata.json` 绑定基线 revision 且状态为 `passed`；
  metadata SHA-256 `fe8494f7cbd5d72129ecb21030f75fca64e178e5e52397b4f1b83a645c069b61`
- 继承关系：本 delta 是 `specs/renderweave-v1.md`、`20260810-visual-recognition-vnext.md`、
  `20260813-document-observation-ir-layered-evaluation.md`、
  `20260814-visual-recognition-quality-repair-offline-admission.md`、
  `20260814-r5-bounded-visual-inspection.md`、N7、N8/R0、N9/R1、product-v45、ADR-0036、ADR-0038 与
  ADR-0039 的 additive successor
- 权威关系：`specs/renderweave-v1.md` 继续是 v1 权威源；本 delta 不修改、重开、重试或重新解释旧 N7/R5
  ticket、authorization、ledger、run、evidence、authority 或 terminal decision
- 影响 AC/规则：新增 AC-R5P-001..020；细化 AC-R5I-002、009..018 与 AC-VRQ-002、016..018；
  RULE-ANCHOR-001、RULE-AUT-001、RULE-VAL-001、RULE-EVD-001、RULE-STATE-001
- 再锚定关系：本 delta 已成为 `RULE-ANCHOR-001` 的对照基准；后续 ticket 必须绑定本 delta、exact clean
  revision、全新 R5P identities 与零 Provider 离线范围

## Problem Statement

N7-04 已用一次性 exact J1 得到不可变 `FAIL`，N7-05 又把 N7-04 `PASS` 作为硬前置条件。因此原 15-ticket
DAG 在规格上永久不可完成；它不能通过改状态、克隆 ticket、重开授权或把后续 repair 解释为历史 PASS 来恢复。

R5 narrow successor 随后选择了由代码拥有、最多一轮两个局部视图的 bounded visual-inspection action，并要求
首个实现门先证明 exact 产品 raster transform 的 3 DEV + 1 HOLDOUT 双跑 A2。该实验确实执行了 16 次本地
acquisition，也得到四例确定性，但权威复核拒绝接受其 A2：

1. source 由评测 rasterizer 直接形成，证据未证明 transform 从产品 `InputNormalizer` 产生并由 BlobStore 交接的
   normalized raster bytes 开始；
2. static plan identity 覆盖完整 plan descriptors，实际 OCR acquisition 却只执行 overview；
3. inspected 分支只执行两个 crop，没有执行产品语义上的“完整 static plan 加 inspected views”；
4. layered metrics 与 Provider-zero 只得到 producer 一致性，没有独立从产品输入和完整 plan 重放；
5. `transit-board-v3` 没有满足 per-case target improvement 与 hallucination non-increase。

所以旧实验同时存在“测量对象不是产品 paired view plan”和“实测质量门未通过”两类失败。只修 verifier、重放旧
evidence 或重新运行同一 runner 都不能解决问题；旧 runner 已正确关闭为
`R5_PRODUCT_TRANSFORM_ROUTE_CLOSED`。

下一步必须是全新的离线实验，而不是旧实验 retry。它先证明测量 harness 对产品 normalization、完整静态 plan、
实际 inspected plan 与 Provider-zero 的忠实性，再在参数完全冻结后比较：

> 完整产品 static view plan
>
> 与
>
> 同一 static plan 经 `BoundedVisualInspection` 加入最多两个 inspected views 后的完整 plan

旧四例已被观察，只能作为 `SEEN_DIAGNOSTIC` 回归 veto；它们不能继续冒充未见 HOLDOUT。真正的 successor
准入还需要一组与旧四例不重叠、在任何新结果产生前冻结的 3 DEV + 1 corpus-v2 HOLDOUT confirmation assignment。
corpus v2 仍只是 shadow diagnostic，confirmation 也不构成 AC-021 certification 或 pristine external holdout。

## Solution

新增一个 evaluation-only 深 Module `PairedProductViewEvaluation`。它复用产品 `InputNormalizer`、BlobStore、
`MultiScaleVisualViewPlanner/1.0`、冻结的 `R5ProductRasterTransform/1.0`、ADR-0039 定义的
`BoundedVisualInspection` Interface 和 exact RapidOCR capability；它不进入产品 create route，也不成为第二套
workflow。

新的离线主 seam 是：

> `normalized ArtifactSet + complete VisualViewPlan/1.0 + FrozenPairedViewAssignment/1.0 + exact PairedViewEvaluationPolicy/1.0 → PairedProductViewOutcome/1.0`

现有产品 action seam 保持：

> `normalized ArtifactSet + renderweave-visual-view-plan/1.0 + decoded InspectionRequest/1.0 + exact AdaptiveInspectionPolicy/1.0 → InspectionOutcome/1.0`

现有感知 seam 也保持：

> `normalized ArtifactSet + exact AcquisitionPolicy → DocumentObservationIR/1.0`

本 delta 不引入 `DocumentObservationIR/1.1`。paired evaluator 可在内存中把各 view 的临时 OCR observation 投影回
原 normalized artifact 并以冻结的 evaluation-only coalescing 计算指标；该投影不是产品 IR、Candidate、Evidence
或 durable state，OCR text 在一次进程内使用后即丢弃。

### Delta Summary

#### ADDED

1. 全新 R5P authority、assignment、evaluation、evidence 与 terminal identity namespace；任何旧 N7-04、N7-05、
   R5 transform runner/assignment/evaluation identity 都不可复用。
2. `PairedProductViewEvaluation` 离线 seam，以及 baseline/successor 两个完整 plan 的 exact acquisition trace。
3. `SEEN_DIAGNOSTIC` 与 `SEALED_CONFIRMATION` 两层 assignment；前者是回归 veto，后者是唯一准入计分集。
4. 独立执行的 product-normalization、plan-acquisition、metrics 与 Provider-zero A2 reconstruction。
5. 新 replacement DAG 与固定终态；其全部节点在规格确认和新 tickets 批准前不可执行。

#### MODIFIED

1. 对 AC-R5I-013 的 successor 解释：旧 R5 route 仍永久停止；新 route 可实现最小、offline-only 的
   `BoundedVisualInspection` core，以生成真实完整 inspected plan，但 Prompt、Profile、Provider、durable integration
   与 live 仍被 paired A2 PASS 阻塞。
2. product-transform qualification 不再比较 overview-only 与 crop-only，而比较两个完整、可逐 view 对账的产品
   plan。
3. 旧四例从 `3 DEV + 1 HOLDOUT qualification` 降级为 `SEEN_DIAGNOSTIC`，不得产生新的 HOLDOUT 或 live 资格
   claim。

#### REMOVED

1. 不允许从 `R5ProductTransformEvaluation/1.0`、旧 assignment 或旧 evidence 衍生 PASS。
2. 不允许 plan identity 描述未实际 acquire 的 view。
3. 不允许 producer summary 自称 A2；只有独立实际重放可形成 A2。

### Replacement Gate Order

1. `R5P-0 AUTHORITY_LOCK`：绑定本 delta 基线、旧 N7 terminal、旧 R5 authority、关闭 runner、full-gate metadata
   和用户批准状态；任何漂移均停止。
2. `R5P-1 HARNESS_CONFORMANCE`：只用无质量含义的自描述 raster fixtures，证明 raw fixture 经产品
   `InputNormalizer`、BlobStore、normalized artifact、完整 static plan、逐 view acquisition trace 和坐标回投保持
   exact identity/count/order/bytes。该门不能读取 corpus gold。
3. `R5P-2 OFFLINE_ACTION_CORE`：仅实现 ADR-0039 action Interface 所需的 strict typed request、固定 policy、
   `R5ProductRasterTransform/1.0` 与完整 plan composition；不实现 JSON model decoder、Prompt、Profile、checkpoint、
   Provider adapter 或产品路由。
4. `R5P-3 ASSIGNMENT_FREEZE`：冻结旧四例 `SEEN_DIAGNOSTIC` 和全新、互斥的 3 DEV + 1 HOLDOUT
   `SEALED_CONFIRMATION`；case、partition、region、preset、threshold、runtime/capability identity 在首次结果前提交。
5. `R5P-4 PAIRED_EXECUTION`：对八例分别执行 baseline/successor 两个完整 plan，两次确定性本地运行；总分支、
   view、bytes、pixels、latency 和 local acquisition 均逐项对账，Provider 计数固定为 0。
6. `R5P-5 INDEPENDENT_A2`：独立进程从 raw repository fixture 重走 normalization、plan、action、local acquisition、
   投影、coalescing 与 layered metric；不能调用 producer decision engine，也不能只读取 producer summary。
7. `R5P-6 OFFLINE_DECISION`：单一 decision seam 根据 conformance、seen veto、confirmation thresholds、A2、privacy
   与 Provider-zero 得到固定终态。失败后所有 product/workflow/live 节点关闭。
8. `R5P-7 PRODUCT_ACTION`：只有 `R5P_ACTION_IMPLEMENTATION_ALLOWED` 才实现 closed JSON decoder、plan v2、protected
   checkpoint substate 与 additive hidden Prompt/Profile/pipeline identity。
9. `R5P-8 DURABLE_REPLAY`：用 Testcontainers PostgreSQL 完成一次 inspection 的完整 IMAGE_ONLY scripted replay
   到 `REVIEW_REQUIRED`，覆盖 crash、lease、cancel、duplicate、exhaustion 与 accepted-stage zero replay。
10. `R5P-9 COMPATIBILITY_AND_ADMISSION`：证明 no-inspection product-v45 行为等价、full gate、payload scan 与独立
    terminal reconstruction；最多输出 `R5_SUCCESSOR_LIVE_REQUEST_ELIGIBLE`，不创建 J1。

### Fixed Outcomes

`PairedProductViewOutcome/1.0` 的 terminal disposition 只能为：

| Disposition | 含义 |
| --- | --- |
| `R5P_MEASUREMENT_INVALID` | normalization、plan/acquisition 对账、独立重放或 Provider-zero 任一未建立；不得解释质量 |
| `R5P_PAIRED_VIEW_NOT_QUALIFIED` | 测量有效，但 seen veto 或 confirmation 质量/资源门失败；route 关闭 |
| `R5P_ACTION_IMPLEMENTATION_ALLOWED` | paired A2 通过；只解锁 offline product-action/workflow tickets，不是 live 资格 |
| `R5P_PRODUCT_PATH_NOT_QUALIFIED` | action、recovery、v45 equivalence、privacy 或 full gate 失败；route 关闭 |
| `R5_SUCCESSOR_LIVE_REQUEST_ELIGIBLE` | 所有离线门和独立 terminal A2 通过；只允许另行起草 fresh exact J1 请求 |

任何失败 disposition 都不能被后续绿色窄测试覆盖。若要更换 transform、region policy、OCR capability、coalescing、
threshold、assignment 或组合 R2/R3，必须进入新的 `$to-spec`。

### Acceptance Criteria

| ID | 行为验收 | 最低证据 |
| --- | --- | --- |
| AC-R5P-001 | authority 精确绑定基线 revision、旧 N7-04 FAIL/CLOSED、N7-05 permanently blocked、旧 R5 authority digest 与 route-closed runner；任一漂移 fail-closed | authority/tamper A1；独立重算 A2 |
| AC-R5P-002 | 旧 N7/R5 ticket、authorization、contract、assignment、evaluation、evidence 和 terminal identity 均不能被重开、重跑、克隆、supersede 或解释为新 route PASS | negative policy A1/A2 |
| AC-R5P-003 | 新离线 seam 只接受 complete normalized artifacts、完整 plan、冻结 assignment 与 exact policy，并返回 closed outcome；caller 不可提供散装 view、任意 crop、路径、URL 或预算 | contract/property A1 |
| AC-R5P-004 | 每个 corpus raw fixture 必须先通过产品 `InputNormalizer` 和 scoped BlobStore；transform 只读取由 `NormalizedArtifact` 精确标识的 bytes/dimensions/media type，不能直接读取 scene、oracle render 或 gold | provenance/integration A1；A2 replay |
| AC-R5P-005 | canonical source raster 只允许按 corpus 冻结尺寸生成一次 raw submitted fixture；禁止为 baseline/successor 以不同 resolution 重渲染 scene | fixture/identity negative A1 |
| AC-R5P-006 | baseline acquisition 必须严格等于 `VisualViewPlan/1.0.providerImages()` 的完整有序集合；plan descriptor、实际 image identity、dimensions、bytes 与 acquisition artifact 一一对应 | product-plan integration A1/A2 |
| AC-R5P-007 | successor acquisition 必须严格等于 action outcome 的完整 plan v2：保留 required overview，加入至多两个 inspected views，再按冻结规则保留 optional static views；不得只 acquire crops 或用未执行 view 参与 identity | paired-plan integration A1/A2 |
| AC-R5P-008 | action core 保持一轮、两个局部视图、10 views、30 MiB、11,520,000 inspected pixels、12,000 additional visual tokens 与 10,000 ms local transform 上限；溢出在 allocation/provider 副作用前失败 | boundary/property A1 |
| AC-R5P-009 | 旧四例固定为 `SEEN_DIAGNOSTIC`；其结果只能 veto，不能贡献 confirmation/HOLDOUT pass、调参或 AC-021 claim | assignment/access A1/A2 |
| AC-R5P-010 | `SEALED_CONFIRMATION` 精确包含与旧四例不重叠的 3 DEV + 1 corpus-v2 HOLDOUT；IDs、regions、presets、thresholds 和 identities 在第一条结果前冻结，HOLDOUT 结果不得触发变化 | pre-result manifest + access audit A1/A2 |
| AC-R5P-011 | 两次运行的 normalized bytes、static/successor plan、view bytes、local observations、投影后 payload-free metric inputs 与结果在 8/8 cases 上确定性一致 | two-run actual execution A1；独立 replay A2 |
| AC-R5P-012 | seen diagnostic 四例全部满足 per-case target improvement 与 hallucination non-increase；`transit-board-v3` 必须显式通过，不能被 aggregate 掩盖 | layered seen report A1/A2 |
| AC-R5P-013 | confirmation 四例全部满足 per-case target improvement 与 hallucination non-increase；aggregate line recall 至少提升 500 bps，character errors 必须下降，order/repeat accuracy 不得下降超过 100 bps | layered confirmation report A1/A2 |
| AC-R5P-014 | view observation 的 source projection 与 coalescing 只存在于 evaluation memory，baseline/successor 使用同一冻结算法；不得写入 `DocumentObservationIR/1.0`、Candidate、checkpoint 或 evidence payload | architecture/snapshot negative A1 |
| AC-R5P-015 | A2 verifier 必须从 raw fixture 实际重跑全部路径，并独立计算 identities、plan coverage、metrics、thresholds 和 decision；读取 producer JSON 后重复同一公式不算 A2 | cross-implementation replay A2 |
| AC-R5P-016 | measurement 不成立只能得到 `R5P_MEASUREMENT_INVALID`；measurement 成立但质量门失败只能得到 `R5P_PAIRED_VIEW_NOT_QUALIFIED`；两者均不能解锁 action integration 或 J1 | decision truth-table/tamper A1/A2 |
| AC-R5P-017 | 只有 paired A2 PASS 才能实现 model-facing decoder、hidden Prompt/Profile/pipeline、durable inspection substate 与完整 scripted workflow | dependency gate A1/A2 |
| AC-R5P-018 | 最高产品 seam通过现有 PostgreSQL FSM 完成 `OBSERVE(request) → local inspection → OBSERVE(grounding) → HIERARCHY → ELEMENT_BINDING → LOCAL_MATERIALIZE → REVIEW_REQUIRED`，且 no-inspection product-v45 行为等价 | Testcontainers workflow/full A1；terminal A2 |
| AC-R5P-019 | 所有当前离线工作 external Provider attempts、reservations、cost 与 API-key reads 均为 0；常规日志/evidence 不含图片、Base64、完整 bbox list、OCR、Prompt、模型/Candidate 原文或 RootDocument | zero-provider + payload scan A1/A2 |
| AC-R5P-020 | 只有 clean revision、fresh EvaluationIdentity、R5P 全部离线门、full gate 与 independent terminal A2 通过时才可输出 `R5_SUCCESSOR_LIVE_REQUEST_ELIGIBLE`；它不是 authorization | admission truth table A1/A2；fresh exact J1 仍为 J0 |

## User Stories

1. As a product owner, I want the impossible N7 DAG preserved as historical evidence rather than edited into success.
2. As a product owner, I want a successor Goal to begin from a new authority and ticket namespace.
3. As an evaluation owner, I want measurement validity decided before quality, so that invalid comparisons cannot produce PASS.
4. As an evaluation owner, I want baseline and successor to acquire complete product plans, so that plan identities describe work actually executed.
5. As an evaluation owner, I want old observed cases separated from confirmation, so that a repaired harness does not reuse a seen HOLDOUT claim.
6. As an evaluation owner, I want `transit-board-v3` retained as an explicit veto, so that its known regression cannot disappear inside an aggregate.
7. As a holdout custodian, I want the new assignment frozen before results, so that confirmation cannot become a tuning loop.
8. As a perception engineer, I want transforms to begin from product-normalized bytes, so that vector oracle and upload behavior are not conflated.
9. As a perception engineer, I want the inspected branch to retain product context, so that crop-only OCR is not mistaken for the model-visible plan.
10. As a perception engineer, I want one action Module to compose the plan, so that evaluation and future product integration do not maintain competing algorithms.
11. As an IR maintainer, I want evaluation-only crop projection kept out of `DocumentObservationIR/1.0`.
12. As a workflow maintainer, I want PostgreSQL to remain the only durable authority.
13. As a security reviewer, I want zero Provider and zero credential reads independently reconstructed.
14. As a security reviewer, I want images and OCR text ephemeral even during A2.
15. As an independent verifier, I want to rerun product normalization and acquisitions rather than trust producer summaries.
16. As a governance owner, I want offline action permission separated from live eligibility and J1 approval.
17. As a future Agent, I want fixed outcomes and reason codes, so that goal persistence cannot override failed evidence.
18. As a future ticket author, I want each node to yield a fresh-context vertical result with its own evidence gate.

## Implementation Decisions

### 1. Authority and lifecycle

1. `R5ProductTransformEvaluation/1.0`、其 assignment/evaluation/evidence 和 authority 保持 closed historical records。
2. 新 route 使用 `R5P` namespace；identity framing 必须绑定本 delta、baseline revision、old authority digest、
   transform、normalizer、static planner、action policy、assignment、capability、evaluator 与 runtime。
3. replacement Goal 只能在本 spec approved 且新 tickets 经用户确认后创建。旧 Goal 不改目标、不伪造 completion。
4. replacement Goal 不承诺成功；任一 terminal failure 都是合法完成结果，但不能被报告为质量 qualified。

### 2. One evaluation Module, one product action Module

1. `PairedProductViewEvaluation` 拥有 raw fixture admission、normalization provenance、paired execution、temporary
   projection/coalescing、resource accounting、payload-safe report 与固定 outcome。
2. `BoundedVisualInspection` 继续拥有 request validation、transform、plan composition、limits 和 outcome；paired
   evaluator 通过其 Interface 获得 successor plan，不复制 plan algorithm。
3. 在 paired PASS 前，action Module 只能由 offline evaluator/test adapter 调用；app create routes、Provider worker、
   Profile catalog 与 UI 不可到达。
4. 不为只有一个实现的内部 image codec、hash 或 metric helper建立公共 seam；测试穿过上述两个 Interface。

### 3. Product-normalized source provenance

1. corpus 的 canonical source-size raster 可作为 repository synthetic raw upload fixture，但 baseline 与 successor 必须
   共享同一份 raw bytes，且均经过产品 `InputNormalizer`。
2. evaluator 从 scoped in-memory/test BlobStore 读取 normalized bytes；scene model、annotation 和 gold 不能传入 action
   Module。
3. evidence 只记录 raw fixture identity、normalized artifact identity、dimensions、media type、normalizer identity、
   hashes 和计数，不记录 bytes 或路径。
4. alternate-resolution rerender、oracle image、人工修图或按结果变化的 preprocessing 都是 hard rejection。

### 4. Paired plan semantics

1. baseline 是完整 `VisualViewPlan/1.0`，实际 acquisition list 必须与其 `providerImages()` 完全一致。
2. successor 从同一 plan 和 frozen typed request 进入 `BoundedVisualInspection`，得到完整 plan v2；required overview、
   inspected view 和 optional tile 的顺序/取舍遵循 ADR-0039。
3. 两个分支各自形成一个 canonical `ArtifactSet` 后调用同一 frozen RapidOCR acquisition capability。不得让某一
   分支获得额外 OCR 参数、gold 或人工过滤。
4. projection/coalescing 对两个分支使用相同实现和阈值。它只为评测计算源坐标指标，不形成产品 crop OCR merge。

### 5. Assignment isolation

1. seen set 精确为旧 assignment 的四例，并继承原 request fixtures；其 partition 在新 report 中统一标记
   `SEEN_DIAGNOSTIC`。
2. confirmation manifest 必须在执行代码读取任何新结果前提交；它选择 3 DEV + 1 corpus-v2 HOLDOUT，并与 seen
   set 完全不重叠。
3. confirmation selection 可使用冻结的 case ID、domain、difficulty 与 failure-slice metadata，不得使用该
   experiment 的 OCR/quality result 选择 winner。
4. 旧 HOLDOUT 已被观察，不能被重命名后进入 confirmation。corpus v2 的任何结果都不升级 AC-021 authority。

### 6. Metrics and independent A2

1. quality predicates保留旧 spec 的严格方向：per-case target improvement、per-case hallucination non-increase、
   aggregate line recall +500 bps、character error reduction，并加入 order/repeat 100-bps non-regression。
2. seen 与 confirmation 分开计算；seen 只能 veto，confirmation 不能补偿 seen failure。
3. A2 在独立进程用独立 evaluator 实际重跑；它可复用 frozen RapidOCR binary/model identity，但不能调用 Java
   producer decision engine或复制 producer decision output。
4. stdout/stderr、JUnit、Python summary、evidence 与 tamper failure 全部进入 decoded/raw payload scan。

### 7. Conditional product integration

1. `R5P_ACTION_IMPLEMENTATION_ALLOWED` 只允许继续实现 ADR-0039 已批准的 closed action contract、one-round state
   与 hidden experimental semantic identities。
2. Prompt 12/7/4、Candidate Prompt 5、product-v45 Profile、pipeline 4.28 与 historical shape bytes 仍不可修改。
3. 新 Prompt/Profile/pipeline 必须 additive、hidden、offline scripted only，直到最终 eligibility 和单独 exact J1。
4. 完整 replay 仍停在人工 `REVIEW_REQUIRED`；不自动 Apply、发布或接受 Candidate。

### 8. Privacy, Provider and authorization

1. OCR text、source/derived image、Prompt、model/Candidate payload 只可存在于最短必要内存生命周期，不能进入普通
   evidence、日志、异常、对象字符串或 tracker。
2. 当前 scope 必须清空 `DASHSCOPE_TOKEN_API_KEY` 的子进程可见值，并使用 no-network/no-provider adapter；不得读取
   或输出任何 secret value。
3. `R5_SUCCESSOR_LIVE_REQUEST_ELIGIBLE` 只允许后续起草请求。fresh exact J1 仍须绑定全新 ticket、authorization、
   contract、evaluation identity、model/Profile snapshot、Prompt/shape/pipeline/policy、数据分类、case assignment、
   attempts/tokens/cost、serial/batch 规则、有效期和 exact secret name。

### 9. Replacement Goal and ticket boundary

规格批准后，下一步只能使用 `$to-tickets` 拆 fresh-context tracer bullets；建议节点为：

1. authority lock 与旧 route negative proof；
2. harness conformance；
3. offline action core；
4. confirmation assignment freeze；
5. paired two-run A1；
6. independent A2 与 offline decision；
7. 条件 action decoder/plan v2；
8. durable replay/recovery；
9. product-v45 compatibility/full gate；
10. independent terminal admission。

所有第 7–10 节点被 `R5P_ACTION_IMPLEMENTATION_ALLOWED` 阻塞。replacement Goal 只覆盖批准后的这些新 tickets；
它不再承诺完成旧 N7-05..15，也不创建 live ticket。

## Testing Decisions

### 1. Pre-agreed seams

主测试 seam：

> `normalized ArtifactSet + complete VisualViewPlan/1.0 + FrozenPairedViewAssignment/1.0 + exact PairedViewEvaluationPolicy/1.0 → PairedProductViewOutcome/1.0`

产品 action seam：

> `normalized ArtifactSet + VisualViewPlan/1.0 + InspectionRequest/1.0 + AdaptiveInspectionPolicy/1.0 → InspectionOutcome/1.0`

最高验收 seam：

> 完整 IMAGE_ONLY scripted replay 经 PostgreSQL durable typed state machine 到 `REVIEW_REQUIRED`，并证明
> no-inspection product-v45 行为等价。

测试只断言 observable outcome、reason code、plan/view identity、resource count、metrics、terminal state 与 redaction，
不断言 private helper、collection、Jackson traversal 或 OCR library DTO。

### 2. Harness conformance tests

1. 用自描述色块/坐标 raster fixture 验证 `InputNormalizer → BlobStore → normalized ArtifactSet` bytes/dimensions/hash。
2. 对 overview-only、overview+tiles 和多 source plan 验证 descriptors 与实际 acquired images 一一对应。
3. 变异 plan identity、漏掉 tile、调换 view、替换 bytes、伪造 dimension 或多 acquire 一个 view 必须失败。
4. raw fixture 直通 action、vector oracle substitution、gold object 注入和不同分支不同 source bytes 必须失败。

### 3. Action and paired execution tests

1. TDD 以已批准 product action seam 执行一 region、两 region、edge clipping、10-view/30-MiB/pixel/token/time 边界。
2. baseline 必须使用 full plan；successor 必须使用 full outcome plan。overview-only/crop-only fixture 是明确 negative。
3. 两 run 的 normalized artifacts、view bytes、plan identities、projected metrics 和 resource summaries 必须 exact。
4. checked arithmetic、duplicate request、unknown view、second round 和 incomplete required view 全部 fail-closed。

### 4. Assignment and metric tests

1. seen/confirmation overlap、旧 HOLDOUT 复用、结果后改 manifest、错误 partition 和未冻结 threshold 均拒绝。
2. 手写 goldens 覆盖 per-case pass/fail、500/499 bps、100/101 bps non-regression 与 hallucination +1。
3. seen failure 即使 confirmation 全绿也必须得到 `R5P_PAIRED_VIEW_NOT_QUALIFIED`。
4. measurement invalid 即使 metrics 看似全绿也必须得到 `R5P_MEASUREMENT_INVALID`。

### 5. Independent A2 and workflow tests

1. A2 从 raw fixture 重跑，不接受仅 hash/summary replay；producer output 缺失时仍能独立得出同一 outcome。
2. Java/Python 对 closed JSON、duplicate/trailing/unknown、bool/int/float、overflow 与 decoded payload marker 等价。
3. offline process 证明 Provider attempts/reservations/cost/API-key reads 为 0。
4. paired PASS 后才运行 PostgreSQL crash/lease/cancel/idempotency、完整 scripted replay 和 v45 comparison。
5. 最终在 exact clean revision 运行 affected tests、server/document-vision/E2E/full gate 与独立 terminal verifier。

## Out of Scope

1. 当前任何真实 Provider 调用、reservation、token、cost、API-key read、authorization creation、ledger OPEN 或
   fresh J1 request。
2. 重开、重试、修改、删除、克隆、supersede N7-04；解锁、执行、重命名或复用 N7-05。
3. 重跑或修补 `R5ProductTransformEvaluation/1.0`、旧 assignment/evaluation/evidence/authority。
4. 在 paired A2 PASS 前实现 model JSON decoder、Prompt/Profile/pipeline、durable checkpoint 或 app create route。
5. 修改 `DocumentObservationIR/1.0`、引入 IR/1.1、把 crop OCR merge 持久化或让 inspected view 进入 Candidate evidence。
6. 修改历史 Prompt 12/7/4、Candidate Prompt 5、product-v45 Profile、pipeline 4.28、validator、materializer 或 Apply。
7. 超分辨率、deblur、alternate OCR、OCR ensemble/fusion、learned crop、不同 interpolation、结果驱动 retuning 或
   R2/R3/R4/R6 组合。
8. 开放式 Agent、ReAct、通用工具执行器、任意文件/HTTP/SQL/browser/shell、LangGraph、Temporal、Step Functions
   或第二 durable truth。
9. 把 corpus v2、seen diagnostic 或 sealed confirmation 宣称为 AC-021 certification、外部真实数据或 pristine
   business acceptance。
10. Template、Template Agent、Template designer、RootDocument connect/extract、Connector、数据适配、Workspace、
    Schema/Template/StaticSchema 发布或任何占位页面、表、接口、导航。
11. 在本草案批准前创建 replacement Goal、issue/ticket、分支、commit、label 或 tracker 发布。

## Further Notes

1. 本 delta 不推翻 ADR-0039。它修正的是“如何在实现 durable integration 前证明 action 的产品视图增益”，而不是
   一轮两个视图、代码拥有动作或 PostgreSQL durable authority。
2. 允许最小 offline action core 是必要的 sequencing 修正：没有真实 plan v2 就无法测量产品 paired view；但它
   不能被误解为 Prompt、workflow 或 live implementation permission。
3. old seen cases保留为 veto 是为了防止修正测量后隐藏已知失败；新 confirmation assignment则防止把已看过的
   HOLDOUT继续包装为新证据。
4. 若 paired route 再次失败，replacement Goal 应以权威 failure 完成，不得自动切换 OCR、调参数或继续申请 live。
5. 规格确认后的顺序是：`$to-tickets` 提案并等待确认 → 写本地 ticket files → 创建 replacement Goal →
   `$implement`。任何 live 仍需在所有离线门通过后另行申请 fresh exact J1。
