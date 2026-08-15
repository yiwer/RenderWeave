# Change Spec：R5P2 完整分支采集、跨视图 source-line reconciliation 与全新 confirmation

- 状态：approved
- 日期：2026-08-15
- 触发任务：用户确认进入新的 `$to-spec`，并要求使用全新、未观察的 confirmation assignment。
- 代码基线：`4b756c52cbc2fd389d8ca34f4c4a65b1bc9615db`
- 历史规格：`specs/changes/20260815-r5-paired-product-view-successor.md`
- 历史终态：`R5P_MEASUREMENT_INVALID`；R5P-07..12 保持关闭。
- 影响 AC/规则：新增 AC-R5P2-001..024；细化 AC-R5P-006、011、014..020；不修改 ADR-0039 的一轮、两个局部视图和代码拥有动作边界。
- Provider 边界：attempts、reservations、cost、API-key reads 必须始终为 0；本 delta 不授权 live 或 J1。
- 再锚定关系：本 delta 已于 2026-08-15 获用户批准，自批准起成为 `RULE-ANCHOR-001` 的新对照基准；下一步只能进入 `$to-tickets`，不得直接创建 Goal、实现提交或运行实际 R5P2 OCR 质量门。

## Problem Statement

R5P 在独立重放节点形成了一个必须保留的负面终态。producer A1 与 independent A2 使用了不同的 acquisition
生命周期：

1. A1 对每个 baseline/successor 完整 branch 调用一次 `VisualEvidenceAcquisition`，由
   `LocalProcessDocumentVisionPreprocessor` 启动一个全新 adapter 进程，并在单个公开
   `renderweave-document-vision-request/1.0` 中提交该 branch 的全部 artifacts。
2. A2 每个 run 只创建一个 RapidOCR engine，再逐 view 调用 adapter 私有 `_artifact` 函数；代码实际执行
   88 个 view-level OCR 调用，却把 32 个 branch 记为 `actualAcquisitionCalls`。
3. A1/A2 的首轮 case comparison 有 23 个 pair-metric 字段不一致；`transit-board-v3` 首个失败为
   `matchedLineGain=2` 对 `3`。因此独立 measurement 没有建立，A2 JSON 内部写出的
   `R5P_PAIRED_VIEW_NOT_QUALIFIED` 不能升级为有效 A2 terminal。
4. A1 自身的质量也未达标：seen hallucination non-increase 为 3/4；confirmation target improvement 为 2/4，
   character error reduction 为 `-65`，order delta 为 `-625 bps`。仅修复进程协议最多得到一个有效的
   `R5P_PAIRED_VIEW_NOT_QUALIFIED`，不会解锁 product tickets。

历史输入固定为：

| 输入 | 固定身份 |
| --- | --- |
| R5P closeout revision | `4b756c52cbc2fd389d8ca34f4c4a65b1bc9615db` |
| producer report | `renderweave-r5p-paired-product-view-report/1.0:2f15a068bd6c5eb8416a1d7da7c8fd679278a8f734cd78d2d35ade6ab01ff783` |
| producer report SHA-256 | `df622da5089f069ed4b6bd2a929fec6539839af6375d3669d18f896397082625` |
| independent evidence | `renderweave-r5p-independent-replay-evidence/1.0:2ccd12203e15ac572d72036530973ad181e76f0a08ebd4b84b2d4b14aaca5281` |
| independent evidence SHA-256 | `1086bbee024a126d7c665995a44461faee36e4a7ee541e73f8bccd2f2fc393d6` |
| historical evaluation | `renderweave-r5p-paired-view-evaluation/1.0:c8ad69263640ca49cd93ca24c6b558c6f913ff89a40c84052634c7cd79f66b65` |

这些文件、身份、指标和终态都是 immutable historical records。新路线不得 amend、删除、覆盖、重命名、
supersede 或解释为旧 R5P PASS。

## Solution

新增全新 namespace `R5P2`。R5P2 同时修复 measurement seam，并验证一个明确、无 gold 的质量假设：

> targeted views 带来的同一 source line OCR 变体，应先按 source geometry 去重并确定性选择一个代表，而不是只有
> text 完全相同时才 coalesce。该过程测量的是跨完整产品视图可见的唯一 source-line evidence，不形成产品 OCR
> merge，也不写入 `DocumentObservationIR`、Candidate 或 checkpoint。

