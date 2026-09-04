# 冻结 v46 CANARY_5 失败后的 successor 认证恢复

Type: grilling
Status: resolved
Claimed by: Codex（2026-08-17 会话）
Blocked by: none；任何后续 live 仍另需 fresh exact scoped J1

## Question

在 `dashscope-qwen38-max-product-v46-hybrid-generic` 的首个 `CANARY_5` 已形成
`TERMINAL_CLOSED/FAILED`，且对应 authorization 与 PostgreSQL ledger 均已 `CLOSED` 的前提下，如何建立一条
不修改、不重开、不克隆旧 cycle 的认证 successor：successor Profile 是否保持 exact DashScope route/model、
8192 output-token fail-closed 边界和 12-call/¥6 总边界，只通过新 Prompt/pipeline identity 收紧密集重复版面的
输出；同 stage 的等价拒绝应在多少次后熔断；旧失败图片只作为非计分 diagnostic，还是进入新的认证 canary；
以及哪些 Provider-zero、A1/A2、人工 review 与 fresh J1 前置必须满足后才可再次外传？

本票只冻结 successor 语义与恢复入口。它不授权 Provider 调用、不创建 `OPEN` authorization、不读取 API Key、
不改变旧 terminal，不 apply Candidate，也不发布 StaticSchema。

## Comments

### 不可变前提

- 旧 cycle：`c3bde304-b0b2-43f8-ab7e-16896ff04aed`；stage=`CANARY_5`；result=`FAILED`；
  lifecycle=`TERMINAL_CLOSED`。
- 旧 authorization：`20260817-iopa-canary5-c3bde304`，已于
  `2026-08-17T10:49:06.053985Z` 关闭；不能跨 Profile、cycle、input、stage 或时限复用。
- exact v46：`dashscope-qwen38-max-product-v46-hybrid-generic` /
  `22f561c88b30fabbf3ba660bcfe203fb570975f770ff122f2ce1c7216454ac0c`；仍为 hidden、未 grant，bytes
  与既有 Profile resource 保持不可变。
- 第一个 case 在 5 calls 后到达 `REVIEW_REQUIRED`；第二个 case 在 OBSERVE 用满 12 calls 后失败；其余 3
  cases 未启动。总消费 17 calls / 301,409 model tokens / ¥6.338772，0 unsettled reservation。
- 旧 stage 结果不会因第一个 case 的后续人工 verdict 改写；Candidate applied=`false`，StaticSchema
  published=`false`，P1-02/P1-03 未解锁。

### Payload-free 失败归因

- 第二个 case 的 12 次 OBSERVE 中，10 次为 `VISUAL_GROUNDING_OUTPUT_TRUNCATED`，且每次 output tokens
  都精确达到 8192；另外 2 次为 `VISUAL_GROUNDING_PARENT_CONTAINMENT_INVALID`。因此 terminal record 的
  最后一跳 containment code 不是主导失败模式，主导模式是稳定输出截断。
- v46 的 8192 上限不是可静默调大的偶然参数。ADR-0032 已记录：`qwen3.8-max` 只有 exact-alias live 证据，
  没有可用于准入的 advertised output 上限；Plus/Flash 的 16384 证据不能外推到 Max。
- `renderweave-visual-elements-prompt/12.0` 要求强重复序列必须保留代表性 ITEM，但没有显式限制每个序列的
  代表 ITEM 数、总 regions/elements，且没有为 `VISUAL_GROUNDING_OUTPUT_TRUNCATED` 定义响应预算收缩动作。
- pipeline 4.28 会把 rejected problem code 带入下一次请求，但同 stage 没有“等价拒绝已无新信息”的运行时
  熔断；只要总 call budget 尚有余额，就会继续相同 Provider hypothesis。此次因此在主导截断模式下继续消费。
- 证据：`.sdlc/evidence/20260817-181409-image-only-p1-live/image-only-canary-live-summary.json`
  （SHA-256 `0e689b55e29752d6b8a2e9ee4211928f9784f24006b02e0334c93af8fca3f8b7`）与
  `plans/image-only-certification-cycles/c3bde304-b0b2-43f8-ab7e-16896ff04aed-canary5-terminal.json`
  （SHA-256 `059c8a3fe38c00e1ce4a76d4c8896fa41eff976378386e21b32297a726e9a197`）。两者均为
  payload-free；不读取或保存旧完整模型响应来“修”结果。

### 已排除方案

1. **重开或重跑 v46/旧 cycle**：违反 ticket 05、approved delta AC-IOPA-006 与 immutable terminal 纪律。
2. **把 v46 的 Max output tokens 原地改成 16384**：改写 immutable Profile，且把其他模型能力证据外推给 Max。
3. **继续把 12 calls 当作 OBSERVE 的无条件 retry 数**：本次已证明等价拒绝可在没有新 hypothesis 时耗尽预算。
4. **改用 Plus/Flash 或自动 fallback**：两者未成为 sole finalist，且第二 Provider/跨模型 fallback 仍在 scope 外。
5. **把旧 case 的人工接受或一次新成功解释为 v46 认证通过**：不能覆盖 stage 失败或替代 fresh 5/20/60。

## Decision frontier（已由所有者全部按推荐确认）

### Q1 — Successor Profile 的最小语义边界

