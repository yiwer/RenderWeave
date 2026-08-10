# Spec Delta：IMAGE_ONLY 可审核技术规范化与执行日志

- 状态：merged
- 触发任务：P6/T6-3a.4
- 触发证据：真实 Product v2 IMAGE_ONLY run 以 `LIVE_UNSAFE_BLOCKER_SET` 结束；用户要求在识别审核页
  展示完整进度和过程
- 影响 AC/规则：AC-015、AC-017、AC-019、AC-020；R-INF-001、R-INF-007
- 再锚定关系：补充 ADR-0010 的 mixed-blocker 路由，不修改既有 Profile、Prompt 或历史 run snapshot。

## 运行事实

失败 run 只有一次成功 STRUCTURE attempt：4,196 input tokens、2,343 output tokens、22,083 ms、实际
费用 2,715 micros CNY。无载荷 taxonomy 为：

- `CANDIDATE_SCHEMA_KEY_INVALID` ×1；
- `CANDIDATE_SCALAR_SHAPE_INVALID` ×6；
- `CANDIDATE_ITEM_UNRESOLVED` ×1。

前两类是可确定处理的技术格式偏差，最后一类必须由用户判断。旧策略正确禁止把混合 blocker 交给模型
完整重建，却也因此无法保留这份可审核 Candidate。

## ADDED

- IMAGE_ONLY Candidate 的窄域技术规范化：生成合法唯一 SchemaKey，清空合法标量的冗余 observedKinds，
  并保留 WARNING。
- `GET /api/v1/inference-runs/{runId}/execution-log` 与生成客户端合同。
- 审核页执行日志：阶段事件、模型调用、Token、费用、耗时、outcome 与有限问题码；活动任务每秒刷新，
  支持手动刷新、折叠、渐进展开和事件窗口截断提示。

## PRESERVED

- 不自动处置低置信、UNRESOLVED/CONFLICT 或 required/constraint 语义，不改字段身份、类型、证据或拓扑。
- scalar 的 items/reference 冲突及任何未知 trust blocker 继续 fail-closed。
- 日志不返回原始 event data、Provider request id、图片、RootDocument、Prompt、模型/Candidate 原文或思维链。
- 旧失败 run 不重写；用户点击重新运行会创建带 `retryOfRunId` 的新任务。

## 验证

- 精确真实问题形状的 PostgreSQL workflow 回归证明：Provider 只调用一次，技术问题转 WARNING，人工未解析项
  原样进入 `REVIEW_REQUIRED`。
- API 合同回归证明 execution log 可见事件与 attempt，且没有 event data 或 Provider request id。
- Web component/E2E 覆盖失败任务 taxonomy、费用/Token、1024/1280 可读性、键盘与无横向溢出。
