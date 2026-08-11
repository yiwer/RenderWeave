# ADR-0032：v42 扩展但仍受控的产品运行边界

- 状态：Accepted
- 日期：2026-08-12
- 决策来源：用户对 `J1-RW-V41-LIVE-20260812-0345` 的明确反提案
- 关联：ADR-0015、ADR-0021、ADR-0031、AC-015、AC-020、P6/T6-3a

## 背景与约束

product-v41 把单个 run 固定为最多五次 Provider call、每个 stage 240 秒和单步 8192 output tokens。用户要求
把本次产品运行边界调整为最多七次、360 秒和 16384 output tokens，并把任务累计成本硬上限设为 ¥5。

v41 Profile、run snapshot 与既有验证证据已经冻结，不能原地改写。pipeline 4.28 的零 GROUP 回退、Prompt、
Document Vision capability、模型定价、Candidate 合同和严格 validator 不需要改变。600 秒 durable lease 足以覆盖
单次 360 秒请求，但每次不可逆调用前仍必须续租和预留费用。

## 决策

1. 新增三份 immutable product-v42 Profile，模型仍精确绑定 `qwen3.7-plus`、`qwen3.8-max` 和
   `qwen3.7-flash`；复用 pipeline 4.28、Prompt 10/7/3、Document Vision capability 与全部语义合同。
2. v42 固定 `maximumTotalCalls=7`、`stageTimeoutSeconds=360`。具有官方 64000 output-token 证据的 Plus/Flash
   固定 `maximumOutputTokens=16384`；只有历史 exact-alias live 证据且无 advertised output 上限的
   `qwen3.8-max` 保持 8192。`maximumRepairRounds=0`、`maximumOutputBytes=262144` 与每次保守预留上界 ¥2
   保持不变。
3. 新建 product-v42 run 必须携带 `costLimitMicrosCny`，允许范围为 1..5,000,000。全部已结算和仍预留费用
   的累计值不得超过它；缺失、非法或超限在 Provider 调用前失败。Web 默认并锁定启用该门，初值为 ¥5。
4. 产品目录切换到 v42，默认 Plus；v41 及更早 Profile 保持不可变、可读和可恢复，但不再接纳新产品 run。
   历史 retry 仍继承原 snapshot 与原任务成本边界，不静默升级到 v42。
5. 启动、readiness、选择文件和切换 Profile 都必须保持零 Provider 调用。v42 仍为 `EXPERIMENTAL`，不继承
   v41 的工程或 live 质量结论，也不允许跨模型自动 fallback。

## 备选方案

| 方案 | 优点 | 代价/风险 | 未选择原因 |
|---|---|---|---|
| 原地修改 v41 | 文件更少 | 改写不可变 snapshot 与既有证据含义 | 违反 Profile 不可变边界 |
| 仅扩大人工 J1、不改 Profile | 无代码变更 | 应用仍会在 5/240/8192 处停止，授权与实际能力不一致 | 不能满足用户要求 |
| Max 也直接提高到 16384 | 三模型表面一致 | exact alias 没有 advertised output 上限证据 | 能力矩阵必须继续 fail-closed |
| 取消单次 ¥2 预留门 | 大输入更少被拒绝 | 单次不可逆费用暴露扩大 | 用户只要求任务累计 ¥5，没有要求放宽单次门 |
| 保持成本上限可选 | 兼容旧产品行为 | 无法保证本次 run 累计不超过 ¥5 | 与本次明确边界不一致 |

## 后果与验证

- 正向后果：同一受控 run 可容纳额外阶段回退和更大结构化输出，同时任务累计费用由服务端硬限制为 ¥5。
- 负向后果：最坏等待窗口和输出面扩大；七次调用并不保证 Candidate 成功，Profile 继续是实验状态。
- 验证：Profile/registry、API policy、required cost limit、OpenAPI/generated client、Node 24 Web、真实
  PostgreSQL synthetic workflow、full gate 和本地 Document Vision canary；所有自动验证清空 Key/live 环境。
- 回退：停止 live；新建目录可切回 v41，但已创建 v42 run 必须继续按其 immutable snapshot 恢复。
