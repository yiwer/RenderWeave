# 核实 DashScope 对生产图片的处理合同

Type: research
Status: resolved
Claimed by: research/image-only-production-dashscope-contract
Blocked by: none

## Question

仅依据阿里云/DashScope 的官方协议、隐私政策、产品文档、数据处理条款和一手 API 文档，当前 RenderWeave 使用的中国区 OpenAI-compatible endpoint 对用户上传图片及派生 prompt 的处理地域、传输与静态加密、服务端保留、训练或产品改进使用、人工访问、删除请求、日志/滥用检测、子处理方、跨境、DPA/企业合同和客户控制分别承诺了什么？哪些事实无法由公开一手资料证明，必须标为 `UNKNOWN/REQUIRES_CONTRACT_REVIEW`？研究不得发送图片、调用模型、读取 Key，也不得把营销页或非官方文章当作合同事实。

## Answer

Disposition：`TOKEN_PLAN_BACKEND_NO_GO`。

- 当前运行时固定的 `token-plan.cn-beijing.maas.aliyuncs.com` 属于 Token Plan；阿里云官方只允许其用于 AI 工具交互，明确不支持自动化脚本或应用后端，超范围使用可能停订或封 Key。外部网关、团队版 Key 或数据低敏都不会改变这一产品使用限制。
- 团队版公开承诺不使用对话数据训练模型，但仍禁止应用后端；个人版还明确授权存储和使用输入/输出以改进服务、优化模型，故个人版对本生产目标为 `DENY`。
- HTTPS、北京地域及中国大陆推理有部分公开依据，但数值保留期、逐请求删除 SLA、人工访问边界、反滥用日志、完整子处理者、完整驻留/跨境保证以及适用于中国站订单的 DPA 均为 `UNKNOWN/REQUIRES_CONTRACT_REVIEW`。
- 官方指向的候选生产路线是华北 2 业务空间专属按量付费 endpoint；其精确视觉模型、Key 类型、合同、SLA 和全部数据处理条款仍须书面冻结。任何 endpoint/model/Profile byte 变化都要求新 immutable Profile 和重新认证。
- 在取得书面 `PROVIDER_USE_AUTHORITY`、`PROVIDER_CONTRACT_IDENTITY`、无二次使用、数值保留/删除、人工访问/子处理者、地域/跨境和安全控制证据前，真实生产图片的 Provider 调用必须为 0。

研究资产：分支 `research/image-only-production-dashscope-contract`，提交 `818790df4bcdddbf09f2bfa0ba5b092e5382ed69`，[报告](E:/java_project/RenderWeave-research-dashscope-processing-contract/docs/research/image-only-production-admission/dashscope-processing-contract.md)。报告只使用一手资料；未调用 Provider、未读取 Key、未发送数据。

## Comments

- 2026-08-16（用户提供链接，Kimi 会话核实）：官方快速入门文档（platform.qianwenai.com《首次 API 调用》，2026-08-16 抓取）以 `qwen3.8-max` 为示例模型，经标准按量付费 endpoint `https://dashscope.aliyuncs.com/compatible-mode/v1` + 环境变量 `DASHSCOPE_API_KEY`（`sk-ws-` 前缀）直接调用。本报告中的 `qwen3.8-max` 可用性 `UNKNOWN/REQUIRES_PRODUCT_CONFIRMATION` 据此收窄为**文档级可用**；控制台开通状态仍待执行时核实。历史结论其余部分不变，Token Plan `NO-GO` 维持。
