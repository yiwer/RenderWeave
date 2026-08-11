# RenderWeave 图片识别数据结构 vNext Goal 计划

- 状态：in_progress（编排 Goal `019fec8e-a851-7952-b49b-8be76a281a57` 因 turn interrupt 当前显示
  `paused`；用户已在本轮明确继续同一目标，未创建 replacement Goal）
- 日期：2026-08-10
- 基线 revision：`eed1ab6ce2eb800b1b6bf0496b052fc3b9bd28d2`
- 分支：`phase/p6-visual-recognition-vnext`
- Spec delta：`specs/changes/20260810-visual-recognition-vnext.md`
- ADR：ADR-0022、ADR-0023、ADR-0024、ADR-0025、ADR-0026、ADR-0027、ADR-0028、ADR-0029、ADR-0030
- 用户 J1：yiwer，2026-08-10；2026-08-11 delta 将 Flash 改为 `qwen3.7-flash-2026-07-15`，随后两次给
  三个预算槽位各追加 500,000 tokens，当前累计 cap 1,500,000，并把 Flash/Plus Goal cost cap 各设为 ¥10、
  固定 24h 窗口至 `2026-08-12T09:51:55Z`；Max ¥18、每槽 180 attempts、单 authorization 500,000 不变。
  2026-08-12 用户又把当前产品 Flash 精确模型改为 `qwen3.7-flash`；该 alias 仍聚合进原 Flash 稳定槽位，
  不重置 cap 或消费，本节点不执行 live
- 当前节点：N0–N1、N3–N4、N6 `automated_verified`；N2 `live_verified_mixed_a1_a2`；N5
  `live_verified_not_promoted`；N7 `in_progress`。pipeline 4.27/product-v40 已完成 bounded diagnostic、Prompt/Profile、
  real-PG recovery、monitor/review/E2E 与 Flash 失败闭环；Goal 为 418 reservations（412 SETTLED、6 RESERVED、
  0 BREACHED），Flash/Plus/Max 为 157/179/82 attempts 与 1,148,324/1,087,500/491,919 exposed tokens，三份
  live ledger `CLOSED`。新建产品入口已切换为 Plus/Max/通用 Flash 三份 `EXPERIMENTAL` v40 Profile，Plus
  默认、Max 面向高难嵌套、Flash 仅作 smoke，并在创建/retry 前按 Profile 精确检查本地 Document Vision
  capability。该入口只声明阶段性工程可用，不冒充生产级可靠性或识别质量验收

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
  `214fff9`，unique evidence-owned relationship region normalization 为 `391bd52`，unique connected relationship
  region normalization 为 `dda763c`，unique exact-region GROUP-owner support normalization 为 `edc0c28`；
  leaf-evidence verifier / v25 workflow / UI telemetry 为 `f8f09b4`、`2b6eb9c`、`6cb2624`；unique
  enclosing-connected GROUP-owner normalization / v26 UI telemetry 为 `d3b0292`、`5ef25bd`；v28 minimal entity
  ownership verifier/Profile/UI 为 `76a0635`、`a96fec1`、`6a8a36f`。三轮 Flash 与后续 Plus 小
  smoke 均有 A2 诊断证据；`e13bf0c` 的 v23 在 4.9 policy 上叠加一次性 ephemeral Document Vision。Plus/Max v22
  均到达 BINDING，但报告不完整、质量未达门且未命中 v22 normalization；Flash v23 五次均在 OBSERVE 因
  region-kind/parent-kind 拒绝。`061101f` 的 v24 只对 documented kind alias、唯一 exact ITEM parent 与受影响
  readingOrder 做 bounded normalization；Max v24 虽完成三阶段，仍有 0 matched GROUP/relationship，Profile 没有晋级。
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

- 状态：`in_progress`。2026-08-11 J1 delta 指定 pinned Flash，并两次把三个模型槽位各增加 500,000 token，
  当前累计 cap 1,500,000；immutable capability/Profile 与 `2b23617` 的 v3 aggregate guard 已冻结。pinned Flash
  v13 与用户重新允许的
  Plus v12 单 case reachability、Plus v14–v22 与 Max v22 smoke 均已 CLOSED/A2；两模型 v22 均触达 BINDING，
  但 final 质量未通过；
  v15 bounded OBSERVE rewind、v16 evidence-derived cardinality 与 v17 exact relationship-region owner rewind
  以及 v18 detailed hierarchy region repair taxonomy、v19 exact-duplicate support-ID normalization、v20 unique
  evidence-owned relationship region normalization、v21 unique connected relationship region normalization、v22
  unique exact-region GROUP-owner support normalization、v23 support-owner hybrid observation、v24 bounded
  observation normalization、v25 leaf-evidence OBSERVE verifier 与 v26 unique enclosing-connected GROUP-owner
  normalization 已 clean A1；Plus/Max v22 live 均未命中 hierarchy normalization。Flash v23/v24/v25/v26 已
  CLOSED/A2 且未通过 OBSERVE；Plus/Max v24 已 CLOSED/A2 并触达 BINDING，但质量未达门。Plus v25 已
  CLOSED/A2，在 HIERARCHY 停止且 leaf-evidence 固定码未命中；Max v25 因新假设信号/三阶段门未成立而未调用。
  Flash/Plus v26 也已 CLOSED/A2，分别止于 OBSERVE/HIERARCHY，enclosing-owner telemetry 未命中；Max v26 因
  同版本三阶段门未成立而未调用。product-v27 的 local/PG/UI 与 `47f622b` clean full 已 A1；随后 Flash v27b、
  Plus v27、Max v27 均 CLOSED/A2。Flash 仍止于 OBSERVE，Plus/Max 三阶段可达但 slot/binding 0 matched，三模型
  都未命中 source-ancestor telemetry，因此不进入 final 20/60。product-v28/v29/v30 的 offline/real-PG/UI
  contract 与受控 live 均已闭环；最新 v30 Flash 五次、Plus 一次调用都在 OBSERVE fail-closed，未命中
  evidence-owner normalization。Max v30 因同版本三阶段门失败而未调用；下一节点只能先形成新的 bounded
  payload-free fixed-code 假设。
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
  选择继续 fail-closed。Plus v19 已受控 CLOSED/A2：OBSERVE 接受 13 SLOT/1 GROUP，随后四次 HIERARCHY 均为
  relationship region/cardinality invalid，未进入 BINDING，也未命中 normalization telemetry。相同 v19 不再重复；
  `391bd52` 的 pipeline 4.7/product-v20 只在唯一支撑 GROUP 恰有一个 cardinality-compatible owned region 时
  确定性归一化；零个回 OBSERVE，多个继续 fail-closed，旧 Profile 保持 strict。Plus v20 已受控 CLOSED/A2：
  OBSERVE 接受 12 SLOT/1 GROUP，HIERARCHY 依次为 support-not-group、两次 relationship-region-connection-invalid
  与 support-IDs-empty，未进入 BINDING，也未命中 normalization telemetry。相同 v20 不再重复；下一安全切片是
  `dda763c` 的 pipeline 4.8/product-v21：仅当唯一 GROUP-owned region 同时满足 cardinality 与 parent/child
  connection 时才确定性归一化，零/多 combined 候选继续 fail-closed；local A1 已通过。下一步只在 fresh
  identity/Profile snapshot 与精确 ledger 下做 Plus product-v21 单 case。该 lifecycle 已 CLOSED/A2：OBSERVE
  在两次拒绝后接受，随后两次 HIERARCHY 都是 support-not-group，未进 BINDING、未命中 normalization telemetry。
  相同 v21 不再重复；`edc0c28` 的 pipeline 4.9/product-v22 只在单个已知非 GROUP support、exact known
  GROUP/REPEATED_GROUP region 与唯一 observed GROUP owner 同时成立时确定性替换 support ID。未知 support、
  非容器、零/多 owner 保留原 fixed code；不跨 GROUP、不排序、不改 topology。local A1 已通过。Plus v22
  lifecycle `6f65516` PROPOSED → `2d396e7` OPEN → `4f86456` CLOSED 已由独立 verifier A2 PASS：5 attempts、
  26,943 actual tokens；第二次 OBSERVE accepted，HIERARCHY 一次拒绝后 accepted，ELEMENT_BINDING accepted，
  首次建立 live 三阶段可达性。该次只命中 cardinality-derived telemetry，未命中 support-owner normalization；
  report `complete=false` 且结构/绑定匹配远未达标，不能视为 Profile 晋级。Max 随后在 fresh identity/Profile
  snapshot 与精确 J1 ledger 下完成 `e0b1d67` PROPOSED → `740d28f` OPEN → `99efc6b` CLOSED。唯一 Provider
  wrapper 的测试主体写出 3 个 SETTLED attempts；外层 PowerShell 因 Mockito stderr warning 未返回 Maven 摘要，
  检查无存活子进程和完整 evidence 后立即 CLOSED，未重跑。独立 verifier A2 PASS：14,481 tokens、¥0.249684、
  61,032 ms；三个阶段均一次 accepted，却产生 0 GROUP/relationship、0/10 binding match、tree edit 30/32，
  也未命中 v22 normalization。相同 Max v22 不再重复；20-case comparison 前先用零 Provider 本地切片收窄
  repeated-group/relationship omission。`e13bf0c` 已形成 pipeline 4.10/product-v23：复用 4.9 全部 bounded policy，
  并把精确绑定的本地 OCR/layout observation 作为三个阶段共享的 ephemeral secondary evidence；不保存 OCR payload，
  不由本地代码补造结构。Flash v23 lifecycle `0c1506f` PROPOSED → `9652837` OPEN → `1185890` CLOSED 已由
  独立 verifier A2 PASS：5 attempts、44,632 tokens、178,163 ms、payload scan PASS；三次 region-kind 与两次
  parent-kind 固定码使全部 attempts 停在 OBSERVE，实际结构计数全为 0。外层 wrapper 因 Mockito stderr warning
  失败后按 process/evidence lease 恢复，确认 5 SETTLED、无子进程后关闭且未重跑。相同 v23 不再重复。
  `061101f` 新增 pipeline 4.11/product-v24：只归一化 `DOCUMENT→ROOT`、`CONTAINER→GROUP`、允许枚举大小写，
  并仅在同 artifact/包含 bbox/精确 repeatGroupId 候选唯一时修复 ITEM parent；受影响 readingOrder 由几何确定。
  未知 alias、零/多候选与结构增删继续 fail-closed。其 clean A1 与 Flash/Plus/Max 单 case A2 已通过；Flash
  仍停在 OBSERVE，Plus/Max 虽到 BINDING 但 stage-gold 质量未达门，三模型都未命中 observation normalization。
  `f8f09b4` / `2b6eb9c` / `6cb2624` 的 pipeline 4.12/product-v25 leaf-evidence rule 已 clean A1；Flash v25
  `a7a2a7f`→`a9635e4`→`edb35bc` 与 Plus v25 `06cef12`→`34e7ab3`→`e93d1f7` 均 CLOSED/A2，但新规则未
  命中，Plus 在 HIERARCHY 停止，故没有调用 Max v25。`d3b0292` / `5ef25bd` 的 pipeline 4.13/product-v26
  仅在 GROUP/region 配对唯一、包围全部 support element regions、cardinality-compatible 且连接 parent/child
  时归一化 support owner；zero/multiple 保持 fail-closed，local/PG/UI gates 已 A1。Flash v26b lifecycle
  `36a7a9e`→`ef6440f`→`b976e5f` 与 Plus v26 `3f95ae3`→`67f33dd`→`31093a6` 已 CLOSED/A2；前者 5 次
  停在 OBSERVE，后者 OBSERVE accepted 后 3 次停在 HIERARCHY，均未命中新 telemetry。Max v26 不调用。下一步
  先离线验证 unique validated ancestor-GROUP owner；没有新 bounded 信号前不进入 20-case 与最佳模型
  60-case/15 HOLDOUT。
  旧 Flash 不再创建 assignment，Plus/Max model ID 不变。
