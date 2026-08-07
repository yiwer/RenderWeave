# ADR-0007：发布采用风险驱动证据门槛，AI Profile 必须通过分模式金标评测

- 状态：accepted
- 日期：2026-08-07
- 关联：AC-021 及全部 release AC

## 背景与约束

高覆盖率不等于关键发布/并发/模型边界正确；模型平均分还会掩盖 image-only 等弱模式。当前仓库没有 CI、独立 verifier 或分支保护，不能把本地自证描述为硬门控。

## 决策

- 每条关键 Rule/AC 绑定明确测试；coverage 只报告，不设置统一百分比 KPI。
- 领域核用 unit/golden/property；PostgreSQL graph/atomicity 用 Testcontainers；Web 用 reducer/component/Playwright；contract 做 lint/generation/drift。
- 项目本地 gate 捕获 revision/diff hash/exit/raw output，最高 A1；release/guarded 关键行为目标 A2；hard gate 只能来自未来外部 A3。
- AI corpus 最少 60 例、三模式各 20，45 dev + 15 holdout；全局和分模式分别过阈值。
- contract/evidence/DAG 100%、critical hallucination 0；其他 F1/accuracy 门槛见 spec。
- 未认证 Profile 可以实验测试，但不能成为生产默认。
- UI/业务体验、live 外部调用和真实数据权限保留 J1，不用自动测试冒充。

## 备选方案

| 方案 | 优点 | 代价/风险 | 未选择原因 |
|---|---|---|---|
| 只看 line coverage | 简单 | 可刷且不证明边界 | 采用 AC/risk trace |
| 只看全局 AI 平均分 | 指标少 | 弱模式被掩盖 | 分模式门槛 |
| 12-case corpus | 成本低 | 方差大、覆盖不足 | 扩至至少 60 |
| 本地脚本称 hard gate | 看似自治 | 没有外部强制 | 诚实限定 A1 |

## 后果与验证

- 正向：自主程度由证据能力决定，模型质量失败不会被 UI 可用性掩盖。
- 代价：release 前需要独立复跑和维护 holdout；live Phase 不能完全无人值守。
- 验证：`plans/env-gates-checklist.md` canary、evaluation report 和最终 acceptance matrix。
- 升级条件：接入受保护 CI/独立 verifier 后更新 capability contract，不能仅改文字等级。

