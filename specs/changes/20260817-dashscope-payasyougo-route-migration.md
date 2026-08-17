# Spec Delta：DashScope 按量付费路由迁移

- 状态：APPROVED
- 日期：2026-08-17
- 授权：用户 2026-08-16/17 会话明确决定弃用 Token Plan、改用标准按量付费模式（`DASHSCOPE_API_KEY`），
  并解除书面合同前置（wayfinder map `image-only-schema-production-admission` ticket 06/07）

## 决策

- application adapter 的 DashScope base URL 固定为
  `https://dashscope.aliyuncs.com/compatible-mode/v1`，并在适配器内部补全 `/chat/completions`。
- 直接凭据只读取 `DASHSCOPE_API_KEY`；Compose secret 使用同一宿主环境变量，容器内
  继续通过有界只读文件 `DASHSCOPE_API_KEY_FILE` 注入。
- 运行时传输路由与既有 immutable v45 Profile 声明的 `providerEndpoint` /
  `apiKeyEnvironmentVariable` 恢复一致；Profile bytes 不改写，历史 run snapshot 不变。
- base URL 保持精确单值 allowlist；启动参数不能把 adapter 扩展为任意 HTTP 客户端。
- 20260812 Token Plan 路由 delta 由本 delta 取代：Token Plan endpoint 与
  `DASHSCOPE_TOKEN_API_KEY`/`_FILE` 退出运行时路由；payload-free guard 与离线 gate 继续
  同时拦截/清除新旧两个 secret 名称。
- 本 delta 不授予任何 live 调用：Provider attempts 保持 0，直到绑定精确 Profile、数据分类、
  次数、费用与时限的当次 J1。

## 安全与验证

- 默认 live/upload 门继续关闭；本节点不运行 Provider、付费 live AI 或 runtime canary。
- 离线 gate、evaluation identity、payload-free evidence guard 继续清除/拦截
  `DASHSCOPE_API_KEY`/`_FILE` 与 `DASHSCOPE_TOKEN_API_KEY`/`_FILE`。
- AC：定向 adapter/prompt 测试与 fast gate 通过；server gate 在下一节点执行。
