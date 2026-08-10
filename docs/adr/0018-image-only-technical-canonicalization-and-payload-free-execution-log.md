# ADR-0018：IMAGE_ONLY 技术规范化与无载荷执行日志

- 状态：Accepted
- 日期：2026-08-10
- 决策来源：真实 Product v2 IMAGE_ONLY run 因技术格式 blocker 与人工语义 blocker 混合而以
  `LIVE_UNSAFE_BLOCKER_SET` 结束；用户要求在审核页查看完整进度与过程
- 关联：AC-015、AC-017、AC-019、AC-020、ADR-0010、ADR-0012、ADR-0014、P6/T6-3a.4

## 背景

真实 run 的唯一成功 Provider 响应包含一个需人工判断的未解析项，同时包含无业务语义的技术格式偏差：
非法 `proposedSchemaKey`，以及六个标量值携带冗余 `observedKinds`。既有 mixed-blocker 策略必须阻止模型
在第二次完整重建中顺带删除人工不确定项，因此该响应被安全拒绝；但这些技术偏差可以在不改变字段、
类型、证据或拓扑的前提下由本地确定性处理。

任务页原先只显示最终 failure code，无法回答每轮调用的阶段、Token、费用、耗时与有限问题分类。直接
展示原始请求、响应或事件 data 又会违反载荷与凭据边界。

## 决策

1. 仅对 `IMAGE_ONLY` Provider Candidate 执行本地技术规范化，并发生在严格 validator 之前。
2. 缺失、非法或重复的 `proposedSchemaKey` 替换为基于 Candidate 局部 UUID 生成的合法唯一技术 key，记录
   `CANDIDATE_SCHEMA_KEY_NORMALIZED` WARNING。
3. TEXT/DECIMAL/DATE/TIME/BOOLEAN 标量仅在 `items=null`、`reference=null` 时清空冗余
   `observedKinds`，记录 `CANDIDATE_SCALAR_OBSERVED_KINDS_NORMALIZED` WARNING。
4. 不修改 fieldKey、显示名称、required、推断类型、约束、证据、置信度、resolution、引用目标或图拓扑；
   scalar 同时带 items/reference 等真实结构冲突继续由 validator 阻断。
5. 规范化后若只剩人工 blocker，Candidate 进入 `REVIEW_REQUIRED`；仍存在 trust/未知 blocker 的组合继续
   fail-closed，不降低 ADR-0010 的 mixed-blocker 边界。
6. 新增只读 `GET /api/v1/inference-runs/{runId}/execution-log`：返回 authoritative run、最多 1000 条
   结构化事件元数据，以及该 run 全部 attempt 的 stage/status/model、Token、费用、耗时、outcome code 和
   bounded problem-code counts。
7. 执行日志明确不返回事件 data、Provider request id、图片、RootDocument、Prompt、Candidate/model 原文、
   stack trace 或 chain-of-thought；超过事件窗口时返回 `truncated=true`。
8. Web 在活动任务中每秒刷新，并在任务状态之后、Candidate 工作区之前展示可折叠时间线；超过 100 个
   DOM 条目时按 100 条逐步展开。

## 后果

- 可审核的人工不确定项不再被纯技术格式问题拖成 `LIVE_UNSAFE_BLOCKER_SET`。
- 本地规范化不是模型质量认证，也不把 Profile 从 `EXPERIMENTAL` 升级；旧失败 run 保持不可变，需通过
  retry 创建新 run 才能受益。
- “完整日志”指完整的受控结构化生命周期与 attempt 统计，不是原始模型转储；诊断能力提高但隐私边界不变。
- 若将来需要超过 1000 个事件，必须设计游标分页，不能取消服务端窗口或把事件 payload 暴露给浏览器。
