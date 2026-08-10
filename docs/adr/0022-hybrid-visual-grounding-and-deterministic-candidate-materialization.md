# ADR-0022：混合视觉 Grounding 与确定性 Candidate Materialization

- 状态：Accepted
- 日期：2026-08-10
- 关联：AC-015..021、AC-VR-001..010、P6/T6-5、ADR-0004、ADR-0011、ADR-0020、ADR-0021

## 背景与约束

pipeline 3 用严格的元素、层级和 binding 合同解决了最终 Candidate 静默压扁拓扑的问题，但仍存在三类
根本缺口：第一阶段漏识别会成为后续不可修正的“事实”；bbox 只验证形状而不验证空间归属；最终
STRUCTURE 调用让 LLM 重写一个已经完全确定的图，增加格式失败、费用和延迟。

RenderWeave 仍须保持：正式 DSL 不放宽、AI create-only、图片/业务值不进入常规日志或证据、历史
Profile/run 不可变、JSON_ONLY 零 Provider、COMBINED 以 JSON 为结构事实源、单节点 Java 主服务和默认
零网络测试。

## 决策

1. **控制面与感知面分离。** durable worker、预算、lease、strict codec 和人工审核继续作为控制面；
   新增多尺度 view、region graph、OCR/layout observation 和图片 verifier 作为可度量的感知面。
2. **计划编译本地化。** validated hierarchy + binding + grounding 是 Candidate 的完整事实输入。pipeline 4
   由 Java materializer 生成 Candidate；LLM 不再承担 UUID、reference/array 拼装或合同 JSON 编译。
3. **空间关系进入合同。** region 使用稳定 run-local ID、parent、readingOrder、repeatGroup、bbox 和原图
   transform；包含、重叠、可达、重复一致性由确定性代码检查，不只写在 Prompt 中。
4. **OCR/layout 使用窄端口和实证选择。** `DocumentVisionPreprocessor` 只接受规范化本地 artifact，返回
   有界 observation；生产实现必须本地执行、默认零网络。先比较 pure VLM、多尺度 VLM、混合 OCR/layout，
   只有满足 spec delta 的 slice 增益门才成为默认。具体引擎属于可替换 adapter，不写入领域模型。
5. **不持久化 OCR 原文。** OCR 文本可在单次 stage request 的受控内存中辅助角色/层级判断，但 checkpoint、
   常规日志、A1/A2 evidence 和 report 只保存 region/element ID、坐标、有限 confidence bucket 与问题码。
6. **语义验证替代生成式收尾。** 节省出的 Provider 调用用于只读 verifier；verifier 只能指出图片与计划的
   bounded gap。repair 回到最早有问题的 stage，并只发送对应 crop 和必要上下文。
7. **通用 Prompt 与领域包分离。** GENERIC 是默认且不包含公交词汇；TRANSIT_BOARD 是显式、版本化 Hint
   Pack。领域包不能提高权限、改变事实优先级或绕过 validator。
8. **模型能力逐一绑定。** 每个 Product Profile 保存模型实际支持的 JSON mode、thinking、max output、
   sampling、timeout、视觉输入和价格合同；未验证能力的模型不进入 vNext 产品目录。
9. **评测优先于默认切换。** 旧 product-v4 先形成同 corpus 小规模 baseline；vNext 只有达到 AC-VR-010
   才能成为默认，未达标 Profile 继续 EXPERIMENTAL。

## 备选方案

| 方案 | 优点 | 代价/风险 | 未选择原因 |
|---|---|---|---|
| 继续四次全图 VLM，只扩 Prompt | 改动小 | 上游漏识别不可见；第四次仍可能超时/截断 | 真实运行和代码审查均已证明边界 |
| 直接接入独立 PP-Structure 微服务 | 文档能力成熟 | 违反 v1 单节点/非微服务边界，部署和恢复面显著扩大 | 先用窄本地 adapter 与消融证明收益 |
| 只使用传统 OCR/layout | 可重复、低生成风险 | 难以从视觉角色推断业务 Schema 与领域关系 | 保留 VLM 语义推理，采用混合路径 |
| 只使用 OCR-free 端到端 VLM | 部署简单 | 密集小字、重复项与空间绑定缺少独立观测 | 作为消融基线，不作为未经证据的默认 |
| 保存完整 OCR/模型输出供调试 | 诊断信息最多 | 扩大隐私、注入、留存和 evidence 泄露面 | 不接受；改用 synthetic trace 与有限 taxonomy |

## 后果与验证

- 正向后果：计划错误可在 region/element/entity/binding 层定位；Candidate 编译零 Provider；调用预算可用于
  真正的语义复核；通用领域不再被公交示例污染。
- 负向后果/债务：增加 view/region/OCR adapter 与 stage-gold 维护成本；本地 OCR 运行时和字体/模型资产
  需要供应链、许可、大小和恢复验证；vNext 在完成 live 评测前仍是 EXPERIMENTAL。
- 验证或观测方式：60-case stage gold、property/adversarial contract tests、真实 PostgreSQL crash/recovery、
  materializer byte golden、OCR payload scan、模型 capability contract、三模型 bounded live baseline/final、
  independent evidence verifier、Web E2E 和最终 full A1；真实调用另有本轮 J1。
- 回退/替代条件：产品目录可回指不可变 product-v4；pipeline 4 的 migration 只前进，历史 checkpoint 按原
  snapshot 恢复；若 OCR 消融未过门，仅关闭该 adapter/default，不删除 region/materializer 能力；已发生费用
  不可回退，只能关闭 Goal ledger。