是否创建全新 immutable `dashscope-qwen38-max-product-v47-hybrid-generic`，保持 provider/model/route、
`maximumOutputTokens=8192`、`maximumTotalCalls=12`、run aggregate cost=¥6、OCR capability、Candidate contract、
threshold 与 pricing snapshot 不变；只把 element Prompt 升为 `renderweave-visual-elements-prompt/13.0`，并因
下述熔断行为把 pipeline 升为新 identity？Prompt 13 固定 snake_case、每个强重复序列最多 3 个代表 ITEM、
总 regions 与 elements 各不超过 32，并在截断 retry 时优先保留高置信 reusable structure、折叠不确定分支。

**推荐：是。** 这是保留 Max sole-finalist 与 fail-closed capability 边界的最小 successor；Profile hash 只能在
实际资源创建后计算，不能预写或从 v46 推导。若 owner 选择 16384、Plus/Flash 或分块多调用，应另行扩大
Provider/capability/quality 决策，不在本最小恢复内暗改。

### Q2 — 无信息量等价拒绝熔断

successor pipeline 是否在同一 run/stage 中同一 payload-free diagnostic code 第 3 次出现后，以该原始 diagnostic
code 终止 run，不再签发下一次 Provider permit；计数只读 append-only attempt ledger，跨 stage 不累计，成功推进
stage 后自然结束？Profile 的 12-call/¥6 仍是总 hard cap，不是保证会消费完的额度。

**推荐：是，阈值 3。** 它允许一次或两次随机纠错，同时把此次 10 次截断型无信息消耗限制到 3 次；只对新
pipeline identity 生效，历史 Profile/run snapshot 行为不变。Provider error、ambiguous attempt 与不同 diagnostic
code 继续沿各自既有 fail-closed 规则处理。

### Q3 — 再认证的输入隔离与阶段顺序

是否先完成 Provider-zero 的 prompt/profile byte diff、fake-provider 重复拒绝熔断、Testcontainers ledger、
payload scan 与受影响 server gate；随后用旧失败 artifact 的 fresh normalization/manifest 发起一个**非计分**
`DIAGNOSTIC_1`（独立 cycle、最大 1 run/5 calls/100,000 tokens/¥3/2h 的新 exact J1），只有它到达
`REVIEW_REQUIRED` 且人工接受，才为 v47 创建使用 **5 张全新 owner ordinary-design 图片** 的评分
`CANARY_5` 与另一份 fresh exact J1？旧 v46 manifest、case assignment、ledger 与剩余 3 个未启动 case 均不复用。

**推荐：是。** diagnostic 把已知高密度失败样本作为回归探针但不污染评分；新 5-case 避免针对已见失败样本
调 Prompt 后产生选择偏差。任一阶段失败都形成自己的 negative terminal；不能在同一 identity 下 patch/rerun。

## Answer

2026-08-17T20:14:05+08:00，所有者回复“全部按推荐”，Q1–Q3 全部冻结：

1. **v47 successor**：创建 immutable
   `dashscope-qwen38-max-product-v47-hybrid-generic`；保持 exact DashScope provider/model/route、
   `maximumOutputTokens=8192`、`maximumTotalCalls=12`、run aggregate cost=¥6、OCR capability、Candidate
   contract、threshold 与 pricing snapshot 不变。element Prompt 升为
   `renderweave-visual-elements-prompt/13.0`，pipeline 升为 `renderweave-inference-pipeline/4.29`。Prompt 13
   内化 snake_case、每个强重复序列最多 3 个代表 ITEM、regions≤32、elements≤32，并在截断 retry 时优先保留
   高置信 reusable structure、折叠不确定分支。v47 canonical hash 只能从实际资源计算。
2. **等价拒绝熔断**：仅 pipeline 4.29 及其 successor 在同一 run/stage 中累计同一 payload-free diagnostic
   code；第 3 次 rejected attempt 已经持久化后，以该原始 diagnostic code 终止 run，禁止签发下一次 Provider
   permit。跨 stage 不累计，成功推进 stage 后结束该计数；12-call/¥6 继续是总 hard cap而非消费目标。历史
   Profile/run snapshot 保持 pipeline 4.28 行为。
3. **恢复阶段**：先完成全部 Provider-zero gates。之后旧失败 artifact 只进入非计分
   `PROFILE_SUCCESSOR_DIAGNOSTIC_1`：fresh normalization/manifest、独立 cycle、1 run/5 calls/100,000
   model tokens/¥3/2h、fresh exact scoped J1；只有 run 到达 `REVIEW_REQUIRED` 且人工接受才可关闭为 PASS。
   Diagnostic 不能计入 5/20/60、不能签发 Profile Certification、不能解锁 P1-02/P1-03。其后评分
   `CANARY_5` 必须使用 5 张全新 `USER_PROVIDED+ORDINARY_DESIGN` 图片和另一份 fresh exact J1；旧 v46
   manifest/assignment/ledger 与 3 个未启动 case 均不得复用。
4. **失败纪律**：v47 diagnostic 或后续任一评分阶段失败，均形成其自身 immutable negative terminal；修改
   Prompt/Profile/pipeline/caps/assignment 后只能再开 source ticket 和新 identity，不得 patch/rerun。
5. **授权边界**：本 Answer 批准 Provider-zero 实现及 successor delta，不是 live J1。实现过程不读取 Key、
   不创建 `OPEN` authorization、不调用 Provider、不 apply Candidate、不发布 StaticSchema。
