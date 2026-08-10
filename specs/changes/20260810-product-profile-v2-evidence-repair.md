# Spec Delta：Product Profile v2、精确 Candidate repair 与 ¥2 单次上界

- 状态：merged
- 触发任务：P6/T6-3a.1
- 触发证据：用户于 2026-08-10 要求将单次预留上界调整到 ¥2，并一同修复真实运行中的
  `assessment.evidence` 结构错误
- 影响 AC/规则：AC-015、AC-019、AC-020、AC-021；R-INF-001、R-INF-007
- 再锚定关系：本 delta 替代产品入口中的 product-v1 revision，不改写任何既有 Profile/run snapshot。

## 运行事实

真实 `dashscope-qwen38-max-product-v1` IMAGE_ONLY run 连续三次得到 HTTP 成功响应，但三次均被严格
codec 以 `CANDIDATE_DECODE_CONSTRUCTOR_INVALID_ASSESSMENT_EVIDENCE` 拒绝。每次实际费用约
¥0.124–¥0.137、预留约 ¥0.449，远低于 Profile 与 run 费用门，因此费用不是失败原因。

Worker 把精确 diagnostic 写入 attempt telemetry，却在 checkpoint/repair 路径降级为
`LIVE_STRUCTURE_OUTPUT_INVALID`。后两次 repair 无法知道 `assessment.evidence` 为 null/缺失，最终以
`LIVE_REPAIR_BUDGET_EXHAUSTED` 结束且没有 Candidate。

## 变更

### ADDED

- 新增 Prompt 4.0：`assessment` 必须精确包含 `confidenceBps/inferred/resolution/evidence`；每个 active
  Schema/field 的 `evidence` 必须是存在、非 null 且非空的 JSON 数组。
- 新增四个 product-v2 Profile，模型顺序不变，Prompt 固定为 4.0，单次保守预留上界统一为
  2,000,000 micros CNY（¥2）。
- 新增真实 workflow 回归：`evidence:null` 首次拒绝后，repair task 必须携带精确 diagnostic，第二次
  合法响应进入 `REVIEW_REQUIRED`。

### MODIFIED

- codec 失败的 bounded diagnostic 同时进入 attempt taxonomy 与 checkpoint validation problems；repair
  不再只收到通用结构错误。
- OpenAPI、generated client、Web 默认 Profile 与 mocked browser fixtures 切换到 product-v2。
- 若 Max 大图批次的保守调用估值超过 ¥2，worker 在 reservation/Provider 之前拒绝；run 累计上限
  不能放宽单次 Profile 上界。

### PRESERVED

- product-v1、Prompt 3、既有 run snapshot、attempt/reservation 均不修改；旧失败 run 的 retry 仍使用
  原 snapshot，但可受益于 worker 的精确 repair feedback。
- 最大调用次数仍为三次，任务累计成本限额仍可填或留空，模型目录与外发确认不变。
- 常规日志/证据仍不保存完整 provider request/response；本次诊断只使用 bounded taxonomy 和 usage。

## 决策

- 批准人：yiwer
- 日期：2026-08-10
- 结论：批准。单次预留上界采用统一 ¥2，Candidate 解码失败必须把精确、无载荷 diagnostic 反馈给
  repair；版本化发布，不原地修改已用 Profile。
