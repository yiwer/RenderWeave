# RenderWeave 图片识别数据结构 vNext Goal 计划

- 状态：active
- 日期：2026-08-10
- 基线 revision：`eed1ab6ce2eb800b1b6bf0496b052fc3b9bd28d2`
- 分支：`phase/p6-visual-recognition-vnext`
- Spec delta：`specs/changes/20260810-visual-recognition-vnext.md`
- ADR：ADR-0022、ADR-0023
- 用户 J1：yiwer，2026-08-10；三模型各 500,000 total tokens，精确约束见 spec delta
- 当前节点：N0–N1 `automated_verified`；N2 `live_verified_mixed_a1_a2`；N3 next；全部 live ledger `CLOSED`

## 四维执行配置

```text
规模：project
自主：auto（goal 内已批准的实现与决策）；live 节点由精确 ledger 机器 fail-closed
风险：guarded（外部模型费用、模型资产、可能的 migration）；其余节点 standard
协作：single writer
```

能力协商：`tools/run-gate.ps1` 提供 A1 evidence capture；无 atomic claim，因此单写入者；用户已提供本轮
human blocking permission。N1 必须建立独立 evidence verifier 后才允许 live，仓库不存在 A3，不把本地
ledger 描述成外部强制门。

版本控制采用 `agent-commit`：每个 N0..N7 节点在受影响 gate 通过后独立提交；不创建 tag，不自动 push，
不 squash 掩盖节点恢复点。

## Phase / 节点

| 节点 | 可验证增量 | 主要门控 | 外部调用 |
|---|---|---|---|
| N0 | 批准 spec/ADR/DAG/J1 信封和恢复边界 | fast A1 | 0 |
| N1 | 60-case IMAGE_ONLY stage-gold、指标、可恢复 eval harness、跨账本 guard、独立 verifier | server + eval A1/A2 | 0 |
| N2 | 当前 product-v4 三模型同一 sentinel baseline，ledger 立即 CLOSED | pre-live + live evidence + verifier | 有界 |
| N3 | pipeline 4 local materializer、三模型 capability/Profile、旧 snapshot 兼容 | server + contract + PG recovery | 0 |
| N4 | multi-scale views、region/grounding 2.0、空间不变量、GENERIC/TRANSIT_BOARD Prompt | server + eval | 0 |
| N5 | OCR/layout adapter + pure/multiscale/hybrid 消融；固化达到门槛的默认 | server + runtime + eval | 有界 |
| N6 | semantic verifier、targeted repair、监控/审核 UI 和 E2E | server + web + e2e + runtime | 小 canary |
| N7 | 三模型 final eval、policy、full gate、独立 verifier 和 Goal 验收 | full A1 + verifier A2 + J1 | 有界 |

## 任务卡与完成信号

### N0：治理与执行闭环

- 状态：`automated_verified`；checkpoint：`plans/logs/P6-T6-5-N0.md`。
- AC：AC-VR-001..010 的计划覆盖；RULE-ANCHOR-001、RULE-REC-001。
- 影响区域：spec delta、ADR、计划、NOTES、CONSTITUTION。
- 局部验证：fast gate；diff/secret/route scope review。
- 完成信号：目标、非目标、预算、DAG、回退、证据等级和节点 commit 策略均可定位。

### N1：Stage-gold 与身份绑定评测底座

- 状态：`automated_verified`；checkpoint：`plans/logs/P6-T6-5-N1.md`。独立 verifier 已通过跨语言重算和篡改负例，真实 live evidence 的 A2 重算从 N2 开始。
- AC：AC-VR-001、002、010。
- 依赖：N0。
- 实现：
  - 版本化 scene spec + deterministic raster generator；60 cases、45 DEV/15 HOLDOUT；
  - gold inventory/region/entity/relationship/binding/Candidate；
  - stage evaluator、slice report、ECE/Brier、token/cost/latency；
  - journal/lease/batch≤5、跨 ledger per-model token/cost/attempt guard；
  - 独立 verifier 从不可变 journal 重算 assignment、usage、费用、指标和 payload-free 边界；
  - PROPOSED/CLOSED 负探针必须零 Provider、零 journal mutation。
- 局部验证：metric goldens、tamper/duplicate/resume/identity drift tests；server/eval gate。
- 完成信号：同一 fixture/report byte-stable；live gate 未 OPEN 时 attempts=0。

### N2：旧 v4 基线

- 状态：`live_verified_mixed_a1_a2`；checkpoint：`plans/logs/P6-T6-5-N2.md`；三模型全部 ledger `CLOSED`。
- AC：AC-VR-002、008、010。
- 依赖：N1；exact clean revision、Profile/corpus/evaluator identity；三份 PROPOSED ledger。
- 执行：三个模型使用同一 12-case sentinel（含 4 HOLDOUT），每批≤5；总用量计入 Goal 500k/model。
- 局部验证：先 OPEN 负探针与一 case canary，再逐批；每模型完成即 CLOSED；independent verifier 重建。
- 完成信号：已得到 element→hierarchy→binding→Candidate 各阶段真实 baseline；三模型 final pass 均为
  0/12，全部保持 `EXPERIMENTAL`。Max/Flash live evidence 为 A2；Plus continuation 为 A2、初始末态为 A1，
  聚合证据不夸大为完整 A2。

