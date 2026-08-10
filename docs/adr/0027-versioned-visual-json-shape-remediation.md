# ADR-0027：版本化视觉 JSON 形状修复与 strict decoder 边界

- 状态：accepted
- 日期：2026-08-11
- 关联：AC-VR-005、AC-VR-010、P6/T6-5 N6、ADR-0023、ADR-0025、ADR-0026

## 背景与约束

N5 的 pipeline 4.1/4.2 消融在 OBSERVE 阶段反复得到笼统的
`VISUAL_GROUNDING_CONTRACT_INVALID`，无法判断是网络、截断、JSON 语法还是具体合同字段。N6 先将失败拆成
payload-free 固定码，再在精确 OPEN ledger 下对仓库合成 `transit-board-v3` 做逐步、低预算探针：

1. 5 次 Qwen3.8 Max v6 返回均为 `VISUAL_GROUNDING_JSON_INVALID`；
2. 解码类别细分后，2 次均为 `VISUAL_GROUNDING_JSON_SHAPE_INVALID`；
3. Jackson record path 映射为有限槽位后，2 次均为
   `VISUAL_GROUNDING_JSON_SHAPE_INVALID_REGION_EVIDENCE`。

结果证明模型返回的是可解析 JSON，但 region 的 `evidence` 不是合同要求的 JSON 数组。v2 prompt 的
“evidence contains exactly one object”虽意图表达单元素数组，却存在被理解为裸对象的真实歧义。
`response_format=json_object` 只约束根输出是 JSON object，不能替代 RenderWeave 的嵌套合同。

既有 Profile、prompt version 与历史 evidence 必须保持可复现。为方便模型而全局开启 singleton-as-array 会让
错误输出静默通过，并扩大所有列表字段的接受面；直接改写 v2 文本又会让相同 prompt identity 指向不同内容。

## 决策

1. **保留严格 decoder。** 不启用 `ACCEPT_SINGLE_VALUE_AS_ARRAY`，不自动把裸 `evidence` object 包成数组；
   duplicate、unknown、trailing、coercion、null primitive 与 record invariant 仍 fail-closed。
2. **解码失败只保存固定分类。** JSON 失败区分 syntax、duplicate、trailing、unknown、enum、format、shape、
   constructor、other；shape 进一步映射到固定的 root/region/element/evidence/bbox/hierarchy/binding 槽位。
   不保存异常 message、Jackson path、模型输出或字段值。`finish_reason=length` 在解析前映射成稳定 truncation code。
3. **新增 immutable visual-elements prompt v3。** 它保持 grounding/2.0 语义不变，但明确所有复数成员和所有
   `evidence` 都是 JSON array；region evidence 精确写为一元素数组形状，并明确禁止裸 object、坐标数组、
   string、null 或 singleton collapse。
4. **新增隐藏 product-v8-generic Profile。** Flash、Plus、Max 各有一份 pipeline 4.1 Profile，绑定 visual
   elements v3、既有 hierarchy/bindings v2 与 generic hint。v6/v7 文件和身份不变；v8 为 `EXPERIMENTAL`，
   仅进入内部 grounding registry/capability 校验，不进入 `productLiveProfiles()` 或 Web 模型选择器。
5. **先恢复合同可达性，再增加 semantic verifier。** v8 必须先在相同合成 case 上通过 OBSERVE strict decode；
   若进入 hierarchy/binding，则按新的最早失败阶段继续有限修复。只有三阶段合同可达后，N6 才增加语义 verifier
   与 targeted repair，避免 verifier 掩盖基础序列化错误。
6. **层级修复继续使用新版本，不改写 v8。** v8 live canary 首次通过 OBSERVE，随后 4 次 HIERARCHY
   均被既有综合拓扑校验拒绝。服务端先把拓扑、支持元素和空间归属拆成有限固定码；随后新增 immutable
   visual-hierarchy prompt v3，把 `N entities => N-1 relationships`、每个非根实体恰好一次作为 child、根绝不作为
   child、父先于子，以及 GROUP 对 relationship 一对一且 cardinality 一致写成机械自检步骤。新增 Flash、Plus、
   Max 三份隐藏 product-v9-generic Profile，绑定 elements v3 + hierarchy v3 + bindings v2；历史 v8 保持不变。

## 备选方案

| 方案 | 优点 | 代价/风险 | 未选择原因 |
|---|---|---|---|
| decoder 自动包 singleton | 无需再次调用模型，兼容当前输出 | 静默接受非合同形状；可能扩散到 regions/elements/regionIds 等字段 | 破坏 strict、可诊断边界 |
| 直接改写 visual-elements v2 | 文件更少 | 历史 Profile 同 identity 不同 prompt，旧 evidence 不可复现 | 违反 immutable Profile/prompt 语义 |
| 只增加重试次数 | 无实现改动 | 同一歧义已连续 9 次复现，继续付费不产生新信息 | 不满足费用与实验假设门 |
| 立即加入全图 semantic verifier | 能覆盖更高层遗漏 | OBSERVE 尚不能反序列化，verifier 没有可验证输入 | 顺序错误，无法定位收益 |
| Provider JSON Schema structured output | 可在服务端约束嵌套形状 | 当前兼容协议/Profile 只验证 JSON_OBJECT；模型/接口能力需独立官方证据与 capability 版本 | 作为后续能力变体，不在本节点假设支持 |

## 后果与验证

- 正向后果：基础合同错误可直接定位到固定槽位；Prompt/Profile 历史保持可复现；模型仍必须生成精确结构，
  服务端不会把错误输出“修成成功”。
- 负向后果/债务：v3 增加少量输入 token；JSON path 分类依赖当前 Jackson record path 语义，依赖升级时必须重跑
  adversarial tests；elements v3 与 hierarchy v3 都增加少量输入 token；明确数组与树恒等式仍不保证语义正确，
  仍需后续 verifier/eval。
- 验证或观测方式：strict decoder 单元矩阵、PostgreSQL attempt taxonomy/finish-reason 回归、Profile/Prompt
  registry 合同；精确 PROPOSED→OPEN→CLOSED 单 case live canary；独立 payload-free evidence verifier。
- 回退/替代条件：v8 默认隐藏，停止新建 v8 run 即可回退；历史 snapshot 继续按其精确 prompt reader 执行。
  若官方 structured-output capability 经独立证据确认，可新建 Profile/pipeline，而不是改变 v8。
