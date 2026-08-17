# 确定 IMAGE_ONLY 生产准入的当前权威状态

Type: task
Status: resolved
Claimed by: Codex /root
Blocked by: none

## Question

在不修改、覆盖或重新解释任何历史 evidence、ledger、J1 与负面 terminal 的前提下，当前 HEAD `490f3556b08eea035d628e52a7b89ef41de6bce1` 上哪一组 spec、plan、authority record 和 evidence 是 IMAGE_ONLY 生产准入必须继承的 canonical lifecycle 事实？旧计划仍写 N7 `in_progress`，但 N7-04、R5、R5P 与 R5P2 已分别形成关闭或无效测量边界；应如何形成一份 payload-free authority inventory，明确每条路线的 `ACTIVE / CLOSED / STALE / REQUIRES_NEW_SPEC` 状态，并指出哪些旧计划只能作为历史索引、不能继续调度？

## Answer

### 结论

当前不是“继续 N7/R5/R5P/R5P2 即可生产”的状态，而是：

- product-v45 的 IMAGE_ONLY 工程路径仍是当前可运行基线，但三份 Profile 全部为 `EXPERIMENTAL`，只证明受控 opt-in 与指定输入 reachability；`ProductionUsable=false`。
- 原 N7 live qualification 路线、R5 transform 路线、R5P paired-view 路线和 R5P2 reconciliation 路线均已形成不可覆盖的关闭终态；它们只能提供不可变事实，不能继续调度。
- 未完成的生产质量义务仍然存在，但只能由全新的 approved IMAGE_ONLY Profile Certification delta、namespace、identity、assignment、停止规则和后续 fresh exact J1 承接。

本清单中的状态含义固定如下：`ACTIVE` 表示仍约束当前产品行为或可复用的已验证基础设施，不表示已生产认证；`CLOSED` 表示生命周期已终止且只能只读继承；`STALE` 表示状态索引已被更晚权威事实否定，只能用于历史导航；`REQUIRES_NEW_SPEC` 表示义务仍在，但不存在可合法继续的现有路线。

### Canonical precedence

冲突时按以下顺序取事实：

1. 当前 v1 authority spec 与更晚的 approved change spec；
2. 与该 spec 绑定的 immutable authority record；
3. 与 exact revision/identity 绑定的封存 evidence 和负面 terminal；
4. 当前 Profile resource/code registry；
5. plan 仅作索引，`.scratch` 仅作规划材料，二者都不能覆盖 1–3。

因此，任何 `FAIL`、`*_NOT_QUALIFIED` 或 `*_MEASUREMENT_INVALID` 都不能被旧 plan 的 `in_progress`、窄门绿色、人工解释或 later rerun 覆盖。

### Payload-free authority inventory

