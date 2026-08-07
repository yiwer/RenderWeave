# 模板 5：验收与沉淀

> 保证等级见 `references/evidence-assurance.md`；状态语义见 `references/core-rules.md` 的 `RULE-STATE-001`。

## AC 验收

| AC | 结果 | 保证等级 | 证据（revision + 来源 + 指针） | 未完成处置 |
|---|---|---|---|---|
| AC-1 | pass/fail/deferred | A1/A2/A3/J1 | __ | __ |

- `deferred` 必须来自已批准的 spec delta，不能用来偷绕承诺的 AC。
- hard gate 只能由 A3 外部强制结果满足；人工体验/业务验收须 J1（可定位到人、时间、对象、结论）。

## 生命周期状态报告（RULE-STATE-001）

最终状态：`planned / implementation_complete / automated_verified / human_acceptance_pending / accepted / released / closed`（辅助 `auto_paused / rejected / deferred`）。

> 示例：实现与自动门控已完成，AC-5 人工体验验收待签字 → 报 `human_acceptance_pending`，不报 `accepted`，也不隐瞒实现与自动验证已完成。

## 一致性检查

- [ ] 最后一次代码变更后的完整/发布级门控通过，或已有等价/更强的最新绿色证据（`RULE-EVD-003`）
- [ ] 关键路径 E2E 与受影响回归通过；未机械重复无关回归（`RULE-VAL-001`）
- [ ] 每条 evidence gate 的保证等级与 Goal 最低要求相符（`RULE-GOAL-001`）；Agent 自填项已标 self-reported（`RULE-EVD-002`）
- [ ] hard gate 由 A3 满足，未用文本伪装硬边界（`RULE-HARD-001`）
- [ ] spec、实现和测试描述同一行为
- [ ] 关键架构决定有 ADR，普通实现细节没有被过度文档化（`RULE-MIN-001`）
- [ ] 若涉及数据/外部副作用，恢复已分别报告，未以源码回退冒充（`RULE-REC-001`）
- [ ] 未完成项已明确延期、降级或拒绝

## 复盘

1. 哪个假设最晚才被证伪？
2. 哪个验证回路最有价值，哪个只是制造等待？
3. 哪次完整回归触发得太早或太晚，下一次如何缩短反馈？
4. 哪次用户打断本可由模型判断或已有证据避免？
5. 再锚定是否及时发现过漂移？哪个保证等级被高估或低估？
6. 有什么经验值得自动化或写入长期规则？

只沉淀会重复使用的内容。没有新知识时记录「无变更」，不强制制造宪章或模板更新。

## 关闭门控

- [ ] 承诺 AC 有结果、保证等级和可定位证据
- [ ] 最终完整/发布级门控通过或复用了等价的最新绿色结果
- [ ] 生命周期状态已如实报告；无 pending 判断 gate 被冒充为 accepted
- [ ] 没有未处置的目标漂移或高风险问题
- [ ] NOTES 留有最终状态与后续入口
