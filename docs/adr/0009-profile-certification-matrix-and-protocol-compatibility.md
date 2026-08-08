# ADR-0009：Profile 认证按协议能力分层，优先评测 pinned Plus

- 状态：accepted
- 日期：2026-08-09
- 关联：ADR-0008、AC-016、AC-021
- 决策来源：Flash 60-case live 结果；用户批准新增千问模型、每个其他模型费用不超过 ¥10

## 背景

`dashscope-qwen37-flash-v1` 已完成 `renderweave-live-eval/2.0` 的 60-case 独立评测并确定为 `EXPERIMENTAL`。官方最新能力表同时澄清：`qwen3.7-plus-2026-05-26` 支持视觉输入与结构化输出；`qwen3.8-max-preview` 仅 Token Plan 可用且只能开启思考，Qwen3.7 Max dated 系列的模型表不提供当前 JSON mode 保证。仓库旧 `qwen3.8-max` Profile 因而不能直接作为已验证可执行协议。

## 决策

- 下一条完整 60-case 评测使用不可漂移的 `qwen3.7-plus-2026-05-26`，Profile ID 为 `dashscope-qwen37-plus-20260526-v1`。
- 继续使用 OpenAI-compatible Chat Completions、`response_format=json_object`、关闭 thinking/tools/remote media，并复用 strict Candidate codec；Profile 仍为 `SYNTHETIC_ONLY` 和 `EXPERIMENTAL`。
- 价格快照为输入 ¥2/M tokens、输出 ¥8/M tokens；单次输入超过 256K 时按 3 倍价格保守估算。视觉输入按归一化上限 `4096²/(32²)+2=16,386 tokens/image` 预留，不以常见小图代替最坏边界。单请求估值上限 ¥0.20，整次授权最多 180 attempts、¥10、每批 5 case、4 小时。
- certification harness 通过严格的仓库 ledger stem 选择器加载不同授权；不接受任意路径。每个 Profile 使用不同 authorization ID、journal 与 CLOSED 生命周期，禁止改写或复用 Flash 账本。
- `qwen3.7-flash-2026-07-15` 与浮动 Flash 暂不重复消耗额度；它可作为未来 pinned 复验 Profile，但不能继承本次 alias 的认证结果。
- `qwen3.8-max-preview`、`qwen3.7-max-2026-06-08`、`-05-20`、`-05-17` 在新增“思考输出分离、prompt-only JSON/Responses、Token Plan credit 与人民币双预算”能力前不调用。用户授权扩大了可用模型集合，不代表可以绕过协议与费用硬门。

官方依据：

- <https://platform.qianwenai.com/docs/developer-guides/getting-started/text-generation-models>
- <https://platform.qianwenai.com/docs/developer-guides/getting-started/vision-models>
- <https://platform.qianwenai.com/docs/developer-guides/text-generation/thinking>
- <https://platform.qianwenai.com/docs/developer-guides/getting-started/pricing>

## 验证结果

- pinned Plus 在 14 个串行批次中完成 60/60 case，共 75 attempts；113,094 input tokens、74,970 output tokens，独立重算费用为 ¥0.825948。账本生命周期为 `PROPOSED → OPEN → CLOSED`，结束时没有活跃 execution 或未结算 reservation。
- exact pass 为 18/60；global entity F1 90.90%、field F1 73.29%、supported type 72.15%、edge F1 66.66%、evidence 92.97%、DAG 93.33%，critical hallucination 51、blocker 44。
- fail-closed policy 独立重建出 31 条违规，因此结果为 `EXPERIMENTAL`。该 Profile 不改为 `CERTIFIED`，也不把本次结果外推给浮动 Plus、dated Flash 或 Max 系列。
- 本地 A2 对 case/run/reservation、逐 attempt 费用、global/mode/partition 共 108 个 scalar、policy 与 payload-free 证据全部复核一致；证据仍不是外部不可篡改 A3。

## 后果

- 正向：评测身份绑定稳定模型版本；不会向强制思考或不支持 JSON mode 的模型发送已知不兼容请求；各 Profile 的授权与证据互不污染。
- 代价：Max 系列需要新的 provider contract 和 credit 预算模型，不能立即复用当前 harness。
- 恢复：Plus authorization 在任何调用前可保持 `PROPOSED`；调用完成、停止或到期立即 `CLOSED`。代码与 Profile 可按节点提交回退，已经发生的调用不可撤销。
