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
- synthetic replay 与受控 DashScope live 识别：durable run → evidence review → create-only 原子 Draft Bundle

当前实现已完成 P1–P5 的受控能力验证并进入 P6 产品收口。基础 Compose 中 DashScope adapter
默认关闭；live overlay 开放逐任务确认的用户输入，提供四个产品 Profile 与可选累计成本硬上限。
所有产品 Profile 仍为 `EXPERIMENTAL`，只能生成待审核 Candidate，不能直接发布或修改 Schema。

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

基础 Compose 不启用外部模型。仅在明确允许传输的数据上启用 DashScope overlay：

```powershell
$env:DASHSCOPE_TOKEN_API_KEY = '<仅设置在当前终端，不写入仓库>'
docker compose -f compose.yaml -f compose.live.yaml up --build
```

overlay 仅通过 Compose secret 向 API 容器只读挂载 Key，浏览器端不会接触 Key；它会为当前本地单用户实例同时开启 worker 与上传门。选择文件、预览、切换模型都不会调用 Provider，只有用户确认数据可外发并点击“排队识别并进入审核”后才会创建任务。产品入口提供 `qwen3.7-flash`、`qwen3.7-plus`、`qwen3.8-max`、`qwen3.7-max-2026-06-08` 四个实验 Profile；可设置本次任务累计成本硬上限，也可留空而只保留 Profile 单次预留上界与最多三次调用的保护。

2026-08-08 的受控 synthetic canary 已实际连通两个 Profile：各 1 次调用、合计 2 次、估算费用 ¥0.054017；两条 Candidate 均进入 `REVIEW_REQUIRED` 并通过对应结构金标。该结果只证明真实闭环，不是 60-case 质量认证；两个 Profile 仍保持 `EXPERIMENTAL` 和默认关闭。

普通 `compose.yaml` 始终保持零外部模型能力；停止 live overlay 后重新用基础 Compose 启动即可恢复默认关闭状态。
