# RenderWeave 图片识别数据结构 vNext Goal 计划

- 状态：active（编排 Goal `019fec8e-a851-7952-b49b-8be76a281a57` 已恢复；未创建 replacement Goal）
- 日期：2026-08-10
- 基线 revision：`eed1ab6ce2eb800b1b6bf0496b052fc3b9bd28d2`
- 分支：`phase/p6-visual-recognition-vnext`
- Spec delta：`specs/changes/20260810-visual-recognition-vnext.md`
- ADR：ADR-0022、ADR-0023、ADR-0024、ADR-0025、ADR-0026、ADR-0027、ADR-0028、ADR-0029
- 用户 J1：yiwer，2026-08-10；2026-08-11 delta 将 Flash 改为 `qwen3.7-flash-2026-07-15` 并给三个
  预算槽位各追加 500,000 tokens，累计 cap 1,000,000；attempt/CNY/time 边界不变，精确约束见 spec delta
- 当前节点：N0–N1、N3–N4、N6 `automated_verified`；N2 `live_verified_mixed_a1_a2`；N5
  `live_verified_not_promoted`；N7 `in_progress`（pinned Flash/Plus reachability、Plus v14/v15/v16/v17/v18 smoke 已 A2，
  三阶段仍不可达；v15 bounded OBSERVE rewind、v16 evidence-derived cardinality 与 v17 exact relationship-region
  owner rewind、v18 detailed region repair taxonomy 与 v19 exact-duplicate support-ID normalization 实现已 clean A1，
  v18 live 仍停在 HIERARCHY，v19 尚未 live）；全部
  live ledger `CLOSED`

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

- 状态：`automated_verified`；checkpoint：`plans/logs/P6-T6-5-N3.md`；实现 `3d56c51`，节点内零外部调用。
- AC：AC-VR-003、008。
- 依赖：N2 CLOSED。
- 实现：pipeline 4、local materializer、保守 assessment、STRUCTURE zero-call invariant；三个 vNext Profile
  独立冻结 JSON mode/output/timeout/pricing/sampling；旧 v1..v4 资源字节不变。
- 局部验证：property/byte golden、malicious plan、UUID/reference/array/evidence、PG crash/recovery、adapter contract。
- 完成信号：已达成。validated plan 的 Candidate 不依赖 Provider；相同语义输入 byte-stable；正常路径
  calls 4→3；三份 v5 capability/Profile 保持内部 `EXPERIMENTAL`，未进入产品选择器。

### N4：多尺度 Region Grounding 与 Prompt 去偏

- 状态：`automated_verified`；checkpoint：`plans/logs/P6-T6-5-N4.md`；实现 `1400edb`，节点内零外部调用。
- AC：AC-VR-004、006。
- 依赖：N3。
- 实现：overview/tile/crop transform、region graph、containment/repeat/order invariants；visual contracts v2；
  GENERIC core 和显式 TRANSIT_BOARD pack；task/checkpoint/profile identity 升级。
- 局部验证：坐标 property、边界/重叠/孤儿/环/重复不一致负例、站牌/菜单/价签 cross-domain regression。
- 完成信号：图片空间关系可被代码证伪；GENERIC Prompt 不包含公交领域词表。

### N5：OCR/Layout Grounding 与消融

- 状态：`live_verified_not_promoted`；checkpoint：`plans/logs/P6-T6-5-N5.md`。实现、runtime、v6/v7
  repository-synthetic live evidence 与独立 verifier 已完成，选择门未通过，全部 ledger `CLOSED`。
- AC：AC-VR-005、010。
- 依赖：N4。
- 实现：`DocumentVisionPreprocessor` port、严格 bounded local adapter、启动 capability；原始文字内存使用并
  在 checkpoint/log/evidence 前消除；pure/full-image、multiscale、hybrid 三方案同 corpus 对比。
- 选择门：dense/small-text field recall +0.05、critical hallucination 不增加；否则 adapter 不成为默认。
- 局部验证：missing binary/model、timeout、malformed output、payload scan、坐标 transform、runtime canary。
- 完成信号：已达成。v4/v6/v7 同 case 报告判定 Hybrid 不晋级；v7 继续默认关闭、产品隐藏和
  `EXPERIMENTAL`。Plus Goal 暴露量 485,886/500,000 tokens，N5 当时停止调用；2026-08-11 J1 delta
  为 N7 追加 token 空间，但不改变该消融结论。