- policy：只有满足既有 AC-021 和 stage 门槛的 Profile 可成为默认；其他保持 EXPERIMENTAL。
- 门控：server/web/e2e/runtime/full A1；独立 verifier A2；费用/Token/secret/payload scan；用户业务/视觉 J1。
- 当前证据：guard/Profile `252dc00`、runner slot 修复 `0d7b73c`、guard v3 `2b23617`；二十三份 Provider-backed single-case live
  独立 verifier PASS；
  hierarchy repair `98ba3d0`、OBSERVE rewind `195894b` 与 evidence-derived cardinality `bb15096` 均已通过
  A1；`31a8c6f` 的 clean evidence 为 fast `20260811-050115`，受影响 server `20260811-045814`、web
  `20260811-045937`、E2E `20260811-050010`；v17 CLOSED clean fast 为 `20260811-051247`。它们不能替代 live
  三阶段。`4d2cc46` 的 server `20260811-052610`、web `20260811-052610`、E2E `20260811-052745` 与 clean fast
  `20260811-052853` 也均为 A1；v18 CLOSED clean fast 为 `20260811-053811`。`214fff9` 的 server
  `20260811-055056`、web `20260811-055228`、E2E `20260811-055259` 与提交后 clean fast
  `20260811-055541` 均为 A1，Provider attempts=0；v19 CLOSED clean fast 为 `20260811-060633`。`391bd52` 的
  server `20260811-062224`、web `20260811-062433`、E2E `20260811-062513` 与 clean fast `20260811-062623`
  均为 A1，Provider attempts=0。v20 lifecycle 为 `7afda44` PROPOSED → `191cf63` OPEN → `85b2000` CLOSED，
  独立 verifier A2 PASS，CLOSED clean fast 为 `20260811-063705`。`dda763c` 的 server `20260811-064835`、
  web `20260811-065005`、E2E `20260811-065038` 与 clean fast `20260811-065134` 均为 A1，Provider attempts=0。
  v21 lifecycle 为 `405fa9e` PROPOSED → `d793c92` OPEN → `02872c5` CLOSED，独立 verifier A2 PASS，
  CLOSED clean fast 为 `20260811-070414`。`edc0c28` 的 targeted 36/36、independent verifier 2/2、real-PG
  lease-expiry 1/1、server `20260811-071714`、Node 24 web `20260811-071856`、E2E `20260811-071927` 与
  clean fast `20260811-072030` 均为 A1 PASS，Provider attempts=0。v22 lifecycle 为 `6f65516` PROPOSED →
  `2d396e7` OPEN → `4f86456` CLOSED，独立 verifier A2 PASS，CLOSED clean fast 为 `20260811-073017`。
  Max v22 lifecycle 为 `e0b1d67` PROPOSED → `740d28f` OPEN → `99efc6b` CLOSED，独立 verifier A2 PASS，
  CLOSED clean fast 为 `20260811-074402`；外壳摘要失败后按 process/evidence lease 恢复且未重跑。
  `e13bf0c` 的 v23 Profile/能力合同 1/1、real-PG hybrid/payload/support-owner 与独立 verifier 2/2、clean fast
  `20260811-075518`、server `20260811-075612`（192 tests、6 gated skip）均为 A1 PASS，Provider attempts=0；
  v23 Document Vision canary `20260811-080747` PASS，lifecycle 为 `0c1506f` → `9652837` → `1185890`，A2
  PASS。`061101f` 的 v24 contract/Profile 20/20、real-PG + independent verifier 2/2、clean server
  `20260811-082418`（193 tests、6 gated skip）A1 PASS；v24 lifecycle 为 `9d2dfa3` → `ded9e78` → `ac17e0e`，
  Document Vision canary `20260811-084304`、独立 verifier A2 与 CLOSED clean fast `20260811-085018` PASS；Plus
  v24 lifecycle 为 `3598c12` → `963d1e6` → `4747947`，复用未变 canary 输入，独立 verifier A2 与 CLOSED
  clean fast `20260811-085833` PASS；Max v24 lifecycle 为 `a04691e` → `b8ac358` → `57b1502`，复用未变
  canary 输入，独立 verifier A2 与 CLOSED clean fast `20260811-091152` PASS。v25 Flash lifecycle 为
  `a7a2a7f`→`a9635e4`→`edb35bc`，Plus lifecycle 为 `06cef12`→`34e7ab3`→`e93d1f7`，两者独立 verifier
  A2 PASS；恢复后的 Document Vision canary 为 `20260811-094753`，CLOSED fast 为 `20260811-100713`。
  `d3b0292` / `5ef25bd` 的 v26 server `20260811-101733`、Node 24 web `20260811-101734`、E2E
  `20260811-101948` 与 runtime `20260811-102032` 均 A1 PASS；revision `371505b` 的隔离 clean full
  `20260811-102845` 为 9/9 steps A1 PASS，Provider attempts=0。Flash v26 的第一次预检因 CRLF corpus identity
  drift 在 Provider/evidence/Goal mutation 前 fail-closed，不计 live report；LF clean identity 下 Flash v26b 与 Plus
  v26 lifecycle 分别为 `36a7a9e`→`ef6440f`→`b976e5f`、`3f95ae3`→`67f33dd`→`31093a6`，独立 verifier
  A2 PASS。三份 ledger CLOSED。该 full gate 早于 live/final
  eval，仍需在最终 revision 重跑；这些证据不能
  替代 final eval、final identity A2 或
  用户业务/视觉 J1。
- 完成信号：所有 ledger CLOSED、Goal guard 不超额、每条 AC 有结果和证据、最终 revision clean。目前未达成。

## Live 预算与停止条件

| 模型 | Goal cap | Goal exposed tokens | 剩余 tokens | attempts | list-price CNY cap | Goal cost |
|---|---:|---:|---:|---:|---:|---:|
| qwen3.8-max | 1,500,000 | 491,919 | 1,008,081 | 82 / 180 | 18.00 | 10.289316 |
| qwen3.7-plus | 1,500,000 | 1,021,208 | 478,792 | 170 / 180 | 4.00 | 3.903838 |
| Flash slot（旧 alias + `qwen3.7-flash-2026-07-15`） | 1,500,000 | 815,516 | 684,484 | 120 / 180 | 0.40 | 0.392962 |

Goal guard v3 共 372 reservations：367 SETTLED、5 个历史 Plus RESERVED；没有 BREACHED。`2b23617` 只提高
token cap，单 authorization 500,000、attempt/CNY cap 与所有历史 reservation 不变。v32 Plus smoke 后
三份 visual ledger 均 `CLOSED`。Flash/Plus 剩余 Goal 费用均低于当前标准 OBSERVE reservation；Max 仍受
同版本 live 三阶段及质量/J1 前置约束。

停止条件：任一 token/attempt/CNY cap、168h ledger expiry、Goal 完成、Provider refusal/Retry-After、identity
drift、journal/guard 不一致、payload 边界失败或同一无新假设失败再次出现。停止只关闭后续调用；已结算费用
不可恢复。

## 恢复与再锚定

- 源码：优先 revert 当前独立节点 commit；不 reset，不覆盖 main 或用户成果。
- 数据：migration forward-only；历史 run/Profile/checkpoint 不 UPDATE；新版本读取失败必须保留旧路径。
- 外部副作用：OPEN→CLOSED 原子停止；Goal aggregate guard 永不因新 ledger 清零；费用不声称可回滚。
- 编排状态：2026-08-11 接管后的初次 `get_goal` 曾与 handoff 漂移；恢复后又因 turn interrupt 显示
  `paused`。用户已明确继续同一 objective；没有创建 replacement Goal，仍以本计划、不可变 ledger/evidence、
  git history 和 checkpoint 共同再锚定。
- 每个 N0..N7 结束后，对照 approved spec delta、AC、非目标和用户原始意图执行再锚定；Template、
  Workspace、Renderer、多租户、任意工具能力仍是非目标。

## 2026-08-11 v27 source-ancestor checkpoint

- 实现：`676180a` 只在 v26 enclosing candidate 为零时，从已验证原始 relationship source region 的祖先链寻找
  唯一 GROUP owner；仍要求 cardinality 与 parent/child connection，零/多候选 fail-closed，不读取 OCR/model
  text/gold、不排名、不改 topology。`e1f1a9d` 接入 pipeline 4.14、三模型 immutable Profile、worker/checkpoint、
  real-PG tracer 与独立 verifier；`3a56af9` 接入 payload-free monitor/review UI 和 E2E。
