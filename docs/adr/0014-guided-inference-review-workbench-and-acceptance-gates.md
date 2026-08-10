# ADR-0014：AI Schema 识别采用四步引导工作台，并以离线 Eval 与浏览器矩阵共同验收

- 状态：Accepted
- 日期：2026-08-10
- 决策来源：AC-015–AC-019、T6-2 产品收口与用户授权按推荐继续推进

## 背景

现有实现已经具备 durable inference run、Candidate evidence、逐项 resolution、自动保存和 create-only 原子落库，但产品表面仍是不完整的技术切片：启动页缺少可检查的文件队列；运行页只显示原始状态字符串；失败/取消没有恢复动作；审核页不能新增或重排 Schema、不能重排字段、不能编辑约束，且一项关联多张图片时只展示第一张。现有 Playwright 旅程还依赖已经删除的页面文案，不能作为当前产品验收依据。

这些缺口不是扩大 v1 范围。`specs/renderweave-v1.md` 已要求审核可新增、删除、重排 Schema/field，修改 type、constraints/ref，并要求 cancel/retry/recovery、1024/1280/1440 和可访问性门控。

## 决策

### 1. 一个连续的四步心智模型

推断入口和审核详情共同呈现四步进度：

1. **准备输入**：选择 image-only、json-only 或 combined，检查 Profile、外发边界、预算和文件队列。
2. **受控识别**：展示 `NORMALIZE → OBSERVE → STRUCTURE → VALIDATE → CRITIQUE/REPAIR` 的人类可读进度；允许状态机许可时取消，失败或取消后由用户显式重试。
3. **逐项校对**：展示完成度、blocker/warning、所有证据，支持新增/删除/重排 Schema 与字段，以及 key、metadata、type、constraints/ref 编辑；仍无 confirm-all。
4. **原子创建**：展示 readiness checklist；只有服务端 blocker 为零且 autosave 稳定时，才允许一次性 create-only 创建 Draft Bundle。

四步心智模型不要求把职责挤在同一个页面。2026-08-10 的产品收口将其固定为四个共享导航、可深链的版面：`/inference` 历史任务、`/inference/new` 新增输入、`/inference-runs/{runId}/monitor` 识别监控、`/inference-runs/{runId}/review` 识别结果。新建/重试先进入监控；结果尚未形成时访问 review 必须返回 monitor。历史列表按任务状态选择 monitor/review，从而保持一个连续流程，同时让输入、运行诊断与 Candidate 编辑各自只有一个主任务。

### 2. Candidate 审核以完整编辑能力为准

- Candidate form 与 map 继续共享一个 reducer state；表单是完整键盘等价路径。
- Schema 与字段排序使用显式“上移/下移”按钮；不要求拖拽。
- 新增 Schema/field 必须是 `source: USER`、`inferred: false`、无 confidence/evidence；root identity 不可改变。
- AI item 编辑保留原始 provenance，并自动进入 `RESOLVED_BY_EDIT`；AI item 删除继续使用 `REMOVED` resolution。
- Candidate save 边界兼容旧页面或旧客户端：只要原始 AI item 的语义值已改变且未删除，就把提交 resolution 归一为 `RESOLVED_BY_EDIT` 后再执行来源、provenance、单项 autosave 与 validator 门控；不能因兼容而接受伪造证据或批量确认。
- 约束按当前最终类型显示合法键，并保留 Candidate 合同的字符串 literal 表示；`enum` 使用 JSON array literal。服务端 validator/materializer 仍是最终权威。
- Evidence 面板必须可遍历当前项关联的全部图片；每张图只绘制属于该 artifact 的 bbox。JSON evidence 独立列出 sample 与 pointer。

### 3. 上传与运行恢复必须可理解