### 1. 一个深 evaluation Module

新增 evaluation-only 深 Module `R5P2PairedProductViewEvaluation`，其唯一 Interface 为：

> `raw RepositoryRasterFixtureSet + FrozenR5P2Assignment/1.0 + exact ProductView preparation + exact VisualEvidenceAcquisition + FrozenSourceLineReconciliationPolicy/1.0 → R5P2PairedProductViewOutcome/1.0`

Interface 只接受完整 raw fixtures、完整 assignment、完整 baseline/successor plans 和 exact policy；caller 不能提交
散装 view、任意 crop、预计算 observation、gold-derived hint、阈值或局部 metric。Module 内部统一拥有：

1. raw fixture admission、产品 `InputNormalizer` 与 scoped BlobStore provenance；
2. static plan 与 `BoundedVisualInspection` 完整 successor plan materialization；
3. branch-scoped local acquisition；
4. source projection、cross-view reconciliation、layered metrics；
5. determinism、resource/provider accounting 与 closed terminal decision。

现有 `BoundedVisualInspection` 仍是独立产品 Module；其 Interface、一轮、两个 inspected views、transform、budget 与
failure code 均不改变。`SourceLineReconciliation` 是 evaluation Module 内的纯 in-process Module，不新增产品
port，也不暴露给 create route。

### 2. branch-scoped frozen adapter process

Java producer 与 Python independent verifier 都必须通过 adapter 的公开进程协议执行完整 branch：

1. 每次 branch acquisition 启动一个全新 adapter process 和一个全新 RapidOCR engine。
2. 一个 request 精确包含该 branch 完整、有序的 `ArtifactSet`；禁止逐 view 调用 `_engine`、`_artifact`、
   `_preprocess` 或任何 adapter 私有函数。
3. 两个 run、12 cases、每 case 两个 branch，固定为 48 次 branch acquisition processes；每个 run 一次、总计
   2 次 capability probe processes；artifact/view 数量单独计数，三者不得互相冒充。
4. response 必须独立执行 strict JSON、capability、artifact count/order/identity、dimensions、line limits、text bytes、
   bbox、confidence、canonical text 与 timeout 验证。
5. A2 可以复用 frozen RapidOCR binary/model/adapter 文件，但不能调用 Java producer decision engine，不能在 replay
   期间读取 producer report，也不能复制 producer terminal。
6. 子进程环境只保留运行所需的最小变量，清空 DashScope/live/credential 变量并保持 no-network。

### 3. FrozenSourceLineReconciliationPolicy/1.0

R5P2 只允许一个在 confirmation 结果前冻结的 reconciliation policy：

1. 所有 observation 先按现有 `renderweave-r5p-source-projection/1.0` 投影回同一 source artifact 的 canonical
   `0..10000` 坐标；baseline 与 successor 使用完全相同实现。
2. 只有来自不同 view、同一 source artifact 的行可以进入同一 cluster；同一 view 内永不互相合并。
3. 两行只有同时满足以下条件才属于同一 source-line candidate：
   - intersection / smaller-area `>= 5000 bps`；
   - vertical-intersection / smaller-height `>= 8000 bps`；
   - 较小 box 的中心点位于较大 box 的闭开区间内。
4. cluster 使用 complete-link：新成员必须与 cluster 中每个已有成员都满足条件，禁止 transitive chaining。
5. cluster 代表项按以下冻结顺序选择：更高 view/source pixel-density、更高 native confidence、更小 projected area、
   更小 view ordinal、再更小 line ordinal。所有比较使用 checked integer/rational arithmetic。
6. 代表项只保留原 observation 的 text、box 与 confidence；禁止拼接、投票生成新 text、gold 修正或语言模型修正。
7. 不同 source boxes 上的相同文字必须保持为不同 lines；repeated list 不能因文字相同被压平。
8. 最终 metric input 按 source top、left、bottom、right、canonical text、view ordinal、line ordinal 排序。

该 policy 的数值、tie-break 和 identity 必须先由无质量含义的 self-describing fixtures 锁定，再允许读取任何 R5P2
paired result。若 policy、阈值或 tie-break 后续变化，必须进入新的 spec/identity，不能原地重跑。

### 4. historical diagnostic cohort

以下八例已经产生 R5P paired results，统一进入 `HISTORICAL_DIAGNOSTIC`：

