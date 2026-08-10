# ADR-0021：DashScope 阶段时限与 Provider 调用前续租

- 状态：Accepted
- 日期：2026-08-10

## Context

product-v3 的串行视觉流程使用 90 秒固定 HTTP deadline。真实运行中元素、
层级和归属阶段均成功，而更长的 Candidate 生成两次均在约 90 秒终止。
`HttpTimeoutException` 继承 `IOException`，原 adapter 将其误报为笼统的网络错误。

同时 durable worker 只在 claim 时取得 lease。提高外部调用时限后，必须在每个
不可撤销调用前刷新 lease，避免调用仍在进行时被其他 recovery worker 当作过期任务。

## Decision

1. 不修改已发布的 v3 Profile；新增 product-v4，阶段 timeout 固定为 240 秒。
2. 保持 pipeline、Prompt、模型、单次费用、总调用、repair 与输出上限不变。
3. 每个 Provider call 之前先续租，再预留费用，最后发送请求；任一前置失败都不得发送。
4. timeout 与其他 network I/O 使用不同的 bounded failure code。
5. 旧 run 的 retry 仍使用旧 snapshot；不在重试边界中悄然升级 Profile。

## Consequences

- 复杂 Candidate 生成获得更长的有界等待时间，但仍可超时并安全失败。
- 超时诊断可与 DNS/TLS/连接失败区分。
- 新建 v4 run 才能使用新时限，这保留了历史可重放性。
- 本地续租仍是单节点 v1 边界；多实例全局并发与 lease 语义需要独立设计。
