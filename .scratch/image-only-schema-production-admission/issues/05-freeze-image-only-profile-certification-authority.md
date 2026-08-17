# 冻结新的 IMAGE_ONLY Profile Certification authority

Type: grilling
Status: resolved
Claimed by: Kimi（2026-08-17 会话）
Blocked by: 01 — 确定 IMAGE_ONLY 生产准入的当前权威状态；07 — 冻结 Provider 生产路线与 Profile migration 边界；08 — 冻结 RapidOCR 生产拓扑与 capability admission

## Question

在 canonical inventory 已确认 product-v45 仅为 `ACTIVE_EXPERIMENTAL`，且 N7/R5/R5P/R5P2 旧路线全部关闭的前提下，新的 approved delta 应如何唯一冻结 IMAGE_ONLY Profile Certification authority：是为 v1 AC-021 建立明确的 mode-specific delta，还是保持全局 AC-021 未完成并新增独立的 IMAGE_ONLY production certification contract；sole finalist 是否只能来自一个生产合同允许的 immutable Plus-compatible Profile；fresh 5-case canary、20-case DEV、60-case final/HOLDOUT 的 corpus/assignment 隔离、门槛、A1/A2、视觉/业务/production-policy J1、exact-revision gate 和失败终点分别是什么；以及哪些 N7/R5/R5P/R5P2 ticket、identity、assignment、evidence、J1 和 ledger 必须列入 prohibited reuse set？若当前 Provider endpoint 或 Profile bytes 不具备生产合同资格，authority 必须输出 `PROFILE_MIGRATION_REQUIRED_BEFORE_CERTIFICATION`，不得先认证旧 Profile 再把结论外推到新 Profile。

## Comments

- 2026-08-17（Kimi 会话，live 证据输入）：48h scoped J1 试用矩阵（授权 `plans/live-canary-authorizations/20260817-payasyougo-trial.json`，CLOSED）产出首批真实质量信号：Max 2/2、Plus 1/2、Flash 0/2（明细见 ticket 07 Comments）。三份 REVIEW_REQUIRED Candidate 的全部 BLOCKER 均为 `LOW_CONFIDENCE_UNRESOLVED` 且置信度统一 7999bps、恰低于 8000 阈值——认证门槛设计需注意：当前阈值校准会让所有 live Candidate 都停在人工审核，certification corpus 的门槛与阈值校准的关系必须在本票冻结。样本仅 2 图，远不足以替代本票的 5/20/60-case 认证 corpus。
- 2026-08-17（所有者审核结论，Kimi 会话记录）：两份 Max Candidate 人工审核通过、原样接受（required 全 false 可接受、fare 维持 TEXT、日期抽取正确）；**命名约定定为 snake_case**——本票冻结认证 contract 时应将 `proposedSchemaKey`/`proposedFieldKey` 的 snake_case 规范纳入 prompt/合同要求，避免 AI 输出 kebab-case 后依赖人工改名。
- 2026-08-17（ticket 07 已 resolved，输入本票）：认证状态外部化为 `ProfileCertificationRecord`（profileId + bytes SHA-256，CONTEXT.md 已收词）；认证对象为将新发的 **v46 Max Profile**（v45 最小 diff：`maximumTotalCalls` 12、`maximumEstimatedCostMicrosCny` ¥6，其余 bytes 原样，试用证据继承）；重认证至少 fresh 5-case canary。本票须设计该记录的存储/签发/撤销机制，且 v46 Profile 创建随本票认证流程落地。

## Answer

2026-08-17 经两轮 grilling 冻结（全部按所有者确认的推荐）：

### Authority 形态与对象

