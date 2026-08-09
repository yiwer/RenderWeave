# ADR-0013：复用 Grounded Profile 的 IMAGE_ONLY 诊断评测方案

- 状态：accepted
- 日期：2026-08-09
- 关联：ADR-0011、ADR-0012、AC-016、AC-020、AC-021
- 决策来源：Grounded Pipeline v2 的 IMAGE_ONLY 20/20 全失败结果与 T5-9 payload-free taxonomy

## 背景

Grounded v2 的 20 个 IMAGE_ONLY case 共发生 60 次 Provider attempt，全部在两轮 repair 后失败。T5-9 已新增不含 Candidate、Prompt、图片、字段名或 Provider request id 的稳定问题 code/count，但历史 CLOSED evidence 无法追溯补齐这些计数。

下一步需要在同一模型、Prompt、pipeline、corpus 与严格验证规则下重新运行 IMAGE_ONLY slice，以隔离“模型输出违反了哪些合同边界”。若新增一个内容相同但产品可见的新 Profile，会扩大 OpenAPI/UI，且容易把 Profile 变化混入归因。

## 决策

### 1. Profile 保持字节不变，方案独立版本化

- 复用不可变 Profile `dashscope-qwen37-plus-20260526-grounded-v1`，不修改其 JSON、Prompt 3.0、pipeline 2.0 或认证状态。
- 新增授权版本 `renderweave-live-image-diagnostic-authorization/1.0`。授权版本本身确定性选择 `renderweave-live-eval/2.0` 中恰好 20 个 IMAGE_ONLY case；不接受可编辑 caseId 列表。
- 该版本只允许上述单一 Grounded Profile。journal 已把 authorization version 写入 guard/state，因此 assignment slice 与费用账本不能在恢复时漂移。

### 2. 诊断不参与 Profile 认证

- 诊断方案的 `evaluationPurpose=IMAGE_ONLY_DIAGNOSTIC`、`certificationEligible=false`，assignment count 固定为 20。
- certification report 升级为 1.3，显式写入 purpose、eligibility 与 authorized assignment count。
- 即使 20 个 case 全部 exact，AC-021 policy 仍只能得到 `INCOMPLETE/DIAGNOSTIC_ONLY`；不能把单 slice 结果升级为 `CERTIFIED`。

### 3. guarded 信封继续 fail-closed

- 数据仅限仓库合成 corpus；不使用真实业务数据。
- 设计上最多 20×3=60 provider attempts，每批最多 5 case，费用硬上限 ¥2，OPEN 窗口最多 4 小时。
- pinned Plus 的最低输入单价为 ¥2/1M tokens；调用前最坏费用预留与 ¥2 总上限把总 token 消耗收窄在用户批准的每模型 1M token 以内。
- 结果继续只保存 scalar evaluation 与 problem taxonomy；所有历史 authorization 保持 CLOSED。
- ledger 先保持 `PROPOSED`。只有最终 tracked tree identity、pre-live A1/A2 和精确 J1 同时满足后才可 OPEN。

价格与能力在 2026-08-09 依据阿里云百炼官方文档重新核对：

- [模型价格](https://platform.qianwenai.com/docs/developer-guides/getting-started/pricing)
- [视觉模型能力与图片 token 公式](https://platform.qianwenai.com/docs/developer-guides/getting-started/vision-models)

## 验证要求

- authorization 单元测试证明只允许 Grounded Profile、20 个 IMAGE_ONLY assignment、≤60 attempts，且 certification eligibility 为 false。
- journal 拒绝同一 Profile 下的 JSON_ONLY/COMBINED assignment。
- 20 个合成 exact result 仍被 certification policy 判为 INCOMPLETE。
- paid test 在普通 server gate 中默认 skipped；PROPOSED 负探针必须在 journal/Provider 之前以 `LIVE_CERTIFICATION_AUTHORIZATION_NOT_OPEN` 拒绝。
- 最终 ledger identity 绑定除自身之外的完整 tracked tree，并由独立 A2 复核。

## 后果与恢复

- 正向：真实归因只改变观测能力，不改变被测 Profile；不新增产品 API/UI 项。
- 代价：诊断结果只能解释 IMAGE_ONLY 合同失败分布，不能替代完整 60-case 认证。
- 源码可按独立节点 commit revert；PROPOSED ledger 无外部副作用。Provider 调用一旦发生不可撤销，只能依靠批次、预算、到期与立即 CLOSED 停止后续调用。