- 文件选择后显示文件名、类型、大小、数量和逐个移除动作；客户端先做数量、扩展名/MIME、单文件与总量预检查，服务端校验仍是权威。
- 选择文件、切换 Profile 或打开页面不触发 Provider。只有模式输入齐全、上传/worker/credential/预算均可用且用户完成两项确认后，启动按钮才可用。
- 本 ADR 落地时的产品合同仍是 `SYNTHETIC_ONLY`。2026-08-10 后续产品运行语义已由
  [ADR-0015](0015-product-live-profile-catalog-and-optional-run-cost-limit.md) 明确扩展为逐任务确认的
  `USER_PROVIDED`；历史 live 授权仍不自动扩展或复用。
- durable run 的状态、阶段、失败 code 和 retry lineage 保持可见；失败/取消只通过已有 retry endpoint 产生新 run，不在原 run 上继续。

### 4. 验收采用两类正交门控

- **离线 Eval gate**：固定 60-case corpus、deterministic profiler/composer、Candidate validator/evaluator 与 policy 全量运行；默认清空 API Key 与 live gates，Provider attempts/reservations 必须为 0。Prompt/Profile/evaluator/workflow 改变时才要求新的 live evaluation identity/J1。
- **浏览器 gate**：Playwright 覆盖启动、运行、逐项校对、新增/重排/约束/多证据、原子创建、取消/重试和错误恢复；在 1024×768、1280×720、1440×900 检查无横向溢出，并执行 axe serious/critical=0 和键盘等价路径。
- 浏览器截图与结果必须绑定 clean revision；人工视觉判断继续单独报告 J1，不由截图自证替代。

## 未选择的方案

| 方案 | 未采用原因 |
|---|---|
| 只修复过期 E2E 文案 | 只能让旧断言变绿，不能证明 AC-017 的编辑与证据闭环。 |
| 复用 Draft editor 并直接保存 Draft | 会混淆 Candidate 宽松模型与正式 DSL，削弱 create-only 权限边界。 |
| 用拖拽实现排序 | 不能提供可靠键盘等价路径，且一层树图无需持久化坐标。 |
| 每次 UI 改动都调用真实模型 | UI/状态机回归应用 deterministic replay 即可；无意义增加费用、漂移和数据外传风险。 |
| 将当前人工授权解释为永久业务数据开关 | 授权是一次运行边界，不是产品数据治理模型。 |

## 影响与验证

- 正向：用户可从输入边界一直完成到 Draft 创建；Candidate 能力与权威 spec 对齐；失败路径可恢复；证据不再被第一张图片遮蔽。
- 代价：审核 reducer、Inspector、启动页和 Playwright fixture 增加复杂度；必须通过 focused component/reducer tests 控制。
- 不变：AI 不 publish/update/delete；Candidate ID 不进入 Draft；默认 live 关闭；原始 payload、Prompt 和 Provider response 不进入常规证据。
- 验证：Web unit + contract generation + mocked Playwright matrix + real PostgreSQL replay journey + offline 60-case eval + independent A2 review。

## 实施结果（2026-08-10）

- 实现锚点为 `4243dd40ce95a9ec5bdb957570a8ef447873d9e4`；四步入口/审核、完整
  Candidate 编辑、多图片/JSON evidence、运行恢复、recent-run resume、1024 drawer 与原子创建均已落地。
- clean A1：`.sdlc/evidence/20260810-050750-inference-e2e`、
  `.sdlc/evidence/20260810-051119-e2e`、`.sdlc/evidence/20260810-051119-eval`。
  Server 126 passed / 3 gated skip；Web 52 tests；browser 13 passed + real PostgreSQL journey 1 passed；
  offline eval 37/37、60 cases=20/20/20、Provider attempts/reservations=0。
- 独立只读 A2 对三份 493 项 input manifest、21 项 browser artifact 与 4 项 real-journey artifact
  逐项重算一致，结论为 PASS、0 Blocker / 0 High / 0 Medium。
- 自动证据不替代人工视觉接受。用户此前接受的是原型方向；最终实现仍为
  `human_acceptance_pending`，也没有因离线 eval 产生新的 live Profile certification。
