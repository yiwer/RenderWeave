# ADR-0011：确定性 JSON Grounding 与受限视觉 Overlay

- 状态：accepted
- 日期：2026-08-09
- 关联：ADR-0004、ADR-0008、ADR-0010、AC-016、AC-021
- 决策来源：Prompt v2 的 `renderweave-live-eval/2.0` 60-case CLOSED evidence

## 背景

Prompt v2 将 pinned Plus 的 exact pass 从 18/60 提升到 47/60，critical hallucination 从 51 降到 10，但仍为 `EXPERIMENTAL`。剩余 13 个非 exact case 中，JSON_ONLY 的 nested/heterogeneous array 与 COMBINED 的 empty array、scalar conflict、heterogeneous array、extra visual field 共 6 个 case，均要求模型重新生成系统已经由 `JsonStructuralProfiler` 与 `JsonCandidateProfiler` 确定性掌握的字段身份、对象/数组拓扑、不确定性边界和 JSON evidence。模型一旦在这些位置偏离，会形成 mixed trust blocker、repair exhaustion 或 evidence/DAG 退化。

继续把 truth table 重复写进更长的 Prompt，会扩大输入、维修和随机性，却不会提高系统对 JSON 事实的控制力。RenderWeave 应认证用户实际使用的完整 pipeline，而不是要求 LLM 独自重做确定性工作。

## 决策

### 1. 新版本只新增，不覆盖

- 保留 pipeline 1.0、Prompt 1.0/2.0 与全部历史 Profile 字节和 CLOSED evidence。
- 新增 `renderweave-inference-pipeline/2.0`、`renderweave-schema-candidate-prompt/3.0` 和独立 pinned Plus 实验 Profile。
- Candidate HTTP/持久化合同仍为 `renderweave-candidate/1.0`；pipeline 内部组合不新增用户可见状态或数据库迁移。

### 2. JSON_ONLY 不调用 Provider

- `JsonStructuralProfile` 直接交给 `JsonCandidateProfiler` 生成完整 Candidate；不发送 JSON profile、图片或任务给外部模型，provider attempt 为 0。
- 根 SchemaKey 从 run 的输入 fingerprint 确定性生成，避免全局固定 key；displayName 使用明确的系统默认值并允许后续逐项审核修改。
- JSON 字段身份、对象/数组拓扑、scalar 类型、UNRESOLVED/CONFLICT、JSON evidence、required=false 与空 constraints 全部由确定性实现产生。
- Candidate 仍是推断工作流产物，item 使用 `source=AI`、`inferred=true`；低置信和不确定类型继续进入人工审核，不因零调用而绕过 Candidate 边界。

### 3. COMBINED 采用 JSON base + 受限视觉 overlay

- 系统先构建与 JSON_ONLY 相同的 deterministic base，并把不含样本值的 grounded Candidate 放入 task，帮助模型只处理视觉语义。
- Provider 返回仍按 Candidate 1.0 严格解析，但只作为不可信 visual proposal。composer 以 canonical reference path 匹配 deterministic Schema，不信任 provider UUID、SchemaKey、JSON evidence 或图拓扑。
- JSON-backed Schema/field 不能被删除、改名、移动或改变 reference/array/uncertainty topology；JSON DECIMAL/BOOLEAN 不得被视觉覆盖。
- 唯一 concrete refinement 是：JSON TEXT 在 provider 给出合法、直接 IMAGE evidence 且 confidence 达阈值时精化为 DATE 或 TIME。
- 合法 IMAGE evidence 可以补充 matched Schema/field 的 displayName/evidence。JSON evidence 始终由 deterministic base 保留，图片不能替代。
- visual-only field 仅在匹配到的 deterministic Schema 下、FieldKey 合法且唯一、具有直接合法 IMAGE evidence时加入；本版本只接受 supported scalar 或单层 scalar array。视觉 reference、新 Schema、nested array、无证据字段与对 JSON 不确定边界的具体化全部安全忽略并产生稳定 warning。
- composer 重新生成 run-local IDs，强制 `source=AI`、`inferred=true`、`required=false`、空 constraints，并由 confidence/类型确定 resolution；provider 不能伪造用户处置。

### 4. IMAGE_ONLY 保留模型图推断，但缩短决策面

- IMAGE_ONLY 仍由模型生成图，因为没有确定性 JSON 拓扑可作为事实源。
- Prompt 3.0 按 mode 分支，IMAGE_ONLY 明确数组/对象数组、同形不同角色、多图非 union、视觉类型冲突和 confidence/resolution 自检；COMBINED 则围绕 grounded Candidate 工作，不再要求模型重算 JSON truth table。
- strict codec、validator、三态 repair policy、最多两轮 repair 与人工不确定性边界保持不变。

### 5. 评测与费用语义

- AC-021 继续评测完整 pipeline；JSON_ONLY 零 provider call 是产品行为改善，不伪装成模型能力。
- certification journal 必须允许一个完成 case 有 0 attempt，同时继续要求 assignment/run 唯一、已有 reservation 全部结算、总次数/费用 fail-closed。
- 不降低任何阈值；global/mode/holdout 的 contract/evidence/DAG 100% 与 critical hallucination 0 仍是认证要求。

## 验证要求

- 20 个 JSON_ONLY corpus case 在零 provider fake 下全部生成确定性 Candidate，并由 whole-graph evaluator exact 匹配。
- 20 个 COMBINED replay visual proposal 经 composer 后保持 JSON truth、允许日期/时间精化与 visual-only scalar，且 whole-graph evaluator exact 匹配。
- adversarial tests 证明 provider 不能删除/改名 JSON 字段、替换 JSON evidence、具体化 uncertainty、引入 required/constraints、额外 Schema、reference/cycle 或未知图片位置。
- live workflow tests 证明 JSON_ONLY provider calls=0；COMBINED request 带 grounded Candidate，持久 Candidate 为 composed 结果；旧 pipeline 行为不变。
- Prompt/Profile/OpenAPI/生成 SDK 字节与枚举同步，最终 tracked tree 形成新的 evaluation identity；真实调用前另建 synthetic-only PROPOSED ledger。

## 后果

- 正向：JSON contract/evidence/DAG 从概率性提示转为确定性不变量；JSON_ONLY 消除外传和费用；COMBINED 将模型能力集中在视觉语义。
- 代价：pipeline 增加一个可独立测试的 composer；visual-only reference 暂不由 COMBINED 自动创建，需用户审核或未来独立协议版本支持。
- 恢复：代码、Prompt/Profile 与 OpenAPI 通过本节点提交 revert；PROPOSED 前零 provider side effect。未来调用一旦发生不可撤销，只能以精确 J1、预算预留、批次 journal 和立即 CLOSED 限制后续影响。
