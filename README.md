# RenderWeave

RenderWeave 是一个 AI-native 设计出图系统。服务端采用 Java 21 / Spring Boot 模块化单体，Web 使用
React + TypeScript strict，正式渲染器是独立 Rust 进程。

## 当前状态

- Schema/Inference v1 已交付：支持 Schema Draft、不可变 StaticSchema、权威 RootDocument 验证，以及带
  Evidence 审核的 Candidate → Draft Bundle 工作流。
- additive Template v1 正在 `main` 上持续实现：Template、DesignDSL、Asset、Authoritative Preview、
  RenderDocument、结构化 Editor 与独立 Renderer 已形成真实纵切，但尚未满足最终 READY 条件。
- IMAGE_ONLY Production Admission 停在稳定检查点：本地 OCR sidecar 与安全准入基础已实现并默认关闭，
  仍未获得完整生产准入、未发布、未部署。
- 数据适配与 Workspace 不在当前批准范围。

## 仓库结构

| 路径 | 职责 |
|---|---|
| `renderweave-schema/` | Schema DSL、Draft/StaticSchema 生命周期与编译 |
| `renderweave-validation/` | 针对精确 Schema 的权威 RootDocument 验证 |
| `renderweave-inference/` | 推断 run、Candidate、Evidence 与 Document Vision 投影 |
| `renderweave-template/` | Template 聚合、DesignDSL、revision 与编辑语义 |
| `renderweave-asset/` | Asset 接纳、Blob/AssetRef、解析与删除证明 |
| `renderweave-rendering/` | RenderInput 准入、求值、RenderDocument 与 Renderer seam |
| `renderweave-app/` | Spring Boot 装配、HTTP/JDBC/进程适配器 |
| `web/` | React 产品界面与 OpenAPI 生成客户端 |
| `renderer/` | Rust RenderEngine、离线 vendor 与认证输入 |
| `docker/ocr-sidecar/` | no-IP Unix-socket OCR sidecar 与冻结的离线 wheel 集合 |

## 权威文档

开始工作前先读 [CONSTITUTION.md](CONSTITUTION.md)，再由 [CONTEXT-MAP.md](CONTEXT-MAP.md)
按任务路由到对应的 `docs/context/` 分片。

- Schema/Inference v1：[specs/renderweave-v1.md](specs/renderweave-v1.md)
- Template v1 实施权威：
  [specs/changes/20260817-template-v1-implementation-authority.md](specs/changes/20260817-template-v1-implementation-authority.md)
- Template v1 当前状态入口：[plans/renderweave-template-v1-plan.md](plans/renderweave-template-v1-plan.md)
- IMAGE_ONLY admission 当前状态入口：
  [plans/image-only-production-admission-plan-v1.md](plans/image-only-production-admission-plan-v1.md)
- HTTP 合同：[openapi/renderweave-v1.yaml](openapi/renderweave-v1.yaml)
- 架构决策索引：[docs/adr/README.md](docs/adr/README.md)
- Web UI 设计：[design.md](design.md) 与 [design-system/](design-system/)
- 本地 issue tracker：[docs/agents/issue-tracker.md](docs/agents/issue-tracker.md)

`docs/history/` 只用于历史查证，不是当前状态机，也不驱动后续工作。

## 首次检出

生产 OCR sidecar 的固定 wheel 由 Git LFS 管理。clone 后先安装 Git LFS 并取回完整构建输入：

```powershell
git lfs install
git lfs pull --include="docker/ocr-sidecar/vendor/*.whl"
git lfs fsck
```

正式 Node 基线是 24 LTS。项目脚本会把校验过的 Node 工具链放在 `.sdlc/toolchains/`，不会修改系统 Node。
服务端构建需要 Java 21 与 Maven；完整本地拓扑和部分 gate 还需要 Docker。

## 本地运行

完整基础拓扑包含 Web、API、PostgreSQL、MinIO 与本地 OCR sidecar：

```powershell
docker compose config --quiet
docker compose up --build
```

打开 <http://127.0.0.1:3000>。基础 `compose.yaml` 不启用外部模型调用。

仅启动 Web 开发服务器：

```powershell
powershell -ExecutionPolicy Bypass -File tools/dev-web.ps1
```

开发服务器位于 <http://127.0.0.1:5173>，并把 `/api`、`/internal` 与 `/actuator` 代理到
`RENDERWEAVE_API_URL`；未设置时使用 `http://127.0.0.1:8080`。

当前产品入口：

- `/schemas`：Schema Draft 目录与 Schema Studio
- `/static-schemas`：不可变 StaticSchema 目录
- `/validator`：RootDocument 验证
- `/inference`：推断历史、启动、监控与 Candidate 审核
- `/templates`：Template 目录、创建与结构化 Editor

仍保留一个用于比较旧 Schema Studio 设计方向的 throwaway 原型：
`/prototype/schema-studio?variant=A|B|C`。它不是产品入口，也不代表 Template Editor。

## 构建与验证

统一 gate 入口：

```powershell
# 默认最短检查：服务端打包 + Web 类型检查
powershell -ExecutionPolicy Bypass -File tools/run-gate.ps1 -Gate fast

# 按受影响面选择
powershell -ExecutionPolicy Bypass -File tools/run-gate.ps1 -Gate server
powershell -ExecutionPolicy Bypass -File tools/run-gate.ps1 -Gate web
powershell -ExecutionPolicy Bypass -File tools/run-gate.ps1 -Gate template
powershell -ExecutionPolicy Bypass -File tools/run-gate.ps1 -Gate asset
powershell -ExecutionPolicy Bypass -File tools/run-gate.ps1 -Gate render
powershell -ExecutionPolicy Bypass -File tools/run-gate.ps1 -Gate e2e
powershell -ExecutionPolicy Bypass -File tools/run-gate.ps1 -Gate runtime
```

只有风险覆盖整个产品时才运行：

```powershell
powershell -ExecutionPolicy Bypass -File tools/run-gate.ps1 -Gate full
```

直接构建命令：

```powershell
mvn -B -ntp verify
npm --prefix web run build
```

gate 会把本地日志与输入清单写入已忽略的 `.sdlc/evidence/<timestamp>-<gate>/`；这些文件不是发布、
人工批准或生产资格证明。

## Live AI 边界

外部模型调用默认关闭。`compose.live.yaml` 只是部署配置片段，不构成 Provider 调用、真实数据外传、预算
或生产运行授权。任何 live 操作都必须遵循 `CONSTITUTION.md` 与 IMAGE_ONLY admission 当前状态，绑定精确
Profile、数据分类、调用次数、费用和时限；不得把一次成功 replay 或 canary 描述为生产可用。
