# ADR-0039：由代码拥有的一轮有界视觉检查动作

- 状态：accepted
- 日期：2026-08-14
- 决策来源：用户批准 R5 narrow successor delta，并确认 bounded visual-inspection action、最多一轮两个局部视图以及 exact 产品 raster transform A2 先行
- 关联：Issue #16、AC-R5I-001..018、ADR-0025、ADR-0028、ADR-0036、ADR-0038、product-v45、VRQ R5 `STOP_TO_SPEC_R5`

## 背景与约束

R5 oracle probe 已证明更高分辨率的局部视觉信息可能改善四个触发 case，但它使用仓库矢量场景重新渲染，不能
证明产品对既有规范化位图执行 crop/resize 后仍能保留同等收益。直接增加模型调用、修改 Prompt 或申请 live
会把诊断信号误报为产品能力；当前权威终态仍是 `LIVE_J1_REQUEST_NOT_ELIGIBLE`。

模型在 OBSERVE 阶段确实可能需要看清已知 view 的一个局部，但这不需要开放式 Agent。RenderWeave 已由
PostgreSQL durable typed state graph/FSM 掌握 lease、checkpoint、恢复、取消、预算与终态，也已有静态多尺度
view、坐标回投、semantic verifier 和 stage-local repair。新能力必须复用这些边界，不建立第二套 workflow
history，不把局部图像或 OCR 结果升级为新的持久 observation 事实。

## 决策

1. **新增一个由代码拥有的深 Module `BoundedVisualInspection`。** 它在唯一高层 Interface
   `normalized ArtifactSet + renderweave-visual-view-plan/1.0 + decoded InspectionRequest/1.0 + exact AdaptiveInspectionPolicy/1.0 → InspectionOutcome/1.0`
   内统一负责 request 验证、crop/resize、view 选择、坐标 lineage、资源预检、确定性 identity 与固定失败码；
   caller 不直接拼装局部 view 或绕过 policy。
2. **模型只能提出闭合的声明式请求，不能执行工具。** `InspectionRequest/1.0` 只允许引用当前 verified view
   的一至两个 region、固定 margin preset 与固定 resolution preset；不得携带工具名、路径、URL、模型、预算、
   循环、优先级、自由文本解释或结束条件。代码可返回的完整结果只有 `EXECUTED`、`REJECTED` 或 `EXHAUSTED`。
3. **每个 run 最多一个 inspection round、两个 inspected views。** 只有第一次 OBSERVE 可以提出该请求；本地
   transform 完成后只允许一次后继 OBSERVE。第二轮、递归检查、HIERARCHY/BINDING 检查或模型自选循环均在
   副作用前 fail-closed。
4. **现有 PostgreSQL 状态机仍是唯一 durable authority。** 合法 request 进入 OBSERVE 的 typed inspection
   substate，而不是另起 workflow。protected checkpoint 只保存恢复所需的 canonical validated request、身份与
   消耗计数；派生 view bytes、原始模型输出、Prompt 与 OCR 不持久化。crash 后从原 blob 确定性重建，同一已
   消耗 request 不重复执行，accepted semantic stage 不重放。
5. **`DocumentObservationIR/1.0` 不变。** inspection 改变的是下一次 OBSERVE 可见的像素视图，不增加 OCR、
   layout 或 order 的 observation 类型，不把 crop OCR merge 进 IR，也不把 inspected view ID 泄漏进 Candidate、
   Evidence 或长期 observation store。若实现需要这些语义，必须停止并回到新的 successor spec。
6. **首个实现门是 exact 产品 raster transform A2。** 冻结的 3 DEV + 1 HOLDOUT assignment 必须从产品实际使用
   的 normalized raster bytes 出发，以冻结 transform/policy/request identity 双跑；独立 verifier 重算确定性、
   几何、资源与分层指标。失败终态固定为 `R5_PRODUCT_TRANSFORM_NOT_QUALIFIED`，所有 request、Prompt、Profile、
   workflow 与 live 后继工作均不运行，不能依据结果调参或换算法补救。
7. **离线证据与敏感载荷继续分离。** 常规日志/evidence 只记录 identity、计数、维度、资源、指标与固定 code；
   不含图片、Base64、完整 bbox list、OCR、Prompt、模型/Candidate 原文、Provider request ID 或 RootDocument。
   离线执行的 Provider attempts、reservations、cost 与 API-key reads 必须全部为 0。
8. **本决策不引入或授权 live。** 不引入开放式 Agent、通用工具执行器、LangGraph、Temporal、Step Functions
   或第二 durable truth。全部离线门通过最多得到 `R5_LIVE_J1_REQUEST_ELIGIBLE`；这不是 J1，也不会创建、打开
   或复用 authorization。任何未来 live 仍须独立的 fresh exact J1。

## 备选方案

| 方案 | 优点 | 未选择原因 |
| --- | --- | --- |
| `DocumentObservationIR/1.1` 加 crop observation | 感知数据统一进入 IR | 当前需求是 provider-facing 像素获取，不是新 observation 事实；会扩大 R0/R1 与持久化合同 |
| 调用前静态生成更多高分辨率 view | 控制流最简单 | 无法按模型已定位的疑难区域分配有限像素预算，且会扩大每次调用成本 |
| 开放式 Agent / 通用视觉工具 | 可自由探索 | 工具、循环、预算、终止和恢复不可由现有封闭合同证明 |
| 新建 INSPECT stage 或迁移通用 workflow runtime | 显式展示动作 | 重复 PostgreSQL durable truth，并为一次有界 OBSERVE 回边引入迁移与双历史风险 |
| 持久化派生局部图片 | crash 恢复直接 | 扩大敏感载荷和留存面；确定性 transform 可从原 blob 重建 |
| 直接依据 oracle probe 申请 live | 最快取得模型结果 | oracle 是矢量重渲染，不是 exact 产品 raster transform，证据层级不成立 |

## 后果与验证

- 正向后果：模型获得一个可表达但不可扩权的信息请求；动作、预算、恢复和证据边界由代码统一拥有；现有
  serial semantic pipeline、IR/1.0、Candidate materialization、validator 与 `REVIEW_REQUIRED` 均可保持。
- 代价：需维护 request/policy/outcome、view-plan/2.0、inspection checkpoint substate 与 additive hidden
  Profile 的版本身份；一次额外 OBSERVE 会增加未来获授权运行的视觉 token、费用和延迟上界。
- 首门验证：exact 产品 raster transform 对 `transit-board-v3`、`restaurant-menu-v3`、
  `hospital-schedule-v3`（DEV）及 `transit-board-v5`（HOLDOUT）执行两次真实本地运行并形成独立 A2；只有 PASS
  才可实施后续合同与 workflow ticket。
- 最高验证：通过现有 PostgreSQL 状态机执行完整 IMAGE_ONLY scripted
  `OBSERVE(request) → local inspection → OBSERVE(grounding) → HIERARCHY → ELEMENT_BINDING → LOCAL_MATERIALIZE → REVIEW_REQUIRED`
  replay，同时证明 product-v45 无 inspection 路径行为等价、payload-safe 与 Provider zero-use。
- 保证等级：本 ADR 与本地实现最多形成 A1/A2，不形成 A3、视觉/业务接受或 live J1。