### N6：Semantic Verifier、Targeted Repair 与 UI

- 状态：`automated_verified`；checkpoint：`plans/logs/P6-T6-5-N6.md`。实现 revisions 为 `4290227`、
  `f0ebe77`、`d5afadf`；full audit selector 修复为 `de97131`；N7 实证驱动的 cross-stage verifier 增量为
  `195894b`，evidence-owned relationship cardinality 增量为 `bb15096`，exact relationship-region owner 增量为
  `31a8c6f`，detailed hierarchy region repair taxonomy 为 `4d2cc46`，exact-duplicate support-ID normalization 为
  `214fff9`。三轮 Flash 与后续 Plus 小 smoke
  均有 A2 诊断证据；最新 v18 仍停在 HIERARCHY，未触达完整三阶段，Profile 没有晋级。
- AC：AC-VR-007、009。
- 依赖：N5。
- 实现：bounded verifier contract、issue→earliest-stage routing、selected crop request、successful-stage
  preservation、checkpoint/attempt taxonomy；监控和审核页展示 region/stage/issue/费用/恢复。
- 局部验证：false omission/edge/binding/extra field、mixed human blocker、crash/retry/cancel；Web component、
  1024 drawer、keyboard/axe、real PG Playwright。
- 完成信号：已达成实现与自动门控。repair 不重做无关 stage；UI 不显示 OCR/Provider 原文；apply 仍是
  唯一 create-only 写边界。Flash v10/v11/v12 共 15 attempts 均停在 OBSERVE，故该状态不表示模型质量或
  三阶段合同已通过，所有相关 Profile 继续隐藏 `EXPERIMENTAL`。

### N7：Final Live Eval 与验收

- 状态：`in_progress`。2026-08-11 J1 delta 指定 pinned Flash，并把三个模型槽位的累计 token cap 提到
  1,000,000；immutable capability/Profile 与 v2 aggregate guard 已冻结。pinned Flash v13 与用户重新允许的
  Plus v12 单 case reachability、Plus v14/v15/v16/v17/v18 hierarchy smoke 均已 CLOSED/A2，但仍未触达 BINDING；
  v15 bounded OBSERVE rewind、v16 evidence-derived cardinality 与 v17 exact relationship-region owner rewind
  以及 v18 detailed hierarchy region repair taxonomy、v19 exact-duplicate support-ID normalization 实现已 clean A1。
- AC：AC-VR-001..010、既有 AC-015..021。
- 依赖：N6 clean gates；final exact identities 和新的 ledger；N2 用量已进入 aggregate guard。
- 执行：已先以 `qwen3.7-flash-2026-07-15`、再以 `qwen3.7-plus` 做单 case reachability；Flash 停在
  OBSERVE，Plus v12/v14/v15/v16/v17/v18 均到 HIERARCHY 后停止。v15 已把 OBSERVE 推进到 1 GROUP，但 HIERARCHY 连续
  两次 cardinality mismatch；`bb15096` 已用 pipeline 4.3/product-v16 把 relationship cardinality 唯一派生自
  已验证 GROUP multiplicity，并保持旧 4.1 严格语义。Plus v16 live 不再出现 mismatch，但以 1 GROUP 支撑多个
  relationships 而三次被拒。count-based rewind 因不能区分上游遗漏与多余 relationship 已否决；`31a8c6f`
  pipeline 4.4/product-v17 只在 relationship exact GROUP/REPEATED_GROUP region 缺 observed GROUP owner 时回到
  OBSERVE，已有 owner 的 GROUP reuse 仍留在 HIERARCHY。Plus v17 已完成受控 lifecycle：OBSERVE 接受
  20 SLOT/1 GROUP，HIERARCHY 三次 region ownership invalid 后一次 support not group，exact-owner rewind 未触发。
  `4d2cc46` 已为新 pipeline 4.5/product-v18 把 region ownership 拆成六类固定码，并为 support failure 补齐
  stage-local repair 指令；旧 v17 行为不变。Plus v18 已受控 CLOSED/A2：OBSERVE 接受 9 SLOT/0 GROUP，随后四次
  HIERARCHY 均为 relationship support IDs invalid。`214fff9` 的 pipeline 4.6/product-v19 已把该失败拆为
  missing/empty/limit/invalid 固定码，并只对同一有效 ID 的精确重复做 stable dedupe；不同 ID、缺 GROUP 与结构
  选择继续 fail-closed。下一安全切片是重新计算 identity/snapshot 后执行 Plus v19 单 case lifecycle；只有 live 实际触达
  BINDING 后才可考虑 Max 和同一 20-case comparison，再按剩余额度优先让最佳模型完成
  60-case/15 HOLDOUT；每批≤5。旧 Flash 不再创建 assignment，Plus/Max model ID 不变。
