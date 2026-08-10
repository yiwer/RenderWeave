# ADR-0015：产品识别使用独立 DashScope Profile 目录与可选任务成本上限

- 状态：Accepted
- 日期：2026-08-10
- 决策来源：用户要求开放“排队识别并进入审核”，替换重复模型选项，并允许自行设置或不设置成本限额
- 关联：AC-015、AC-019、AC-020、P6/T6-3a

## 背景与约束

P5 的 live Profile、authorization ledger 与有限预算用于合成语料 canary/认证实验。它们已经全部
`CLOSED`，不能被产品入口当作长期运行配额；此前启动页仍读取该有限账本，因此即使部署已配置
DashScope，按钮也会因历史预算耗尽而永久禁用。入口还同时暴露多个同模型、不同评测阶段的
Profile，用户无法把它们理解为稳定的产品模型选择。

产品运行仍必须保留既有边界：选择文件和切换模型不产生调用；每次调用前先做保守费用预留；
最多三次调用；Candidate 只能进入人工审核和 create-only Draft Bundle；API Key 不进入浏览器、
数据库、日志或证据。所有产品模型当前仍是 `EXPERIMENTAL`，历史质量结论不能自动继承给新
Profile。

## 决策

### 1. 评测 Profile 与产品 Profile 分离

产品入口只按以下固定顺序展示四个独立、版本化 Profile：

| Profile | DashScope model |
|---|---|
| `dashscope-qwen37-flash-product-v1` | `qwen3.7-flash` |
| `dashscope-qwen37-plus-product-v1` | `qwen3.7-plus` |
| `dashscope-qwen38-max-product-v1` | `qwen3.8-max` |
| `dashscope-qwen37-max-20260608-product-v1` | `qwen3.7-max-2026-06-08` |

`qwen3.7-plus-2026-05-26` 等历史 Profile 继续作为不可变评测资产存在，但不出现在产品模型选择器。
产品 Profile 使用 `USER_CONFIRMED` 输入分类、Grounded Pipeline 2.0、Prompt 3.0、JSON object
输出、关闭 thinking/tools/remote media，并把完整 snapshot 写入 run。

官方当前视觉模型资料列出了 Flash、Plus 与 dated Max 的视觉能力和图像 token 规则；产品仍按用户
指定保留 `qwen3.8-max`。由于当前官方目录未明确列出这一 exact alias，它是显式兼容性残余：
Provider 若拒绝该模型，run 必须稳定失败，不能静默改投其他模型。

### 2. 用户输入采用逐任务确认

live API 只接受 `USER_PROVIDED`，并要求“有权外发当前文件”和“接受实验 Profile”两个显式确认。
选择、预览、移除文件或切换模型都只发生在本地；只有点击“排队识别并进入审核”才创建 durable
run。服务端继续规范化图片，只把规范化图片和当前模式所需的有界结构信息发给 DashScope。

基础 `compose.yaml` 始终关闭 worker/upload；显式叠加 `compose.live.yaml` 时同时打开两门，并从
Compose secret 只读挂载 Key。两门、credential 或 Profile 任一不满足都 fail closed。

### 3. 成本上限可选，但单次调用永远有界

- 用户可为每个 run 设置 `costLimitMicrosCny`，范围为 1..100,000,000（¥0.000001..¥100）；该值是
  首次识别与最多两次 repair 的累计硬上限。
- 留空表示不增加“本次任务累计上限”，不是取消保护：每次 Provider 调用仍受 Profile 的保守
  `maximumEstimatedCostMicrosCny`、输出 token 上限和 `maximumTotalCalls=3` 约束。
- 每次调用前在同一事务/锁边界内累计该 run 已结算或仍预留的费用；新预留会超过任务上限时，
  以 `PROVIDER_RUN_COST_LIMIT_EXCEEDED` 在零调用处失败。
- retry 创建新 run，并显式继承原 run 的成本上限。所有 reservation/settlement 仍是追加式审计记录。
- 产品 reservation 使用独立 `product-live` 命名空间，不消费、重开或改写任何 P5 CLOSED 账本。
  该命名空间只承载持久审计和并发串行化，不作为 UI 中的有限产品配额。

### 4. 开放运行不等于质量认证

`live-availability` 在 worker/upload/credential 就绪时报告可用，并返回四个产品 Profile；不再用历史
canary 的 remaining attempts/cost 禁用按钮。四个 Profile 均继续显示 `EXPERIMENTAL`，必须经过
Candidate 审核才能创建 Draft，不能发布、更新或删除 Schema。

## 备选方案

| 方案 | 优点 | 代价/风险 | 未选择原因 |
|---|---|---|---|
| 重开 P5 canary ledger | 改动少 | 混淆评测授权与产品运行，历史额度会再次耗尽 | 违背账本不可复用边界 |
| 每个 model 暴露所有历史 Profile | 便于对比 Prompt | 用户看到多个同名模型，无法理解质量/协议差异 | 评测资产不应成为产品选择 |
| 强制每次填写成本上限 | 最保守 | 与用户明确要求“不设置成本限额”冲突 | 保留可选上限与固定单次保护 |
| 无任何费用预留 | 实现简单 | Provider 费用发生后才发现越界 | 违反调用前 fail-closed 原则 |

## 后果与验证

- 正向后果：部署开门后按钮按真实运行条件可用；模型列表稳定；用户可在易用与累计硬限额间选择；
  历史评测治理与产品运行互不污染。
- 负向后果/债务：产品运行没有有限的跨 run 总费用配额；部署方仍应使用 DashScope 账户额度/告警。
  `qwen3.8-max` exact alias 需通过真实 Provider 可用性验证，不能从其他模型资料推断。
- 验证：Profile registry/费用估算单测、PostgreSQL V013 fresh migration、累计预留与 retry 继承集成测
  试、OpenAPI generated client、Web 单元测试、mocked Playwright。实现门控全部清空 Key/live 环境，
  Provider attempts 必须为 0；真实点击单独属于用户运行行为。
- 回退：停止 live overlay 并以基础 Compose 重启即可恢复零外部模型能力；数据库列与追加式 reservation
  保留，不执行 destructive down migration。

