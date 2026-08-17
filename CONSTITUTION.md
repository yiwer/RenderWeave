# RenderWeave 项目宪章

> Agent 根据项目先给出推荐与理由，人选择采用、调整或不适用。这里只保存跨阶段稳定、无法从代码可靠推断的纪律。
> 行为规则的唯一创作源是 skill 内 `references/core-rules.md`；本文件按 rule ID 引用，不复述全文。

## 工程基线

| 实践 | RenderWeave 落地方式 | 决定 |
|---|---|---|
| SDD/spec-first | `specs/renderweave-v1.md` 是 Schema/Inference v1 历史权威；新增 effort 由 approved spec delta 与其冻结 source records 管辖。实现发现冲突先提交 spec delta。 | 采用 |
| 风险驱动测试 | 领域不变量先建立失败测试；DSL/编译/验证用 unit + golden + property，PostgreSQL 并发用 Testcontainers。 | 采用 |
| 独立 Review | Phase 退出前要求不同上下文 Agent、CI 或人工复核；视觉评测已有独立 Python 重算器，其他范围仍需独立 Agent/CI/人工。 | 分范围采用 |
| ADR | 只记录身份、不可变发布、编译权威、模块边界、AI 权限和编辑器状态等真实取舍。 | 采用 |
| 自动验收 | API 契约、关键业务路径和桌面 Web 路径使用自动 gate；体验与视觉选择保留 J1。 | 采用 |
| 本地运行/部署 | PowerShell gate + Docker Compose + Maven/Node lockfile 构成可复现入口。 | 采用 |

已有等价能力时直接复用，不机械新增流程；不适用时简述理由。一次性实现细节不进入宪章或 ADR（`RULE-MIN-001`）。

## 能力协商结论（RULE-AUT-001，详见 references/capability-contract.md）

> Agent 检查现有 Harness/tracker/CI/权限后填写，不是让用户填表。Auto-ready 以此为据。

```yaml
capabilities:
  evidence_capture: tool       # 项目本地 gate 捕获 revision、diff hash、exit 与原始输出；上限 A1
  atomic_claim: none           # 当前 single writer，不伪造 claim
  blocking_permission: human  # live AI、真实数据、生产/难恢复动作需人工批准
  independent_verify: limited   # 视觉评测、Template Editor static/SPEC_REGISTRY 仅在 strict inputs 内可独立重算
  isolated_workspace: available # Template effort 使用相邻 worktree；dirty main 保持只读
binding: generic + project-local tools/run-gate.ps1
```

- 本地 gate 捕获本身只能产生 A1；视觉与 Template SPEC_REGISTRY 的独立 verifier 可在各自严格输入范围内形成
  A2，但不是产品执行或外部硬门控。
- 确定性、可逆的标准风险 Phase 在 canary 通过后可达到 Auto-ready。
- live AI、真实数据、生产发布和恢复演练属于 guarded；没有 A2 与当次 human permission 时保持 copilot。
- 当前没有 CI/分支保护，因此不存在 A3 hard gate。

## 版本控制策略（RULE-REC-001，详见 references/recovery-model.md）

| 策略 | 含义 | 本项目选择 |
|---|---|---|
| `record-only` | Agent 只记录 commit/diff，不自行 commit/tag | P1–P4 采用 |
| `agent-commit` | 用户已授权按任务/Phase 提交 | **P5/P6 图片识别按既有授权；Template v1 在 `feature/template-v1` 按 verified ticket 独立提交** |
| `branch-per-agent` | 多写入者各在 branch/worktree，集成者合并 | N/A：当前 single writer |

Template 授权不包含 push、tag 或 PR；其他范围未授权时不默认创建 branch/commit/tag。

## 项目验证命令

| 用途 | 命令 | 通过标准 | 保证等级上限 |
|---|---|---|---|
| 局部/快速检查 | `tools/run-gate.ps1 -Gate fast` | whitespace、Java package 与 Web typecheck 通过 | A1 |
| 服务端回归 | `tools/run-gate.ps1 -Gate server` | Maven verify、架构与 PostgreSQL 集成测试通过 | A1 |
| Web 回归 | `tools/run-gate.ps1 -Gate web` | 固定 Node 24 下 npm ci、OpenAPI generation、typecheck、lint、unit 与 production build 通过 | A1 |
| Template 静态权威 | `tools/run-gate.ps1 -Gate template` | Editor static 与 SPEC_REGISTRY 在临时副本重放通过、冻结计数成立且 authority byte diff=0 | 捕获 A1；strict independent replay 范围 A2 |
| 完整/发布级门控 | `tools/run-gate.ps1 -Gate full` | Template static + server + Node24 web + runtime + Compose config + browser E2E 通过 | A1；发布前需 A2/CI |
| 构建/打包 | `mvn -B -ntp verify`; `npm --prefix web run build` | 全部制品生成且 exit 0 | A1 |
| 关键路径 E2E | `tools/run-gate.ps1 -Gate e2e` | Playwright 支持矩阵关键路径通过 | A1；体验另需 J1 |
| 本地运行 canary | `tools/run-gate.ps1 -Gate runtime` | 临时 PostgreSQL、实际 API 进程和 HTTP/database readiness 通过 | A1 |
| 参考拓扑部署 | `docker compose up --build` | PostgreSQL、API health 与 Web 同源入口可用 | A1；本机 registry 恢复后补证 |

安全、性能、迁移和恢复属于真实风险时，再增加对应门控。本地命令的保证等级上限由捕获方式决定，见 `references/evidence-assurance.md`。

## 验证节奏

按 `RULE-VAL-001`（局部→受影响→Phase→Goal，共享面变化提前升级，输入未变复用绿色证据）执行；完整语义见 `references/core-rules.md`，各级命令见上表。

## Agent 自主与人工边界

- 自主范围与降级：`RULE-AUT-001`、`RULE-SCOPE-001`（影响区域不是权限白名单）。
- 改变目标/AC、增加外部权限或付费、生产/真实数据、破坏性难恢复操作需要人决定（`RULE-USER-001`）。
- 局部阻塞时继续其他安全任务；只有没有安全路径时才合并询问。

## 项目稳定原则

- 历史 Schema/Inference v1 只交付 Schema Draft、StaticSchema、确定性编译/验证、AI Candidate 推断与审核；
  additive Template v1 只受 approved delta 与冻结 Template authority 管辖，不反向改写这段历史。
- 服务端是 Java 21 / Spring Boot 4.1 modular monolith；客户端是 React 19 + TypeScript strict + Vite 8.1。
- PostgreSQL 是全部环境的唯一数据库语义来源；生产代码不依赖 H2/SQLite。
- OpenAPI 3.1.2 是 HTTP 合同源；Java 服务端手写并做合同验证，TypeScript SDK 由固定版本生成。
- 所有 live 模型调用都显式启动、预算受限、可审计且默认关闭；provider-specific retention 参数只有在官方协议可证明支持时才发送。
- 源码、数据库和外部模型副作用分别恢复；Git 回退绝不冒充数据或费用恢复。

## 修订

宪章变化记录动机和日期，由人确认；不要把一次性例外永久写入。

---
版本：1.3 ｜ 采用日期：2026-08-07 ｜ P5 节点提交与 DashScope guarded delta：2026-08-08 ｜ P6 图片识别 vNext Goal 节点提交：2026-08-10 ｜ Template additive effort：2026-08-17
