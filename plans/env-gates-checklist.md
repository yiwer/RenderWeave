# RenderWeave 环境、能力与反馈闭环

- 状态：Environment canary automated-verified；独立复核与原型 J1 pending
- 日期：2026-08-17（原 P0 canary 2026-08-07；Template capability update 2026-08-17）
- Binding：`generic + tools/run-gate.ps1`
- 适用 Phase：Schema/Inference P0–P6 与 additive Template TV1-P0+；live AI 的附加 guarded gate 见 ADR-0007

## 能力协商（RULE-AUT-001）

```yaml
capabilities:
  evidence_capture: tool
  atomic_claim: none
  blocking_permission: human
  independent_verify: limited
  isolated_workspace: available
binding: references/bindings/generic.md + project-local gate
assurance_ceiling: A1 generally; A2 only for registered strict-input independent replays
```

结论：当前可由本地脚本绑定输入文件清单、Git 状态、退出码、逐步原始日志和结果元数据，达到 A1。
Template effort 已使用相邻隔离 worktree；视觉评测和 Template Editor static/SPEC_REGISTRY 有 strict-input
独立 verifier，可在各自封闭范围达到 A2。仓库仍没有 CI、A3、原子 claim 或通用产品独立 verifier，因此保持
single writer，不把局部 A2 外推为产品认证。live AI、真实数据、生产与恢复演练保持 guarded。

## 工具链实测

| 能力 | 固定/实测值 | 处理 |
|---|---|---|
| Java | Temurin 21.0.10 | 本机 gate 使用 |
| Maven | 3.8.8；POM 固定 Boot/BOM/plugin | 本机 reactor 使用 |
| Node | Web 正式固定 24.19.0 LTS；当前全局 24.12.0 只用于冻结 Template replay | `ensure-node24.ps1` 下载官方 zip、SHA-256 校验、仓库局部用于 Web；Template registry evidence 绑定各 executor runtime |
| npm | Node 24 随附版本；`package-lock.json` | Web gate 执行 `npm ci` |
| PostgreSQL | 16 Alpine | Testcontainers 与 runtime canary；无 H2/SQLite |
| Docker | Desktop 29.2.0 | 本地已有 PostgreSQL 镜像可用 |
| Browser | Playwright Chromium 151 | Node smoke + Python interaction audit |
| Contract | OpenAPI 3.1.2；Hey API 0.99.0 | 每次 Web gate 重生成并 typecheck |

Node 工具链落在忽略提交的 `.sdlc/toolchains/`，不会修改系统 Node。下载固定为 `node-v24.19.0-win-x64.zip`，校验值写在脚本中。

## 自动反馈命令

| 回路 | 命令 | 使用时机 | 通过标准 | 类型 | 上限 | 热缓存耗时 |
|---|---|---|---|---|---|---|
| 局部检查 | `tools/run-gate.ps1 -Gate fast` | 连贯改动后 | diff whitespace、Java package、Web typecheck | evidence | A1 | ~10s |
| 服务端回归 | `-Gate server` | Java/SQL/依赖变化 | Maven verify；Flyway + PG Testcontainers | evidence | A1 | ~13s |
| Web 回归 | `-Gate web` | Web/OpenAPI/lockfile 变化 | pinned Node24 `npm ci/check/build` | evidence | A1 | ~25s 热缓存 |
| Template 静态权威 | `-Gate template` | Template spec/registry/gate 变化 | 临时副本 replay 全绿、冻结计数成立、authority byte diff=0 | evidence | A1；strict replay A2 | 以 evidence 为准 |
| 关键路径 E2E | `-Gate e2e` | UI/路由/交互变化 | Chromium smoke + 三方案 Python audit | evidence + J0/J1 | A1 | ~50s |
| 运行 canary | `-Gate runtime` | API/config/infra 变化 | 临时 PG + 实际 Boot 进程 + HTTP/DB ready | evidence | A1 | ~16s |
| 拓扑检查 | `-Gate compose` | Compose/Dockerfile 变化 | `docker compose config --quiet` | evidence | A1 | ~2s |
| 完整门禁 | `-Gate full` | Phase/Goal 退出 | 上述 server/web/runtime/compose/E2E 全绿 | evidence | A1 | ~2min |