- `transit-board-v3`
- `restaurant-menu-v3`
- `hospital-schedule-v3`
- `transit-board-v5`
- `transit-board-v2`
- `invoice-lines-v3`
- `school-timetable-v4`
- `building-directory-v5`

它们可以用于 TDD、因果诊断和 regression veto，但不能贡献 fresh confirmation、HOLDOUT、AC-021 或 live claim。
R5P2 producer 的八例 diagnostic 必须全部满足 per-case target improvement 与 hallucination non-increase，且
`transit-board-v3` 必须显式通过；否则路线关闭。

### 5. 全新、paired-treatment 未观察的 confirmation assignment

本 delta 中“未观察”精确定义为：在基线 revision `4b756c5` 前，从未进入 R5/R5P product-transform/paired
assignment、从未产生 R5/R5P paired branch observation/metric/evidence，并且 case family 与上述八例不重叠。
它不表示 case 从未存在于 60-case repository synthetic corpus，也不表示 pristine external/business acceptance。

selection 只读取 frozen corpus v2 的 case ID、partition、difficulty、failure-slice、case identity；禁止读取 OCR、
paired metric、gold text、candidate output 或任何模型结果。固定算法为：

1. corpus identity 固定为
   `renderweave-visual-stage-corpus/2.0:c596621eb680e7e10d42d2e1d1f926995cec9716cc6ef83a96a50ad53adc285c`；
   identity-lock SHA-256 固定为 `cf54fd985e89a024fdc0742a737c21442c49718fdf58b0bb05b87e2cffd2247d`。
2. 候选 family 只允许 `analytics-dashboard`、`event-agenda`、`product-catalog`、`warehouse-inventory`、
   `weather-forecast`，且 failure slices 必须包含 `REPEATED_LIST`；family 精确等于从 case ID 删除末尾
   `-v[0-9]+` 后的值。
3. strata 顺序固定为 DEV/DENSE_TEXT、DEV/MULTI_COLUMN、DEV/LOW_CONTRAST、HOLDOUT/NOISY。
4. 每个 stratum 按
   `SHA-256("renderweave-r5p2-confirmation-selection/1.0|<partition>|<difficulty>|<caseIdentity>")`
   升序选择第一个尚未使用的 family。

由该算法得到并在本 spec 中冻结的 assignment 为：

| Cohort | Case | Partition / difficulty | Case identity | Selection rank SHA-256 |
| --- | --- | --- | --- | --- |
| `SEALED_CONFIRMATION` | `weather-forecast-v3` | DEV / DENSE_TEXT | `renderweave-layered-case/2.0:b8276787bbfa99b49851308a7c963fcd41e5bbd311ad1db9169885fc766ee890` | `19f8156bddc9fd7a08e8324e6e3e165060207060fe49c972e51613dabcd1068d` |
| `SEALED_CONFIRMATION` | `warehouse-inventory-v2` | DEV / MULTI_COLUMN | `renderweave-layered-case/2.0:9ca1319537a06892e7920a973b66a9a893f0dea1fea31c4bcb21b7efaf4bf456` | `25fc1c7bc6c9f070c90893c9839c5c9859db0e5fd9492b0bcb4ad9be02251535` |
| `SEALED_CONFIRMATION` | `event-agenda-v4` | DEV / LOW_CONTRAST | `renderweave-layered-case/2.0:8691ed1f501c6597fecbd5c93ac433f42100f5025c7339dcff5474299a9c314f` | `2552e253b354d65e1e8c5d570f696104c9bf62715e6db11cf2e4453bad15417e` |
| `SEALED_CONFIRMATION` | `product-catalog-v5` | HOLDOUT / NOISY | `renderweave-layered-case/2.0:2c0e17e489cf1ef4f1f55be50ff9ca2d070114bf58395125a811a30de3444b14` | `5d7decfb23a7b8090ddc032ead18c40c1d6c7fd2852557c56447cfa58351c3bc` |

四例使用同一预先冻结的 request policy：upper region `[200,200,9800,2500]`、lower region
`[200,2500,9800,9800]`、`TIGHT_0000_BPS`、`INSPECT_LONG_EDGE_2400`。raw submitted fixture 只能从 canonical
corpus raster 生成一次；fixture bytes、dimensions、render identity、normalization fingerprint、regions、runtime、
capability、reconciliation policy、thresholds 和 assignment identity 必须在第一次 OCR acquisition 前提交。