1. **独立合同**：新增《IMAGE_ONLY Profile Certification Contract》approved delta（specs/changes/ 下新票起草），v1 AC-021 全局保持未完成、IMAGE_ONLY mode slice 由新 contract 承接，结论不外溢 image/json/combined；合同保留 `PROFILE_MIGRATION_REQUIRED_BEFORE_CERTIFICATION` 守护条款防未来 Provider/Profile 漂移。
2. **首个认证周期只服务 v46 Max 管线**（RapidOCR 本地确定性层，ticket 07/08 已冻结其 bytes 与拓扑）；ticket 15 若引入 DeepSeek-OCR-API 变体，走同一 contract 的**后续周期**（新 identity、新证据、新 J1）。
3. v46 Profile 的创建（v45 最小 diff：12 次调用/¥6）是本合同下的执行步骤，随认证周期落地；本地图不实施产品代码。

### Corpus、门槛与证据

4. **Corpus**：复用 N9/R1 的 60-case 语料与 58-metric evaluator 作 DEV/final 骨架（ticket 01 ACTIVE 白名单）；canary 5-case 用所有者提供的 fresh 真实设计图；live 输入必须 `USER_PROVIDED + ORDINARY_DESIGN`。费用量级 ¥40–95/周期（单 run 实测 ¥0.43–1.11）。
5. **Assignment 隔离**：frozen corpus manifest（每 case hash + DEV/HOLDOUT 标签，seeded 划分，manifest 入证据包、周期内不可改）；HOLDOUT = final 60 例中 20 例，DEV 阶段全程不可见。
6. **门槛**：单 case 通过 = run 到 `REVIEW_REQUIRED`/`COMPLETED` 且人工审核接受（结构正确性由 reviewer 判定，不要求零 BLOCKER——7999bps 贴线为预期行为，8000bps 阈值维持 flag-only 语义）；周期门槛 canary 5/5、DEV ≥18/20、final ≥54/60；任一不过 = 周期终止并输出失败终点报告，不修补重跑（继承 N7/R5 的 immutable 终态纪律）。
7. **命名约定入合同**：`proposedSchemaKey`/`proposedFieldKey` 必须 snake_case 是合同级要求——本周期内 AI 输出 kebab-case 不算 case fail（结构正确性为准、reviewer 可规范化），下一 prompt 版本必须内化该约定。

### 记录机制与 J1

8. **ProfileCertificationRecord**：PostgreSQL append-only 表（`profile_id`、bytes SHA-256、verdict、证据指针、门槛、签发时间；撤销 = 追加 revoked-with-reason 行，从不 UPDATE）；`ImageOnlyReadiness` 的 `PROFILE_NOT_CERTIFIED` 读最新行。签发 = 认证工具全门槛通过后落行 + 所有者 J1 确认。
9. **Exact-revision gate**：记录钉 corpus manifest hash、evaluator exact revision、门槛数值与各阶段证据指针；任一漂移 = 记录失效、需重认证。
10. **有效期纯事件制**：不设时间过期，只被 ticket 07 撤销清单 revoke。
11. **J1 组合**：每阶段开始前一份 scoped live J1（精确 Profile、输入清单、次数、费用、时限，沿用 2026-08-17 试用格式）；周期末一份 production-policy J1（确认门槛达成并批准落记录）。证据 = 每阶段 A1（run ledger/execution-log 汇总）+ A2（N9/R1 evaluator 对 frozen corpus 独立重算）。

### Prohibited reuse set（确认）

禁止复用：N7 全部 authorization/ledger/identity/DAG（N7-04 immutable FAIL、N7-05 永久阻塞）、R5 `product-transform-authority-v2`、R5P `authority-v1`、R5P2 全部 evidence/identity、历史 product-v45 J1 与 ledger、`.scratch/visual-recognition-vnext-n7-closeout/issues/01..15` 与旧 R5P/R5P2 issue DAG。白名单可复用：`DocumentObservationIR/1.0`、N9/R1 evaluator/corpus 基础设施、ticket 01–08/14 决定与 2026-08-17 试用证据。

### 下游

- ticket 09/15 阻塞解除；《IMAGE_ONLY Profile Certification Contract》delta 起草与 v46 Profile 创建归最终 Blueprint handoff 后的执行阶段。