所有命令写 `.sdlc/evidence/<timestamp>-<gate>/metadata.json`、输入 SHA-256 manifest、Git status 和逐步日志。命令定义不是通过证据，只有具体目录中的退出码与输出才是。

## 失败探针与恢复验证

- Spring Boot 4.1 缺少 MVC test starter：compile gate 非零；增加模块化 starter 和新包名后恢复。
- Flyway 只引入 core：真实 PG canary 找不到 history table；改用 Boot 4 `starter-flyway` 后迁移与状态 API 通过。
- Testcontainers Ryuk registry 阻塞：线程栈定位到 image resolution；日常 canary 固定本地 PG16 并禁用 Ryuk，JUnit 仍负责正常 container stop。
- Vitest 误收 Playwright suite：失败后把 unit scope 固定到 `src`。
- Docker Node24 pull：由于 Docker registry 代理不可用，gate 保留 exit 125 失败证据；未改全局代理，改用官方校验过的局部 Node24。
- `run-gate.ps1` 的 unborn HEAD 与 PowerShell 5.1 stderr/list binder 均经失败探针修正；绿色元数据明确记录 `revision=UNBORN`，不虚构 commit。

恢复边界：runtime canary 只停止自己创建的 Java PID 与唯一命名临时 PG container；Node 引导只写
`.sdlc/downloads|toolchains`。Template static gate 只删除系统 temp 下自己创建且已校验的 GUID 目录，仓库
authority 只读。Schema/Inference 的历史 record-only/agent-commit 规则不变；Template 在独立分支按 verified
ticket agent-commit。普通 gate 没有数据库生产、真实数据或外部模型副作用。

## 协作与决策闭环

- [x] `AGENTS.md` 含命令、目录、项目禁区和受管规则。
- [x] SDD、风险测试、ADR、Gate、checkpoint 的落点明确。
- [x] 普通失败输出足以自主定位，并已完成多次红→绿干跑。
- [x] guarded 操作写明权限和三类恢复边界。
- [x] Template Editor static/SPEC_REGISTRY 与视觉评测已有封闭输入独立 verifier；其他产品范围和 release 仍需 A2。
- [ ] 编辑器 A+B 推荐组合仍需用户 J1。
- [ ] Docker registry 恢复后执行 `docker compose up --build`；当前只有 config + 等价 live runtime canary。

## 项目特有风险

- 安全/敏感数据：AI 输入默认 synthetic/replay；真实图片/RootDocument 与 key 需要当次授权；日志不得保留原文或 chain-of-thought。
- API/数据/migration：OpenAPI 是合同源；PostgreSQL 是唯一语义源；Static 不可 update/delete/recompile；migration 必须 forward + recovery evidence。
- 性能/成本：256-field UI、2 MiB compiled artifact、10k/100k 数据基线在对应 Phase 才测；live model profile 有调用/费用预算。
- 部署/恢复：Compose 是单节点参考，不承诺 HA；DB、BlobStore、源码和不可撤销模型费用分别报告恢复。

## Goal 最低保证（RULE-GOAL-001）

| 场景 | 要求 |
|---|---|
| standard deterministic task | 相关 gate A1；Phase 退出优先补 A2 |
| project + standard + auto | P0 full A1 + 计划/原型 J1；无原子 claim 时 single writer |
| guarded live AI/真实数据/生产 | A2 + 当次 human permission + 恢复/停止条件 |
| hard gate | 只有未来 CI/策略外部强制结果可记 A3 |
| 体验/业务选择 | 明确 J1；自动截图不能替代 |

## 退出结论

最小工程 canary 已覆盖 spec→实现→失败定位→修正→分层验证→实际运行→证据；Template static gate 另覆盖
frozen authority 的无写重放。Agent 不需要用户代跑普通命令。P0 自动部分可记 `automated_verified`；局部 A2
不替代产品独立复核，原型选择仍为 `human_acceptance_pending`，Compose 全拓扑与 release evidence 继续 pending。
