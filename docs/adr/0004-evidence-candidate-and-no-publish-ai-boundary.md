# ADR-0004：AI 只产生证据化 Candidate，经人工审核后 create-only，永无 publish capability

- 状态：accepted
- 日期：2026-08-07
- 关联：AC-015–AC-021

## 背景与约束

图片和有限 JSON 样本不足以证明业务结构；模型可幻觉、受提示注入影响并产生费用。合法 JSON 输出也不代表合法/正确的 Schema。产品仍希望 AI 显著减少初始建模劳动。

## 决策

- image/json/combined 都走有界 NORMALIZE→OBSERVE→STRUCTURE→VALIDATE→CRITIQUE→最多两轮 REPAIR→USER_APPROVAL→ATOMIC_CREATE。
- Candidate 是独立宽松模型；可含 blocker 和 run-local IDs，不能直接写 Schema repository。
- 每个 AI item 显示 evidence/inferred/confidence；低置信项逐个确认、编辑或删除。
- 用户可完整编辑候选；只有全部 blocker 解决后，materializer 才确定性转换为合法 DSL。
- v1 create-only：任一 key/tombstone 冲突整 bundle 零写；不 merge/update/delete existing Draft。
- 应用在同一 PostgreSQL 事务创建全部 Draft revision 0 并完成 run。
- provider/model 永远没有 publish/update/delete/SQL/filesystem/arbitrary HTTP tool；发布只能走人工 Schema journey。
- OpenAI Responses adapter 使用官方 Java SDK、`store:false`、versioned Profile、显式预算；默认无 key 也可启动。

## 备选方案

| 方案 | 优点 | 代价/风险 | 未选择原因 |
|---|---|---|---|
| 模型直接返回并保存 Draft | 路径短 | unresolved/越权/部分写难隔离 | Candidate gate 必须独立 |
| 自动发布 | 操作少 | 把概率输出变成不可变合同 | 明确禁止 |
| Agent 自动更新 existing Draft | 体验强 | 覆盖人工内容、merge/rollback 复杂 | v1 create-only |
| 完全开放 ReAct 工具 | 灵活 | prompt injection 和成本不可界定 | 固定串行 stages 更可测 |
| separate OCR first | 可能提升文字识别 | 多一模型/依赖/误差层 | 先由 eval 证明必要性 |

## 后果与验证

- 正向：AI-native 但权限最小；候选问题可定位；失败零 Schema 写。
- 代价：用户必须审核；模型质量可能导致功能保持 experimental。
- 验证：tool-manifest/architecture tests、replay corpus、fault/atomic tests、independent release eval（A2；live J1）。
- 恢复：未 apply 的 run 可取消/删除；apply 成功后的 Draft 可按普通生命周期编辑/软删除；已发生模型费用不可撤销。