HOLDOUT 的 gold/metric accessor 在 freeze commit 前必须 fail-closed；freeze 后只允许 official producer 与 independent
replay 各自按固定顺序读取，任何 exploratory run、case substitution 或结果后 mutation 都使 measurement invalid。

### 6. quality predicates

R5P2 沿用严格方向，不因历史失败降低阈值：

1. `HISTORICAL_DIAGNOSTIC` 八例全部满足：
   - `matchedLineGain > 0` 或 `characterErrorReduction > 0`；
   - `hallucinationIncrease <= 0`。
2. `SEALED_CONFIRMATION` 四例逐例满足同样两个 predicates。
3. confirmation aggregate 必须同时满足：
   - line recall gain `>= 500 bps`；
   - character error reduction `>= 1`；
   - order accuracy delta `>= -100 bps`；
   - repeat recall delta `>= -100 bps`。
4. diagnostic 只能 veto，不能补偿 confirmation；confirmation 不能补偿任一 diagnostic failure。
5. A1/A2 stage identities、per-case metrics、cohort summaries、resource counts 与 terminal 必须 exact 一致。

### 7. replacement gate order

1. `R5P2-0 AUTHORITY_LOCK`：绑定 approved spec SHA、`4b756c5`、历史 R5P terminal/evidence、route-closed runner、
   corpus identity、zero-provider 与新 namespace。
2. `R5P2-1 BRANCH_PROCESS_CONFORMANCE`：只用 fake/self-describing adapter，证明一次完整 branch 一个公开进程
   request，process/artifact/probe 分账且 private-function path 不可达。
3. `R5P2-2 RECONCILIATION_CONFORMANCE`：只用无质量含义的 synthetic lines 锁定 projection、geometry cluster、
   complete-link、representative selection、order 与 identities。
4. `R5P2-3 ASSIGNMENT_FREEZE`：冻结八例 historical diagnostic、上述四例 fresh confirmation、一次性 raw fixtures、
   request/policy/threshold/runtime identities 与 holdout access audit。
5. `R5P2-4 PAIRED_PRODUCER`：12 cases × 2 branches × 2 runs，执行 48 次 complete branch acquisitions；形成 A1。
6. `R5P2-5 INDEPENDENT_A2`：从 raw fixtures 重建 normalization、plans、action、48 个公开 adapter process、
   reconciliation、metrics 与 decision；replay 期间 producer report reads=0。
7. `R5P2-6 OFFLINE_DECISION`：对 conformance、diagnostic veto、fresh confirmation、A1/A2、privacy 与 Provider-zero
   作出唯一 closed terminal。
8. `R5P2-7 PRODUCT_ACTION`：只有 `R5P2_ACTION_IMPLEMENTATION_ALLOWED` 才允许创建新的 closed decoder、hidden
   Prompt/Profile/pipeline 与 product action integration tickets；不得复活 R5P-07..12。
9. `R5P2-8 DURABLE_REPLAY`：在现有 PostgreSQL FSM 中实现并验证 inspection substate、crash/lease/cancel/idempotency。
10. `R5P2-9 COMPATIBILITY`：完整 IMAGE_ONLY scripted replay 到 `REVIEW_REQUIRED`，并证明 no-inspection
    product-v45 行为等价。
11. `R5P2-10 EXACT_REVISION_GATE`：clean exact revision 运行 affected、server、web、document-vision、E2E、full 与
    payload scan。
12. `R5P2-11 DUAL_AXIS_REVIEW_AND_ADMISSION`：在同一 exact revision 并行执行 Standards/Spec 双轴 code review，
    修复发现后必须产生新 revision 并重跑 exact full gate；最终独立重建 terminal。

规格批准后才使用 `$to-tickets` 创建新的 12-node ticket DAG；本 spec 阶段不创建 ticket、Goal、branch 或实现 commit。

### 8. fixed outcomes

`R5P2PairedProductViewOutcome/1.0` 只允许：

