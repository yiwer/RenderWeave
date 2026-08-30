# RenderWeave 本地环境与反馈回路

本文件记录可复现的本地工具与 gate，不再承担 Agent SDLC 能力协商、证据分级、Phase 退出或人工状态判断。

## Toolchain

| Capability | Baseline |
|---|---|
| Java | Temurin 21 |
| Maven | Repository POM/BOM/plugin pins |
| Node | 正式 Web gate 使用固定 Node 24 LTS |
| npm | `package-lock.json` + `npm ci` |
| PostgreSQL | PostgreSQL 16；Testcontainers/runtime canary |
| Browser | Playwright Chromium |
| Contract | OpenAPI 3.1.x；固定版本 TypeScript generator |

## Gates

| Scope | Command | Use when |
|---|---|---|
| Fast | `tools/run-gate.ps1 -Gate fast` | 连贯改动后的快速反馈 |
| Server | `tools/run-gate.ps1 -Gate server` | Java、SQL、模块依赖变化 |
| Web | `tools/run-gate.ps1 -Gate web` | Web、OpenAPI、lockfile 变化 |
| Template | `tools/run-gate.ps1 -Gate template` | Template authority、registry、Editor static 变化 |
| Renderer | `tools/run-gate.ps1 -Gate render` | Java/Rust renderer 协议或实现变化 |
| Runtime | `tools/run-gate.ps1 -Gate runtime` | API、配置、迁移或装配变化 |
| Compose | `tools/run-gate.ps1 -Gate compose` | Compose、Dockerfile、拓扑变化 |
| E2E | `tools/run-gate.ps1 -Gate e2e` | UI、路由、关键交互变化 |
| Full | `tools/run-gate.ps1 -Gate full` | 跨面集成或发布风险需要完整覆盖 |

选择能覆盖当前风险的最小 gate。Ticket 结束运行受影响回归；不要仅为了流程状态重复 `full`。

## Operational notes

- Node 引导与 gate 可能继续使用 `.sdlc/downloads`、`.sdlc/toolchains`、`.sdlc/evidence` 等历史路径；这些只是工具缓存和日志目录，不表示证据等级或人工验收。
- runtime canary 只能停止自己创建的 Java PID 与唯一命名临时 PostgreSQL container。
- Template static gate 只操作自己创建并校验的临时目录；仓库 authority 输入只读。
- 数据库测试使用 PostgreSQL，不以 H2/SQLite 代替。
- 普通 gate 不得触发 provider、读取 API Key、发送真实数据或接触生产。
- Docker registry 或本机基础设施不可用时，如实报告未执行范围，不用其他环境冒充。
