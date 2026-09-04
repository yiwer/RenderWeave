# 18 — IOPA-P1-R03：冻结 v48 bounded region-recovery contract

**What to build:** 把 v47 单例 diagnostic 的不可变失败转成一份所有者批准的 v48 successor 恢复合同。该合同必须在不知道原始模型 payload 的前提下，冻结可观测的 region 拒绝分类、允许纠正的 fixed code、无信息重试熔断、identity bump 和失败终态规则，使后续实现有唯一权威且不会暗改 v47。

**Blocked by:** None — can start immediately；票 17 与 v47 negative terminal 是不可变输入，不是待完成 blocker。

**Status:** resolved

- [x] 以 exact v47 Profile、失败 cycle、已关闭 authorization、manifest/evaluator/normalization identities 和 negative terminal 为恢复基线，并确认当前 OPEN authorization 数为 0。
- [x] 明确记录：连续三次 VISUAL_GROUNDING_REGION_INVALID 只能证明现有分类粒度不足，不能据此猜测某个具体 region 字段或把猜测写成根因。
- [x] 使用 synthetic/provider-zero 证据列出 generic region rejection 可覆盖的验证边界，并冻结哪些边界获得独立 fixed code、哪些仍安全回退为 generic code。
- [x] 冻结哪些 fixed code 可以进入 bounded correction、每个 stage 的等价拒绝阈值、不同 code 是否构成新 hypothesis，以及在 breaker 或 hard cap 到达时何时禁止下一张 Provider permit。
- [x] 冻结 Profile、Prompt、pipeline、normalization 和 diagnostic identity 的 bump 规则；v47 的资源 bytes、hash、ledger、terminal 和既有证据均不得修改或重解释。
- [x] 保持 DASHSCOPE/qwen3.8-max exact route、8192 output-token 边界、12-call/¥6 run aggregate 边界、Candidate contract 和 evaluator 阈值不变；任何扩大必须另开 source decision。
- [x] 明确新 diagnostic 仍是非计分、单 case、fresh cycle、fresh exact J1；失败不得同 identity patch/rerun，REVIEW_REQUIRED 也不得自动 apply 或发布 StaticSchema。
- [x] 将所有者最终选择形成 append-only authoritative decision 与 approved successor delta；若与既有 Blueprint/票据发生实质冲突，按附录 A 新开票而非静默修改。
- [x] 本票全过程 provider attempts 为 0，不读取或输出 API Key，不创建 OPEN authorization，不读取、落盘或转述原始模型输入输出。

## Provider-zero findings

- v47 exact registry identity 仍为
  a9fe98e1cfa4b7cc126db1f74601fdebe60526a1c999924daf189ed5f1ac5eb0；失败 terminal digest 仍为
  5aad42165c1dd595d02e99c7c22c3c50cd2aeda83d08f6716cb8bd2081f7a664；当前 OPEN authorization 数为 0。
- 三次失败只有 VISUAL_GROUNDING_REGION_INVALID。它包住 region item 构造和 view evidence 转换；forest、
  parent containment、reading order、repeat semantics 与 JSON shape/enum 已在后续或 decode 边界拥有细分
  fixed code。因此现有证据不能判定具体 region 字段。
- generic 边界可由 deterministic synthetic fixtures 完整覆盖，无需读取 v47 原始 response：region item、
  regionId、parentRegionId、multiplicity、readingOrder range、repeatGroupId 和 evidence/view mapping。
- v47 Prompt 13 已能收到 retryProblemCodes，但没有针对 VISUAL_GROUNDING_REGION_INVALID 的可行动纠正合同；
  pipeline 4.29 会把该 generic code 重试至第三次 breaker。继续同一行为只会重复已证伪 hypothesis。

## Decision frontier

### Q1 — successor-only taxonomy

是否只为新 pipeline 增加 opt-in field-specific region diagnostics：