- 定向证据：codec 22/22、registry 1/1、real-PG 1/1、独立 verifier 2/2；Node 24 Web 73/73，受影响
  Playwright 1/1。真实 PostgreSQL 流程达到 `REVIEW_REQUIRED`，四个 bounded normalization telemetry 精确命中，
  OCR sentinel 未进入 checkpoint/Candidate/problems。
- 治理修复：首轮 server `.sdlc/evidence/20260811-113055-server` 暴露 5 参数 default reservation 入口的事务
  代理绕过；`5ada0fa` 显式覆写并在事务内串行预算行锁，针对性并发回归 10/10。修复后 server
  `.sdlc/evidence/20260811-113412-server`、web `.sdlc/evidence/20260811-113607-web`、E2E
  `.sdlc/evidence/20260811-113652-e2e`、runtime `.sdlc/evidence/20260811-113726-runtime` 全部 A1 PASS。
- 状态与预算：本节点 Provider attempts=0；Max 79 / 465,016 tokens / ¥9.816288，Plus 150 / 883,569 /
  ¥3.436302，Flash slot 95 / 598,343 / ¥0.280418；324 reservations（319 SETTLED、5 历史 Plus RESERVED、
  0 BREACHED），三份 ledger CLOSED。所有 product-v27 Profile 仍为 `EXPERIMENTAL`。
- 当时下一门（已完成，见下一节）：先在隔离 clean worktree 为本治理 revision 完成 full gate。之后每次 live 前重新计算 evaluation
  identity、Profile snapshot 与 aggregate budget；Flash 单 case 优先，Plus 按新信号决定，Max 仅在同版本三阶段
  与质量/J1 门成立时考虑。final 20/60、最终 verifier、最终 revision full 与业务/视觉 J1 均仍未完成。

## 2026-08-11 v27 live checkpoint

- clean full：revision `47f622b` 的 detached LF worktree 9/9 steps A1 PASS，evidence 为
  `D:\Yiwer\code\RenderWeave-v27-full-47f622b\.sdlc\evidence\20260811-114304-full`；它早于 live/final eval，
  不冒充最终 revision release gate。
- identity/Profile：Java 与独立 Python 一致得到 tree identity `…960c965`；Flash/Plus/Max snapshot 为
  `a0e159…128394`、`e0ed97…b6a94c`、`8c083d…a77624`。每个 lifecycle 均重新核算 Goal 最坏 reservation、
  先 PROPOSED 负探针，再 OPEN、单 wrapper、CLOSED；没有并发重跑。
- Flash 首个 v27 lifecycle `7cf9709`→`ac63bc9`→`d1c076e` 因漏传 Document Vision Spring 属性在 Provider 前
  以 `DOCUMENT_VISION_DISABLED` 结束，0 attempts/0 token，未重开。canary `20260811-115701-document-vision`
  重验 pinned runtime 后，v27b `6185570`→`ea9cb87`→`a473d2f` 完成 5 attempts；全部停在 OBSERVE。
- Plus v27 `ccfce3b`→`7f49117`→`854d652` 与 Max v27 `1fa1ccf`→`705ccff`→`95be8fa` 均以 3 attempts
  完成 OBSERVE/HIERARCHY/BINDING，但 slot/binding matched 均为 0；Plus 为 7 blockers/4 critical，Max 为
  26 blockers/27 critical。三模型都没有命中 source-ancestor telemetry。
- 最终三份 current evidence 交叉独立 verifier A2 PASS、0 abandoned、payload scan PASS；335 reservations 为
  330 SETTLED + 5 历史 Plus RESERVED，0 BREACHED。CLOSED fast 环境红灯 `20260811-121310` 保留；依赖联接后
  `20260811-121335` PASS。
- 决策：N7 继续 `in_progress`，所有 product-v27 Profile 继续隐藏 `EXPERIMENTAL`。当前没有足够信号扩大
  20/60-case final eval；下一增量必须来自新的 bounded fixed-code hypothesis，并先完成离线/真实 PG/受影响 gate。
  final eval、最终 revision full、final independent verifier 与业务/视觉 J1 仍是 Goal 完成硬门。

## 2026-08-11 Git-blob evaluation identity `/2` checkpoint

- 实现：`cded69e` 默认生成 `renderweave-visual-evaluation-tree-sha256/2`，按 UTF-8 path bytes 排序并 framing
  regular Git mode 与 canonical blob bytes；使用 index OID + `git cat-file --batch`，不再读取 checkout 换行表示
  作为新身份内容。dirty/untracked、assume-unchanged/skip-worktree、non-regular/missing input 与双捕获漂移均拒绝。
- 兼容：Java executor 只允许 `/2` OPEN；`/1` 只可作为 CLOSED 历史 ledger 加载。独立 Python verifier 依
  identity prefix 选择完全独立的 `/1` 或 `/2` 重算，不重写历史 evidence、不降低 clean-tree/profile/budget 检查。
- 证据：clean LF/CRLF 双 checkout、mode drift 与 hidden-index 负例通过；exact-clean `cded69e` 的 Java/Python
  `/2` 同为 `fc46a428…b5a7bf`。server `.sdlc/evidence/20260811-123055-server`、fast
  `.sdlc/evidence/20260811-123245-fast` A1 PASS；Flash v27b、Plus v27、Max v27 的真实 CLOSED `/1` evidence
  用新 verifier 回放均 PASS、0 abandoned、payload scan PASS。
- 状态：本节点 Provider attempts=0，Goal 用量与 335 reservations 不变，三份 ledger CLOSED。identity 治理债务
  已关闭，但 v27 quality gate 未改变；N7 继续 `in_progress`，下一步仍须先形成新的 bounded、payload-free
  fixed-code hypothesis，不能直接扩大 final eval。

## 2026-08-11 v28 minimal entity ownership checkpoint

- 实现：`76a0635` 以 opt-in policy 拒绝非根 entity 拥有 ROOT、同一 entity 同时拥有祖先/后代 region，并要求
  field evidence 只有一个最小 spatial entity owner；旧 Profile 不变。`a96fec1` 发布 pipeline 4.15 与三模型
  immutable product-v28 Profile。binding ambiguity 固定返回最早 HIERARCHY，checkpoint 保留 OBSERVE inventory/
  grounding、清空 hierarchy/binding；store 只对白名单精确单码允许该逆向 transition。
- 验证：verifier/codec 27/27、inference 180/180、真实 PostgreSQL repair tracer 2/2、独立 Profile snapshot
  1/1 PASS。`6a8a36f` 接入 monitor/review 固定码；Node 24 Web gate 73/73、typecheck/lint/build PASS，evidence
  `.sdlc/evidence/20260811-125512-web`，隔离端口 Playwright 1/1 PASS，payload sentinel 不可见。
- 治理：本增量 Provider attempts=0，335 reservations 与模型累计用量不变，三份 ledger CLOSED。每模型累计 cap
  为 1,500,000，单 authorization 500,000 与 attempts/CNY/time 硬门不变。
- 下一门：先对含文档 checkpoint 的 clean revision 跑 full gate，再重新计算 `/2` identity、三模型 Profile
  snapshot、aggregate budget 与时限。Flash 优先单 case/最多 5 calls；Plus 依 Flash 新信号决定；Max 仅在 v28
  三阶段合同可达且质量/J1 门仍有效时考虑。未满足 final 20/60、final full、final independent verifier 与业务/
  视觉 J1 前，N7 保持 `in_progress`，product-v28 保持 `EXPERIMENTAL`。

## 2026-08-11 v28 full 与 live checkpoint

- clean gate：`0a3b90b` 的 detached full 9/9、`workingTreeDirty=false`，evidence
  `.sdlc/evidence/20260811-125916-full`；Document Vision 19-line canary
  `.sdlc/evidence/20260811-130940-document-vision`。fresh Java/Python `/2` identity 一致为 `…c669d172`，
  三份 product-v28 Profile snapshot 精确匹配。
- Flash：CRLF preflight lifecycle `54b4e9d`→`7a4778d`→`61088f4` 在 Provider 前因 corpus checkout bytes
  漂移关闭；LF replacement `701f774`→`10789d8`→`c39672f` A2 PASS，5 attempts / 44,335 tokens，全部
  OBSERVE rejected，未形成结构。
- Plus：首个 `0f8a0dd`→`6668708`→`4215a14` 因调用侧 timeout=120 超过 60 秒合同而在 Provider 前以
  `DOCUMENT_VISION_TIMEOUT_INVALID` 完成，0 attempts；独立 v28b `2a69291`→`fcd3abc`→`4add041` 使用合法
  60 秒边界，A2 PASS，5 attempts / 36,204 tokens。OBSERVE accepted 后，三次 HIERARCHY 均由
  `VISUAL_HIERARCHY_V2_RELATIONSHIP_REGION_CARDINALITY_INVALID` 拒绝，BINDING 未执行，slot 仅 1/10 matched。
- 停止门：同版本三阶段与质量门未成立，Max CLOSED 且未调用。Goal 为 345 reservations（340 SETTLED、
  5 历史 Plus RESERVED、0 BREACHED）；Flash/Plus/Max 分别为 105/158/82 attempts 与
  685,591/936,770/491,919 tokens。下一安全节点是针对同一 HIERARCHY fixed code 的 bounded、payload-free
  stage-local repair/no-progress 假设；先离线与真实 PG，不能直接 final 20/60。N7/Goal 保持 `in_progress`，
  Profile 保持 `EXPERIMENTAL`。

## 2026-08-11 v29 bidirectional group-region cardinality checkpoint

- 假设：v28 OBSERVE 只证明每个 REPEATED_GROUP 有 MANY GROUP owner，没有证明反向的每个 MANY GROUP 至少
  拥有一个 REPEATED_GROUP。模型因此可以让 MANY GROUP 只指向 singular GROUP；HIERARCHY 随后从 GROUP
  multiplicity 推导 MANY，却没有兼容的 relationship region，造成同一 cardinality 固定码重复失败。