| Disposition | 含义 |
| --- | --- |
| `R5P2_MEASUREMENT_INVALID` | authority、normalization、完整 branch process、reconciliation、assignment、A1/A2 或 Provider-zero 任一未建立；不得解释质量 |
| `R5P2_PAIRED_VIEW_NOT_QUALIFIED` | measurement 有效，但 diagnostic veto 或 fresh confirmation 质量/资源门失败；route 关闭 |
| `R5P2_ACTION_IMPLEMENTATION_ALLOWED` | paired A2 通过；只解锁新的 offline product-action/workflow tickets，不是 live 资格 |
| `R5P2_PRODUCT_PATH_NOT_QUALIFIED` | action、recovery、v45 equivalence、privacy、full gate 或双轴 review 失败；route 关闭 |
| `R5P2_SUCCESSOR_LIVE_REQUEST_ELIGIBLE` | 所有离线门与 final independent terminal 通过；只允许另行起草 fresh exact J1 请求 |

任何失败 disposition 都不能被窄测试、人工解释或 later rerun 覆盖。变更 transform、request regions、OCR capability、
reconciliation policy、threshold、assignment 或 case access 后必须新建 spec/identity。

## Acceptance Criteria

| ID | 行为验收 | 最低证据 |
| --- | --- | --- |
| AC-R5P2-001 | authority 精确绑定 approved spec、`4b756c5`、历史 R5P terminal、两份 evidence identity/SHA 与 route closure | authority/tamper A1；独立重算 A2 |
| AC-R5P2-002 | 历史 R5/R5P spec、assignment、evidence、commit 和 terminal 保持 immutable，不被删除、amend、重跑或解释为 PASS | negative policy A1/A2 |
| AC-R5P2-003 | evaluation Module 的 Interface 只接受完整 fixtures、assignment、plans/acquisition 与 frozen policy；散装 view、任意 threshold/gold hint 均拒绝 | contract/property A1 |
| AC-R5P2-004 | producer 与 verifier 都通过公开 adapter process protocol，每个完整 branch 恰好一个 fresh process/engine；私有 Python adapter 函数不可达 | fake process/integration A1/A2 |
| AC-R5P2-005 | 两 run × 12 cases × 2 branches 精确为 48 branch acquisitions；capability probes、artifact views、process calls 分账且 identities 可独立重算 | accounting/tamper A1/A2 |
| AC-R5P2-006 | 每个 request artifact list 与完整 baseline/successor plan 的 count/order/bytes/dimensions/identity 一致 | product-plan integration A1/A2 |
| AC-R5P2-007 | raw fixture 必须先经产品 InputNormalizer 与 scoped BlobStore；禁止从 scene、oracle、gold 直通 acquisition | provenance/negative A1/A2 |
| AC-R5P2-008 | reconciliation 对两个 branch 使用同一 frozen projection/geometry/representative policy；无 text synthesis、gold、模型或人工过滤 | golden/property A1/A2 |
| AC-R5P2-009 | geometry 阈值 5000/8000 bps、center containment、cross-view-only、complete-link 与 tie-break 边界 exact | 4999/5000、7999/8000 boundary tests A1 |
| AC-R5P2-010 | repeated identical text at different source boxes 保持分离；OCR variants at the same source line 只保留一个原始 representative | adversarial golden A1/A2 |
| AC-R5P2-011 | reconciliation 只存在于 evaluation memory；不修改 `DocumentObservationIR/1.0`，不持久化到 Candidate、Evidence、checkpoint 或 product store | architecture/snapshot negative A1 |
| AC-R5P2-012 | 八例 historical diagnostic 精确固定且只作 regression veto；旧四例 confirmation 永不重新包装为 fresh confirmation | assignment/access A1/A2 |
| AC-R5P2-013 | fresh confirmation 精确为本 spec 表中的 3 DEV + 1 HOLDOUT，selection hash、case identity、family uniqueness 与 prior paired absence 可复算 | manifest/hash/access A1/A2 |
| AC-R5P2-014 | confirmation fixture、regions、policy、thresholds、runtime/capability 与 assignment identity 在首次 OCR 前冻结；结果后 mutation fail-closed | pre-result manifest + tamper A1/A2 |
| AC-R5P2-015 | HOLDOUT 在 freeze 前 gold/metric reads=0；official producer/A2 之外的 access 或 exploratory run 使 measurement invalid | access audit A1/A2 |
| AC-R5P2-016 | 两次 producer runs 的 normalized bytes、plans、view bytes、raw/canonical observation identity、reconciled metric input、metrics 与 accounting 在 12/12 cases exact | actual deterministic A1 |
| AC-R5P2-017 | independent verifier 从 raw fixtures 实际重走全路径，replay 期间 producer report/decision reads=0，并独立计算 stage identities、metrics、thresholds、terminal | cross-implementation actual replay A2 |
| AC-R5P2-018 | producer/A2 在 12/12 case metrics、cohort summaries、stage identities、accounting 与 terminal exact 一致；首个差异以 payload-free stage code fail-closed | post-replay exact comparison A2 |
| AC-R5P2-019 | diagnostic 八例全部 per-case target improvement 且 hallucination non-increase，`transit-board-v3` 显式通过 | layered diagnostic report A1/A2 |
| AC-R5P2-020 | fresh confirmation 四例全部逐例通过，aggregate recall +500 bps、character errors 下降、order/repeat 回退不超过 100 bps | sealed confirmation report A1/A2 |
| AC-R5P2-021 | measurement failure 只能为 `R5P2_MEASUREMENT_INVALID`；质量 failure 只能为 `R5P2_PAIRED_VIEW_NOT_QUALIFIED`；两者均关闭后继 | decision truth table/tamper A1/A2 |
| AC-R5P2-022 | 全部离线工作 Provider attempts/reservations/cost/API-key reads=0；日志/evidence 不含图片、Base64、完整 bbox、OCR、gold、Prompt、Candidate 或 RootDocument | zero-provider + decoded/raw payload scan A1/A2 |
| AC-R5P2-023 | 只有 `R5P2_ACTION_IMPLEMENTATION_ALLOWED` 才可进入新 product tickets；最高 workflow 仍停在 `REVIEW_REQUIRED` 且 no-inspection product-v45 等价 | dependency/FSM/Testcontainers/full A1/A2 |
| AC-R5P2-024 | 最终 clean exact revision 的 full gate、payload scan、Standards/Spec 双轴 review 与 independent terminal 全绿才可输出 eligibility；eligibility 不是 J1 | exact-revision gate + dual review + A2 |