### N3：确定性 Candidate Materializer 与 capability matrix

- 状态：next；N2 已 CLOSED，节点内零外部调用。
- AC：AC-VR-003、008。
- 依赖：N2 CLOSED。
- 实现：pipeline 4、local materializer、保守 assessment、STRUCTURE zero-call invariant；三个 vNext Profile
  独立冻结 JSON mode/output/timeout/pricing/sampling；旧 v1..v4 资源字节不变。
- 局部验证：property/byte golden、malicious plan、UUID/reference/array/evidence、PG crash/recovery、adapter contract。
- 完成信号：validated plan 的 Candidate 不依赖 Provider；相同语义输入可稳定比较；baseline calls 4→3。

### N4：多尺度 Region Grounding 与 Prompt 去偏

- AC：AC-VR-004、006。
- 依赖：N3。
- 实现：overview/tile/crop transform、region graph、containment/repeat/order invariants；visual contracts v2；
  GENERIC core 和显式 TRANSIT_BOARD pack；task/checkpoint/profile identity 升级。
- 局部验证：坐标 property、边界/重叠/孤儿/环/重复不一致负例、站牌/菜单/价签 cross-domain regression。
- 完成信号：图片空间关系可被代码证伪；GENERIC Prompt 不包含公交领域词表。

### N5：OCR/Layout Grounding 与消融

- AC：AC-VR-005、010。
- 依赖：N4。
- 实现：`DocumentVisionPreprocessor` port、严格 bounded local adapter、启动 capability；原始文字内存使用并
  在 checkpoint/log/evidence 前消除；pure/full-image、multiscale、hybrid 三方案同 corpus 对比。
- 选择门：dense/small-text field recall +0.05、critical hallucination 不增加；否则 adapter 不成为默认。
- 局部验证：missing binary/model、timeout、malformed output、payload scan、坐标 transform、runtime canary。
- 完成信号：默认路径由报告而非偏好决定；不可用时 fail-readable 且不静默改变 Profile。

### N6：Semantic Verifier、Targeted Repair 与 UI

- AC：AC-VR-007、009。
- 依赖：N5。
- 实现：bounded verifier contract、issue→earliest-stage routing、selected crop request、successful-stage
  preservation、checkpoint/attempt taxonomy；监控和审核页展示 region/stage/issue/费用/恢复。
- 局部验证：false omission/edge/binding/extra field、mixed human blocker、crash/retry/cancel；Web component、
  1024 drawer、keyboard/axe、real PG Playwright。
- 完成信号：repair 不重做无关 stage；UI 不显示 OCR/Provider 原文；apply 仍是唯一 create-only 写边界。

### N7：Final Live Eval 与验收

- AC：AC-VR-001..010、既有 AC-015..021。
- 依赖：N6 clean gates；final exact identities 和新的 ledger；N2 用量已进入 aggregate guard。
- 执行：三个模型先跑同一 20-case comparison；按剩余额度优先让最佳模型完成 60-case/15 HOLDOUT；每批≤5。
- policy：只有满足既有 AC-021 和 stage 门槛的 Profile 可成为默认；其他保持 EXPERIMENTAL。
- 门控：server/web/e2e/runtime/full A1；独立 verifier A2；费用/Token/secret/payload scan；用户业务/视觉 J1。
- 完成信号：所有 ledger CLOSED、Goal guard 不超额、每条 AC 有结果和证据、最终 revision clean。

## Live 预算与停止条件

| 模型 | Goal cap | N2 consumed | 剩余 tokens | attempts | list-price CNY cap | N2 cost |
|---|---:|---:|---:|---:|---:|---:|
| qwen3.8-max | 500,000 | 304,043 | 195,957 | 54 / 180 | 18.00 | 6.406980 |
| qwen3.7-plus | 500,000 | 317,619 | 182,381 | 64 / 180 | 4.00 | 1.229322 |
| qwen3.7-flash | 500,000 | 291,784 | 208,216 | 56 / 180 | 0.40 | 0.106318 |

停止条件：任一 token/attempt/CNY cap、168h ledger expiry、Goal 完成、Provider refusal/Retry-After、identity
drift、journal/guard 不一致、payload 边界失败或同一无新假设失败再次出现。停止只关闭后续调用；已结算费用
不可恢复。

## 恢复与再锚定

- 源码：优先 revert 当前独立节点 commit；不 reset，不覆盖 main 或用户成果。
- 数据：migration forward-only；历史 run/Profile/checkpoint 不 UPDATE；新版本读取失败必须保留旧路径。
- 外部副作用：OPEN→CLOSED 原子停止；Goal aggregate guard 永不因新 ledger 清零；费用不声称可回滚。
- 每个 N0..N7 结束后，对照 approved spec delta、AC、非目标和用户原始意图执行再锚定；Template、
  Workspace、Renderer、多租户、任意工具能力仍是非目标。
