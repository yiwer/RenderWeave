# 24 — IOPA-P1-R09：冻结 v49 mixed-region fallback recovery contract

**What to build:** 把 v48 单例 diagnostic 的 immutable generic negative terminal 转成一份所有者批准的 v49 Provider-zero recovery contract。该合同必须在不知道原始模型 payload 的前提下，区分“多个已知 region 字段同时失败”和“仍无法安全分类”，冻结 payload-free rejection envelope、mixed-set correction/breaker、identity bump 与 fresh diagnostic 边界，使后续实现有唯一权威且不改写 v48。

**Blocked by:** None — can start immediately；票 23 与 v48 negative terminal 是不可变输入，不是待完成 blocker。

**Status:** resolved

- [x] 以 exact v48 Profile、失败 cycle、CLOSED authorization/ledger、manifest/evaluator/normalization identities、live summary 与 negative terminal digests 为恢复基线，并确认当前 OPEN authorization=0。
- [x] 明确记录：一次 `VISUAL_GROUNDING_REGION_INVALID` 只能证明当前 fallback 被触发，不能据此猜测具体字段、字段值、region 数量或模型根因。
- [x] 使用 synthetic/provider-zero 证据穷举 v48 仍可落入 generic 的确定性 seam，并区分“两个及以上已知 field family 同时失败”与“collection/constructor/未知异常等无法安全分类”。
- [x] 冻结 successor-only primary fixed code 与 bounded detail code-set 的表达；detail 只能来自封闭枚举，必须去重、稳定排序、数量有上界，不能包含字段值、坐标、局部 ID、异常 message 或 payload 片段。
- [x] 冻结 mixed-set correction eligibility、canonical set breaker、不同 set 是否构成新 hypothesis，以及任何 breaker/hard cap 到达时何时禁止下一张 Provider permit。
- [x] 冻结 v49 Profile、Prompt、pipeline、normalization、diagnostic 和 verifier identity bump；v48 及更早资源 bytes、hash、J1、ledger、terminal 和证据保持不可变。
- [x] 保持 exact DASHSCOPE/qwen3.8-max route、8192 output tokens、12 calls/¥6、360s、OCR、Candidate/evaluator/pricing 不变；任何扩大另开 source decision。
- [x] 冻结 fresh non-scoring diagnostic：同一已知失败 artifact 只作 regression probe，使用新 cycle/manifest/authorization/ledger 和 fresh exact J1；失败不得 patch/rerun，`REVIEW_REQUIRED` 也不得自动 apply/publish。
- [x] 将所有者最终选择形成 append-only Answer 与 approved successor delta；在 Answer 前不得实现语义、创建 v49 Profile 或准备 live authority。
- [x] 本票全过程 Provider attempts/reservations/cost/API-key reads=0，不创建 OPEN authorization，不读取、落盘或转述原始模型输入输出。

## Decision frontier

### Q1 — mixed 与 unclassified taxonomy

是否为新 pipeline 冻结两个 successor-only primary code：已知字段集合同时失败使用
`VISUAL_GROUNDING_REGION_FIELDS_INVALID`，并携带七类既有 field-specific code 的 bounded、排序后 detail set；
其余无法安全分类使用 `VISUAL_GROUNDING_REGION_UNCLASSIFIED`，不携带推测性 detail？v48 继续保持 legacy
`VISUAL_GROUNDING_REGION_INVALID`。

**推荐：是。** 这把可行动的有限集合与真正未知状态分开，同时避免动态拼接 code 或泄漏 payload。

### Q2 — mixed-set bounded correction

是否仅当 primary 为 `VISUAL_GROUNDING_REGION_FIELDS_INVALID` 且 detail set 的每个成员都由新 Prompt 明确覆盖时
允许纠正；相同 canonical set 在同 run/stage 第三次 rejected attempt 落账后 terminal，不同 set 分别计数，
`UNCLASSIFIED`、未知或未列成员第一次落账即 terminal，所有 call/token/cost/time hard caps 永远优先？

**推荐：是。** 模型能收到有限、明确的多个纠正目标；真正未知状态仍不消费下一次调用。

### Q3 — exact v49 successor boundary

是否创建 `dashscope-qwen38-max-product-v49-hybrid-generic`，使用新 pipeline 与 element Prompt identity；相对 v48
只改变 `profileId`、`pipelineVersion`、`elementPromptVersion`，其余 frozen fields 完全相同？建议 identity 为
pipeline `renderweave-inference-pipeline/4.31`、Prompt `renderweave-visual-elements-prompt/15.0`。

**推荐：是。** rejection envelope 与 correction semantics 已改变，不能 patch v48。

### Q4 — fresh diagnostic boundary

是否继续使用同一 ordinary-design regression probe，但创建 fresh normalization/manifest/cycle/J1，hard cap 不宽于
1 run、5 calls、100,000 model tokens、¥3、2h；只有 `REVIEW_REQUIRED` 且人工接受后才允许另拆评分认证票？

**推荐：是。** 保持可比性，同时不复用 v48 的 CLOSED authority 或 terminal。

## Answer

所有者于 `2026-08-18T09:54:40+08:00` 回复“批准 24：Q1–Q4 全部按推荐”。据此冻结：

1. v49 对同一 region 观测到两个及以上已知 field family 同时失败时，primary 使用
   `VISUAL_GROUNDING_REGION_FIELDS_INVALID`，detail 使用七类既有 field-specific code 的封闭子集；集合必须
   去重、按冻结枚举顺序稳定排序，cardinality 为 2..7。其他不能安全分类的 fallback 使用
   `VISUAL_GROUNDING_REGION_UNCLASSIFIED`，不携带推测性 detail。v48 继续保留 immutable legacy
   `VISUAL_GROUNDING_REGION_INVALID`。
2. 只有 mixed primary 且全部 detail 都被 Prompt 15 明确覆盖时才允许 bounded correction。相同 canonical detail
   set 在同 run/stage 第三次 rejected attempt 落账后 terminal，不签发下一次 reservation/Provider permit；不同
   canonical set 分别计数。unclassified、未知或未列成员在第一次 rejected attempt 落账后 terminal；call/token/
   cost/time hard caps 永远优先。
3. exact successor 为 `dashscope-qwen38-max-product-v49-hybrid-generic`，pipeline 为
   `renderweave-inference-pipeline/4.31`，element Prompt 为
   `renderweave-visual-elements-prompt/15.0`。相对 v48 canonical Profile 只允许改变 profileId、pipelineVersion、
   elementPromptVersion；canonical SHA-256 必须从最终 resource bytes 计算，不能预填猜测。v49 保持 hidden、
   experimental、ungranted、uncertified、非 product-live。
4. 后续 diagnostic 只可复用同一已知失败 ordinary-design artifact 作为 regression probe，并必须创建 fresh
   normalization/manifest/cycle/J1/ledger。caps 不宽于 1 run、5 Provider calls、100,000 model tokens、¥3、每
   run 5 calls/¥3、authorization window≤2h；只有 `REVIEW_REQUIRED` 且所有者人工接受，才可另开 scoring ticket。

本次批准是 source decision J1 与 Provider-zero 实施合同，不是 paid live J1；不授权创建 OPEN authorization、读取
API Key、调用 Provider、认证计分/grant、Candidate apply、StaticSchema 发布或生产部署。批准时恢复基线仍为 exact
v48 immutable negative terminal，OPEN authorization=0；下一执行前沿为 ticket 25。