## User Stories

1. As an evaluation owner, I want one branch-scoped public adapter process per acquisition, so accounting and engine lifecycle match the product seam.
2. As an independent verifier, I want to replay without importing adapter private functions or reading producer decisions.
3. As a quality owner, I want cross-view OCR variants reconciled by source geometry, so extra product views are measured as unique source evidence rather than duplicate payload.
4. As a reviewer, I want adjacent/repeated source lines preserved, so geometry reconciliation cannot inflate quality by deleting legitimate repetition.
5. As a data-governance owner, I want all previously observed R5P cases diagnostic-only and a new metadata-selected confirmation cohort.
6. As a product owner, I want measurement validity decided before quality and quality decided before product integration.
7. As a security owner, I want all work offline with zero Provider/key access and no sensitive payload persistence.
8. As a maintainer, I want historical negative terminals preserved and every successor to use fresh identities rather than rewriting history.

## Testing Decisions

### 1. TDD order

每个节点执行 red → green → refactor，并按局部→受影响→node gate 扩大验证。实际 corpus OCR 只允许在 authority、
process conformance、reconciliation conformance、assignment freeze 与 payload guards 全绿并提交后执行一次 official
producer + 一次 official A2。任何 negative terminal 如实停止。

### 2. branch process tests

1. fake adapter 要求单 request 含完整多 view branch；逐 view request 必须失败。
2. stateful fake adapter 证明跨 branch engine reuse 会改变输出，并要求实现使用 fresh process。
3. process calls、capability probes、artifact count 分别断言；把 48 branch 错报为 artifact calls 或反之必须失败。
4. duplicate/trailing/unknown/coercion、oversize request/response、timeout、nonzero exit、wrong capability、missing artifact、
   reordered artifact 与 private function import 全部 fail-closed。

### 3. reconciliation tests

1. 同 source line、不同 view、不同 OCR text 且满足 geometry thresholds 时只保留一个原始 representative。
2. 同一 view 的重叠 lines、相邻 source lines、不同 source artifacts、同文不同位置均不得合并。
3. A-B、B-C 满足但 A-C 不满足时不得通过 transitive chain 形成一个 cluster。
4. 5000/4999 area、8000/7999 vertical overlap、center edge、pixel-density/confidence/area/ordinal tie-break 全覆盖。
5. NFC、ASCII/Unicode whitespace、ISO control、checked arithmetic 与 stable ordering 使用 Java/Python cross-language goldens。