- 实现：`70da862` 为 opt-in policy 增加反向校验并复用最早 OBSERVE 固定码
  `VISUAL_SEMANTIC_REPEATED_GROUP_CARDINALITY_INVALID`；legacy policy 不变。`dd920cc` 发布 pipeline 4.16、
  generic visual-elements v9 与三模型 product-v29 immutable Profile。只有已验证 region forest、element kind、
  multiplicity 与 region ownership 参与判断；不读取 OCR/model text，不按 gold 排名，不补造重复结构。
- 恢复：真实 PostgreSQL tracer 的四次 scripted provider stage 精确为 OBSERVE rejected→OBSERVE accepted→
  HIERARCHY accepted→BINDING accepted，并到达 `REVIEW_REQUIRED`。retry 只携带固定码，清空未验证
  elementInventory/grounding，不重跑 Document Vision；OCR sentinel 未进入 checkpoint。
- UI/验证：`70e0f2c` 将固定码解释更新为双向归属问题并加入组件与 1024 viewport Playwright 场景。detached
  clean revision `70e0f2c` 的 fast `20260811-135547`、server `20260811-135605`、web `20260811-135807`、
  inference-e2e `20260811-140010` 均 A1 PASS；Inference 182、App 213（6 gated skip）、Web 73，真实 replay→
  review→atomic Draft Apply 浏览器链路 1/1。
- 状态：本节点 Provider attempts=0，Goal 仍为 345 reservations（340 SETTLED、5 历史 Plus RESERVED、0
  BREACHED），三份 ledger CLOSED。Flash/Plus/Max 仍为 685,591/936,770/491,919 exposed tokens；每槽累计
  cap 1,500,000，单 authorization 500,000、attempt/CNY/time 边界不变。product-v29 仍 `EXPERIMENTAL`，N6
  为 `automated_verified`、N7 为 `in_progress`。文档 checkpoint 后必须先跑 final-tree clean full，并重新计算
  `/2` identity、Profile snapshots、Goal aggregate 与时限；Flash 才可优先进入单 case/最多 5 calls lifecycle。

## 2026-08-11 v29 bounded live checkpoint

- pre-live：clean `c4f92b9` full `20260811-140553` 9/9、Document Vision `20260811-141657` 19-line canary
  均 PASS；Java/独立 Python identity 与 Flash/Plus snapshot 精确一致。首次 Flash 因 runtime enable flag 漏传在
  Provider 前 `DOCUMENT_VISION_DISABLED`，CLOSED/A2 为 0 attempts，Goal 零漂移；没有重开该 ledger。
- Flash v29b：`a2c82e6` PROPOSED→负探针→`f40a6ad` OPEN→唯一 wrapper→`9454422` CLOSED。A2 PASS、5
  OBSERVE rejections、43,203 tokens、¥0.022207、0 abandoned、payload PASS；enum invalid×3，另有 parent
  containment/kind invalid，未到 HIERARCHY。
- Plus v29：基于 Flash 信号与 Plus v28 的更深阶段历史，执行 `f98bfd5` PROPOSED→负探针→`e256e53` OPEN→
  唯一 wrapper→`f443d86` CLOSED。A2 PASS、3 OBSERVE rejections、21,000 tokens、¥0.093918、0 abandoned、
  payload PASS；sibling overlap×1、element evidence outside region×2，随后成本预留 fail-closed。
- 治理：两模型 CLOSED probe 均为 `NOT_OPEN` 且 Goal/evidence 零写入，无残留进程。Goal=353 reservations
  （348 SETTLED、5 历史 Plus RESERVED）；Flash/Plus/Max 为 110/161/82 attempts、
  728,794/957,770/491,919 tokens、¥0.348298/¥3.702040/¥10.289316。三 ledger CLOSED、0 BREACHED。
- 门控：v29 未取得 accepted OBSERVE→HIERARCHY→BINDING，故 Max 不调用，final 20/60 不启动。Profile 仍
  `EXPERIMENTAL`、N6=`automated_verified`、N7/Goal=`in_progress`。下一节点先离线收敛 OBSERVE enum、
  overlap 与 evidence-region 归属，保持最早阶段 repair 与 no-progress 上界。

## 2026-08-11 v30 unique evidence-owner normalization checkpoint

- 信号：Plus v29 三次 OBSERVE 中两次为
  `VISUAL_GROUNDING_ELEMENT_EVIDENCE_OUTSIDE_REGION`。模型已经给出合法 region forest 与 canonical evidence，
  但 element owner 没有覆盖 evidence；该缺口可在不读取模型原文的前提下由已验证几何判定。
- 边界：`71ccbdf` 只在 opt-in policy 中保留能覆盖至少一块 evidence 的既有 owner，并为每块未覆盖 evidence
  选择唯一最具体、非 ROOT、kind/multiplicity 兼容的 region；SLOT 可归属任意非 ROOT，ONE GROUP 只归属
  GROUP，MANY GROUP 只归属 REPEATED_GROUP。inventory/ownership 不全、unknown region、零/多候选、空结果或
  超过 8 owners 时原子返回原 plan，由既有固定码 fail-closed；不改 region topology/evidence，不猜 enum 或
  sibling overlap，也不从 rejected OBSERVE 选择 crop。
- 产品接入：`d3fedf3` 发布 pipeline 4.17 与 Flash/Plus/Max product-v30 immutable Profile，旧 4.16 行为不变；
  成功归一化只记录 payload-free 固定码 `VISUAL_GROUNDING_ELEMENT_REGION_NORMALIZED`。真实 PostgreSQL tracer
  精确执行 OBSERVE→HIERARCHY→BINDING 并到达 `REVIEW_REQUIRED`，Document Vision 一次，OCR sentinel 未进入
  checkpoint/Candidate；独立 Python verifier 接受三份精确 snapshot。
- UI/验证：`837c015` 将固定码与中文解释接入 monitor/review。Inference 183/183、real-PG 1/1、Node 24 Web
  14 files/73 tests 与 build、真实 replay→review→atomic Apply 1/1、1024px diagnostics Playwright 1/1 PASS；
  证据为 `20260811-150327-web`、`20260811-150428-inference-v30-ui`、
  `20260811-150534-v30-diagnostics-e2e-results`。端口冲突与 strict locator 的两次预检红灯未触发 Provider，修正后
  重跑通过。
- 状态：本节点 Provider attempts=0，Goal 仍为 353 reservations（348 SETTLED、5 历史 Plus RESERVED、0
  BREACHED），Flash/Plus/Max 仍为 110/161/82 attempts、728,794/957,770/491,919 tokens 与
  ¥0.348298/¥3.702040/¥10.289316。三 ledger CLOSED；product-v30=`EXPERIMENTAL`、N6=
  `automated_verified`、N7/Goal=`in_progress`。只有本 checkpoint 后的 clean full、fresh identity/snapshot、
  aggregate budget、时限与 exact J1 均通过，才可优先执行 Flash 单 case/最多 5 calls；Max 仍要求同版本三阶段
  可达及质量门。

## 2026-08-11 v30 full 与 bounded live checkpoint

- 前置门：clean `e5d1977` 的 full gate `.sdlc/evidence/20260811-150901-full` 9/9 PASS；Document Vision
  `.sdlc/evidence/20260811-151430-document-vision` 得到 19 lines。fresh `/2` identity 为
  `…5b28c8af`，Flash/Plus snapshot 为 `e11a708f…77a3e` / `ce966122…8552f`；Profile、Goal、费用、时限、
  API 配置存在性和 evidence lease 均在每次 live 前重算。
- Flash：`ff3e5a4` PROPOSED→负探针→`4180ef8` OPEN→`5f99083` CLOSED。唯一 wrapper 176,686 ms；
  独立 verifier A2 PASS，5 attempts、20,621 input + 22,325 output、¥0.021989、0 abandoned、payload scan
  PASS。五次均为 OBSERVE reject：parent-kind、non-repeated cardinality、region-kind×2、parent-containment；
  evidence-owner normalization 未命中。
- Plus：用户已恢复 Plus 权限，且 v29 evidence-outside-region 是 v30 policy 的直接信号，因此执行收紧到 ¥0.10
  的单 case：`d82563f` PROPOSED→负探针→`7a8eade` OPEN→`ec0a307` CLOSED。唯一 wrapper 74,883 ms；
  A2 PASS，1 attempt、4,104 input + 3,248 output、¥0.034192、0 abandoned、payload scan PASS。OBSERVE
  命中 `VISUAL_SEMANTIC_REPEATED_ITEM_FIELD_MISSING`，下一预留前成本守卫 fail-closed；normalization 未命中。
- 停止门：最终 359 reservations 为 354 SETTLED + 5 历史 Plus RESERVED，0 BREACHED；Flash/Plus/Max
  累计为 115/162/82 attempts、771,740/965,122/491,919 tokens、¥0.370287/¥3.736232/¥10.289316。
  v30 没有 accepted OBSERVE/HIERARCHY/BINDING，Max CLOSED 且未调用。N6 仍 `automated_verified`，N7/Goal
  仍 `in_progress`，product-v30 仍 `EXPERIMENTAL`；下一安全切片只允许从新 fixed code 建立离线 bounded
  verifier/repair/no-progress 合同，final 20/60 不启动。

## 2026-08-11 v31 repeated-item SLOT owner checkpoint

- 信号：Plus v30 的 OBSERVE 已通过 JSON shape/grounding 前置合同，但以
  `VISUAL_SEMANTIC_REPEATED_ITEM_FIELD_MISSING` fail-closed。v30 的一般 evidence-owner policy 会保留能覆盖全部
  evidence 的粗粒度 REPEATED_GROUP owner；语义 verifier 则要求每个 ITEM 至少有一个可见 SLOT owner，缺口可由
  已验证 region forest 与 canonical bounding boxes 唯一判定。
