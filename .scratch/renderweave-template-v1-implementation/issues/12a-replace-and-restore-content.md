# 实现 Asset content replace 与旧内容恢复

Type: task
Status: open
Blocked by: 05, 11

## Question

如何在既有 Asset persistence 纵切上物化 content replace（携带 `expectedAssetRevision`，先完成验证与安全暂存
再原子追加 contentVersion、切换 current 并增加 assetRevision；验证后内容与 current 完全相同时成功但 no-op，
不增加 revision/事件）与旧内容恢复（复用旧 Blob、追加新 contentVersion 并推进 current，绝不回拨或修改旧
版本）？每次有效操作追加有界审计事件（assetId、前后 assetRevision、actorId、时间、操作类型、内容版本身份，
不记原始字节），并记录 STALE 事实；Template 侧 STALE 消费/反向索引属于 Template 依赖投影票，不在本票实现。
失败必须零部分写入，不暴露未提交的新版本；容量水位 fail-closed 继续生效。本票不实现 delete/restore、
确认 token、Resolver/lease 或 UI。
