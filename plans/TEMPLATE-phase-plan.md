# 模板 3：Phase 与任务计划

> 保存为 `plans/<milestone>.md`。优先端到端可验证增量，不按技术层横切。
> 行为规则引用 skill 内 `references/core-rules.md` 的 rule ID。

单任务 fast-track 可直接在对话或现有 issue 中写目标、AC 和验证命令，不创建本文件（`RULE-FAST-001`）。

Project Mode 由 Agent 根据 spec、原型和环境闭环先提交完整草案；人与 Agent 在这一阶段讨论范围和取舍，不让人手工从空白 Todo 开始拆分。

## 四维执行配置（RULE-MODE-001）

记录本计划的判定结果（AC 数量/用户要求 auto/多 Agent/单点风险不在此硬路由，分别影响自主/并发/风险维度）：

```text
规模：fast | project      自主：copilot | auto
风险：standard | guarded  协作：single | multi
```

判定理由：__。配置可在执行中按 `references/core-rules.md` 的迁移规则动态调整。

## Phase

| Phase | 可验证增量 | 回归范围 | 退出门控 |
|---|---|---|---|
| P0 | 最薄主路径 | 相关集成 + 关键路径 | __ |

优先处理高不确定、高影响或难回退部分，让错误尽早暴露。

## 任务卡

```markdown
### T1：<可验证目标>
- AC：AC-__
- 依赖：无 / T__
- 影响区域：__（仅用于估算与并发协调，不是文件权限白名单，RULE-SCOPE-001）
- 局部验证：__
- 回归升级条件：无 / 命中 RULE-VAL-001 提前升级清单中的：__
- 证据保证等级：A0/A1/A2/A3/J1（见 references/evidence-assurance.md）
- Review/ADR：无 / __
- 完成信号：__
```

任务大小以模型能在一次专注执行中理解、实现并验证为准，不设置固定文件数或时长。执行发现漏列文件时直接修改，在 checkpoint 概括影响即可（`RULE-SCOPE-001`）。

## 依赖与并行

- DAG 无环；标出真正的前置依赖。
- 只避免实际写冲突；共享只读文件不妨碍并行。
- 并发控制按 `RULE-CLAIM-001`：主路径为 harness/tracker 原子 claim + 隔离工作区 + 单一集成者；无原子 claim 时串行化或保持 copilot。**NOTES.md 是广播板，不承担锁职责。**
- migration、公共 schema、lock 等共享写点由单一 owner 串行处理。
- 某任务阻塞时继续其他依赖已满足的任务。

## Copilot → Auto 交接

Agent 一次性汇总：四维配置、Phase 增量、完整 Todo、关键路径、风险、各阶段退出门控、能力协商结论和预计人工 gate，并声明交接状态：

- `Plan-ready`：规格和计划足以审阅，但环境反馈闭环尚未实测，或用户本次只要求规划包。
- `Auto-ready`：环境命令与工程 canary 已实测，且 `evidence_capture` 至少为 tool（A1）；guarded 已具备 blocking permission 与恢复 gate；multi 已具备 atomic claim。

只有 `Auto-ready` 计划经人确认关键目标与取舍后，才成为阶段 4 的执行 goal；之后不为普通任务或 Phase 逐项请求批准。能力不满足时按 `RULE-AUT-001` 降级，不假装可以长程自治。`Plan-ready` 应列出升级到 `Auto-ready` 的剩余条件。

## 退出门控

- [ ] 四维配置已记录且理由清楚；无 AC 数量硬路由
- [ ] 每条 AC 被任务覆盖，每个任务有局部验证、保证等级和必要的升级条件
- [ ] 每个 Phase 都产生可验证增量
- [ ] 依赖、共享写点与并发控制方式清楚（无原子 claim 时已串行化）
- [ ] 各 Phase 的退出门控可由阶段 2 的闭环验证
- [ ] 已明确 `Plan-ready` / `Auto-ready`；若移交执行，Auto-ready 的能力前提已满足，且人已集中确认目标与关键取舍，不做逐文件签字
