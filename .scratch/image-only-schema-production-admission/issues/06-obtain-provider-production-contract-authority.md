# 取得 Provider 生产产品与数据处理合同权威

Type: task
Status: resolved
Claimed by: 用户（2026-08-16 所有者决定）
Blocked by: 02 — 核实 DashScope 对生产图片的处理合同

## Question

由有权代表签约主体的人向阿里云商务/法务取得并封存一份覆盖 RenderWeave 单租户应用后端的书面、payload-free 合同矩阵：精确产品、华北 2 业务空间 endpoint、计费/Key 类型、允许的自动调用/批量/重试/并发、可用视觉模型与 SLA；原图、裁剪、OCR、Prompt、输出、cache、计费、内容安全及支持日志的数据类别与处理目的；训练/评测/标注/产品改进/模型优化二次使用；在线/缓存/反滥用/支持/备份的数值保留期；逐请求删除流程、SLA、备份上限与证明；人工访问、子处理者、地域、跨境、安全事件、加密、DPA 和审计权。应以什么 identity/digest、到期/变更规则与 J1 人工签署记录把它纳入后续 Provider route 和 Profile Certification authority？若供应商不能给出必要答案，必须形成 `PROVIDER_CONTRACT_NOT_ADMISSIBLE`，不得用口头答复、控制台截图或国际站 DPA 推定替代。

## Working notes

- 2026-08-16（Kimi wayfinder session）：agent 侧准备完成。人类执行请求包见 [assets/provider-contract-request.md](../assets/provider-contract-request.md)：含执行红线、四步流程、A–G 合同问题矩阵（逐项采纳标准）、封存/J1 登记约定与 `PROVIDER_CONTRACT_NOT_ADMISSIBLE` 收口规则。
- 本票剩余工作为人类向阿里云商务/法务发函并取得书面回复；回复到位后由任一会话按请求包末节收口并 resolve。期间本票保持 claimed，不进入 agent frontier。

## Answer

### 结论（2026-08-17 记录；所有者决定于 2026-08-16/17 会话作出）

所有者决定：**不寻求阿里云书面企业合同/法务同意**，生产路线的 Provider authority 基础改为**标准按量付费在线服务协议 + 所有者明示风险接受**。所有者陈述的理由：不把数据用于 LLM 训练、不做商用。记录在案的澄清：原书面合同要约束的从来不是所有者自身行为，而是 Provider 拿到图片后的保留、人工审核与二次使用行为；所有者知悉后仍以标准在线条款为充分基础。

- 产品路线：标准按量付费；Key 环境变量 `DASHSCOPE_API_KEY`（官方文档示例前缀 `sk-ws-`）；endpoint `https://dashscope.aliyuncs.com/compatible-mode/v1`；Token Plan 弃用（ticket 02 的 `NO-GO` 维持，仅针对 Token Plan）。
- `qwen3.8-max` 可用性：官方快速入门文档以 `qwen3.8-max` 为示例模型、标准按量付费 endpoint 直接调用，记为文档级可用证据（见 ticket 02 Comments）；控制台开通状态在执行时核实。
- 原《合同问题矩阵》B–G 节书面法务问询不再执行；`assets/provider-contract-request.md` 保留为历史材料，其 A 节（产品/Key/模型/SLA 事实）改由公开文档与控制台在执行时核实，不再要求书面回复。

### 随决定一并记录在案、所有者已接受的残余风险

1. 在线服务协议保留对输入/输出的技术或人工审核权利——不得对用户宣称"零人工访问"；
2. 各类数据无数值保留期与逐请求删除 SLA——RenderWeave 侧的数据政策与删除 SLO 不得依赖 Provider 侧数字；
3. 子处理者、跨境与中国站 DPA 无书面保证——主要缓释为单租户、仅 `ORDINARY_DESIGN` 输入、逐 run 外传确认；
4. Provider 侧模型/价格/条款漂移无合同锁定——准入撤销、drain 与 Profile 重认证语义仍由 ticket 07 冻结。

### 红线处理

所有者要求解除 provider 0 调用红线。AGENTS.md 的宪法级规则机制保持不变：付费 live 仍需当次 scoped J1（精确 Profile、数据分类、次数、费用、时限）；且代码（`application.yml:43`、`specs/changes/20260812-dashscope-token-plan-startup-routing.md`）与 `specs/renderweave-v1.md:495` 仍固定 Token Plan endpoint/`DASHSCOPE_TOKEN_API_KEY`，任何 live 调用之前必须先完成按量付费 route migration（change-spec delta + 新 immutable Profile）。首个 J1 的具体范围由所有者另行给定；在此之前 Provider attempts 仍为 0。

本次未运行任何 gate、Provider 调用或 API-key 检查。

### Errata

- 2026-08-17（migration 执行时发现）：v45 immutable Profile 本就声明 `providerEndpoint=https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions` 与 `apiKeyEnvironmentVariable=DASHSCOPE_API_KEY`，因此 route migration 无需改动 Profile bytes；上文「新 immutable Profile」的表述收窄为「change-spec delta + adapter/config 迁移」。已落地：`specs/changes/20260817-dashscope-payasyougo-route-migration.md`。
