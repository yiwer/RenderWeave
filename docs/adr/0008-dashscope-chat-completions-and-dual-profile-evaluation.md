# ADR-0008：DashScope Chat Completions 首适配，双视觉 Profile 独立评测后再路由

- 状态：accepted
- 说明：模型能力与后续矩阵已由 ADR-0009 部分修正；本 ADR 保留首轮 canary 的历史决策。
- 日期：2026-08-08
- 关联：AC-015、AC-016、AC-020、AC-021
- 决策来源：用户批准千问 AI 平台、`qwen3.7-flash` / `qwen3.8-max`、synthetic-only ≤6 attempts / ≤¥1 live canary（J1）

## 背景与约束

P4 只有零网络 `replay-v1`。旧规格预设 OpenAI Responses API 与 `store:false`，但首个真实 Provider 已改为千问 AI 平台。官方接入以 OpenAI-compatible Chat Completions、`DASHSCOPE_API_KEY`、Base64 图像和 JSON mode 为稳定交集；JSON mode 只保证合法 JSON，不能替代 RenderWeave Candidate 合同与确定性校验。

`qwen3.7-flash` 与 `qwen3.8-max` 都支持图像/文本/视频输入和结构化输出，但成本和能力定位不同。未经同一金标语料评测，不能把价格或模型名称当作质量证据，也不能先验固化自动升级路由。

## 决策

- 领域层新增 provider-neutral port；DashScope HTTP DTO、认证与 endpoint 只在 application adapter。
- 协议使用 `POST /compatible-mode/v1/chat/completions`，图片由规范化 artifact 转为 Base64 Data URL；不接受远程图片 URL。
- 每次调用使用 JSON object response format、关闭 thinking、禁用 tools/search，并由既有 Candidate codec/validator 再校验；repair 与 retry 都计入调用和费用预算。
- 建立两个 repo-versioned `EXPERIMENTAL` Profile：`dashscope-qwen37-flash-v1` 和 `dashscope-qwen38-max-v1`。Prompt、协议、模型、价格快照、token/call/费用预算和评测身份均进入 Profile snapshot。
- 先在完全相同的 45 dev + 15 holdout 金标集分别运行；只有达到 spec 分模式门槛后才可认证。默认模型与 Max 升级条件由评测结果决定，不在本 ADR 中先验指定。
- Provider 页面只公开浮动模型 ID、未证明不可变 snapshot 时，评测结论绑定 Profile 版本、执行日期及 provider 返回的可用模型标识；模型行为变化使旧认证失效。
- API Key 只读取 `DASHSCOPE_API_KEY`，不进入 Profile、数据库、UI、日志、异常或证据。
- 当前 live J1 只允许仓库合成数据，最多 6 次 provider attempt、累计费用上限 ¥1。真实业务数据、扩大调用或预算均需新 J1。
- worker 与任意 multipart 外传使用两个默认关闭的部署门；Provider credential 已配置不等于获得执行或数据外传权限。新建 live run、复制保留输入的 live retry 和 queued recovery 复用同一组 worker/upload/credential 门。已关闭授权下 Compose overlay 同时保持两个门为 false，避免领取历史队列。
- 调用前使用 UTF-8 文本字节 + 2,048 framing tokens + 每图 1,024 visual tokens + Profile 最大输出 tokens 形成费用上界。千问默认视觉预算约 326 tokens/图，应用不启用最高 16,384 tokens/图的 `vl_high_resolution_images`，因此 1,024 是当前协议下的保守边界；Flash 再按官方 32K/256K 输入长度档使用 1x/3x/6x 输入与输出单价。计算结果超过 Profile 或全局剩余预算则不调用。依据：<https://platform.qianwenai.com/docs/developer-guides/run-and-scale/token-counting>、<https://platform.qianwenai.com/docs/developer-guides/getting-started/pricing>。
- provider reservation 是不可由 run 删除级联抹除的账本；V009 以追加迁移移除 destructive FK，保留 immutable run UUID 审计值。reservation 事务先以 `FOR KEY SHARE` 验证 run 存在，提交后仍允许用户删除 run 与素材而不擦除费用。授权账本在实际 2 attempts / ¥0.054017 后标记 `CLOSED`，旧 canary 即使重新设置环境 gate 也会在网络前失败。
- `Retry-After` 不再被忽略：当前策略是终止 run 并保留一次已预留 attempt，不做即时重试。以后若实现延迟重排，必须先有持久 `not-before` 状态及恢复测试。

## 备选方案

| 方案 | 优点 | 代价/风险 | 未选择原因 |
|---|---|---|---|
| 继续实现 OpenAI Responses | 贴合旧规格 | 与已批准 Provider 不一致 | 由本 ADR 正式替代 |
| 直接依赖 DashScope Java SDK | 本地文件接口方便 | provider 类型泄漏、升级面较大 | OpenAI-compatible HTTP 足够且边界更窄 |
| Flash 默认、失败自动 Max | 体验直接 | 自动升级成本且语义错误未必被 validator 捕获 | 先用 eval 决定 |
| 只评 Max | 可能质量更高 | 缺少成本/质量基线 | 双 Profile 同集对比 |
| 保存完整 Provider I/O 调试 | 排障方便 | 数据、prompt injection 与密钥风险 | 仅保留安全元数据 |

## 后果与验证

- 正向：领域与供应商协议解耦；模型选择有同集证据；无 Key 时确定性功能仍可运行。
- 代价：需要维护 Prompt/Profile 与真实金标 corpus；浮动模型 ID 的认证必须时间化。
- 验证：fake transport 合同、密钥缺失降级、预算/重试单测、真实 PG/API/UI 闭环、≤6 次 synthetic canary、60-case 独立评测。
- 恢复：源码按节点 commit revert；数据库新增记录采用追加/兼容迁移；已产生模型费用不可撤销，只能停止后续 attempt。
