# ADR-0016：Product Profile v2 使用精确解码修复反馈与 ¥2 单次预留上界

- 状态：Accepted
- 日期：2026-08-10
- 决策来源：真实 qwen3.8 Max 失败诊断及用户对 evidence 修复和 ¥2 上界的明确要求
- 关联：ADR-0015、AC-015、AC-019、AC-020、AC-021、P6/T6-3a.1

## 背景

Profile 是不可变、随 run 保存完整 snapshot 的版本化资产，不能为了调整费用或 Prompt 原地修改
product-v1。真实失败也证明 attempt telemetry 的精确诊断如果未进入 repair task，最多两次 repair
只会重复消耗调用预算，而不能针对实际合同槽位纠错。

## 决策

1. 保留全部 product-v1/Profile 3 资源，仅将产品目录切换到四个 product-v2/Profile 4 资源。
2. product-v2 的 `maximumEstimatedCostMicrosCny` 全部固定为 2,000,000。任务累计限额只会进一步
   收紧，不得放宽该单次门。
3. `InvalidCandidateContractException.diagnosticCode` 同时写入 bounded attempt taxonomy 和 checkpoint
   blocker；下一次 repair 原样接收该 code。只有旧 checkpoint 缺精确诊断时才使用通用 fallback。
4. Prompt 4 明确 `assessment.evidence` 必须存在且为非 null 数组，并解释对应 constructor diagnostic。
5. 不持久化原始模型响应来实现修复；精确 code、计数、token 和费用已经足够完成安全反馈与审计。

## 后果

- Flash/Plus 的单次门提高到 ¥2；两个 Max 的单次门由 ¥2.50 收紧为 ¥2，四模型 UI 一致。
- 最大分辨率十图的 Max 请求可能因保守估值超过 ¥2 而零调用失败，这是明确成本边界，不是运行故障。
- Prompt/worker 改善 repair 成功概率，但不构成模型质量认证；Profile 继续为 `EXPERIMENTAL`。
- 回退只需把产品目录指回 product-v1 并恢复 worker 版本；既有数据库无需 down migration。