- bounded 合同：`7e464df` 只处理已有 SLOT。所有 canonical evidence 必须位于某个 ITEM 内，每块 evidence 必须有
  唯一最具体非 ROOT region，且每个当前缺字段 ITEM 都必须由 eligible SLOT evidence 覆盖；inventory/ownership
  不全、unknown、root-only、零/多候选、缺任一 ITEM 或 owner 超过 8 时原子返回原 plan。不得新建字段/region、修改
  topology/evidence、读取 OCR/模型文字、按 gold 排名或从 rejected OBSERVE 生成 selected crop；旧 v30 policy 不变。
- 产品接入：`791d4e9` 以 pipeline 4.18 和三份 product-v31 immutable Profile 显式 opt-in，继承 v30 的 hierarchy/
  binding/Document Vision 合同；成功只记录数量型
  `VISUAL_GROUNDING_REPEATED_ITEM_SLOT_OWNER_NORMALIZED`。真实 PostgreSQL tracer 一次 Document Vision 后严格执行
  OBSERVE→HIERARCHY→ELEMENT_BINDING，三次 scripted provider stage 到达 `REVIEW_REQUIRED`，归一化 owner 为
  `item-a,item-b`；OCR sentinel 未进入 checkpoint/Candidate/validation。
- 独立复核与审核面：`f6cc529` 令独立 Python verifier 逐份重算 Flash/Plus/Max v31 snapshot；`eea8b3f` 将固定码、
  中文解释和最早 OBSERVE repair scope 接入 monitor/review。Inference 184/184、real-PG 1/1、snapshot verifier
  1/1、Node 24 Web 14 files/73 tests + build、1024px diagnostics Playwright 1/1、Axe serious/critical=0、payload
  sentinel=0；证据为 `.sdlc/evidence/20260811-155052-web` 与
  `.sdlc/evidence/20260811-155200-v31-diagnostics-e2e-results`。已有 4173 prototype 进程未被终止，浏览器证据改用
  隔离空闲端口；测试端口退出后已清理。
- 治理：本节点 Provider attempts=0，Goal 保持 359 reservations；Flash/Plus/Max 仍为 115/162/82 attempts、
  771,740/965,122/491,919 tokens、¥0.370287/¥3.736232/¥10.289316，三 ledger CLOSED。最新用户 J1 将每槽累计
  cap 设为 1.5M tokens 并允许 Plus，但 180 attempts、既有 CNY 与 time 边界不变。product-v31=`EXPERIMENTAL`、
  N6=`automated_verified`、N7/Goal=`in_progress`。只有 checkpoint commit 后的 clean full、Document Vision、fresh
  identity/snapshot/aggregate preflight 与 exact J1 全部匹配，才可启动 bounded smoke；Max 与 final 20/60 的门
  保持不变。

## 2026-08-11 v31 clean/full 与 bounded live 结果

- clean gate：exact code `e5b4994` 的 full `20260811-155539` 9/9 PASS、workingTreeDirty=false；Document Vision
  `20260811-160517` 以冻结 capability 得到 19 lines。fresh Java/Python identity 为
  `/2:578c631edfa2948527013fc0c1831de2242891a2e87bc233376fb208f3a2c0f3`；Flash/Plus/Max snapshot 为
  `c4a32c21…398b7`、`9cdbf6df…f8df3`、`c760ef14…edb8c`。
- Flash：`4ed323f` PROPOSED → NOT_OPEN 负探针 → `cbda25d` OPEN → 唯一 wrapper → `d2fd1cf` CLOSED。
  A2 PASS、5 SETTLED、43,776 tokens / ¥0.022675、0 abandoned、payload scan PASS；5 次均停在 OBSERVE，固定码
  为 invalid-region-kind×4、element-invalid×1，未命中 v31 normalization。
- Plus：基于 v30 repeated-item-field 的直接信号执行 `58d5530` PROPOSED → NOT_OPEN → `adeac0d` OPEN →
  唯一 wrapper → `d538638` CLOSED。A2 PASS、5 SETTLED、34,770 tokens / ¥0.100380；首个 OBSERVE accepted，
  其后四次 HIERARCHY 均为 `VISUAL_HIERARCHY_V2_RELATIONSHIP_SUPPORT_IDS_EMPTY`，未到 BINDING/Candidate。
- 治理：两模型 CLOSED probes 同样 NOT_OPEN，Goal/evidence 字节零变化；369 reservations 中 364 SETTLED、
  5 历史 Plus RESERVED、0 BREACHED。Flash/Plus/Max 为 120/167/82 attempts、
  815,516/999,892/491,919 tokens、¥0.392962/¥3.836612/¥10.289316，三 ledger CLOSED，无残留进程/lease。
  Max 因同版本 HIERARCHY/BINDING 不可达而未调用；final 20/60 不启动。product-v31=`EXPERIMENTAL`、
  N6=`automated_verified`、N7/Goal=`in_progress`。下一节点仅离线收敛 hierarchy support-id 的 bounded repair/
  no-progress 合同。

## 2026-08-11 v32 empty relationship support owner checkpoint

- live 信号：Plus v31 已接受 OBSERVE，随后四次在同一可信 checkpoint 上以
  `VISUAL_HIERARCHY_V2_RELATIONSHIP_SUPPORT_IDS_EMPTY` fail-closed；没有 BINDING/Candidate，也未读取模型原文。
- bounded 合同：`212f468` 只在 relationship 的既有 GROUP/REPEATED_GROUP region 位于父子 entity ownership
  连线上、且恰有一个 kind/multiplicity 兼容的既有 GROUP owner 时补入该 ID。v31、null/missing、unknown、
  non-container、zero/multiple owner 与 disconnected structure 均保留原 fixed code；不创建关系、region、
  evidence、文字、selected crop 或 Candidate。
- 产品接入：`7e4e70c` 发布 pipeline 4.19 与 Flash/Plus/Max product-v32 immutable Profile，继承 v31 的
  Document Vision、OBSERVE normalization、hierarchy/binding verifier 与 materializer；独立 verifier 重算三份
  snapshot。成功只写通用 owner 计数及
  `VISUAL_HIERARCHY_RELATIONSHIP_EMPTY_SUPPORT_OWNER_NORMALIZED` 数量，不写 owner/payload。
- 恢复/审核：`b892503` 的真实 PostgreSQL tracer 以一次 Document Vision、OBSERVE→HIERARCHY→BINDING 三次
  SUCCEEDED attempt 到达 `REVIEW_REQUIRED`，OCR sentinel 零持久化；`7404c7a` 完成 monitor/review 中文说明、
  Web 73/73/build 与隔离 4174 的 1024px Playwright 1/1。
- 状态：本增量 Provider attempts=0，369 reservations 与 Flash/Plus/Max 120/167/82 attempts、
  815,516/999,892/491,919 tokens、¥0.392962/¥3.836612/¥10.289316 不变，三 ledger CLOSED。v32=
  `EXPERIMENTAL`、N6=`automated_verified`、N7/Goal=`in_progress`；下一节点是 clean fast/server/web/e2e/full 与
  Document Vision，再 fresh 重算 identity/Profile/Goal/budget/time/process/lease。门控全绿前不 OPEN；Max 与
  final 20/60 仍要求同版本三阶段质量证据和 J1。

## 2026-08-11 v32 clean/full 与 bounded live 结果

- clean gate：exact `954792f` 的 full `20260811-164653` 9/9 PASS；Document Vision
  `20260811-165243` 为 1/1、19 lines。Java/Python evaluation identity 一致为 `/2:d3057906…e452fca`，
  Flash/Plus/Max v32 snapshot 依次为 `cadc8c7f…adfbb`、`1d839ca5…b86c7`、`7a2e42e2…c5477`。
- Flash：Goal 剩余 ¥0.007038 低于标准 OBSERVE reservation ¥0.009740，因此在新 reservation
  前 fail-closed，保持 CLOSED。
- Plus：`54bc798` PROPOSED → NOT_OPEN 负探针 → `a94810c` OPEN →唯一 wrapper →
  `5d71b3f` CLOSED。A2/payload scan PASS；3 attempts、21,316 tokens、¥0.067226、0 abandoned。
  OBSERVE accepted；HIERARCHY 两次为 `VISUAL_HIERARCHY_V2_SUPPORT_ELEMENT_UNKNOWN`，下一
  reservation 在 Provider 前因费用耗尽停止，未到 BINDING。
- 停止门：372 reservations = 367 SETTLED + 5 历史 Plus RESERVED，0 BREACHED。Flash/Plus/Max
  为 120/170/82 attempts、815,516/1,021,208/491,919 tokens、¥0.392962/¥3.903838/¥10.289316。
  三 ledger CLOSED。v32 未取得 accepted HIERARCHY/BINDING，Max 不调用，final 20/60 不启动。

## 2026-08-11 v33 unknown relationship-support owner checkpoint

- 信号边界：v32 live 只暴露通用 support-element-unknown fixed code，未暴露 ID 或原文。v33 因此只
  处理“一个 relationship 的唯一 unknown support ID 可被既有结构唯一反证”的子集，不声称已
  定位 live unknown 的 owner 类型。
- bounded 合同：`5951047` 要求 relationship region 为已知 container、parent→relationship→child
  ownership 连通、且恰有一个 multiplicity-compatible 的已有 GROUP owner。non-container、
  ambiguous、disconnected 或多 unknown ID 原子保留旧失败；不创建任何结构或 payload。
- 产品/恢复：`7ac4259` 发布 pipeline 4.20 与三份 product-v33 immutable Profile；`edd310d`
  真实 PostgreSQL tracer 以一次 Document Vision 与三次 stage attempt 到达 `REVIEW_REQUIRED`，
  unknown-support 及通用 owner telemetry 只在 HIERARCHY，OCR sentinel 零持久化。
- 审核面/验证：`94060a0` 将 fixed code 与中文说明接入 monitor/review。contract 28/28、
  inference 186/186、Profile/capability 3/3、independent verifier 2/2、real-PG 1/1、Web 73/73 +
  lint/typecheck/build、隔离 4187 的 1024px Playwright 1/1 PASS；端口已释放。
- 状态：本节点 Provider attempts=0，Goal 用量不变，三 ledger CLOSED。product-v33=`EXPERIMENTAL`、
  N6=`automated_verified`、N7/Goal=`in_progress`。下一步是 checkpoint commit 后 exact-clean
  fast/server/web/e2e/full/Document Vision 与独立 verifier；在费用或同版本阶段门未变前不创建
  新 live ledger，不启动 final 20/60。