| 对象 | 状态 | 必须继承的事实 | 调度含义 |
| --- | --- | --- | --- |
| `specs/renderweave-v1.md` 与 product-v45 registry/resources | `ACTIVE` | 新建产品目录只暴露 Plus/Max/Flash 三份 IMAGE_ONLY v45；三者 `certification=EXPERIMENTAL`。v1 明确允许显式接受实验 Profile，但不得称为已认证；未达质量门者不能成为默认生产 Profile。见 `specs/renderweave-v1.md:496-505,513-520,553-568`、`InferenceProfileRegistry.java:162-170` 和三份 v45 JSON `:35`。 | 这是当前行为/兼容基线，不是 production certification。旧 Profile snapshot 仍只读可恢复。 |
| N8/R0 `DocumentObservationIR/1.0` 与 N9/R1 分层评测基础设施 | `ACTIVE` | exact checkpoint 的完整 IMAGE_ONLY scripted replay、60-case/58-metric 独立重算和 Provider-zero 已建立；visual diff 仍为 J0。见 `plans/renderweave-visual-recognition-vnext-plan.md:963-985`。 | 可被新 spec 复用为 IR、corpus/evaluator 与 A2 基础设施事实；不能替代 live 模型质量、AC-021 或人工验收。 |
| N7-04 / N7-05 原 live DAG | `CLOSED` | N7-04 五例为 2 `REVIEW_REQUIRED`、3 `FAILED`，contract/DAG 4000 bps、evidence 4925 bps、33 critical hallucinations/blockers、0 Candidate 通过；authorization `CLOSED`，决定为 immutable `FAIL`。N7-05 依赖 N7-04 `PASS`，所以永久不能解锁。见 approved quality-repair spec `:27-40,182-187,371-376`。 | 禁止重开、重试、改名、复用或从 N7-05 继续。更高层“需要生产认证”的义务转入新 spec，不把 N7 报成成功。 |
| 历史 live J1、authorization 与 ledger | `CLOSED` | v1 plan 明确历史评测 authorization 均 `CLOSED`；历史 product-v45 J1 只证明指定输入 reachability。见 `plans/renderweave-v1-plan.md:3,10-13` 与 visual plan `:956-961`。 | 不得为任何 future canary、20/60 或生产 run 提供权限；未来 live 必须在新离线 DAG 完成后申请 fresh exact J1。 |
| R5 product-transform | `CLOSED` | authority 只接受 `A1_PRODUCER_REPORT_CONSISTENCY_ONLY`，`a2Disposition=NOT_ESTABLISHED`，终态 `R5_PRODUCT_TRANSFORM_NOT_QUALIFIED`，fresh J1 不合格；代码明确 `a2Established/allowsTransformRerun/allowsActionImplementation/allowsFreshJ1Request=false`。见 `visual-eval/r5/product-transform-authority-v2.json:1` 与 `R5ProductTransformAuthority.java:143-172`。 | 禁止 rerun、实现 action 或申请 J1；未来方案不能把该 A1 自称为 A2。 |
| R5P paired product-view | `CLOSED` | raw replay JSON 曾自报 measurement valid/quality fail，但 approved R5P2 spec 已根据 A1/A2 acquisition lifecycle 不同及 23 个字段不一致，把有效历史终态固定为 `R5P_MEASUREMENT_INVALID`；R5P-07..12 保持关闭。见 R5P2 spec `:8,15-28` 与 `visual-eval/r5p2/authority-v1.json:1`。 | 不得把 raw JSON 内的 `R5P_PAIRED_VIEW_NOT_QUALIFIED` 升级为有效 A2 质量结论，也不得修补后重跑。A1 的负面质量数值只保留为诊断信号。 |
| R5P2 source-line reconciliation | `CLOSED` | HEAD `490f3556b08eea035d628e52a7b89ef41de6bce1` 的 independent evidence 为 A2 public-process actual replay，但 `measurementValid=false`、首败 `R5P2_A2_VIEW_COVERAGE_DRIFT`、终态 `R5P2_MEASUREMENT_INVALID`；Provider attempts/reservations/cost/API-key reads 全为 0。见 `.sdlc/evidence/20260816-0510-r5p2-independent-a2/r5p2-independent-actual-replay.json:1` 与 approved spec `:204-217,291-297,316-321`。 | 这是 measurement-validity blocker，不是质量结论；R5P2-07..12 不可调度，不运行 offline decision/product integration/full/review 来掩盖终态。任何 transform/region/OCR/reconciliation/threshold/assignment 变化必须新 spec/identity。 |
| `plans/renderweave-v1-plan.md` 与 `plans/renderweave-visual-recognition-vnext-plan.md` 的 lifecycle header | `STALE` | 前者仍写 N7 `in_progress` 且把 N8/N9 写成 `pending`（`:8-14`）；后者把 N8/N9 写成 `automated_verified`，但仍把 N7 写成 `in_progress`（`:17-23`），两者都未折叠之后的 N7-04/R5/R5P/R5P2 负终态。 | 历史 checkpoint、revision 和 evidence 指针仍可查阅；这些 header 不再是调度权威，不能据此继续 N7。 |
| `.scratch/visual-recognition-vnext-n7-closeout/issues/01..15` 及旧 R5P/R5P2 issue DAG | `STALE` | N7 closeout 是未批准的 `planned` scratch DAG，其中 Plus 5-case canary/20-case 链仍假设可继续原 N7；旧 R5P/R5P2 下游则已被各自负终态关闭。 | 全部只能作为历史设计材料；不得 claim、执行、改名复用或据其创建 J1。可复用的需求必须在新 authority 下重新出票。 |
| IMAGE_ONLY production certification/live admission | `REQUIRES_NEW_SPEC` | v1 AC-021 当前要求完整 60 例及 image/json/combined mode slice（`specs/renderweave-v1.md:553-568,709-715`），而本 Wayfinder 只认证 IMAGE_ONLY。当前没有 approved mode-specific delta，也没有 sole-finalist exact production Profile、fresh DEV/HOLDOUT/live authority、final A2、production policy J1。 | 下一合法节点是冻结新的 IMAGE_ONLY Profile Certification authority；不得直接进入 `$to-tickets`/`$implement` 或 live。若 Provider/model/endpoint/Profile bytes 改变，必须对新 immutable Profile 重新认证。 |

### Canonical lifecycle record

从本 ticket 起，Wayfinder 采用以下单一状态记录：

```text
CURRENT_PRODUCT_BASELINE = ACTIVE_EXPERIMENTAL_PRODUCT_V45
N8_R0_N9_R1_INFRASTRUCTURE = ACTIVE_AUTOMATED_VERIFIED_PROVIDER_ZERO
N7_ORIGINAL_LIVE_ROUTE = CLOSED_N7_04_FAIL_N7_05_PERMANENTLY_BLOCKED
R5_ROUTE = CLOSED_R5_PRODUCT_TRANSFORM_NOT_QUALIFIED
R5P_ROUTE = CLOSED_R5P_MEASUREMENT_INVALID
R5P2_ROUTE = CLOSED_R5P2_MEASUREMENT_INVALID
PRODUCTION_PROFILE_CERTIFICATION = REQUIRES_NEW_SPEC
PRODUCTION_USABLE = false
```

本次只重建 authority inventory；未运行任何 gate、Provider、J1、API-key 检查或旧路线重放。
