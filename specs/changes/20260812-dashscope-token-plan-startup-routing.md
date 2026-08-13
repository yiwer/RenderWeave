# Spec Delta：DashScope Token Plan 启动路由

- 状态：APPROVED
- 日期：2026-08-12
- 授权：用户明确要求切换启动 LLM base URL 与 API Key 环境变量

## 决策

- application adapter 的 DashScope base URL 固定为
  `https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1`，并在适配器内部补全
  `/chat/completions`。
- 直接凭据只读取 `DASHSCOPE_TOKEN_API_KEY`；Compose secret 使用同一宿主环境变量，容器内
  继续通过有界只读文件 `DASHSCOPE_TOKEN_API_KEY_FILE` 注入。
- base URL 使用精确 allowlist，启动参数不能把 adapter 扩展为任意 HTTP 客户端。
- 已发布的 Inference Profile 资源与历史 run snapshot 不改写；本 delta 只改变部署时传输路由和
  application adapter 的 secret source。

## 安全与验证

- 默认 live/upload 门继续关闭；本节点不运行 Provider、付费 live AI 或 runtime canary。
- 离线 gate、evaluation identity、payload-free evidence guard 同时清除/拦截新旧 secret 名称。
- AC：定向 adapter/prompt 测试、fast、server、Node 24 Web gate 与 Compose live overlay 解析通过。