## 2026-08-11 v4 cost guard 与 24h J1 恢复入口

- 用户将 Flash/Plus 各自的稳定槽位 Goal cost cap 设为 ¥10，总 token/attempt 与 Max ¥18 不变；
  时间固定为 `2026-08-11T09:51:55Z`–`2026-08-12T09:51:55Z`。
- guard v4 只把 Plus/Flash cost map 改为 ¥10/¥10；v1–v3 reservations 保持不可变，迁移必须在原子
  lock 内先按来源 guard 验证完整 state。Java guard 与独立 Python verifier 都要覆盖四版。
- 执行顺序恢复为：提交并 exact-clean 验证 v4 → fresh `/2` identity 与三份 v33 snapshot → Flash
  单 case lifecycle/A2 → fresh preflight 后 Plus lifecycle/A2。Max 仍须同版本 live 三阶段和质量门。
- 本 checkpoint 只固化授权与 guard，Provider attempts=0、372 reservations 与三份 CLOSED ledger
  尚未变化；product-v33 仍 `EXPERIMENTAL`，N7/Goal 仍 `in_progress`。

## 2026-08-11 v33 cost-restored live 与下一安全切片

- `15b5d00` clean full 9/9、Document Vision 19 lines、双实现 identity 与三份 v33 snapshot 通过；
  v3 guard 在首个 OPEN reservation 内原子迁移 v4。
- Flash `f12e5af`→`69e8455`→`f50f591`：负探针 NOT_OPEN、唯一 wrapper、CLOSED 后 A2 PASS；
  4 attempts / 37,181 tokens / ¥0.019870，全部为 OBSERVE parent/enum fail-closed。
- Plus `b0bceab`→`36c13db`→`f7a87b9`：同等生命周期与 A2/payload PASS；5 attempts /
  35,407 tokens / ¥0.159584，第五次 OBSERVE accepted 后 call cap 阻止 HIERARCHY。
- Goal 最终 381 reservations（376 SETTLED、5 历史 RESERVED、0 BREACHED），三 ledger CLOSED、无残留
  process/lease。Max/final 20/60 因同版本三阶段门失败不启动。
- 下一节点：先离线实现并证明 unique-existing-parent OBSERVE normalization；只允许同 artifact、严格包含、
  kind/repeat-group 兼容且唯一最具体的已有 parent，任何歧义/循环/root/全局校验失败原子回退。随后才进行
  versioned Profile、real-PG recovery、payload-free telemetry、monitor/review UI/E2E 和分层 gates。

## 2026-08-11 v34 unique-existing-parent 离线 checkpoint

- `14e02b8`：新增同 artifact、严格包含、kind/repeat-group 兼容、唯一最具体的 existing-parent bounded
  normalization；ROOT/equal-box/zero-or-many/cycle/limit/forest failure 原子回退，enum/overlap/歧义继续关闭。
- `10f11b3`：发布 pipeline 4.21、三份 immutable product-v34 Profile、worker telemetry 与独立 snapshot
  verifier；`029277a`：锁定 v34 继续继承 evidence-owner 与 repeated-item SLOT-owner repair。
- `abb52a3`：真实 PostgreSQL lease-expiry 恢复从已完成 OBSERVE 继续 HIERARCHY/BINDING，到达
  `REVIEW_REQUIRED`；ephemeral OCR 可重算但不持久化，Provider OBSERVE 不重放。
- `de18000`：monitor/review 中文说明、Node 24 组件/构建和 1024px payload-free Playwright 闭环。
- 自动证据：inference 188/188、independent verifier 2/2、real-PG 57/57、Web 73/73 + build、Playwright
  1/1；`.sdlc/evidence/20260811-190723-web` 与
  `.sdlc/evidence/20260811-191314-v34-diagnostics-e2e-results`。
- 治理：本节点 Provider attempts=0，381 reservations 与三份 CLOSED ledger 不变。N6=`automated_verified`、
  N7/Goal=`in_progress`、product-v34=`EXPERIMENTAL`。

下一门：提交 checkpoint 后在 exact-clean revision 执行 full 与 Document Vision；随后逐次 fresh 重算 identity、
v34 snapshot、Goal/token/attempt/CNY/time、J1、API 配置存在性、进程与 lease。优先 Flash 单 case/最多 5 calls；
Plus 仅在剩余 5 attempts 内且 Flash 信号直接相关时使用；Max/final 20/60 仍受同版本 live 三阶段、质量、独立
复核与最终 J1 硬门。

## 2026-08-11 v34 bounded live 结果

- exact-clean `751e412`：full `20260811-191800` 9/9、Document Vision `20260811-192239` 19 lines；
  Java/Python identity 一致为 `/2:dbeeb7cf…d1f50`，Flash/Plus/Max v34 snapshot 依次为
  `9a678ea6…17d21`、`8ce6f12f…04f9b`、`3a7066ad…cd6`。
- Flash `e213243` PROPOSED → NOT_OPEN → `72e25cd` OPEN →唯一 wrapper→`ea5bda5` CLOSED；A2
  PASS，5 attempts、43,396 tokens、¥0.022364、0 abandoned。5 次均停在 OBSERVE：invalid-region-kind×3、
  parent-kind×2，无可信 checkpoint。
- Plus `f36195f` PROPOSED → NOT_OPEN → `4ab12ea` OPEN →唯一 wrapper→`fd7fb35` CLOSED；A2
  PASS，4 actual attempts、30,885 tokens、¥0.096198、0 abandoned。OBSERVE 首次 accepted；HIERARCHY
  分别 empty-support×1、support-not-group×2，第五次 reservation 在 Provider 前按 authorization cost
  fail-closed。
- Goal 结算为 390 reservations（385 SETTLED、5 历史 Plus RESERVED、0 BREACHED）：Flash/Plus/Max
  为 129/179/82 attempts、896,093/1,087,500/491,919 tokens、¥0.435196/¥4.159620/¥10.289316。
  三 ledger CLOSED；Max/final 20/60 因同版本 BINDING 与质量门未满足而不启动。

## 2026-08-11 v35 empty source-ancestor support 离线 checkpoint

- `614359f` 新增独立 support policy：empty support 在 exact relationship-region GROUP owner 缺失时，
  只搜索已知 relationship region 的严格祖先；候选必须为 GROUP/REPEATED_GROUP、基数兼容、唯一且连接
  parent/child entity regions，成功同时归一化 support 与 relationship region。exact owner 优先；unknown
  support、zero/many、disconnected、non-ancestor 继续原 fixed code。
- `708522b` 发布 pipeline 4.22 与 Flash/Plus/Max product-v35 immutable Profile，并增加 payload-free
  `VISUAL_HIERARCHY_RELATIONSHIP_EMPTY_SOURCE_ANCESTOR_SUPPORT_OWNER_NORMALIZED`；旧 Profile/Prompt
  不改写，v35 保留 v30/v31/v34 observation repair 与后续 hierarchy/binding semantic policy。
- `a2b8181` 的真实 PostgreSQL tracer 在 OBSERVE checkpoint 后模拟 lease expiry，再从 HIERARCHY 恢复并
  完成 BINDING 到 `REVIEW_REQUIRED`；Provider OBSERVE 不重放、ephemeral OCR 重算但 sentinel 零持久化。
  `5c59ce3` 将 code、中文说明、scope 与 earliest HIERARCHY repair 接入 monitor/review，1024px E2E 保持
  keyboard 可达且 payload-free。
- 当前自动证据：contract 31/31、inference 189/189、independent snapshot verifier 1/1、real-PG 1/1、
  Web 73/73 + typecheck/lint、Playwright 1/1。v35 Provider attempts=0，Goal 总量与三份 CLOSED ledger
  不变。N6=`automated_verified`、N7/Goal=`in_progress`、product-v35=`EXPERIMENTAL`。

下一门：提交 docs checkpoint 后，在 exact-clean revision 上重新跑 full 与冻结 Document Vision；然后逐次
fresh 重算 `/2` identity、v35 snapshot、Goal token/attempt/CNY、固定 J1 时限、API 配置存在性、进程与
evidence lease。Flash 可优先单 synthetic case/最多 5 calls；Plus 仅剩 1 Goal attempt，不能被当作三阶段
证明。Max 和 final 20/60 继续要求同版本 live OBSERVE/HIERARCHY/BINDING、质量门、独立 verifier 与最终 J1。

## 2026-08-11 v35 exact-clean Flash live checkpoint

- `0e52ec7` clean full `20260811-201946` 9/9；Document Vision `20260811-202810` 1/1、19 lines。
  首次 `20260811-202505` 缺 executable 配置，确认 0 Provider/进程/held lease 后串行恢复。Java/Python
  identity `/2:e623107c…e49d37`，Flash/Plus/Max snapshots 为 `f84747…34d0`、`8302e6…af70`、
  `ba36e8…826a`。
- Flash `d2c2c3d` PROPOSED → NOT_OPEN → `b795f0a` OPEN →唯一 wrapper→`a4298f3` CLOSED →
  NOT_OPEN；A2/payload PASS，5 attempts、41,477 tokens、¥0.020835、0 abandoned。
- 5 次均为 OBSERVE rejection：region-kind enum×4、parent-kind×1；实际结构全 0，没有 HIERARCHY/BINDING。
  Goal 更新为 395 reservations；Flash/Plus/Max 分别 134/179/82 attempts、
  937,570/1,087,500/491,919 tokens、¥0.456031/¥4.159620/¥10.289316。

下一门：不调用只剩 1 attempt 的 Plus，也不启动 Max/final 20/60。仅用 fixed-code 与既有 typed shape 合同
诊断可证明唯一性的 OBSERVE region-kind/parent-kind bounded repair；不能证明唯一时保持 fail-closed。

## 2026-08-11 v36 contract-unique region kind 离线 checkpoint