1. VISUAL_GROUNDING_REGION_ENTRY_INVALID；
2. VISUAL_GROUNDING_REGION_ID_INVALID；
3. VISUAL_GROUNDING_REGION_PARENT_ID_INVALID；
4. VISUAL_GROUNDING_REGION_MULTIPLICITY_INVALID；
5. VISUAL_GROUNDING_REGION_READING_ORDER_INVALID；
6. VISUAL_GROUNDING_REGION_REPEAT_GROUP_ID_INVALID；
7. VISUAL_GROUNDING_REGION_EVIDENCE_INVALID；
8. 无法安全分类时仍回退 VISUAL_GROUNDING_REGION_INVALID。

分类必须来自确定的验证 seam，不记录字段值、异常 message、坐标、局部 ID 或 payload；v47 及更早 pipeline
继续保持 legacy generic 行为。

**推荐：是。** 这覆盖 generic wrapper 内全部可纠正 region family，同时避免把内部异常文本变成 API。

### Q2 — bounded correction allowlist

是否创建与新 element Prompt identity 一起冻结的 OBSERVE correction allowlist：Prompt 已明确给出纠正动作的
既有 fixed code 加上 Q1 的七个细分 code才允许继续；VISUAL_GROUNDING_REGION_INVALID、未知 code 或未列 code
在 rejected attempt 落账后立即 fail，不 reserve 下一次调用；allowlist code 仍按同 run/stage 同 code 第三次
熔断，不同 code 分别计数，并继续受总 call/token/cost hard caps 限制。

**推荐：是。** generic code 没有可验证的新 hypothesis，不应再消费三次；细分 code 才能驱动明确纠正。

### Q3 — exact v48 successor boundary

是否创建 dashscope-qwen38-max-product-v48-hybrid-generic，pipeline 升为
renderweave-inference-pipeline/4.30，element Prompt 升为 renderweave-visual-elements-prompt/14.0；相对 v47
只改变 profileId、pipelineVersion、elementPromptVersion，保持 exact provider/model/route、8192 output tokens、
12 calls/¥6、360s、OCR capability、其他 Prompts、Candidate/evaluator/pricing 全部不变？

**推荐：是。** taxonomy 与 correction allowlist 都会改变运行语义，必须由新 Profile/Prompt/pipeline identities
承载，不能 patch v47。

### Q4 — fresh diagnostic boundary

是否继续使用同一个已知失败 ordinary-design artifact 作为非计分 regression probe，但创建 fresh
normalization/manifest/cycle/J1；hard cap 不宽于 1 run、5 calls、100,000 model tokens、¥3、2h，只有
REVIEW_REQUIRED 且人工接受才允许后续另拆评分认证票？

**推荐：是。** 这保持回归探针可比，同时不复用任何旧 cycle、manifest、authorization、assignment 或 ledger。

## Answer

2026-08-18T01:32:48+08:00，所有者回复“批准 R03：Q1–Q4 全部按推荐”，决定如下：

1. 新 pipeline 使用 Q1 的七类 field-specific region fixed code，无法安全分类时回退
   VISUAL_GROUNDING_REGION_INVALID；该 policy 只对 successor opt in，v47 及更早行为不变。
2. OBSERVE correction 使用与新 Prompt identity 一起冻结的显式 allowlist。allowlist code 可受限纠正并继续受
   同 run/stage 同 code 第三次 breaker 与全部 hard caps 约束；generic、未知或未列 code 在 rejected attempt
   落账后立即 fail，不 reserve 下一次调用。
3. 创建 dashscope-qwen38-max-product-v48-hybrid-generic，绑定
   renderweave-inference-pipeline/4.30 与 renderweave-visual-elements-prompt/14.0；相对 v47 只改变
   profileId、pipelineVersion、elementPromptVersion，其余 frozen fields 完全相同。
4. 同一已知失败 artifact 只作为 fresh、非计分 regression diagnostic：新 normalization/manifest/cycle/J1，
   caps 不宽于 1 run、5 calls、100,000 model tokens、¥3、2h。只有 REVIEW_REQUIRED 且人工接受后才可另拆
   评分认证票。
5. 本 Answer 是 Provider-zero successor 实现授权，不是 paid live J1；当前 OPEN authorization 数仍为 0。
