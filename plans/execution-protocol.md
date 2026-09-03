# Matt Pocock skills 执行协议

本协议替代旧 Goal/Auto、Phase、证据等级和生命周期状态机。长期 goal 可以连续推进，但每个实现上下文只处理一张 ticket。

## 工作流

```text
读取当前 ticket、spec、CONTEXT-MAP.md 路由到的领域分片与相关 ADR
→ 必要时 grill-with-docs / to-spec
→ to-tickets 提议拆分并在用户批准后发布依赖与 frontier
→ implement：在约定 seam 取得 RED、实现、运行局部检查与受影响回归
→ 创建仅本地 candidate commit
→ code-review：以 ticket base...HEAD 检查 Standards + Spec
→ 修复阻断问题并 amend 尚未 push 的 candidate commit
→ 选择下一张 ready ticket
```

## Ticket 纪律

- ticket 必须描述可观察结果、范围、依赖、验收条件、测试计划和明确非目标。
- 一张 ticket 是一个可以独立实现、验证和提交的纵向增量；过大时先用 `to-tickets` 继续拆分。
- 普通实现 ticket 使用 `ready-for-agent → in-progress → done`。旧 tracker 状态只作为历史事实，不要求批量改写。
- `to-tickets` 产生的新拆分必须先经用户集中确认；已批准 ticket 集合的 frontier 实现不逐票等待。
- `wayfinder` 仅在真正需要探索未知地图时使用；它自己的 map/claim 约定不扩散为所有实现工作的治理层。

## 实现与验证

- 在预先约定的接口或行为 seam 测试先行，不为私有实现细节制造脆弱测试。
- 编辑过程中运行最短、最相关的检查；完成前运行一次受影响 gate。
- 只有共享合同、迁移、app wiring、跨语言协议或发布风险需要时才扩大验证范围。
- 受影响 gate 是本仓库对 `implement` 收尾完整测试的具体绑定；只有影响跨全仓时才运行 `full`。
- code review 的 Standards 与 Spec 发现分开处理；为满足其 fixed-point 合同，先建本地 candidate commit，阻断问题修复并 amend 后才把票记为 done。
- 提交说明以功能结果为中心。不要为证据等级、人工标签、checkpoint 或历史日志广播增加工作。

## 持续 goal

- goal 保持 active 时，从已批准 dependency frontier 持续选择下一张 ready ticket，不在普通实现 ticket 后等待用户。
- 如果同一路径阻塞，先寻找其他不依赖该决策的安全 ticket。
- 只有产品语义必须改变、新的付费/真实数据/生产/外部授权不可避免，或动作难恢复且无安全替代时，才请求用户决定。

## 安全与版本控制

- 保护现有 dirty work；只 stage 当前 ticket 的文件。
- 每张完成票独立提交。未经授权，不 push、不建 tag、不建 PR。
- 默认禁止外部模型、真实数据和生产副作用；不得读取或输出秘密。
