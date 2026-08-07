# Checkpoint：<task-id>

- 结果：完成 / 局部阻塞 / 失败 / auto_paused
- AC：__
- 生命周期状态：implementation_complete / automated_verified / human_acceptance_pending / …（RULE-STATE-001）
- 摘要：__

## 验证证据

> 优先由 Harness 生成，Agent 只补充解释（见 `references/evidence-assurance.md`）。
> Agent 自填字段必须标 `self-reported`，**不能把 A0 升级为 A1**。命令本身只是复现配方，不是通过证据。

| Gate | 保证等级 | Revision | 命令/来源 | Exit/结论 | 原始证据指针 | 验证者 captured_by | 新鲜度 |
|---|---|---|---|---|---|---|---|
| | A0/A1/A2/A3/J0/J1 | | | | | | exact_revision/impact_analyzed/stale |

- 证据复用须满足 `RULE-EVD-003`（revision/输入绑定、gate 未变、产物可访问）；复用理由：__
- 人工 gate 只能是 J0/J1，不得以 A 级自证代替。

## 重要偏差

- 无 / __
- 是否需要 spec delta：否 / __（`deferred` 必须来自已批准 delta，不得偷绕 AC）
- 再锚定是否发现漂移：否 / __（RULE-ANCHOR-001）

## 多 Agent（如适用，RULE-CLAIM-001）

- 本任务 claim：task_id / owner / base_revision / write_scope / 释放：是/否
- NOTES.md 仅作广播，不作锁。

## 下一步

- 下一任务或恢复入口：__
- 需要用户决定：无 / __（只列真正阻塞项，RULE-USER-001）
