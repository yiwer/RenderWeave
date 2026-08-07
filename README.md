# RenderWeave

RenderWeave 是一个 AI-native 设计出图系统。本仓库当前固定的是 v1 的定义层：设计 Schema Draft、发布不可变 StaticSchema、验证数据，并把图片/JSON/文本中的结构推断为可逐项审核的 Candidate。Template、数据适配、Workspace 与图片渲染明确属于后续版本。

## 当前交付

- 权威产品与软件规格：[`specs/renderweave-v1.md`](specs/renderweave-v1.md)
- 五维领域模型：[`docs/modeling/renderweave-v1-domain-model.md`](docs/modeling/renderweave-v1-domain-model.md)
- 架构决策：[`docs/adr/`](docs/adr/)
- 分阶段实施计划：[`plans/renderweave-v1-plan.md`](plans/renderweave-v1-plan.md)
- 编辑器原型说明：[`docs/prototypes/schema-studio.md`](docs/prototypes/schema-studio.md)
- OpenAPI 合同：[`openapi/renderweave-v1.yaml`](openapi/renderweave-v1.yaml)
- Java 21 / Spring Boot 4.1 / PostgreSQL 与 React 19 / Vite 8 可运行产品纵切
- strict Schema DSL、Draft revision/history/restore/copy、不可变 StaticSchema 与自底向上 JSON Schema 编译
- Form + 一层 Map 共享状态的 Schema Studio，以及 RootDocument 批量验证
- 零网络 synthetic replay：durable run → evidence review → create-only 原子 Draft Bundle

当前实现已完成计划中 P1–P4，并以本地 A1 证据验证。它不包含 live AI/provider adapter；真实数据外传、付费调用与 Profile 认证仍属于需要独立 A2 和显式 J1 授权的 P5。

## 运行原型

Windows PowerShell 5.1：

```powershell
powershell -ExecutionPolicy Bypass -File tools\dev-web.ps1
```

打开：

- `http://127.0.0.1:5173/prototype/schema-studio?variant=A`
- 把 `variant` 改为 `B` 或 `C` 比较方案；页面右下角也可切换。

脚本会下载并校验官方 Node 24.19.0 到 `.sdlc/toolchains/`；不会修改系统 Node。首次运行需要访问 nodejs.org 和 npm registry。

真实 PostgreSQL + API + Web 的零网络 replay 闭环可用以下命令重放：

```powershell
powershell -ExecutionPolicy Bypass -File tools\run-gate.ps1 -Gate server
powershell -ExecutionPolicy Bypass -File tools\run-gate.ps1 -Gate web
powershell -ExecutionPolicy Bypass -File tools\run-inference-e2e.ps1
```

## 验证闭环

```powershell
# 最短反馈
powershell -ExecutionPolicy Bypass -File tools\run-gate.ps1 -Gate fast

# Java + Flyway + Testcontainers PostgreSQL
powershell -ExecutionPolicy Bypass -File tools\run-gate.ps1 -Gate server

# 固定 Node 24：安装、OpenAPI 生成、类型、lint、测试、生产构建
powershell -ExecutionPolicy Bypass -File tools\run-gate.ps1 -Gate web

# 真实浏览器交互、控制台检查、截图与 1024px 边界
powershell -ExecutionPolicy Bypass -File tools\run-gate.ps1 -Gate e2e

# 临时 PostgreSQL + 实际 Spring Boot 进程 + HTTP/database readiness
powershell -ExecutionPolicy Bypass -File tools\run-gate.ps1 -Gate runtime

# 上述全部，加 Compose 结构检查
powershell -ExecutionPolicy Bypass -File tools\run-gate.ps1 -Gate full
```

每次运行把输入文件清单、Git 状态、逐步原始日志、退出码和元数据写入忽略提交的 `.sdlc/evidence/<timestamp>-<gate>/`。这是本机工具捕获的 A1 证据，不等同于 CI/独立复核的 A2，也不是外部强制门禁 A3。

## 本地拓扑

`compose.yaml` 定义 Web、API、PostgreSQL 与持久 BlobStore 卷：

```powershell
docker compose config --quiet
docker compose up --build
```

当前机器的 Docker registry 代理不可用，因此本轮只验证了 Compose 结构，并用 `runtime` gate 完成等价的 API/PostgreSQL live canary；镜像构建与整套 Compose 启动仍需在 registry 可用后执行，不能记为已通过。
