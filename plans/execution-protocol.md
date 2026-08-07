# 模板 4：Goal/Auto 长程执行协议

> 阶段 3 确认的 Todo、AC 与 Phase 门控共同构成一个 goal。除真正阻塞外，Agent 连续执行到 goal 完成，不在每张任务卡后等待用户。
> 行为规则引用 skill 内 `references/core-rules.md` 的 rule ID。

## 自主执行循环

```text
读取宪章、spec、计划、NOTES 与能力协商结论
→ 领取依赖已满足的下一任务（并发按 RULE-CLAIM-001）
→ 建立失败/基线证据 → 实现 → 局部验证 → 必要 review/ADR
→ 写简短 checkpoint/NOTES（记录保证等级与 captured_by）
→ 继续下一 ready 任务
→ Phase 门控全过 → 再锚定检查（RULE-ANCHOR-001）→ 进入下一 Phase
→ Todo 全部完成 → 最终验收（phase5，按生命周期状态机报告）
```

## 自主范围（RULE-AUT-001 / RULE-SCOPE-001）

- 当前 goal 内需要的仓库文件可直接读写，影响区域不是权限白名单。
- 具体实现、代码组织、测试组合和可逆取舍由 Agent 根据仓库判断。
- checkpoint 用于可见性和恢复，不是例行审批点。
- 能力或证据不足时降级（如 auto→copilot），不用散文伪装能力已存在。

## 规范遵循

每项任务按宪章采用的 SDD/TDD/Review/ADR 和验收规则执行，并按 `RULE-GOAL-001` 达到对应保证等级。若某实践在具体任务不适用，Agent 在 checkpoint 简述理由，不需要逐次请示。

跨会话或长程任务使用 `plans/logs/TEMPLATE-checkpoint.md`。单会话任务的最终验证摘要就是 checkpoint；普通文件差异由版本控制提供。

## 再锚定检查（RULE-ANCHOR-001，Phase 门控通过后必做）

对照顺序：当前批准 spec → 已批准 spec delta → 当前 goal/AC/非目标 → 用户原始意图。**已批准的合法需求演化不是漂移。**

1. 当前每项工作能否追溯到有效 AC、风险缓解或已批准 delta？
2. 当前 diff 是否触碰任何非目标？
3. 是否新增了未在 spec/ADR 中出现的外部接口、数据状态或权限语义？
4. 当前测试是否证明用户价值，还是只证明内部实现自洽？
5. 剩余任务是否仍是完成 goal 的必要条件？
6. 若用户只看当前可观察行为，是否会认为它解决了批准的问题？

发现疑似漂移：先做影响分析与可逆修正；能由现有 spec 唯一决定则自行处理；必须改变产品语义时才提交 spec delta 或询问用户。再锚定不应变成新的高频打扰源。长 Phase 可在 checkpoint 触发，不必等到 Phase 结束。

## 熔断（RULE-BREAK-001）

出现以下任一，进入 `auto_paused`（不是项目失败），保留仍有效的增量并给出恢复入口，不静默重试：

- 同一失败再次发生且本轮没有新假设、新证据或新实验；
- 多个独立假设被证伪，达到已配置探索上限；
- 已实例化预算且消耗与通过 gate 的增量显著失衡（平台无预算时不启用此伪指标）；
- 必须撤销多个已验证任务才能继续（累积误差信号）；
- 再锚定连续发现同类目标偏差；
- 进入未获授权的 guarded 范围。

熔断报告：当前状态 `auto_paused`；已完成且仍有效的增量；失效/可疑增量；已验证假设；新证据；未解决决策；推荐（继续探索 / 调整 spec / 回退 / 人工接管）。

## 恢复（RULE-REC-001，详见 references/recovery-model.md）

源码、数据、外部副作用三类恢复分别建模与报告；Git 回退不冒充数据或生产恢复。

- 默认优先级：revert 自己拥有的独立 commit → 恢复明确列出的文件/区块 → 隔离 branch/worktree 重建 → 仅在用户明确授权且确认无他人成果时整体回退。
- 禁止默认：`git reset --hard`、覆盖未提交用户修改、回退其他 Agent 的已验收成果。
- 不默认创建 tag/branch/commit（遵循宪章选定的版本控制策略）。
- guarded 回退前确认数据 down migration / 备份、外部 provider rollback / 补偿路径存在；无则显式标注「未覆盖」。

## 验证节奏（RULE-VAL-001，行动点摘要）

连贯改动后跑最短局部检查，不每次编辑后跑全套；任务/批次结束跑受影响回归；Phase 退出跑相关集成、契约与关键路径；Goal 完成确保最后一次代码变更后有完整/发布级绿色证据，上层已等同或覆盖则复用。升级触发与绿色证据复用见 `references/core-rules.md`。相同失败不再提供新信息时先换假设，不反复回测。

## Spec 回流与用户介入

实现发现规格错误：暂停受影响任务 → 记录新事实 → 提 spec delta → 更新影响分析。还有独立任务时继续执行。

仅当目标/非目标/AC 必变、需新外部权限/付费/生产或真实数据、操作破坏性难恢复或越权，且无安全替代任务时，才合并请求用户（`RULE-USER-001`）。普通测试失败、漏列文件、等价重构、实现选择和首次诊断不请求用户。

## 多 Agent（RULE-CLAIM-001）

- 主路径：harness/tracker 原子 claim → branch/worktree 隔离 → 单一集成者合并 → 跨 Agent 回归。
- 无原子 claim：单写入者或协调者串行派发，保持 copilot，不宣称 multi Auto-ready。
- 文件型 claim 仅 fallback（见 `references/bindings/generic.md`），不默认创建。**NOTES.md 不承担锁职责。**
- 每个 Agent 交付包含 base revision、最终 revision/diff、gate evidence、新 ADR/spec delta 与推荐集成顺序。

## Goal 完成（RULE-STATE-001 / RULE-GOAL-001）

完成全部 Todo 后自动使用阶段 5 模板，按生命周期状态机如实报告：

- 对照每条 AC 收集对应保证等级的证据（A0–A3/J0–J1）；
- 确认所有 Phase 退出门控通过；
- 确认最后一次代码变更后的完整/发布级门控为绿色，允许复用等价或更强的最新结果（`RULE-EVD-003`）；
- 审计 spec、实现、测试和关键 ADR 一致；
- 明确处置未完成项并沉淀真正可复用的经验。

自动 gate 过而人工 gate pending 时报 `automated_verified`，不报 `accepted`。达到对应状态的判据全部满足才报告该状态。