### 4. assignment and access tests

1. 重新执行固定 selection algorithm 必须得到本 spec 四例及同一 rank hashes。
2. 任一 selected case/family 出现在旧 R5/R5P assignment/evidence、四例不唯一、partition/difficulty 错误均拒绝。
3. raw fixture 只生成一次；baseline/successor 不得分别 render。
4. freeze 前访问 HOLDOUT gold/metric、freeze 后 mutation、exploratory replay 或替换 case 均产生
   `R5P2_MEASUREMENT_INVALID`。

### 5. actual execution and A2

1. producer 两 run 逐 case/branch 记录 payload-free stage identities 与 resource counts。
2. independent A2 不读取 producer report，使用公开 adapter process 重跑；完成后 runner 才进行 exact A1/A2 compare。
3. compare 必须先检查 normalization/plan/view/process/raw observation/reconciled input identities，再检查 metrics；输出首个
   mismatch stage code，不输出 OCR/geometry payload。
4. diagnostic 或 confirmation 任一门失败即关闭 R5P2-07..11，不运行 full gate或双轴 review来掩盖 negative terminal。

### 6. final gate and review

只有 offline action/workflow 路线已解锁并完成后，才在同一 clean revision 运行：

1. `powershell -ExecutionPolicy Bypass -File tools/run-gate.ps1 -Gate fast`
2. `powershell -ExecutionPolicy Bypass -File tools/run-gate.ps1 -Gate server`
3. `powershell -ExecutionPolicy Bypass -File tools/run-gate.ps1 -Gate web`
4. `powershell -ExecutionPolicy Bypass -File tools/run-gate.ps1 -Gate eval`
5. `powershell -ExecutionPolicy Bypass -File tools/run-gate.ps1 -Gate document-vision`
6. `powershell -ExecutionPolicy Bypass -File tools/run-gate.ps1 -Gate e2e`
7. `powershell -ExecutionPolicy Bypass -File tools/run-gate.ps1 -Gate full`
8. exact revision payload scan 与 independent terminal replay
9. Standards 与 Spec 双轴 code review

review 修复产生新 revision 后，旧 full evidence 不得复用；必须在新 exact revision 重跑 full、payload scan、independent
terminal 与受影响 review。

## Out of Scope

1. 任何 live Provider 调用、reservation、token、cost、secret read、authorization creation、ledger OPEN 或 fresh J1。
2. 修改、删除、重跑或覆盖历史 R5/R5P assignment、evidence、terminal、commit 或 ticket lifecycle。
3. 把历史八例、旧 confirmation 四例或其改名版本用于 fresh confirmation PASS。
4. alternate OCR、OCR ensemble、super-resolution、deblur、learned crop、不同 transform/interpolation 或 Prompt/model tuning。
5. 修改 `DocumentObservationIR/1.0`、持久化 crop OCR merge、让 inspected view/observation 进入 Candidate evidence。
6. 在 `R5P2_ACTION_IMPLEMENTATION_ALLOWED` 前实现 decoder、Prompt/Profile/pipeline、durable checkpoint 或 create route。
7. 自动 Apply、发布、接受 Candidate、Template、Connector、Workspace、图片渲染或任何占位页面/表/接口。
8. 把 repository synthetic paired result 宣称为外部真实数据、业务验收、A3 或 AC-021 certification。

## Impact

- 用户价值/范围：修复独立测量可信度，并用未参与既有 paired treatment 的四例重新验证跨视图信息增益。
- 实现与数据：新增 R5P2 identities、branch-process verifier、evaluation-only reconciliation 与 4 个一次性 raw fixtures；
  不修改产品 IR、历史 artifacts 或 Provider route。
- 验证与发布：先 conformance，再 diagnostic/fresh confirmation，再 A2；只有 conditional product path 完成后才运行
  exact full gate 与双轴 review。
- DAG/预算：批准后另拆新的 12-node DAG；所有离线 provider accounting 为 0，任何 live 预算均不存在。
- 恢复影响：只新增源码、测试、fixture 和 payload-safe evidence；失败时保留新 terminal，不回写历史文件。

## Decision

- 批准人：用户（本线程明确批准）
- 日期：2026-08-15
- 结论：approved；下一步只能是 `$to-tickets`，不得直接 `$implement`。
