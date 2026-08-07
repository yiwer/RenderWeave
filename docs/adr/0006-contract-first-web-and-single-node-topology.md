# ADR-0006：OpenAPI 合同优先、独立 Web 制品与单节点同源部署

- 状态：accepted
- 日期：2026-08-07
- 关联：AC-022–AC-024

## 背景与约束

Java 服务和 React 客户端需独立演进，但 v1 不需要两个仓库或微服务。SSE 只用于任务进度，普通 CRUD 仍应保持可调试的 HTTP 语义。

## 决策

- 单 repo、独立制品：根 Maven aggregator + `web/` npm 项目 + `openapi/` contract。
- OpenAPI 3.1.2 是 HTTP source；Java DTO/controller 手写并验证，TypeScript Fetch SDK 由 exact-pinned Hey API 生成。
- `/api/v1` REST/JSON；RFC 9457 problems；page pagination；Inference create 才要求 Idempotency-Key。
- SSE 是 at-least-once notification，客户端按 sequence 去重并 GET database snapshot。
- production reverse proxy 提供同源 `/` 与 `/api/v1`；dev Vite proxy；无 wildcard CORS。
- Docker Compose：Web、Spring Boot、PostgreSQL、persistent BlobStore；无 Redis/queue/HA 声称。
- v1 无 auth/tenant；部署在可信网络或由外部 proxy 负责 TLS/auth。

## 备选方案

| 方案 | 优点 | 代价/风险 | 未选择原因 |
|---|---|---|---|
| separate repos | 发布独立 | contract/agent context 漂移 | v1 单 repo 更易保持一致 |
| GraphQL/gRPC | typed/stream 强 | tooling/浏览器/错误语义复杂 | REST + generated SDK 足够 |
| Web 打进 JAR | 单文件部署 | build/缓存/职责耦合 | 独立制品 + proxy 更清楚 |
| WebSocket 状态源 | 实时 | reconnect/一致性复杂 | SSE 通知 + GET snapshot 更可靠 |

## 后果与验证

- 正向：合同单一、两端可独立构建、部署简单。
- 代价：需 reverse proxy 和 contract drift gate；SSE client 手写。
- 验证：OpenAPI lint/generation/typecheck、MockMvc/Playwright、Compose health canary（A1/A2）。
- 恢复：源码制品回退、PostgreSQL restore、BlobStore restore 分别执行；不能互相替代。