- policy：只有满足既有 AC-021 和 stage 门槛的 Profile 可成为默认；其他保持 EXPERIMENTAL。
- 门控：server/web/e2e/runtime/full A1；独立 verifier A2；费用/Token/secret/payload scan；用户业务/视觉 J1。
- 当前证据：guard/Profile `252dc00`、runner slot 修复 `0d7b73c`；七份单 case live 独立 verifier PASS；
  hierarchy repair `98ba3d0`、OBSERVE rewind `195894b` 与 evidence-derived cardinality `bb15096` 均已通过
  A1；`31a8c6f` 的 clean evidence 为 fast `20260811-050115`，受影响 server `20260811-045814`、web
  `20260811-045937`、E2E `20260811-050010`；v17 CLOSED clean fast 为 `20260811-051247`。它们不能替代 live
  三阶段。`4d2cc46` 的 server `20260811-052610`、web `20260811-052610`、E2E `20260811-052745` 与 clean fast
  `20260811-052853` 也均为 A1；v18 CLOSED clean fast 为 `20260811-053811`。`214fff9` 的 server
  `20260811-055056`、web `20260811-055228`、E2E `20260811-055259` 与提交后 clean fast
  `20260811-055541` 均为 A1，Provider attempts=0。这些证据不能替代 v19 live、final eval、final identity A2 或
  用户业务/视觉 J1。
- 完成信号：所有 ledger CLOSED、Goal guard 不超额、每条 AC 有结果和证据、最终 revision clean。目前未达成。

## Live 预算与停止条件

| 模型 | Goal cap | Goal exposed tokens | 剩余 tokens | attempts | list-price CNY cap | Goal cost |
|---|---:|---:|---:|---:|---:|---:|
| qwen3.8-max | 1,000,000 | 428,816 | 571,184 | 73 / 180 | 18.00 | 9.204720 |
| qwen3.7-plus | 1,000,000 | 667,874 | 332,126 | 116 / 180 | 4.00 | 2.714632 |
| Flash slot（旧 alias + `qwen3.7-flash-2026-07-15`） | 1,000,000 | 428,373 | 571,627 | 76 / 180 | 0.40 | 0.190286 |

停止条件：任一 token/attempt/CNY cap、168h ledger expiry、Goal 完成、Provider refusal/Retry-After、identity
drift、journal/guard 不一致、payload 边界失败或同一无新假设失败再次出现。停止只关闭后续调用；已结算费用
不可恢复。

## 恢复与再锚定

- 源码：优先 revert 当前独立节点 commit；不 reset，不覆盖 main 或用户成果。
- 数据：migration forward-only；历史 run/Profile/checkpoint 不 UPDATE；新版本读取失败必须保留旧路径。
- 外部副作用：OPEN→CLOSED 原子停止；Goal aggregate guard 永不因新 ledger 清零；费用不声称可回滚。
- 编排状态：2026-08-11 接管后的初次 `get_goal` 曾与 handoff 漂移；当前编排 Goal
  `019fec8e-a851-7952-b49b-8be76a281a57` 已恢复为 active。没有创建 replacement Goal，仍以本计划、
  不可变 ledger/evidence、git history 和 checkpoint 共同再锚定。
- 每个 N0..N7 结束后，对照 approved spec delta、AC、非目标和用户原始意图执行再锚定；Template、
  Workspace、Renderer、多租户、任意工具能力仍是非目标。