- `fdf7d44`：新增结构唯一分类 policy；`MANY + repeatGroupId`→`REPEATED_GROUP`、
  `ONE + repeatGroupId`→`ITEM`、无 parent/无 repeat/ONE/单 full-artifact evidence→`ROOT`。无法唯一
  决定的 SECTION/GROUP、缺失 repeat、歧义、非法 topology 或完整 forest failure 原子回退。
- `86b6074`：发布 pipeline 4.23 与三份 product-v36 immutable Profile；Prompt/Document Vision/call cap
  保持 v35，独立 verifier 支持三份 snapshot。成功仅记录
  `VISUAL_GROUNDING_REGION_KIND_NORMALIZED` 数量。
- `2076684`：real-PG lease-expiry tracer 在 OBSERVE 记录 3 次归一化并持久化 checkpoint，恢复时不重放
  OBSERVE，只执行 HIERARCHY/BINDING 后到 `REVIEW_REQUIRED`；OCR sentinel 零持久化。
- `f395f90`：monitor/review 中文说明、Web 14 files/73 tests、typecheck/lint 与隔离 4174 的 1024px
  Playwright 1/1 PASS，端口已释放；4173 的无关 TAMP 服务保持不动。Node 20 结果只算兼容验证。
- 治理：Provider attempts=0，Goal 仍为 395 reservations；Flash/Plus/Max 为 134/179/82 attempts、
  937,570/1,087,500/491,919 tokens、¥0.456031/¥4.159620/¥10.289316，三 ledger CLOSED。
  product-v36=`EXPERIMENTAL`、N6=`automated_verified`、N7/Goal=`in_progress`。

下一门：在 docs checkpoint 的 exact-clean revision 上跑 full（包含正式 Node 24）与冻结 Document Vision；
fresh 重算 `/2` identity、三份 v36 snapshot、Goal/token/attempt/CNY/time、J1、API 配置存在性、进程与
evidence lease。全部匹配后仅优先 Flash 单 synthetic case/最多 5 calls。Plus 只剩 1 Goal attempt，不调用；
Max/final 20/60 必须等待 v36 同版本 live OBSERVE/HIERARCHY/BINDING、质量门、独立 verifier 与最终 J1。

### v36 Flash live disposition

- gates/identity：full `20260811-211447-full`、Document Vision `20260811-211916-document-vision`、
  Java/Python identity `e2fb024c…89d2d`、Flash snapshot `cf32df27…a86a` 全部 PASS。
- lifecycle：`5a6bfc4` PROPOSED → NOT_OPEN → `220de94` OPEN →唯一 wrapper→`ab11a8b`
  CLOSED → NOT_OPEN；探针零写入，wrapper 171.790 秒，0 残留 process/lease。
- evidence：A2/payload PASS，1 case、0 abandoned、5 attempts、42,469 tokens、¥0.021607；OBSERVE
  fixed code 为 invalid region-kind enum×3、sibling overlap×1、parent kind×1，未触达后两阶段。
- budget/state：Goal=400 reservations；Flash/Plus/Max=139/179/82 attempts、
  980,039/1,087,500/491,919 tokens、¥0.477638/¥4.159620/¥10.289316；三 ledger CLOSED。

所以 v36 仍 `EXPERIMENTAL`，N6=`automated_verified`，N7/Goal=`in_progress`。下一安全路径是对剩余
fixed code 建立不读取 payload 的 bounded 分类、反例和 stage-local repair；在同版本三阶段可达前不调用
Plus/Max，也不启动 final 20/60。

## 2026-08-11 v37 constraint-unique GROUP kind 离线 checkpoint

- `ebd0281`：只在 ONE GROUP element 的 distinct/known ownership 缺少兼容 GROUP，且唯一 unresolved
  singular non-root/no-repeat owner 时归一化 GROUP；所有歧义与残余 unknown fail-closed，v36 不变。
- `b5a4555`：pipeline 4.24 与 Flash/Plus/Max product-v37 Profile immutable；Prompt/Document Vision/
  call cap/timeout/pricing 继承 v36，Java/Python snapshot 合同扩展。
- `007afe6`：real-PG v37 recovery 在 1 次 kind normalization 后持久化 OBSERVE，恢复时只执行
  HIERARCHY/BINDING 到 REVIEW_REQUIRED；OCR sentinel 未进入持久层。
- `e6682b4`：monitor/review 文案覆盖唯一绑定约束；Web 73/73、typecheck/lint、4174 Chromium 1/1，
  生成测试目录已清理。inference 191/191、verifier 2/2、recovery 2/2。
- governance：Provider=0，Goal=400 reservations；Flash/Plus/Max 用量保持
  139/179/82 attempts、980,039/1,087,500/491,919 tokens、¥0.477638/¥4.159620/¥10.289316；
  三 ledger CLOSED。v37=`EXPERIMENTAL`、N6=`automated_verified`、N7/Goal=`in_progress`。

下一门：exact-clean full/Document Vision，fresh `/2` identity、v37 snapshots、Goal/J1/time/process/lease/
API-key-presence。仅 Flash 可在全绿后单 case/最多 5 calls；Plus/Max/final 仍等待同版本三阶段与质量门。

### v37 Flash 首次 live：pre-provider fail-closed

- gate/preflight：full `20260811-220155-full`、Document Vision `20260811-220627-document-vision`、
  Java/Python identity `2b498c45…daeb2`、Flash snapshot `2dc4b025…e9919` 及 Goal/J1/process/lease PASS。
- lifecycle/evidence：`99940ef` PROPOSED → NOT_OPEN → `045b5b9` OPEN →唯一 wrapper→ `c3223ee`
  CLOSED → NOT_OPEN；A2/payload PASS，1 completed、0 abandoned、0 attempts/tokens/cost/latency。
- fixed outcome 为 `DOCUMENT_VISION_ADAPTER_MISSING`。启动参数误用了非合同 `adapter`，正确属性为
  `renderweave.inference.document-vision.adapter-script`；因此网络调用和 Goal reservation 均未发生。
- CLOSED 探针监控文件零变化，0 process/lease；Goal 仍为 400 reservations且三模型累计不变，三 ledger
  CLOSED。此节点没有质量信号，v37=`EXPERIMENTAL`、N6=`automated_verified`、N7/Goal=`in_progress`。

下一门：提交本 checkpoint 后重新跑 exact-clean gates 和 fresh preflight，以新 authorization 和正确
adapter-script 绑定串行重试 Flash。旧 authorization 不重放；Plus/Max/final 20/60 继续等待同版本三阶段。

### v37b Flash live disposition

- lifecycle/gates：`9204a49` PROPOSED → NOT_OPEN → `0960c9f` OPEN →唯一 wrapper→ `4d8e48b`
  CLOSED → NOT_OPEN；full `20260811-221947-full`、Document Vision `20260811-222400-document-vision`、
  fresh identity/snapshot/preflight 与 A2/payload scan 全绿。
- evidence：1 completed、0 abandoned、5 attempts、42,691 tokens、¥0.021815、143,088 ms；OBSERVE
  invalid region-kind×4、parent-containment×1，后两阶段未触达。
- budget/state：Goal=405 reservations；Flash/Plus/Max=144/179/82 attempts、
  1,022,730/1,087,500/491,919 tokens、¥0.499453/¥4.159620/¥10.289316；三 ledger CLOSED。

v37 保持 `EXPERIMENTAL`，N6=`automated_verified`、N7/Goal=`in_progress`。下一安全节点回到离线 bounded
parent-containment/region-kind verifier；Plus/Max/final 20/60 的同版本三阶段与质量门仍不满足。

### product-v38 offline disposition

- commits：`632e641` codec/contract；`b91637b` pipeline 4.25、三模型 immutable Profile 与独立 verifier；
  `060dd47` real-PG checkpoint recovery；`1504ac6` monitor/review 与 E2E。
- bounded rule：常规唯一最具体兼容 parent 搜索为空后，只允许沿当前已知错误 parent 的无环 ancestor
  chain 归一化到唯一 parent=null、同 artifact 且严格包含非 ROOT/ITEM child 的 ROOT；missing/self、ITEM、
  artifact mismatch、cycle、equal/full box、歧义与最终完整 plan failure 全部原子 fail-closed。
- evidence：contract 34/34、Profile/independent verifier 37/37、real-PG v37/v38 2/2、inference 192/192、
  Web 73/73、typecheck/lint、Playwright 7/7 PASS；Node 20 Web 仅为兼容证据。
- state：Provider=0；Goal=405 reservations；Flash/Plus/Max=144/179/82 attempts、
  1,022,730/1,087,500/491,919 tokens、¥0.499453/¥4.159620/¥10.289316；三 ledger CLOSED。

v38 保持 `EXPERIMENTAL`，N6=`automated_verified`、N7/Goal=`in_progress`。下一门：在本 checkpoint 的
exact-clean revision 上执行 full/Document Vision，fresh 重算 evaluation identity、v38 snapshots、Goal/J1/
time/process/lease；全绿后才考虑 Flash single synthetic case、最多 5 calls。Plus/Max/final 20/60 的同版本
三阶段、质量、独立复核与最终 J1 门不变。

### v38 Flash live disposition

- gates/identity：clean `3e44974`，full `20260811-225452-full` 9/9，Document Vision
  `20260811-225916-document-vision` 19 lines，identity=`fc334bc7…8a524`，Flash snapshot=
  `d91bc968…c9412`。
- lifecycle/A2：`882c8ca` PROPOSED→NOT_OPEN→`19c726c` OPEN→唯一 wrapper→`31109c4` CLOSED→
  NOT_OPEN；5 attempts、40,797 tokens、¥0.020282、110,782 ms、0 abandoned、payload PASS。
- outcome：OBSERVE invalid region-kind×2、reading-order gap×2、JSON unknown member×1；0 HIERARCHY/
  BINDING，v38 parent normalization 未命中。
- budget/state：Goal=410 reservations；Flash/Plus/Max=149/179/82 attempts、
  1,063,527/1,087,500/491,919 tokens、¥0.519735/¥4.159620/¥10.289316；三 ledger CLOSED。

v38 继续 `EXPERIMENTAL`，N6=`automated_verified`、N7/Goal=`in_progress`。下一安全节点回到离线
reading-order verifier；unknown-member/enum 不放宽，Plus/Max/final 20/60 的门不变。

### product-v39 offline disposition

- commits：`a08f099` codec/contract；`c99a4ac` pipeline 4.26、三模型 immutable Profile 与独立 verifier；
  `80d0b73` real-PG checkpoint recovery；`d0ed4d3` monitor/review 与 E2E。
- bounded rule：仅对有 parent 的 sibling set，在 existing order 互异、按 order 的总序与
  `(top,left,regionId)` canonical 空间顺序完全一致且序号非连续时，最多改变 8 个值并压紧为 `0..n-1`；
  root、duplicate/tie、反向/位置不一致、missing parent、cycle 与最终完整 plan failure 原子 fail-closed。
- evidence：contract 35/35、跨模块 Profile/independent verifier 38/38、real-PG v39 1/1 与 v38/v39 pair
  2/2、inference 193/193、Web 73/73、typecheck/lint、Playwright 7/7 PASS；Node 20 Web 仅为兼容证据。
- state：Provider=0；Goal=410 reservations；Flash/Plus/Max=149/179/82 attempts、
  1,063,527/1,087,500/491,919 tokens、¥0.519735/¥4.159620/¥10.289316；三 ledger CLOSED。

v39 保持 `EXPERIMENTAL`，N6=`automated_verified`、N7/Goal=`in_progress`。下一门：在本 checkpoint 的
exact-clean revision 上执行 full/Document Vision，fresh 重算 evaluation identity、v39 snapshots、Goal/J1/
time/process/lease；全绿后才考虑 Flash single synthetic case、最多 5 calls。Plus 仅余 1 attempt 不调用；
Max/final 20/60 的同版本三阶段、质量、独立复核与最终 J1 门不变。

### v39 Flash live disposition

- gates/identity：clean `0625a23`；full `20260811-233119-full` 9/9；Document Vision
  `20260811-233555-document-vision` 19 lines；identity=`3abc7eba…52696d`；Flash snapshot=
  `667db9b4…d1a2bc`。
- lifecycle/A2：`37cc036` PROPOSED→NOT_OPEN→`1431233` OPEN→唯一 wrapper→`678ef2e` CLOSED→
  NOT_OPEN；3 attempts、17,289 actual tokens、¥0.008900 actual cost、76,442 ms、0 abandoned、payload PASS。
- outcome：OBSERVE invalid region-kind×1、reading-order gap×1、network error×1；第三次无 actual usage 并保留
  worst-case reservation；0 HIERARCHY/BINDING，v39 normalization 未命中。
- budget/state：Goal=413 reservations；Flash/Plus/Max=152/179/82 attempts、
  1,105,020/1,087,500/491,919 exposed tokens、¥0.538392/¥4.159620/¥10.289316；三 ledger CLOSED。

v39 继续 `EXPERIMENTAL`，N6=`automated_verified`、N7/Goal=`in_progress`。CLOSED authorization 不重跑；
Plus/Max/final 20/60 不启动。下一安全节点回到离线 payload-free diagnostic/reading-order verifier。

### product-v40 offline freeze candidate

- commits：`3b0d92d` 精确 reading-order 诊断；`b179b7e` pipeline 4.27、visual-elements Prompt 10 与三模型
  immutable Profile；`05e1b65` real-PG checkpoint recovery；`3eff5ce` monitor/review/E2E。
- bounded rule：继承 v39 的唯一 canonical gap compaction。仅当最终 shape 原本会报 GAP、root orders 已连续，
  且所有非连续的非 root sibling set 都能落入同一互斥类别时，duplicate/tie 输出
  `VISUAL_GROUNDING_READING_ORDER_DUPLICATE`，existing-order 与 `(top,left,regionId)` 不一致输出既有
  `VISUAL_GROUNDING_READING_ORDER_POSITION_INVALID`；mixed、root gap、canonical 超界 gap 继续 GAP。
  不持久化 order/region/box/model payload，也不放宽 JSON、enum、parent、overlap、ownership 或 semantic gate。
- identity binding：Prompt 10 仅在 v9 repair 列表加入 duplicate fixed code；Flash/Plus/Max v40 snapshot 分别为
  `1f6f8cec…9e9e19`、`2fd63065…17dd2`、`7444e737…30708`，三份 Profile 均为 `EXPERIMENTAL`。
- evidence：contract 36/36、Profile/Prompt 20/20、独立 Profile verifier 2/2、real-PG v39/v40 pair 2/2、
  inference 195/195、Web 73/73、typecheck/lint、Playwright 7/7 PASS；Node 20 Web 仅为兼容证据。
- budget/state：Provider=0；Goal 仍为 413 reservations；Flash/Plus/Max=152/179/82 attempts、
  1,105,020/1,087,500/491,919 exposed tokens、¥0.538392/¥4.159620/¥10.289316；三 ledger CLOSED。

product-v40 保持 `EXPERIMENTAL`，N6=`automated_verified`、N7/Goal=`in_progress`。下一门是在本 checkpoint
exact-clean revision 上执行 full/Document Vision 与独立 verifier，fresh 重算 identity/Profile/Goal/J1/time/
process/lease；有效时只执行 Flash single synthetic case、最多 5 calls。完成后冻结 v40 并阶段性收尾；
若仍未达同版本三阶段/质量门，不调用 Plus/Max/final，也不把阶段可用性写成生产验收。

### product-v40 Flash smoke 与阶段收尾

- anchors：exact-clean `af2076a`；full `20260812-001439-full` 9/9；Document Vision
  `20260812-002223-document-vision` 19 lines；identity=`902577dd…a70abd63`；三份 v40 snapshot 匹配。
- lifecycle：`0d38448` PROPOSED→NOT_OPEN→`5392aa1` OPEN→唯一 wrapper→`6e7f522` CLOSED→
  NOT_OPEN；wrapper exit 0/183.863 秒，后置 7 watched files 零变化，0 process/lease。
- A2/outcome：verifier/payload scan PASS；1 completed、0 abandoned、5 attempts、20,699 input + 22,605
  output tokens、¥0.022226、171,157 ms。全部停在 OBSERVE：enum×2、overlap×1、parent-kind×1、generic
  reading-order gap×1；duplicate/position classifier 未命中，0 HIERARCHY/BINDING/Candidate。
- budget：Goal=418 reservations（412 SETTLED、6 RESERVED、0 BREACHED）；Flash/Plus/Max=157/179/82
  attempts、1,148,324/1,087,500/491,919 exposed tokens、¥0.560618/¥4.159620/¥10.289316；三
  ledger CLOSED，evidence/Goal 同步校验一致。

phase disposition：冻结 pipeline 4.27/Prompt 10/product-v40。deterministic/real-PG/browser gates 支持内部工程
试用与失败后人工处置，但 live 没有证明该 case 的 Candidate 产出能力。product-v40=`EXPERIMENTAL`、
N6=`automated_verified`、N7/Goal=`in_progress`；Plus/Max/final 不启动，Goal 不完成。

### product-v40 产品切换与 capability-aware admission

- `f47c54a` 将新建产品目录从历史 product-v4 切换为 Plus、Max、pinned Flash 三份冻结 v40 Profile，并把
  IMAGE_ONLY/Plus 设为默认；Max 明示用于高难嵌套，Flash 明示为低成本 smoke。历史 Profile 与 run snapshot
  不修改且仍可恢复，三份 v40 继续 `EXPERIMENTAL`。
- 成功 real-PG 合成集成路径严格只有 OBSERVE、HIERARCHY、ELEMENT_BINDING 三次 Provider reservation；测试
  Provider 收到 STRUCTURE/REPAIR 会立即失败，因而旧 v4 的字段 key 二次改写缺陷不再能从新产品入口到达。
- `f27f86a` 为 `live-availability` 增加逐 Profile、payload-free readiness。创建与 live retry 都在 run、费用
  reservation 和 Provider 调用之前校验启动时探测的 Document Vision capability 与 Profile snapshot 精确一致；
  缺失、模型缺失或 identity mismatch 均 fail-closed，Web 禁用对应选项并只显示固定 code 的人类可读说明。
- 本节点不改 pipeline 4.27、Prompt 10 或任一 v40 Profile，不自动跨模型 fallback，也没有 Provider 调用；
  Goal/ledger 数值和三份 `CLOSED` 状态不变。该节点修复“旧 v4 缺陷仍是默认路径”和“本地能力缺失却先创建
  失败任务”两项工程可用性问题，不改变 Flash 未通过 OBSERVE 的质量事实。
- clean A1：隔离 revision `6906be1` 的 full `20260812-012644-full` 为 9/9 PASS、Document Vision
  `20260812-013158-document-vision` 为 1/1 PASS/19 lines；两份 metadata 均 `workingTreeDirty=false`。
  full 覆盖独立 evidence verifier 2/2、正式 Node 24、真实 PostgreSQL、runtime canary、v40 catalog/readiness
  浏览器检查及 replay→review→Apply。Provider=0，门控后无 live/Maven/Java/Python 残留。

### product-v40 通用 Flash selector checkpoint

- 用户把新建产品目录中的 Flash 从 dated `qwen3.7-flash-2026-07-15` 改为 `qwen3.7-flash`；Plus/Max
  精确 model ID、默认顺序和用途不变。
- `67d46c5` 新增 immutable `dashscope-qwen37-flash-product-v40-hybrid-generic`，复用 pipeline 4.27 的
  三阶段合同、Prompt、Document Vision capability 和预算边界；原 dated v40 Profile/evidence 不修改且仍可
  读取和恢复，只是不再 product-live。
- Registry/API/OpenAPI/generated client/Web/E2E 已同步；定向 Java 为 Registry 2/2、API 9/9、policy 6/6、
  live API 4/4，正式 Node 24 的 typecheck/lint 与 Web 73/73、产品目录 Playwright 1/1 均 PASS。
- 本节点没有 Provider 调用，三个 ledger 继续 `CLOSED`，Flash alias 继续共用同一累计槽位且不重置消费。
  successor 尚无 live 质量证据，故 product-v40=`EXPERIMENTAL`、N6=`automated_verified`、N7/Goal=
  `in_progress`。
